'use client'

import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useReportApi } from '@/lib/useApi'
import type { NetWorthBreakdown, NetWorthPoint } from '@/types/report'
import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid,
  Tooltip, ResponsiveContainer,
} from 'recharts'

const TYPE_KO: Record<string, string> = {
  STOCK: '주식', CRYPTO: '암호화폐', REAL_ESTATE: '부동산',
  VEHICLE: '자동차', GOLD: '금', CASH: '현금', ETC: '기타',
}
const TYPE_COLORS: Record<string, string> = {
  STOCK: '#3b82f6', CRYPTO: '#f59e0b', REAL_ESTATE: '#10b981',
  VEHICLE: '#8b5cf6', GOLD: '#eab308', CASH: '#6b7280', ETC: '#ec4899',
}

function fmt(n: number, currency = 'KRW') {
  return new Intl.NumberFormat('ko-KR', { style: 'currency', currency, maximumFractionDigits: 0 }).format(n)
}
function fmtShort(n: number) {
  if (Math.abs(n) >= 100_000_000) return `${(n / 100_000_000).toFixed(1)}억`
  if (Math.abs(n) >= 10_000) return `${(n / 10_000).toFixed(0)}만`
  return n.toLocaleString('ko-KR')
}

export default function NetWorthPage() {
  const reportApi = useReportApi()
  const { data, isLoading, isError } = useQuery({
    queryKey: ['report', 'networth'],
    queryFn: () => reportApi!.networth(),
    enabled: !!reportApi,
  })

  if (isLoading) return <Skeleton />
  if (isError || !data) return <Err />

  const pnlColor = data.netWorth >= 0 ? 'text-emerald-400' : 'text-red-400'
  const loanRatio = data.totalAssets > 0
    ? ((data.totalLoan / data.totalAssets) * 100).toFixed(1)
    : '0.0'

  const chartData = data.trend.map((p: NetWorthPoint) => ({
    date: p.date,
    nav: Number(p.nav),
  }))

  return (
    <div className="space-y-8">
      <div className="flex items-center gap-3">
        <Link href="/unified/reports" className="text-sm text-gray-500 hover:text-gray-300">← 보고서</Link>
        <h1 className="text-2xl font-bold">순자산 추이</h1>
      </div>
      <p className="text-xs text-gray-500">생성: {new Date(data.generatedAt).toLocaleString('ko-KR')}</p>

      {/* 핵심 KPI */}
      <div className="grid gap-4 sm:grid-cols-3">
        <div className="rounded-xl border border-gray-700 bg-gray-900 p-5">
          <p className="text-xs text-gray-500">총 자산</p>
          <p className="mt-2 text-2xl font-bold tabular-nums">{fmt(data.totalAssets)}</p>
        </div>
        <div className="rounded-xl border border-red-900 bg-gray-900 p-5">
          <p className="text-xs text-gray-500">총 부채</p>
          <p className="mt-2 text-2xl font-bold tabular-nums text-red-400">
            {data.totalLoan > 0 ? `-${fmt(data.totalLoan)}` : '—'}
          </p>
          {data.totalLoan > 0 && (
            <p className="mt-0.5 text-xs text-red-600">자산 대비 {loanRatio}%</p>
          )}
        </div>
        <div className={`rounded-xl border bg-gray-900 p-5 ${data.netWorth >= 0 ? 'border-emerald-800' : 'border-red-800'}`}>
          <p className="text-xs text-gray-500">순자산 (NAV - 부채)</p>
          <p className={`mt-2 text-2xl font-bold tabular-nums ${pnlColor}`}>{fmt(data.netWorth)}</p>
        </div>
      </div>

      {/* 순자산 추이 차트 */}
      <div className="rounded-xl border border-gray-700 bg-gray-900 p-6">
        <h2 className="mb-4 text-sm font-semibold text-gray-300">총 자산(NAV) 추이</h2>
        {chartData.length >= 2 ? (
          <ResponsiveContainer width="100%" height={280}>
            <AreaChart data={chartData} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
              <defs>
                <linearGradient id="navGrad" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor="#3b82f6" stopOpacity={0.3} />
                  <stop offset="95%" stopColor="#3b82f6" stopOpacity={0} />
                </linearGradient>
              </defs>
              <CartesianGrid strokeDasharray="3 3" stroke="#1f2937" />
              <XAxis dataKey="date" tick={{ fontSize: 11, fill: '#6b7280' }} tickLine={false} />
              <YAxis
                tickFormatter={(v) => `₩${fmtShort(v)}`}
                tick={{ fontSize: 11, fill: '#6b7280' }}
                tickLine={false}
                axisLine={false}
                width={70}
              />
              <Tooltip
                formatter={(v: number) => [fmt(v), '총 자산']}
                contentStyle={{ background: '#111827', border: '1px solid #374151', borderRadius: 8 }}
                labelStyle={{ color: '#d1d5db' }}
              />
              <Area
                type="monotone"
                dataKey="nav"
                name="총 자산"
                stroke="#3b82f6"
                strokeWidth={2}
                fill="url(#navGrad)"
                dot={false}
              />
            </AreaChart>
          </ResponsiveContainer>
        ) : (
          <div className="flex h-48 flex-col items-center justify-center gap-2 text-center text-sm text-gray-500">
            <p>자산 이력이 부족합니다.</p>
            <p className="text-xs text-gray-600">매일 Sync를 실행하면 추이 그래프가 채워집니다.</p>
          </div>
        )}
      </div>

      {/* 유형별 순자산 */}
      <div className="rounded-xl border border-gray-700 bg-gray-900 p-6">
        <h2 className="mb-4 text-sm font-semibold text-gray-300">유형별 순자산</h2>
        <div className="space-y-3">
          {data.byType.map((b: NetWorthBreakdown) => {
            const nw = Number(b.netWorth)
            const isPos = nw >= 0
            return (
              <div key={b.type} className="grid grid-cols-[auto_1fr_auto_auto_auto] items-center gap-4">
                <span
                  className="h-3 w-3 shrink-0 rounded-full"
                  style={{ background: TYPE_COLORS[b.type] ?? '#6b7280' }}
                />
                <span className="text-sm text-gray-300">{TYPE_KO[b.type] ?? b.type}</span>
                <span className="text-xs text-gray-500 tabular-nums">{fmt(Number(b.assets))}</span>
                {Number(b.loan) > 0 && (
                  <span className="text-xs text-red-400 tabular-nums">-{fmt(Number(b.loan))}</span>
                )}
                <span className={`text-sm font-semibold tabular-nums ${isPos ? 'text-white' : 'text-red-400'}`}>
                  {fmt(nw)}
                  <span className="ml-1 text-xs font-normal text-gray-500">({Number(b.pct).toFixed(1)}%)</span>
                </span>
              </div>
            )
          })}
        </div>

        {/* 비중 바 */}
        <div className="mt-4 flex h-3 overflow-hidden rounded-full bg-gray-800">
          {data.byType.filter(b => Number(b.netWorth) > 0).map((b: NetWorthBreakdown) => (
            <div
              key={b.type}
              style={{ width: `${Number(b.pct)}%`, background: TYPE_COLORS[b.type] ?? '#6b7280' }}
              title={`${TYPE_KO[b.type] ?? b.type}: ${Number(b.pct).toFixed(1)}%`}
            />
          ))}
        </div>
      </div>

      {/* 부채 안내 */}
      {data.totalLoan > 0 && (
        <div className="rounded-xl border border-amber-800 bg-amber-950/30 p-4 text-xs text-amber-400">
          부채 비율 {loanRatio}% — 일반적으로 자산 대비 부채 비율 40% 이하를 권장합니다.
        </div>
      )}
    </div>
  )
}

function Skeleton() { return <div className="h-96 animate-pulse rounded-xl bg-gray-800" /> }
function Err() { return <div className="rounded-xl border border-red-800 bg-red-950 p-6 text-sm text-red-400">보고서를 불러올 수 없습니다.</div> }
