package com.allfolio.broker.kis

import com.allfolio.broker.BrokerAccountInfo
import com.allfolio.broker.BrokerAdapter
import com.allfolio.broker.BrokerTradeResult
import com.allfolio.broker.BrokerType
import com.allfolio.dlq.DlqService
import com.allfolio.dlq.FailedTradeEvent
import com.allfolio.dlq.FetchParamsPayload
import com.allfolio.metrics.BrokerMetrics
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 한국투자증권(KIS) BrokerAdapter
 *
 * 계좌번호 형식: "{CANO}_{ACNT_PRDT_CD}"  ex) "50123456_01"
 * 커서: ctxAreaFk100 (빈 문자열 = 첫 페이지, 공백 = 마지막 페이지)
 */
@Component
@ConditionalOnProperty(prefix = "kis", name = ["app-key"], matchIfMissing = false)
class KisAdapter(
    private val kisApiClient: KisApiClient,
    private val kisProperties: KisProperties,
    private val dlqService: DlqService,
    private val metrics: BrokerMetrics,
    private val objectMapper: ObjectMapper,
) : BrokerAdapter {

    override val brokerType = BrokerType.KIS

    private val log      = LoggerFactory.getLogger(javaClass)
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val KST      = ZoneId.of("Asia/Seoul")

    override fun fetchTrades(portfolioId: UUID, accountId: String, cursor: String): BrokerTradeResult {
        val (accountNo, productCode) = parseAccountId(accountId)

        val accessToken = runCatching {
            metrics.recordApiLatency("KIS", "resolveToken") {
                kisApiClient.resolveAccessToken(portfolioId)
            }
        }.getOrElse { e ->
            metrics.apiError("KIS", "resolveToken")
            log.error("[KisAdapter] token resolve failed account={}", accountId, e)
            pushFetchDlq(portfolioId, accountId, cursor, e.message ?: "token error")
            return BrokerTradeResult(emptyList(), cursor)
        }

        // **한국 거래소 달력의 오늘.** `LocalDate.now()`는 호스트 벽시계 기준이라, 운영(Render)
        // 컨테이너가 UTC인 탓에 KST 00:00~09:00에 폴러가 돌면 "어제"를 오늘로 잡아 그날 체결분이
        // 창 밖으로 빠진다.
        //
        // **여기 KST를 박는 건 표시 계층의 규약과 충돌하지 않는다 — 걷어내지 말 것.**
        // 사용자에게 보여줄 시각은 읽는 쪽 달력이 무엇인지 서버가 모르므로 오프셋만 실어
        // 보내고 존은 브라우저가 정한다(ReportGeneratedAtOffsetTest 참고). 반면 이건
        // **외부 API 계약**이다 — KIS가 INQR_STRT_DT/INQR_END_DT를 한국 거래소 달력으로
        // 해석하므로, 어느 사용자가 어디서 보든 이 값은 KST 달력이어야 한다.
        // 사용자 달력과 거래소 달력은 다른 문제다.
        val today    = LocalDate.now(KST)
        val fromDate = today.minusDays(90).format(DATE_FMT)
        val toDate   = today.format(DATE_FMT)

        val response = runCatching {
            metrics.recordApiLatency("KIS", "getDailyOrders") {
                kisApiClient.getDailyOrders(
                    accessToken  = accessToken,
                    accountNo    = accountNo,
                    productCode  = productCode,
                    fromDate     = fromDate,
                    toDate       = toDate,
                    ctxAreaFk100 = cursor,
                )
            }
        }.getOrElse { e ->
            metrics.apiError("KIS", "getDailyOrders")
            log.error("[KisAdapter] getDailyOrders failed account={}", accountId, e)
            pushFetchDlq(portfolioId, accountId, cursor, e.message ?: "API error")
            return BrokerTradeResult(emptyList(), cursor)
        }

        if (response.rtCd != "0") {
            log.warn("[KisAdapter] API error rtCd={} msg={}", response.rtCd, response.msg1)
            return BrokerTradeResult(emptyList(), cursor)
        }

        val commands = response.output1.mapNotNull { item ->
            KisTradeMapper.toCommand(item, portfolioId, portfolioId)
        }

        // ctxAreaFk100가 공백이면 마지막 페이지
        val nextCursor = response.ctxAreaFk100.trim()

        log.info("[KisAdapter] fetched {} trades account={} hasMore={}", commands.size, accountId, nextCursor.isNotBlank())
        return BrokerTradeResult(commands, nextCursor)
    }

    override fun fetchAccounts(userId: UUID): List<BrokerAccountInfo> {
        // KIS는 별도 계좌 목록 API 없음 — 사용자가 수동 입력한 계좌 사용
        return emptyList()
    }

    private fun parseAccountId(accountId: String): Pair<String, String> {
        val parts = accountId.split("_")
        return if (parts.size >= 2) parts[0] to parts[1]
        else accountId to "01"
    }

    private fun pushFetchDlq(portfolioId: UUID, accountId: String, cursor: String, errorMessage: String) {
        val payload = runCatching {
            objectMapper.writeValueAsString(FetchParamsPayload(portfolioId, accountId, cursor))
        }.getOrDefault("{}")
        dlqService.push(
            FailedTradeEvent(
                brokerType   = BrokerType.KIS.name,
                accountNo    = accountId,
                payloadType  = FailedTradeEvent.TYPE_FETCH_PARAMS,
                payload      = payload,
                errorMessage = errorMessage,
            )
        )
    }
}
