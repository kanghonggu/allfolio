'use client'

import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useReportApi } from '@/lib/useApi'
import type { DailyPerf } from '@/types/report'
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip,
  ResponsiveContainer, ReferenceLine, Legend,
} from 'recharts'

const PERIODS = ['1W', '1M', '3M', 'YTD', '1Y'] as const
type Period = typeof PERIODS[number]

function fmtPct(n: number | null) {
  if (n === null || n === undefined) return '—'
  return `${n >= 0 ? '+' : ''}${n.toFixed(2)}%`
}

export default function PerformancePage() {
  const reportApi = useReportApi()
  const [period, setPeriod] = useState<Period>('1M')

  const { data, isLoading, isError } = useQuery({
    queryKey: ['report', 'performance', period],
    queryFn: () => reportApi.performance(period),
    enabled: !!reportApi,
  })

  if (isLoading) return <Skeleton />
  if (isError || !data) return <Err />

  const periodLabels: Record<string, string> = {
    '1W': '1주', '1M': '1개월', '3M': '3개월', 'YTD': '연초 이후', '1Y': '1년',
  }

  const chartData = data.dailySeries.map((d: DailyPerf) => ({
    date: d.date,
    cumReturn: Number(d.cumulativeReturn),
    dailyReturn: Number(d.dailyReturn),
    nav: Number(d.nav),
  }))

  const totalReturnColor = Number(data.totalReturn) >= 0 ? 'text-emerald-400' : 'text-red-400'

  return (
    <div className="space-y-8">
      <div className="flex items-center gap-3">
        <Link href="/unified/reports" className="text-sm text-gray-500 hover:text-gray-300">← 보고서</Link>
        <h1 className="text-2xl font-bold">수익률 분석</h1>
      </div>

      {/* Period Selector */}
      <div className="flex gap-2">
        {PERIODS.map((p) => (
          <button
            key={p}
            onClick={() => setPeriod(p)}
            className={`px-4 py-1.5 rounded-lg text-sm font-medium transition-colors ${
              period === p
                ? 'bg-blue-600 text-white'
                : 'bg-gray-800 text-gray-400 hover:bg-gray-700'
            }`}
          >
            {periodLabels[p]}
          </button>
        ))}
      </div>

      {/* Total Return */}
      <div className="rounded-xl border border-gray-700 bg-gray-900 p-6">
        <p className="text-sm text-gray-400">전체 수익률 (매입 원가 기준)</p>
        <p className={`mt-2 text-4xl font-bold tabular-nums ${totalReturnColor}`}>
          {fmtPct(Number(data.totalReturn))}
        </p>
        {data.twr !== null && (
          <p className="mt-1 text-sm text-gray-500">TWR: {fmtPct(Number(data.twr))}</p>
        )}
      </div>

      {/* Period Returns Grid */}
      <div className="grid gap-3 grid-cols-2 sm:grid-cols-5">
        {PERIODS.map((p) => {
          const val = data.periodReturns[p]
          const n = val !== null && val !== undefined ? Number(val) : null
          return (
            <div key={p} className={`rounded-xl border bg-gray-900 p-4 ${period === p ? 'border-blue-600' : 'border-gray-700'}`}>
              <p className="text-xs text-gray-500">{periodLabels[p]}</p>
              <p className={`mt-1 text-xl font-bold tabular-nums ${
                n === null ? 'text-gray-600' : n >= 0 ? 'text-emerald-400' : 'text-red-400'
              }`}>
                {n === null ? '—' : fmtPct(n)}
              </p>
            </div>
          )
        })}
      </div>

      {/* Cumulative Return Chart */}
      <div className="rounded-xl border border-gray-700 bg-gray-900 p-6">
        <h2 className="mb-4 text-sm font-semibold text-gray-300">누적 수익률 시계열</h2>
        {chartData.length > 0 ? (
          <ResponsiveContainer width="100%" height={320}>
            <LineChart data={chartData} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#1f2937" />
              <XAxis dataKey="date" tick={{ fontSize: 11, fill: '#6b7280' }} tickLine={false} />
              <YAxis
                tickFormatter={(v) => `${v.toFixed(1)}%`}
                tick={{ fontSize: 11, fill: '#6b7280' }}
                tickLine={false}
                axisLine={false}
              />
              <Tooltip
                formatter={(v: number) => [`${v.toFixed(2)}%`]}
                contentStyle={{ background: '#111827', border: '1px solid #374151', borderRadius: 8 }}
                labelStyle={{ color: '#d1d5db' }}
              />
              <ReferenceLine y={0} stroke="#374151" strokeDasharray="4 4" />
              <Line
                type="monotone" dataKey="cumReturn" name="누적 수익률"
                stroke="#3b82f6" strokeWidth={2} dot={false}
              />
            </LineChart>
          </ResponsiveContainer>
        ) : (
          <Empty message="성과 이력이 없습니다. 자산을 추가하고 sync 해주세요." />
        )}
      </div>

      {/* Daily NAV Chart */}
      {chartData.length > 0 && (
        <div className="rounded-xl border border-gray-700 bg-gray-900 p-6">
          <h2 className="mb-4 text-sm font-semibold text-gray-300">일별 NAV</h2>
          <ResponsiveContainer width="100%" height={240}>
            <LineChart data={chartData} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#1f2937" />
              <XAxis dataKey="date" tick={{ fontSize: 11, fill: '#6b7280' }} tickLine={false} />
              <YAxis
                tickFormatter={(v) => `$${(v / 1000).toFixed(0)}k`}
                tick={{ fontSize: 11, fill: '#6b7280' }}
                tickLine={false}
                axisLine={false}
              />
              <Tooltip
                formatter={(v: number) => [new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 }).format(v)]}
                contentStyle={{ background: '#111827', border: '1px solid #374151', borderRadius: 8 }}
              />
              <Line type="monotone" dataKey="nav" name="NAV" stroke="#10b981" strokeWidth={2} dot={false} />
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}

      {data.benchmarkAlpha !== null && (
        <div className="rounded-xl border border-gray-700 bg-gray-900 p-4">
          <p className="text-xs text-gray-500">벤치마크 대비 알파</p>
          <p className={`mt-1 text-xl font-bold ${Number(data.benchmarkAlpha) >= 0 ? 'text-emerald-400' : 'text-red-400'}`}>
            {fmtPct(Number(data.benchmarkAlpha))}
          </p>
        </div>
      )}
    </div>
  )
}

function Skeleton() { return <div className="h-96 animate-pulse rounded-xl bg-gray-800" /> }
function Err() { return <div className="rounded-xl border border-red-800 bg-red-950 p-6 text-sm text-red-400">보고서를 불러올 수 없습니다.</div> }
function Empty({ message }: { message: string }) {
  return <div className="flex h-48 items-center justify-center text-sm text-gray-500">{message}</div>
}
