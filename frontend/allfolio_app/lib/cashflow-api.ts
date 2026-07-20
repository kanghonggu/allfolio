import axios from 'axios'
import type { CashFlowItem, RecordCashFlowRequest } from '@/types/returns'

const BASE_URL = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'}/api/cashflows`

export function createCashFlowApi(accessToken: string) {
  const api = axios.create({
    baseURL: BASE_URL,
    timeout: 15_000,
    headers: { Authorization: `Bearer ${accessToken}` },
  })

  return {
    list: async (from?: string, to?: string): Promise<CashFlowItem[]> =>
      (await api.get<CashFlowItem[]>('', { params: from && to ? { from, to } : {} })).data,

    record: async (req: RecordCashFlowRequest): Promise<CashFlowItem> =>
      (await api.post<CashFlowItem>('', req)).data,

    remove: async (id: string): Promise<void> => {
      await api.delete(`/${id}`)
    },
  }
}
