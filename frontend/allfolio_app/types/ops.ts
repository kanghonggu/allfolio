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
  limit?: number
}
