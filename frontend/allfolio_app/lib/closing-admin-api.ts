import axios from 'axios'
import type { WfDayDetail, WfJobLogView, WfMonthView, WfRunSummary } from '@/types/closing'

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'
const BASE_URL = `${API_BASE}/api/admin/closing`

export function closingSseUrl(accessToken: string) {
  return `${API_BASE}/api/sse/closing?token=${encodeURIComponent(accessToken)}`
}

export function createClosingAdminApi(accessToken: string) {
  const api = axios.create({
    baseURL: BASE_URL,
    timeout: 120_000, // 전 사용자 루프 액션 대비
    headers: { Authorization: `Bearer ${accessToken}` },
  })

  return {
    dashboard: async (month: string): Promise<WfMonthView> =>
      (await api.get<WfMonthView>('/dashboard', { params: { month } })).data,

    dayDetail: async (ymd: string): Promise<WfDayDetail> =>
      (await api.get<WfDayDetail>(`/days/${ymd}`)).data,

    runDay: async (ymd: string): Promise<WfRunSummary> =>
      (await api.post<WfRunSummary>(`/days/${ymd}/run`)).data,

    runSubStep: async (ymd: string, stepCd: string, subStepCd: string): Promise<{ status: string }> =>
      (await api.post<{ status: string }>(`/days/${ymd}/steps/${stepCd}/substeps/${subStepCd}/run`)).data,

    manualComplete: async (
      ymd: string, stepCd: string, subStepCd: string,
      result: 'SUCCESS' | 'ERROR', remark: string,
    ): Promise<void> => {
      await api.post(`/days/${ymd}/steps/${stepCd}/substeps/${subStepCd}/manual`, { result, remark })
    },

    reworkLogs: async (ymd: string): Promise<WfJobLogView[]> =>
      (await api.get<WfJobLogView[]>('/jobs/rework', { params: { ymd } })).data,

    holidays: async (year: number): Promise<Record<string, string | null>> =>
      (await api.get<Record<string, string | null>>('/holidays', { params: { year } })).data,
  }
}

export type ClosingAdminApi = ReturnType<typeof createClosingAdminApi>
