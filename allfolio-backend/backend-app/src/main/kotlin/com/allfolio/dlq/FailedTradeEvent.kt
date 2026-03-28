package com.allfolio.dlq

import java.time.LocalDateTime
import java.util.UUID

/**
 * DLQ 이벤트 모델
 *
 * payloadType 구분:
 * - TRADE_COMMAND : RecordTradeCommand JSON — record() 실패 시 Worker가 직접 재시도
 * - FETCH_PARAMS  : FetchParamsPayload JSON — API 호출 실패 시 BrokerSyncScheduler 자연 재시도
 *
 * retryCount: Worker 재처리 횟수. MAX_RETRIES(5) 초과 시 dead-letter 이동
 */
data class FailedTradeEvent(
    val id: UUID = UUID.randomUUID(),
    val brokerType: String,
    val accountNo: String,
    val payloadType: String,     // TRADE_COMMAND | FETCH_PARAMS
    val payload: String,         // JSON
    val errorMessage: String,
    val retryCount: Int = 0,
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    companion object {
        const val TYPE_TRADE_COMMAND = "TRADE_COMMAND"
        const val TYPE_FETCH_PARAMS  = "FETCH_PARAMS"
    }
}

/**
 * Adapter API 실패 시 DLQ에 저장하는 fetch 파라미터
 *
 * BrokerSyncScheduler가 cursor 미진행(syncAccount() 빈 결과)으로 자연 재시도하므로
 * Worker에서는 dead-letter 전환 전까지 audit 용도로만 사용.
 */
data class FetchParamsPayload(
    val portfolioId: UUID,
    val accountId: String,
    val cursor: String,
)
