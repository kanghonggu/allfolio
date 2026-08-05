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
import PageHeader from '@/components/ui/PageHeader'
import SectionHeader from '@/components/ui/SectionHeader'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import { LoadingState, ErrorState, EmptyState } from '@/components/ui/states'
import { dirTone, toneText } from '@/lib/format'

const PERIODS = ['1W', '1M', '3M', 'YTD', '1Y'] as const
type Period = typeof PERIODS[number]

const PERIOD_KO: Record<string, string> = {
  '1W': '1주', '1M': '1개월', '3M': '3개월', 'YTD': '연초 이후', '1Y': '1년',
}

const TOOLTIP_STYLE = {
  background: 'var(--c-surface)',
  border: '1px solid var(--c-line-card)',
  borderRadius: 0,
  color: 'var(--c-ink)',
} as const
const TICK_STYLE = { fontSize: 10, fill: 'var(--c-fg-faint)', fontFamily: 'monospace' } as const

function fmtPct(n: number) {
  return `${n >= 0 ? '+' : ''}${n.toFixed(2)}%`
}

export default function BenchmarkPage() {
  const reportApi = useReportApi()
  const [period, setPeriod] = useState<Period>('YTD')

  const { data, isLoading, isError } = useQuery({
    queryKey: ['report', 'benchmark', period],
    queryFn: () => reportApi!.benchmark(period),
    enabled: !!reportApi,
  })

  if (isLoading) return <Skeleton />
  if (isError || !data) return <Err />

  const barData = [
    { name: '내 포트폴리오', value: Number(data.portfolioReturn), color: 'var(--c-ink)' },
    ...data.benchmarks.map((b: BenchmarkItem) => ({
      name: b.name,
      value: Number(b.benchmarkReturn),
      // 한국 관례: 상승 빨강(gain) / 하락 파랑(loss)
      color: Number(b.benchmarkReturn) >= 0 ? 'var(--c-gain)' : 'var(--c-loss)',
    })),
  ]

  const chartData = data.series.map((s: BenchmarkSeries) => ({
    date: s.date,
    portfolio: Number(s.portfolio),
    'S&P 500': s.sp500 !== null ? Number(s.sp500) : null,
    BTC: s.btc !== null ? Number(s.btc) : null,
    KOSPI: s.kospi !== null ? Number(s.kospi) : null,
  }))

  return (
    <div className="border border-line-card bg-surface">
      <div className="px-5 pt-4 sm:px-7">
        <Link
          href="/unified/reports"
          className="font-mono text-[10px] tracking-label text-fg-faint transition-colors hover:text-ink"
        >
          ← 보고서
        </Link>
      </div>
      <PageHeader
        className="px-5 pt-2 sm:px-7"
        title="벤치마크 비교"
        meta="B-06 · 스냅샷 기반 자동 산출"
      />

      <div className="px-5 py-5 pb-10 sm:px-7">
        {/* Period selector */}
        <div className="flex flex-wrap gap-2">
          {PERIODS.map((p) => (
            <button
              key={p}
              onClick={() => setPeriod(p)}
              className={`border px-3.5 py-1.5 font-mono text-[10px] tracking-label transition-colors ${
                period === p
                  ? 'border-ink bg-ink text-white'
                  : 'border-line bg-surface text-fg-3 hover:border-ink hover:text-ink'
              }`}
            >
              {PERIOD_KO[p]}
            </button>
          ))}
        </div>

        {/* Portfolio return */}
        <div className="mt-6 border border-line-soft bg-surface px-4 py-4">
          <Label size="sm" tone="faint">내 포트폴리오 수익률 ({PERIOD_KO[period]})</Label>
          <Num tone={dirTone(Number(data.portfolioReturn))} className="mt-1.5 block text-[26px]">
            {fmtPct(Number(data.portfolioReturn))}
          </Num>
        </div>

        {/* Alpha Cards — 지수 데이터 없으면 명시적 빈 상태 (합성값 표시 금지) */}
        {data.benchmarks.length === 0 && (
          <p className="mt-3 border border-line-soft bg-surface-muted px-4 py-4 text-[12.5px] leading-relaxed text-fg-3">
            벤치마크 지수 데이터가 아직 수집되지 않았습니다. 지수 시세는 매일 새벽 자동
            동기화되며, 수집되는 대로 실제 지수 기준 비교가 표시됩니다.
          </p>
        )}
        {data.benchmarks.length > 0 && (
          <div className="mt-3 grid gap-px border border-line-soft bg-line-soft sm:grid-cols-3">
            {data.benchmarks.map((b: BenchmarkItem) => {
              const alpha = Number(b.alpha)
              const alphaClass = toneText[dirTone(alpha)]
              const benchClass = toneText[dirTone(Number(b.benchmarkReturn))]
              return (
                <div key={b.name} className="bg-surface px-3.5 py-3">
                  <Label size="sm" tone="faint">{b.name}</Label>
                  <Num className={`mt-1 block text-[16px] ${benchClass}`}>
                    {fmtPct(Number(b.benchmarkReturn))}
                  </Num>
                  <div className="mt-3 border-t border-line-hair pt-3">
                    <Label size="sm" tone="faint">알파 (초과 수익)</Label>
                    <Num className={`mt-1 block text-[14px] ${alphaClass}`}>{fmtPct(alpha)}</Num>
                  </div>
                </div>
              )
            })}
          </div>
        )}

        {/* Bar Chart Comparison */}
        <section className="mt-8">
          <SectionHeader label={`수익률 비교 (${PERIOD_KO[period]})`} />
          <ResponsiveContainer width="100%" height={240}>
            <BarChart data={barData} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--c-line)" vertical={false} />
              <XAxis dataKey="name" tick={TICK_STYLE} axisLine={false} tickLine={false} />
              <YAxis
                tickFormatter={(v) => `${v.toFixed(0)}%`}
                tick={TICK_STYLE}
                axisLine={false}
                tickLine={false}
              />
              <Tooltip
                formatter={(v: number) => [`${v.toFixed(2)}%`, '수익률']}
                contentStyle={TOOLTIP_STYLE}
                labelStyle={{ color: 'var(--c-fg-muted)' }}
              />
              <ReferenceLine y={0} stroke="var(--c-line)" />
              <Bar dataKey="value">
                {barData.map((entry, index) => (
                  <Cell key={index} fill={entry.color} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </section>

        {/* Cumulative Return Chart */}
        <section className="mt-8">
          <SectionHeader label="누적 수익률 비교" />
          {chartData.length > 0 ? (
            <ResponsiveContainer width="100%" height={360}>
              <LineChart data={chartData} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="var(--c-line)" />
                <XAxis
                  dataKey="date"
                  tick={TICK_STYLE}
                  tickLine={false}
                  interval="preserveStartEnd"
                />
                <YAxis
                  tickFormatter={(v) => `${v.toFixed(0)}%`}
                  tick={TICK_STYLE}
                  axisLine={false}
                  tickLine={false}
                />
                <Tooltip
                  formatter={(v: number, name) => [`${v.toFixed(2)}%`, name]}
                  contentStyle={TOOLTIP_STYLE}
                  labelStyle={{ color: 'var(--c-fg-muted)' }}
                />
                <ReferenceLine y={0} stroke="var(--c-line)" strokeDasharray="4 4" />
                <Legend formatter={(v) => <span className="font-mono text-[10px] text-fg-3">{v}</span>} />
                <Line type="monotone" dataKey="portfolio" name="내 포트폴리오" stroke="var(--c-ink)" strokeWidth={2.5} dot={false} />
                <Line type="monotone" dataKey="S&P 500" stroke="var(--c-fg-muted)" strokeWidth={1.5} dot={false} strokeDasharray="5 5" connectNulls />
                <Line type="monotone" dataKey="BTC" stroke="var(--c-fg-ghost)" strokeWidth={1.5} dot={false} strokeDasharray="5 5" connectNulls />
                <Line type="monotone" dataKey="KOSPI" stroke="var(--c-line)" strokeWidth={1.5} dot={false} strokeDasharray="5 5" connectNulls />
              </LineChart>
            </ResponsiveContainer>
          ) : (
            <EmptyState title="데이터 없음" />
          )}
        </section>

        <p className="mt-4 text-[11.5px] leading-relaxed text-fg-faint">
          * S&amp;P 500, BTC, KOSPI는 일별 종가 기준 실제 지수 데이터입니다. 데이터가 없는 날짜는 표시되지 않습니다.
        </p>
      </div>
    </div>
  )
}

function Skeleton() {
  return (
    <div className="border border-line-card bg-surface px-5 sm:px-7">
      <LoadingState />
    </div>
  )
}
function Err() {
  return (
    <div className="border border-line-card bg-surface px-5 sm:px-7">
      <ErrorState message="보고서를 불러올 수 없습니다." />
    </div>
  )
}
