import axios from 'axios'
import type {
  SummaryReport, AllocationReport, PerformanceReport,
  RiskReport, PositionsReport, BenchmarkReport,
} from '@/types/report'

const BASE_URL = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'}/api/reports`

export function createReportApi(accessToken: string) {
  const api = axios.create({
    baseURL: BASE_URL,
    timeout: 30_000,
    headers: { Authorization: `Bearer ${accessToken}` },
  })

  return {
    summary: async (): Promise<SummaryReport> =>
      (await api.get<SummaryReport>('/summary')).data,

    allocation: async (): Promise<AllocationReport> =>
      (await api.get<AllocationReport>('/allocation')).data,

    performance: async (period = '1M'): Promise<PerformanceReport> =>
      (await api.get<PerformanceReport>('/performance', { params: { period } })).data,

    risk: async (): Promise<RiskReport> =>
      (await api.get<RiskReport>('/risk')).data,

    positions: async (): Promise<PositionsReport> =>
      (await api.get<PositionsReport>('/positions')).data,

    benchmark: async (period = 'YTD'): Promise<BenchmarkReport> =>
      (await api.get<BenchmarkReport>('/benchmark', { params: { period } })).data,
  }
}
