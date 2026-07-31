import axios from 'axios'
import type {
  OpsSummary, OutboxEventSummary, OutboxEventDetail,
  ReprocessResult, FailedDlqEvent, OutboxListParams,
} from '@/types/ops'

const BASE_URL = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'}/api/admin/ops`

export function createOpsAdminApi(accessToken: string) {
  const api = axios.create({
    baseURL: BASE_URL,
    timeout: 30_000,
    headers: { Authorization: `Bearer ${accessToken}` },
  })

  return {
    summary: async (): Promise<OpsSummary> =>
      (await api.get<OpsSummary>('/summary')).data,

    outboxList: async (params: OutboxListParams): Promise<OutboxEventSummary[]> =>
      (await api.get<OutboxEventSummary[]>('/outbox', { params })).data,

    outboxDetail: async (id: string): Promise<OutboxEventDetail> =>
      (await api.get<OutboxEventDetail>(`/outbox/${id}`)).data,

    reprocess: async (ids: string[]): Promise<ReprocessResult> =>
      (await api.post<ReprocessResult>('/outbox/reprocess', { ids })).data,

    dlqDead: async (broker: string): Promise<FailedDlqEvent[]> =>
      (await api.get<FailedDlqEvent[]>('/dlq/dead', { params: { broker } })).data,

    dlqRequeue: async (broker: string): Promise<{ requeued: number }> =>
      (await api.post<{ requeued: number }>('/dlq/requeue', { broker })).data,
  }
}

export type OpsAdminApi = ReturnType<typeof createOpsAdminApi>
