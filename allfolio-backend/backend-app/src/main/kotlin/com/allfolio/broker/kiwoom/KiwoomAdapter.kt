package com.allfolio.broker.kiwoom

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
import java.time.format.DateTimeFormatter
import java.util.UUID

@Component
@ConditionalOnProperty(prefix = "kiwoom", name = ["app-key"], matchIfMissing = false)
class KiwoomAdapter(
    private val kiwoomApiClient: KiwoomApiClient,
    private val dlqService: DlqService,
    private val metrics: BrokerMetrics,
    private val objectMapper: ObjectMapper,
) : BrokerAdapter {

    override val brokerType = BrokerType.KIWOOM

    private val log      = LoggerFactory.getLogger(javaClass)
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")

    override fun fetchTrades(portfolioId: UUID, accountId: String, cursor: String): BrokerTradeResult {
        val accessToken = runCatching {
            metrics.recordApiLatency("KIWOOM", "resolveToken") {
                kiwoomApiClient.resolveAccessToken(portfolioId)
            }
        }.getOrElse { e ->
            metrics.apiError("KIWOOM", "resolveToken")
            log.error("[KiwoomAdapter] token resolve failed account={}", accountId, e)
            pushFetchDlq(portfolioId, accountId, cursor, e.message ?: "token error")
            return BrokerTradeResult(emptyList(), cursor)
        }

        val today    = LocalDate.now()
        val fromDate = today.minusDays(90).format(DATE_FMT)
        val toDate   = today.format(DATE_FMT)

        val response = runCatching {
            metrics.recordApiLatency("KIWOOM", "getOrderHistory") {
                kiwoomApiClient.getOrderHistory(
                    accessToken = accessToken,
                    accountNo   = accountId,
                    fromDate    = fromDate,
                    toDate      = toDate,
                    nextKey     = cursor,
                )
            }
        }.getOrElse { e ->
            metrics.apiError("KIWOOM", "getOrderHistory")
            log.error("[KiwoomAdapter] getOrderHistory failed account={}", accountId, e)
            pushFetchDlq(portfolioId, accountId, cursor, e.message ?: "API error")
            return BrokerTradeResult(emptyList(), cursor)
        }

        if (response.returnCode != 0) {
            log.warn("[KiwoomAdapter] API error returnCode={}", response.returnCode)
            return BrokerTradeResult(emptyList(), cursor)
        }

        val commands = response.list.mapNotNull { item ->
            KiwoomTradeMapper.toCommand(item, portfolioId, portfolioId)
        }

        val nextCursor = if (response.hasNext) response.nextKey else ""

        log.info("[KiwoomAdapter] fetched {} trades account={} hasMore={}", commands.size, accountId, response.hasNext)
        return BrokerTradeResult(commands, nextCursor)
    }

    override fun fetchAccounts(userId: UUID): List<BrokerAccountInfo> {
        val accessToken = kiwoomApiClient.resolveAccessToken(userId)
        return kiwoomApiClient.getAccounts(accessToken).accountList.map { item ->
            BrokerAccountInfo(
                accountId   = item.accountNo,
                accountName = item.accountName,
                currency    = "KRW",
            )
        }
    }

    private fun pushFetchDlq(portfolioId: UUID, accountId: String, cursor: String, errorMessage: String) {
        val payload = runCatching {
            objectMapper.writeValueAsString(FetchParamsPayload(portfolioId, accountId, cursor))
        }.getOrDefault("{}")
        dlqService.push(
            FailedTradeEvent(
                brokerType   = BrokerType.KIWOOM.name,
                accountNo    = accountId,
                payloadType  = FailedTradeEvent.TYPE_FETCH_PARAMS,
                payload      = payload,
                errorMessage = errorMessage,
            )
        )
    }
}
