// lib/report-archive-api.ts
import axios from 'axios'
import type { ArchiveMeta, ArchiveDetail, MonthlyReportBody } from '@/types/monthly-report'

const BASE_URL = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'}/api/reports/archive`

export const MONTHLY_REPORT = 'MONTHLY_REPORT'

export function createReportArchiveApi(accessToken: string) {
  const api = axios.create({
    baseURL: BASE_URL,
    timeout: 30_000,
    headers: { Authorization: `Bearer ${accessToken}` },
  })

  return {
    generate: async (year: number, month: number): Promise<ArchiveMeta> =>
      (await api.post<ArchiveMeta>('/generate', { type: MONTHLY_REPORT, year, month })).data,

    list: async (): Promise<ArchiveMeta[]> =>
      (await api.get<ArchiveMeta[]>('', { params: { type: MONTHLY_REPORT } })).data,

    detail: async (id: string): Promise<ArchiveDetail> =>
      (await api.get<ArchiveDetail>(`/${id}`)).data,
  }
}

export function parseMonthlyReportBody(body: string): MonthlyReportBody {
  return JSON.parse(body) as MonthlyReportBody
}
