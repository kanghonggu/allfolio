package com.allfolio.broker.toss

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
@ConditionalOnProperty(prefix = "toss", name = ["client-id"], matchIfMissing = false)
class TossAdapter(
    private val tossApiClient: TossApiClient,
    private val tossProperties: TossProperties,
    private val dlqService: DlqService,
    private val metrics: BrokerMetrics,
    private val objectMapper: ObjectMapper,
) : BrokerAdapter {

    override val brokerType = BrokerType.TOSS

    private val log      = LoggerFactory.getLogger(javaClass)
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")

    override fun fetchTrades(portfolioId: UUID, accountId: String, cursor: String): BrokerTradeResult {
        val accessToken = runCatching {
            metrics.recordApiLatency("TOSS", "resolveToken") {
                tossApiClient.resolveAccessToken(portfolioId)
            }
        }.getOrElse { e ->
            metrics.apiError("TOSS", "resolveToken")
            log.error("[TossAdapter] token resolve failed account={}", accountId, e)
            pushFetchParamsDlq(portfolioId, accountId, cursor, e.message ?: "token error")
            return BrokerTradeResult(emptyList(), cursor)
        }

        val today    = LocalDate.now()
        val fromDate = today.minusDays(90).format(DATE_FMT)
        val toDate   = today.format(DATE_FMT)

        val page = runCatching {
            metrics.recordApiLatency("TOSS", "getTrades") {
                tossApiClient.getTrades(
                    accessToken   = accessToken,
                    accountNumber = accountId,
                    fromDate      = fromDate,
                    toDate        = toDate,
                    cursor        = cursor,
                )
            }
        }.getOrElse { e ->
            metrics.apiError("TOSS", "getTrades")
            log.error("[TossAdapter] getTrades failed account={}", accountId, e)
            pushFetchParamsDlq(portfolioId, accountId, cursor, e.message ?: "API error")
            return BrokerTradeResult(emptyList(), cursor)
        }

        if (page.trades.isEmpty()) return BrokerTradeResult(emptyList(), cursor)

        val commands   = page.trades.map { TossTradeMapper.toCommand(it, portfolioId, portfolioId) }
        val nextCursor = if (page.hasMore) page.nextCursor ?: "" else ""

        log.info("[TossAdapter] fetched {} trades account={} hasMore={}", commands.size, accountId, page.hasMore)
        return BrokerTradeResult(commands, nextCursor)
    }

    override fun fetchAccounts(userId: UUID): List<BrokerAccountInfo> {
        val accessToken = tossApiClient.resolveAccessToken(userId)
        return tossApiClient.getAccounts(accessToken).accounts.map { dto ->
            BrokerAccountInfo(accountId = dto.accountNumber, accountName = dto.accountName, currency = dto.currency)
        }
    }

    private fun pushFetchParamsDlq(portfolioId: UUID, accountId: String, cursor: String, errorMessage: String) {
        val payload = runCatching { objectMapper.writeValueAsString(FetchParamsPayload(portfolioId, accountId, cursor)) }.getOrDefault("{}")
        dlqService.push(
            FailedTradeEvent(
                brokerType   = BrokerType.TOSS.name,
                accountNo    = accountId,
                payloadType  = FailedTradeEvent.TYPE_FETCH_PARAMS,
                payload      = payload,
                errorMessage = errorMessage,
            )
        )
    }
}
