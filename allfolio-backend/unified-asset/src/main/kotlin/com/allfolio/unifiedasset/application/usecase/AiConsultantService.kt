package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.infrastructure.entity.UserAiConfigEntity
import com.allfolio.unifiedasset.infrastructure.jpa.UserAiConfigJpaRepository
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class AiConfigResponse(val baseUrl: String, val model: String, val hasKey: Boolean)
data class SaveAiConfigRequest(val baseUrl: String, val apiKey: String, val model: String)
data class ChatMessage(val role: String, val content: String)
data class ChatJobResult(
    val status: String,
    val content: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

private data class ChatJob(
    val ownerId: UUID,
    val result: ChatJobResult,
)

@Service
class AiConsultantService(
    private val configRepo: UserAiConfigJpaRepository,
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newCachedThreadPool()
    private val jobs = ConcurrentHashMap<String, ChatJob>()

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

    fun submitChat(userId: UUID, messages: List<ChatMessage>): String {
        val jobId = UUID.randomUUID().toString()
        jobs[jobId] = ChatJob(userId, ChatJobResult("pending"))
        val cutoff = System.currentTimeMillis() - 600_000
        jobs.entries.removeIf { it.value.result.createdAt < cutoff }
        CompletableFuture.runAsync({
            try {
                val content = chat(userId, messages)
                jobs[jobId] = ChatJob(userId, ChatJobResult("done", content = content))
            } catch (e: Exception) {
                jobs[jobId] = ChatJob(userId, ChatJobResult("error", error = e.message ?: "오류가 발생했습니다"))
            }
        }, executor)
        return jobId
    }

    fun getChatResult(userId: UUID, jobId: String): ChatJobResult {
        val job = jobs[jobId]
        if (job == null || job.ownerId != userId) {
            throw NoSuchElementException("Chat job not found: $jobId")
        }
        return job.result
    }

    fun chat(userId: UUID, messages: List<ChatMessage>): String {
        val config = configRepo.findById(userId).orElse(null)
            ?: throw IllegalStateException("LLM 설정이 없습니다")

        val systemPrompt = buildSystemPrompt(userId)
        val allMessages = listOf(mapOf("role" to "system", "content" to systemPrompt)) +
            messages.map { mapOf("role" to it.role, "content" to it.content) }

        val body = mapOf(
            "model" to config.model,
            "stream" to false,
            "messages" to allMessages,
        )

        log.info("[AI] chat userId={} model={}", userId, config.model)
        val isAnthropic = config.baseUrl.contains("anthropic.com")

        return try {
            val response = WebClient.builder().build()
                .post()
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
                .bodyToMono(ChatCompletionResponse::class.java)
                .block() ?: throw IllegalStateException("Empty response from LLM")

            response.choices?.firstOrNull()?.message?.content
                ?: throw IllegalStateException("No content in LLM response")
        } catch (e: WebClientResponseException) {
            log.error("[AI] error userId={} status={}: {}", userId, e.statusCode.value(), e.message)
            when (e.statusCode.value()) {
                429 -> throw IllegalStateException("[오류] 요청 한도 초과(429). API 키 사용량을 확인해주세요.")
                401 -> throw IllegalStateException("[오류] API 키가 올바르지 않습니다(401).")
                else -> throw IllegalStateException("[오류] LLM 서버 오류: ${e.statusCode}")
            }
        }
    }

    private fun buildSystemPrompt(userId: UUID): String {
        val nav = queryScalar(
            "SELECT COALESCE(SUM(current_value),0) FROM ua_assets WHERE user_id = ?", userId
        ) as BigDecimal? ?: BigDecimal.ZERO

        val pnl = queryScalar(
            """SELECT COALESCE(SUM(
                CASE WHEN liquidity_type = 'ILLIQUID' THEN current_value - purchase_price
                     ELSE current_value - purchase_price * quantity
                END
            ), 0) FROM ua_assets WHERE user_id = ?""", userId
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

        data class AssetRow(val name: String, val type: String, val value: BigDecimal, val currency: String, val quantity: BigDecimal, val areaPyeong: BigDecimal?, val liquidityType: String)
        val topAssets = jdbc.query(
            "SELECT name, type, current_value, currency, quantity, area_pyeong, liquidity_type FROM ua_assets WHERE user_id = ? ORDER BY current_value DESC LIMIT 10",
            { rs, _ -> AssetRow(rs.getString("name"), rs.getString("type"),
                rs.getBigDecimal("current_value"), rs.getString("currency"), rs.getBigDecimal("quantity"),
                rs.getBigDecimal("area_pyeong"), rs.getString("liquidity_type")) },
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

        val topAssetRows = topAssets.joinToString("\n") { asset ->
            val pct = if (nav > BigDecimal.ZERO)
                asset.value.divide(nav, 4, java.math.RoundingMode.HALF_UP)
                    .multiply(BigDecimal(100)).setScale(1, java.math.RoundingMode.HALF_UP)
            else BigDecimal.ZERO
            val detail = when {
                asset.areaPyeong != null -> "${asset.areaPyeong.toInt()}평"
                asset.liquidityType == "ILLIQUID" -> "1채"
                else -> "${asset.quantity.toInt()}주"
            }
            "| ${asset.name} | ${asset.type} | ${asset.value.toLong()}${asset.currency} | $pct% | $detail |"
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
| 종목명 | 유형 | 현재가치 | 비중 | 수량/면적 |
|--------|------|----------|------|-----------|
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
    data class ChatCompletionResponse(val choices: List<CompletionChoice>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CompletionChoice(val message: CompletionMessage? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CompletionMessage(val content: String? = null)
}
