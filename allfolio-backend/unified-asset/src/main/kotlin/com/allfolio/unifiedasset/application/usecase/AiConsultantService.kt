package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.infrastructure.entity.UserAiConfigEntity
import com.allfolio.unifiedasset.infrastructure.jpa.UserAiConfigJpaRepository
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

data class AiConfigResponse(val baseUrl: String, val model: String, val hasKey: Boolean)
data class SaveAiConfigRequest(val baseUrl: String, val apiKey: String, val model: String)
data class ChatMessage(val role: String, val content: String)

@Service
class AiConsultantService(
    private val configRepo: UserAiConfigJpaRepository,
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun getConfig(userId: UUID): AiConfigResponse? =
        configRepo.findById(userId).orElse(null)?.let {
            AiConfigResponse(it.baseUrl, it.model, true)
        }

    fun saveConfig(userId: UUID, req: SaveAiConfigRequest) {
        configRepo.save(
            UserAiConfigEntity(userId, req.baseUrl, req.apiKey, req.model, LocalDateTime.now())
        )
    }

    fun deleteConfig(userId: UUID) = configRepo.deleteById(userId)

    fun chat(userId: UUID, messages: List<ChatMessage>): SseEmitter {
        val config = configRepo.findById(userId).orElse(null)
            ?: throw IllegalStateException("LLM 설정이 없습니다")

        val systemPrompt = buildSystemPrompt(userId)
        val allMessages = listOf(mapOf("role" to "system", "content" to systemPrompt)) +
            messages.map { mapOf("role" to it.role, "content" to it.content) }

        val body = mapOf(
            "model" to config.model,
            "stream" to true,
            "messages" to allMessages,
        )

        val emitter = SseEmitter(0L)
        val client = WebClient.builder().build()

        val isAnthropic = config.baseUrl.contains("anthropic.com")
        client.post()
            .uri("${config.baseUrl}/chat/completions")
            .apply {
                if (isAnthropic) {
                    header("x-api-key", config.apiKey)
                    header("anthropic-version", "2023-06-01")
                } else {
                    header("Authorization", "Bearer ${config.apiKey}")
                }
            }
            .header("Content-Type", "application/json")
            .bodyValue(body)
            .retrieve()
            .bodyToFlux(String::class.java)
            .subscribe(
                { line -> handleLine(line, emitter) },
                { e ->
                    log.error("[AI] stream error userId={}: {}", userId, e.message)
                    runCatching {
                        val msg = when (e) {
                            is org.springframework.web.reactive.function.client.WebClientResponseException ->
                                when (e.statusCode.value()) {
                                    429 -> "[오류] 요청 한도 초과(429). API 키 사용량을 확인해주세요."
                                    401 -> "[오류] API 키가 올바르지 않습니다(401)."
                                    else -> "[오류] LLM 서버 오류: ${e.statusCode}"
                                }
                            else -> "[오류] ${e.message}"
                        }
                        emitter.send(msg)
                    }
                    emitter.complete()
                },
                { emitter.complete() },
            )

        return emitter
    }

    private fun handleLine(line: String, emitter: SseEmitter) {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) return
        val payload = trimmed.removePrefix("data:").trim()
        if (payload == "[DONE]") {
            emitter.complete()
            return
        }
        runCatching {
            val chunk = objectMapper.readValue(payload, StreamChunk::class.java)
            val content = chunk.choices?.firstOrNull()?.delta?.content ?: return
            if (content.isNotEmpty()) emitter.send(content)
        }.onFailure { e ->
            log.debug("[AI] parse error: {} | line={}", e.message, line)
        }
    }

    private fun buildSystemPrompt(userId: UUID): String {
        val nav = queryScalar(
            "SELECT COALESCE(SUM(current_value),0) FROM ua_assets WHERE user_id = ?", userId
        ) as BigDecimal? ?: BigDecimal.ZERO

        val pnl = queryScalar(
            "SELECT COALESCE(SUM(current_value - purchase_price * quantity),0) FROM ua_assets WHERE user_id = ?", userId
        ) as BigDecimal? ?: BigDecimal.ZERO

        val pnlPct = if (nav > BigDecimal.ZERO)
            pnl.divide(nav, 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal(100)).setScale(2, java.math.RoundingMode.HALF_UP)
        else BigDecimal.ZERO

        val assetCount = (queryScalar(
            "SELECT COUNT(*) FROM ua_assets WHERE user_id = ?", userId
        ) as Long? ?: 0L).toInt()

        val accountCount = (queryScalar(
            "SELECT COUNT(DISTINCT id) FROM ua_accounts WHERE user_id = ?", userId
        ) as Long? ?: 0L).toInt()

        val topAssets = jdbc.query(
            "SELECT name, type, current_value, currency FROM ua_assets WHERE user_id = ? ORDER BY current_value DESC LIMIT 10",
            { rs, _ -> Triple(rs.getString("name"), rs.getString("type"),
                Pair(rs.getBigDecimal("current_value"), rs.getString("currency"))) },
            userId,
        )

        val byType = jdbc.query(
            "SELECT type, SUM(current_value) AS v FROM ua_assets WHERE user_id = ? GROUP BY type ORDER BY v DESC",
            { rs, _ -> Pair(rs.getString("type"), rs.getBigDecimal("v")) },
            userId,
        )

        val byCurrency = jdbc.query(
            "SELECT currency, SUM(current_value) AS v FROM ua_assets WHERE user_id = ? GROUP BY currency ORDER BY v DESC",
            { rs, _ -> Pair(rs.getString("currency"), rs.getBigDecimal("v")) },
            userId,
        )

        val thisYear = LocalDate.now().year
        val dividendRow = jdbc.query(
            """SELECT COALESCE(SUM(total_amount),0) AS total, COUNT(*) AS cnt
               FROM ua_stock_trades
               WHERE user_id = ? AND trade_type = 'DIVIDEND' AND EXTRACT(YEAR FROM traded_at) = ?""",
            { rs, _ -> Pair(rs.getBigDecimal("total"), rs.getInt("cnt")) },
            userId, thisYear,
        ).firstOrNull() ?: Pair(BigDecimal.ZERO, 0)

        val topAssetRows = topAssets.joinToString("\n") { (name, type, vCur) ->
            val (v, cur) = vCur
            val pct = if (nav > BigDecimal.ZERO)
                v.divide(nav, 4, java.math.RoundingMode.HALF_UP)
                    .multiply(BigDecimal(100)).setScale(1, java.math.RoundingMode.HALF_UP)
            else BigDecimal.ZERO
            "| $name | $type | ${v.toLong()}$cur | $pct% |"
        }

        val typeRows = byType.joinToString(", ") { (t, v) ->
            val pct = if (nav > BigDecimal.ZERO)
                v.divide(nav, 4, java.math.RoundingMode.HALF_UP)
                    .multiply(BigDecimal(100)).setScale(1, java.math.RoundingMode.HALF_UP)
            else BigDecimal.ZERO
            "$t $pct%"
        }

        val currencyRows = byCurrency.joinToString(", ") { (c, v) ->
            val pct = if (nav > BigDecimal.ZERO)
                v.divide(nav, 4, java.math.RoundingMode.HALF_UP)
                    .multiply(BigDecimal(100)).setScale(1, java.math.RoundingMode.HALF_UP)
            else BigDecimal.ZERO
            "$c $pct%"
        }

        return """
당신은 사용자의 개인 금융 자문 AI입니다. 오늘 날짜: ${LocalDate.now()}
사용자의 실제 포트폴리오 데이터를 기반으로 구체적이고 실용적인 조언을 제공하세요.

## 포트폴리오 요약
- 총 자산(NAV): ${nav.toLong()}원
- 미실현 손익: ${pnl.toLong()}원 ($pnlPct%)
- 보유 계좌: ${accountCount}개 | 보유 자산: ${assetCount}개

## 주요 보유 종목 (상위 10개)
| 종목명 | 유형 | 현재가치 | 비중 |
|--------|------|----------|------|
$topAssetRows

## 자산 배분
유형별: $typeRows
통화별: $currencyRows

## 올해 배당 수령액
총 ${dividendRow.first.toLong()}원 (${dividendRow.second}회)

데이터 기준 시각: ${LocalDateTime.now()}
""".trimIndent()
    }

    private fun queryScalar(sql: String, vararg args: Any?): Any? =
        runCatching {
            jdbc.queryForObject(sql, Any::class.java, *args)
        }.getOrNull()

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StreamChunk(val choices: List<Choice>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Choice(val delta: Delta? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Delta(val content: String? = null)
}
