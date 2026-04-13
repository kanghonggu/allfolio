'use client'

import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useReportApi } from '@/lib/useApi'
import type { BenchmarkItem, BenchmarkSeries } from '@/types/report'
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip,
  ResponsiveContainer, ReferenceLine, Legend,
  BarChart, Bar, Cell,
} from 'recharts'

const PERIODS = ['1W', '1M', '3M', 'YTD', '1Y'] as const
type Period = typeof PERIODS[number]

const PERIOD_KO: Record<string, string> = {
  '1W': '1주', '1M': '1개월', '3M': '3개월', 'YTD': '연초 이후', '1Y': '1년',
}

function fmtPct(n: number) {
  return `${n >= 0 ? '+' : ''}${n.toFixed(2)}%`
}

export default function BenchmarkPage() {
  const reportApi = useReportApi()
  const [period, setPeriod] = useState<Period>('YTD')

  const { data, isLoading, isError } = useQuery({
    queryKey: ['report', 'benchmark', period],
    queryFn: () => reportApi.benchmark(period),
    enabled: !!reportApi,
  })

  if (isLoading) return <Skeleton />
  if (isError || !data) return <Err />

  const portfolioReturnColor = Number(data.portfolioReturn) >= 0 ? 'text-emerald-400' : 'text-red-400'

  const barData = [
    { name: '내 포트폴리오', value: Number(data.portfolioReturn), color: '#3b82f6' },
    ...data.benchmarks.map((b: BenchmarkItem) => ({
      name: b.name,
      value: Number(b.benchmarkReturn),
      color: Number(b.benchmarkReturn) >= 0 ? '#10b981' : '#ef4444',
    })),
  ]

  const chartData = data.series.map((s: BenchmarkSeries) => ({
    date: s.date,
    portfolio: Number(s.portfolio),
    'S&P 500': Number(s.sp500),
    BTC: Number(s.btc),
    KOSPI: Number(s.kospi),
  }))

  return (
    <div className="space-y-8">
      <div className="flex items-center gap-3">
        <Link href="/unified/reports" className="text-sm text-gray-500 hover:text-gray-300">← 보고서</Link>
        <h1 className="text-2xl font-bold">벤치마크 비교</h1>
      </div>

      {/* Period selector */}
      <div className="flex gap-2">
        {PERIODS.map((p) => (
          <button
            key={p}
            onClick={() => setPeriod(p)}
            className={`px-4 py-1.5 rounded-lg text-sm font-medium transition-colors ${
              period === p ? 'bg-blue-600 text-white' : 'bg-gray-800 text-gray-400 hover:bg-gray-700'
            }`}
          >
            {PERIOD_KO[p]}
          </button>
        ))}
      </div>

      {/* Portfolio return */}
      <div className="rounded-xl border border-gray-700 bg-gray-900 p-6">
        <p className="text-sm text-gray-400">내 포트폴리오 수익률 ({PERIOD_KO[period]})</p>
        <p className={`mt-2 text-4xl font-bold tabular-nums ${portfolioReturnColor}`}>
          {fmtPct(Number(data.portfolioReturn))}
        </p>
      </div>

      {/* Alpha Cards */}
      <div className="grid gap-4 sm:grid-cols-3">
        {data.benchmarks.map((b: BenchmarkItem) => {
          const alpha = Number(b.alpha)
          const alphaColor = alpha >= 0 ? 'text-emerald-400' : 'text-red-400'
          const benchColor = Number(b.benchmarkReturn) >= 0 ? 'text-emerald-400' : 'text-red-400'
          return (
            <div key={b.name} className="rounded-xl border border-gray-700 bg-gray-900 p-5">
              <p className="text-xs text-gray-500">{b.name}</p>
              <p className={`mt-2 text-2xl font-bold tabular-nums ${benchColor}`}>
                {fmtPct(Number(b.benchmarkReturn))}
              </p>
              <div className="mt-3 pt-3 border-t border-gray-800">
                <p className="text-xs text-gray-500">알파 (초과 수익)</p>
                <p className={`mt-1 text-lg font-bold tabular-nums ${alphaColor}`}>
                  {fmtPct(alpha)}
                </p>
              </div>
            </div>
          )
        })}
      </div>

      {/* Bar Chart Comparison */}
      <div className="rounded-xl border border-gray-700 bg-gray-900 p-6">
        <h2 className="mb-4 text-sm font-semibold text-gray-300">수익률 비교 ({PERIOD_KO[period]})</h2>
        <ResponsiveContainer width="100%" height={240}>
          <BarChart data={barData} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="#1f2937" vertical={false} />
            <XAxis dataKey="name" tick={{ fontSize: 11, fill: '#9ca3af' }} axisLine={false} tickLine={false} />
            <YAxis
              tickFormatter={(v) => `${v.toFixed(0)}%`}
              tick={{ fontSize: 11, fill: '#6b7280' }}
              axisLine={false}
              tickLine={false}
            />
            <Tooltip
              formatter={(v: number) => [`${v.toFixed(2)}%`, '수익률']}
              contentStyle={{ background: '#111827', border: '1px solid #374151', borderRadius: 8 }}
              labelStyle={{ color: '#d1d5db' }}
            />
            <ReferenceLine y={0} stroke="#374151" />
            <Bar dataKey="value" radius={[4, 4, 0, 0]}>
              {barData.map((entry, index) => (
                <Cell key={index} fill={entry.color} />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>

      {/* Cumulative Return Chart */}
      <div className="rounded-xl border border-gray-700 bg-gray-900 p-6">
        <h2 className="mb-4 text-sm font-semibold text-gray-300">누적 수익률 비교</h2>
        {chartData.length > 0 ? (
          <ResponsiveContainer width="100%" height={360}>
            <LineChart data={chartData} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#1f2937" />
              <XAxis
                dataKey="date"
                tick={{ fontSize: 10, fill: '#6b7280' }}
                tickLine={false}
                interval="preserveStartEnd"
              />
              <YAxis
                tickFormatter={(v) => `${v.toFixed(0)}%`}
                tick={{ fontSize: 11, fill: '#6b7280' }}
                axisLine={false}
                tickLine={false}
              />
              <Tooltip
                formatter={(v: number, name) => [`${v.toFixed(2)}%`, name]}
                contentStyle={{ background: '#111827', border: '1px solid #374151', borderRadius: 8 }}
                labelStyle={{ color: '#d1d5db' }}
              />
              <ReferenceLine y={0} stroke="#374151" strokeDasharray="4 4" />
              <Legend formatter={(v) => <span className="text-xs text-gray-300">{v}</span>} />
              <Line type="monotone" dataKey="portfolio" name="내 포트폴리오" stroke="#3b82f6" strokeWidth={2.5} dot={false} />
              <Line type="monotone" dataKey="S&P 500" stroke="#10b981" strokeWidth={1.5} dot={false} strokeDasharray="5 5" />
              <Line type="monotone" dataKey="BTC" stroke="#f59e0b" strokeWidth={1.5} dot={false} strokeDasharray="5 5" />
              <Line type="monotone" dataKey="KOSPI" stroke="#8b5cf6" strokeWidth={1.5} dot={false} strokeDasharray="5 5" />
            </LineChart>
          </ResponsiveContainer>
        ) : (
          <div className="flex h-48 items-center justify-center text-sm text-gray-500">데이터 없음</div>
        )}
      </div>

      <p className="text-xs text-gray-600">
        * S&P 500, BTC, KOSPI 수익률은 2024년 추정치 기반 시뮬레이션이며, 실제 지수 데이터와 다를 수 있습니다.
      </p>
    </div>
  )
}

function Skeleton() { return <div className="h-96 animate-pulse rounded-xl bg-gray-800" /> }
function Err() { return <div className="rounded-xl border border-red-800 bg-red-950 p-6 text-sm text-red-400">보고서를 불러올 수 없습니다.</div> }
