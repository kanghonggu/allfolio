package com.allfolio.broker.samsung

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
@ConditionalOnProperty(prefix = "samsung", name = ["app-key"], matchIfMissing = false)
class SamsungAdapter(
    private val samsungApiClient: SamsungApiClient,
    private val dlqService: DlqService,
    private val metrics: BrokerMetrics,
    private val objectMapper: ObjectMapper,
) : BrokerAdapter {

    override val brokerType = BrokerType.SAMSUNG

    private val log      = LoggerFactory.getLogger(javaClass)
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")

    override fun fetchTrades(portfolioId: UUID, accountId: String, cursor: String): BrokerTradeResult {
        val accessToken = runCatching {
            metrics.recordApiLatency("SAMSUNG", "resolveToken") {
                samsungApiClient.resolveAccessToken(portfolioId)
            }
        }.getOrElse { e ->
            metrics.apiError("SAMSUNG", "resolveToken")
            log.error("[SamsungAdapter] token resolve failed account={}", accountId, e)
            pushFetchParamsDlq(portfolioId, accountId, cursor, e.message ?: "token error")
            return BrokerTradeResult(emptyList(), cursor)
        }

        val today    = LocalDate.now()
        val fromDate = today.minusDays(90).format(DATE_FMT)
        val toDate   = today.format(DATE_FMT)

        val page = runCatching {
            metrics.recordApiLatency("SAMSUNG", "getTrades") {
                samsungApiClient.getTrades(
                    accessToken = accessToken,
                    accountNo   = accountId,
                    fromDate    = fromDate,
                    toDate      = toDate,
                    cursor      = cursor,
                )
            }
        }.getOrElse { e ->
            metrics.apiError("SAMSUNG", "getTrades")
            log.error("[SamsungAdapter] getTrades failed account={}", accountId, e)
            pushFetchParamsDlq(portfolioId, accountId, cursor, e.message ?: "API error")
            return BrokerTradeResult(emptyList(), cursor)
        }

        if (page.trades.isEmpty()) return BrokerTradeResult(emptyList(), cursor)

        val commands   = page.trades.map { SamsungTradeMapper.toCommand(it, portfolioId, portfolioId) }
        val nextCursor = if (page.hasMore) page.nextCursor ?: "" else ""

        log.info("[SamsungAdapter] fetched {} trades account={} hasMore={}", commands.size, accountId, page.hasMore)
        return BrokerTradeResult(commands, nextCursor)
    }

    override fun fetchAccounts(userId: UUID): List<BrokerAccountInfo> {
        val accessToken = samsungApiClient.resolveAccessToken(userId)
        return samsungApiClient.getAccounts(accessToken).accounts.map { dto ->
            BrokerAccountInfo(accountId = dto.accountNo, accountName = dto.accountName, currency = dto.currency)
        }
    }

    private fun pushFetchParamsDlq(portfolioId: UUID, accountId: String, cursor: String, errorMessage: String) {
        val payload = runCatching { objectMapper.writeValueAsString(FetchParamsPayload(portfolioId, accountId, cursor)) }.getOrDefault("{}")
        dlqService.push(
            FailedTradeEvent(
                brokerType   = BrokerType.SAMSUNG.name,
                accountNo    = accountId,
                payloadType  = FailedTradeEvent.TYPE_FETCH_PARAMS,
                payload      = payload,
                errorMessage = errorMessage,
            )
        )
    }
}
