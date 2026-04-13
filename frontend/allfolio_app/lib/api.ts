import axios from 'axios'
import { getSession } from 'next-auth/react'
import type { PortfolioSnapshot, Position } from '@/types/portfolio'
import type { Trade } from '@/types/trade'

const api = axios.create({
  baseURL: '/api',  // next.config.mjs rewrites → localhost:8090
  timeout: 10_000,
})

api.interceptors.request.use(async (config) => {
  const session = await getSession()
  if (session?.accessToken) {
    config.headers.Authorization = `Bearer ${session.accessToken}`
  }
  return config
})

// ── Portfolio ──────────────────────────────────────────────────

export async function getLatestSnapshot(
  portfolioId: string,
  tenantId: string,
): Promise<PortfolioSnapshot> {
  const { data } = await api.get<PortfolioSnapshot>(
    `/portfolios/${portfolioId}/snapshot/latest`,
    { params: { tenantId } },
  )
  return data
}

export async function getSnapshotByDate(
  portfolioId: string,
  date: string,
  tenantId: string,
): Promise<PortfolioSnapshot> {
  const { data } = await api.get<PortfolioSnapshot>(
    `/portfolios/${portfolioId}/snapshot/${date}`,
    { params: { tenantId } },
  )
  return data
}

// ── Positions ─────────────────────────────────────────────────

export async function getPositions(portfolioId: string): Promise<Position[]> {
  const { data } = await api.get<Position[]>(`/portfolios/${portfolioId}/positions`)
  return data
}

// ── Trades ────────────────────────────────────────────────────

export async function getTrades(portfolioId: string): Promise<Trade[]> {
  const { data } = await api.get<Trade[]>(`/portfolios/${portfolioId}/trades`)
  return data
}
