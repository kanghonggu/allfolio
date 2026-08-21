// Outbox·DLQ 운영 모니터링 (AF-7/8)

export type OutboxStatus = 'PENDING' | 'PROCESSED' | 'PROCESSED_KAFKA' | 'FAILED' | 'DEAD'

export interface OpsSummary {
  outbox: Record<string, number>
  dlq: DlqBrokerSummary[]
}

export interface DlqBrokerSummary {
  broker: string
  waiting: number
  dead: number
}

export interface OutboxEventSummary {
  id: string
  aggregateType: string
  aggregateId: string
  eventType: string
  status: string
  retryCount: number
  errorMessage: string | null
  createdAt: string
  processedAt: string | null
}

export interface OutboxEventDetail extends OutboxEventSummary {
  payload: string
}

export interface ReprocessResult {
  processed: number
  failed: number
  skipped: number
}

export interface FailedDlqEvent {
  id: string
  brokerType: string
  accountNo: string
  payloadType: string
  payload: string
  errorMessage: string
  retryCount: number
  createdAt: string
}

export interface OutboxListParams {
  status: OutboxStatus
  eventType?: string
  from?: string
  to?: string
  /**
   * 날짜 필터가 어느 달력의 하루인지 서버에 알린다 (IANA 존 이름).
   *
   * 표시 시각은 서버가 오프셋을 실어 보내므로 브라우저가 알아서 맞추지만, 필터는 질의 시점에
   * 존이 필요하고 그걸 아는 건 클라이언트뿐이다. 안 보내면 서버는 UTC 하루로 잡는다 —
   * 한국에서는 창이 9시간 밀린다.
   */
  zone?: string
  limit?: number
}
