// lib/report-archive-api.ts
import axios from 'axios'
import type { ArchiveMeta, ArchiveDetail } from '@/types/report-archive'
import type { MonthlyReportBody } from '@/types/monthly-report'

const BASE_URL = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'}/api/reports/archive`

export const MONTHLY_REPORT = 'MONTHLY_REPORT'
export const DIVIDEND_INTEREST = 'DIVIDEND_INTEREST'
export const COST = 'COST'
export const CASHFLOW = 'CASHFLOW'

export type ReportType = typeof MONTHLY_REPORT | typeof DIVIDEND_INTEREST | typeof COST | typeof CASHFLOW

export function createReportArchiveApi(accessToken: string, reportType: ReportType) {
  const api = axios.create({
    baseURL: BASE_URL,
    timeout: 30_000,
    headers: { Authorization: `Bearer ${accessToken}` },
  })

  return {
    generate: async (year: number, month: number): Promise<ArchiveMeta> =>
      (await api.post<ArchiveMeta>('/generate', { type: reportType, year, month })).data,

    list: async (): Promise<ArchiveMeta[]> =>
      (await api.get<ArchiveMeta[]>('', { params: { type: reportType } })).data,

    detail: async (id: string): Promise<ArchiveDetail> =>
      (await api.get<ArchiveDetail>(`/${id}`)).data,
  }
}

export function parseReportBody<T>(body: string): T {
  return JSON.parse(body) as T
}

export function parseMonthlyReportBody(body: string): MonthlyReportBody {
  return parseReportBody<MonthlyReportBody>(body)
}
