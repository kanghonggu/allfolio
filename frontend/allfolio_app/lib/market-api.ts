import axios from 'axios'
import type { MarketSnapshot } from '@/types/market'

const BASE_URL = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'}/api/market`

export function createMarketApi(accessToken: string) {
  const api = axios.create({
    baseURL: BASE_URL,
    timeout: 30_000,
    headers: { Authorization: `Bearer ${accessToken}` },
  })

  return {
    snapshot: async (): Promise<MarketSnapshot> => (await api.get<MarketSnapshot>('')).data,
  }
}
