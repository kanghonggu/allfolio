'use client'

import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useUnifiedApi } from '@/lib/useApi'
import type { AssetSummary, TypeAllocation } from '@/types/unified'
import {
  PieChart, Pie, Cell, Tooltip, ResponsiveContainer, Legend,
} from 'recharts'

const TYPE_COLORS: Record<string, string> = {
  CRYPTO: '#f59e0b', STOCK: '#3b82f6', REAL_ESTATE: '#10b981',
  VEHICLE: '#8b5cf6', GOLD: '#eab308', CASH: '#6b7280', ETC: '#ec4899',
}
const TYPE_KO: Record<string, string> = {
  CRYPTO: '암호화폐', STOCK: '주식', REAL_ESTATE: '부동산',
  VEHICLE: '자동차', GOLD: '금', CASH: '현금', ETC: '기타',
}

function fmt(n: number, currency = 'USD') {
  return new Intl.NumberFormat('ko-KR', {
    style: 'currency', currency, maximumFractionDigits: 0,
  }).format(n)
}

export default function UnifiedDashboard() {
  const api = useUnifiedApi()

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['unified', 'portfolio'],
    queryFn:  () => api!.portfolio.get(),
    enabled:  !!api,
    refetchInterval: 60_000,
  })

  if (isLoading || !api) return <PageSkeleton />
  if (isError)   return <ErrorBox message={(error as Error).message} />
  if (!data)     return null

  const pieData = Object.values(data.byType).map((t: TypeAllocation) => ({
    name:  TYPE_KO[t.type] ?? t.type,
    value: Number(t.totalValue),
    color: TYPE_COLORS[t.type] ?? '#6b7280',
    pct:   Number(t.percentage),
  }))

  return (
    <div className="space-y-8">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">통합 자산 대시보드</h1>
          <p className="mt-1 text-sm text-gray-400">모든 계좌의 자산을 한눈에</p>
        </div>
        <Link
          href="/unified/accounts"
          className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium hover:bg-blue-500 transition-colors"
        >
          계좌 관리
        </Link>
      </div>

      {/* Total Value */}
      <div className="rounded-xl border border-gray-700 bg-gray-900 p-6">
        <p className="text-sm text-gray-400">총 자산</p>
        <p className="mt-2 text-4xl font-bold tabular-nums">
          {fmt(Number(data.totalValue), data.currency)}
        </p>
        <p className="mt-1 text-xs text-gray-500">{data.currency} 기준</p>
      </div>

      {/* Chart + Allocation */}
      <div className="grid gap-6 lg:grid-cols-2">
        <div className="rounded-xl border border-gray-700 bg-gray-900 p-6">
          <h2 className="mb-4 text-sm font-semibold text-gray-300">자산 비중</h2>
          {pieData.length > 0 ? (
            <ResponsiveContainer width="100%" height={260}>
              <PieChart>
                <Pie data={pieData} dataKey="value" cx="50%" cy="50%"
                  innerRadius={60} outerRadius={110} paddingAngle={2}>
                  {pieData.map((entry, i) => (
                    <Cell key={i} fill={entry.color} />
                  ))}
                </Pie>
                <Tooltip formatter={(v: number) => fmt(v, data.currency)}
                  contentStyle={{ background: '#111827', border: '1px solid #374151', borderRadius: 8 }}
                  labelStyle={{ color: '#d1d5db' }} />
                <Legend formatter={(v) => <span className="text-xs text-gray-300">{v}</span>} />
              </PieChart>
            </ResponsiveContainer>
          ) : (
            <EmptyState message="자산 없음" />
          )}
        </div>

        <div className="rounded-xl border border-gray-700 bg-gray-900 p-6">
          <h2 className="mb-4 text-sm font-semibold text-gray-300">유형별 비중</h2>
          <div className="space-y-3">
            {Object.values(data.byType)
              .sort((a, b) => Number(b.totalValue) - Number(a.totalValue))
              .map((t: TypeAllocation) => (
                <div key={t.type} className="flex items-center gap-3">
                  <span
                    className="h-3 w-3 shrink-0 rounded-full"
                    style={{ background: TYPE_COLORS[t.type] ?? '#6b7280' }}
                  />
                  <span className="flex-1 text-sm text-gray-300">{TYPE_KO[t.type] ?? t.type}</span>
                  <div className="flex-1 h-2 rounded-full bg-gray-800 overflow-hidden">
                    <div
                      className="h-full rounded-full transition-all"
                      style={{ width: `${Number(t.percentage)}%`, background: TYPE_COLORS[t.type] ?? '#6b7280' }}
                    />
                  </div>
                  <span className="w-12 text-right text-sm tabular-nums text-gray-300">
                    {Number(t.percentage).toFixed(1)}%
                  </span>
                  <span className="w-28 text-right text-sm tabular-nums text-gray-400">
                    {fmt(Number(t.totalValue), data.currency)}
                  </span>
                </div>
              ))}
          </div>
        </div>
      </div>

      {/* Asset List */}
      <div className="rounded-xl border border-gray-700 bg-gray-900">
        <div className="flex items-center justify-between px-6 py-4 border-b border-gray-700">
          <h2 className="text-sm font-semibold text-gray-300">자산 목록</h2>
          <span className="text-xs text-gray-500">{data.assets.length}개</span>
        </div>
        {data.assets.length === 0 ? (
          <div className="py-12 text-center text-sm text-gray-500">
            자산이 없습니다.{' '}
            <Link href="/unified/accounts/new" className="text-blue-400 hover:underline">
              계좌를 추가
            </Link>
            하고 sync 해주세요.
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs text-gray-500 border-b border-gray-800">
                  <th className="px-6 py-3 font-medium">자산명</th>
                  <th className="px-4 py-3 font-medium">계좌</th>
                  <th className="px-4 py-3 font-medium">유형</th>
                  <th className="px-4 py-3 text-right font-medium">수량</th>
                  <th className="px-4 py-3 text-right font-medium">현재 가치</th>
                  <th className="px-4 py-3 text-right font-medium">손익</th>
                  <th className="px-4 py-3 text-right font-medium">수익률</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-800">
                {data.assets.map((a: AssetSummary) => {
                  const pnl = Number(a.unrealizedPnl)
                  const ret = Number(a.returnRate)
                  return (
                    <tr key={a.id} className="hover:bg-gray-800/50 transition-colors">
                      <td className="px-6 py-3">
                        <div className="font-medium">{a.name}</div>
                        {a.symbol && <div className="text-xs text-gray-500">{a.symbol}</div>}
                      </td>
                      <td className="px-4 py-3 text-gray-400 text-xs">{a.accountName}</td>
                      <td className="px-4 py-3">
                        <span
                          className="rounded-full px-2 py-0.5 text-xs font-medium"
                          style={{ background: `${TYPE_COLORS[a.type]}20`, color: TYPE_COLORS[a.type] ?? '#9ca3af' }}
                        >
                          {TYPE_KO[a.type] ?? a.type}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-right tabular-nums text-gray-300">
                        {Number(a.quantity).toLocaleString('ko-KR', { maximumFractionDigits: 6 })}
                      </td>
                      <td className="px-4 py-3 text-right tabular-nums">
                        {fmt(Number(a.currentValue), a.currency)}
                      </td>
                      <td className={`px-4 py-3 text-right tabular-nums ${pnl >= 0 ? 'text-emerald-400' : 'text-red-400'}`}>
                        {pnl >= 0 ? '+' : ''}{fmt(pnl, a.currency)}
                      </td>
                      <td className={`px-4 py-3 text-right tabular-nums ${ret >= 0 ? 'text-emerald-400' : 'text-red-400'}`}>
                        {ret >= 0 ? '+' : ''}{ret.toFixed(2)}%
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  )
}

function PageSkeleton() {
  return (
    <div className="space-y-8">
      <div className="h-8 w-48 animate-pulse rounded bg-gray-800" />
      <div className="h-32 animate-pulse rounded-xl bg-gray-800" />
      <div className="grid gap-6 lg:grid-cols-2">
        <div className="h-72 animate-pulse rounded-xl bg-gray-800" />
        <div className="h-72 animate-pulse rounded-xl bg-gray-800" />
      </div>
      <div className="h-64 animate-pulse rounded-xl bg-gray-800" />
    </div>
  )
}
function EmptyState({ message }: { message: string }) {
  return <div className="flex h-48 items-center justify-center text-sm text-gray-500">{message}</div>
}
function ErrorBox({ message }: { message: string }) {
  return (
    <div className="rounded-xl border border-red-800 bg-red-950 p-6">
      <p className="text-sm font-medium text-red-400">오류 발생</p>
      <p className="mt-1 text-sm text-red-500">{message}</p>
    </div>
  )
}
