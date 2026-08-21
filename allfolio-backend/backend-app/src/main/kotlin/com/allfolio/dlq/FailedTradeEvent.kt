package com.allfolio.dlq

import java.time.LocalDateTime
import java.time.ZoneOffset
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
    /**
     * **타입은 `LocalDateTime`으로 남겨 둔다.** 이 클래스는 DTO가 아니라 Redis 저장 포맷이라
     * (`DlqService`가 `writeValueAsString`/`readValue`) 타입을 바꾸면 이미 큐에 있는 항목이
     * 역직렬화에 실패하고, `peekDead`가 그 실패를 삼켜서 관리자 목록에서 조용히 사라진다.
     * 전선은 `FailedDlqEventResponse`가 오프셋을 달아 책임진다.
     *
     * 찍는 시계에는 존을 명시한다 — 값의 의미(UTC 벽시계)를 호스트 TZ에 맡기지 않기 위해서다.
     */
    val createdAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
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
