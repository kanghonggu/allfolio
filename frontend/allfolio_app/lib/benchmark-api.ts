import axios from 'axios'
import type { BenchmarkConfig, BenchmarkType } from '@/types/returns'

const BASE_URL = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'}/api/benchmark-config`

export function createBenchmarkApi(accessToken: string) {
  const api = axios.create({
    baseURL: BASE_URL,
    timeout: 15_000,
    headers: { Authorization: `Bearer ${accessToken}` },
  })

  return {
    get: async (): Promise<BenchmarkConfig> => (await api.get<BenchmarkConfig>('')).data,

    set: async (indexType: BenchmarkType | null): Promise<BenchmarkConfig> =>
      (await api.put<BenchmarkConfig>('', { indexType })).data,
  }
}
