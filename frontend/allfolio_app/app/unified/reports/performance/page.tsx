'use client'

import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useReportApi } from '@/lib/useApi'
import type { DailyPerf } from '@/types/report'
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip,
  ResponsiveContainer, ReferenceLine,
} from 'recharts'
import PageHeader from '@/components/ui/PageHeader'
import SectionHeader from '@/components/ui/SectionHeader'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import { LoadingState, ErrorState, EmptyState } from '@/components/ui/states'
import { dirTone } from '@/lib/format'

const PERIODS = ['1W', '1M', '3M', 'YTD', '1Y'] as const
type Period = typeof PERIODS[number]

const TOOLTIP_STYLE = {
  background: 'var(--c-surface)',
  border: '1px solid var(--c-line-card)',
  borderRadius: 0,
  color: 'var(--c-ink)',
} as const
const TICK_STYLE = { fontSize: 10, fill: 'var(--c-fg-faint)', fontFamily: 'monospace' } as const

function fmtPct(n: number | null) {
  if (n === null || n === undefined) return '—'
  return `${n >= 0 ? '+' : ''}${n.toFixed(2)}%`
}

export default function PerformancePage() {
  const reportApi = useReportApi()
  const [period, setPeriod] = useState<Period>('1M')

  const { data, isLoading, isError } = useQuery({
    queryKey: ['report', 'performance', period],
    queryFn: () => reportApi!.performance(period),
    enabled: !!reportApi,
  })

  if (isLoading) return <Skeleton />
  if (isError || !data) return <Err />

  const periodLabels: Record<string, string> = {
    '1W': '1주', '1M': '1개월', '3M': '3개월', 'YTD': '연초 이후', '1Y': '1년',
  }
  // 기간별 필요 일수 — 시계열 커버리지 미달이면 버튼 비활성 (QA P2)
  const now = new Date()
  const ytdDays = Math.floor((now.getTime() - new Date(now.getFullYear(), 0, 1).getTime()) / 86_400_000) + 1
  const periodDays: Record<string, number> = { '1W': 7, '1M': 30, '3M': 90, 'YTD': ytdDays, '1Y': 365 }
  const coverageDays = Number(data.coverageDays ?? 0)

  const chartData = data.dailySeries.map((d: DailyPerf) => ({
    date: d.date,
    cumReturn: Number(d.cumulativeReturn),
    dailyReturn: Number(d.dailyReturn),
    nav: Number(d.nav),
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
        title="수익률 분석"
        meta="B-02 · 스냅샷 기반 자동 산출"
      />

      <div className="px-5 py-5 pb-10 sm:px-7">
        {/* Period Selector */}
        <div className="flex flex-wrap gap-2">
          {PERIODS.map((p) => {
            const insufficient = coverageDays > 0 && coverageDays < periodDays[p]
            return (
              <button
                key={p}
                onClick={() => setPeriod(p)}
                disabled={insufficient}
                title={insufficient ? `데이터 부족 (${coverageDays}일)` : undefined}
                className={`border px-3.5 py-1.5 font-mono text-[10px] tracking-label transition-colors disabled:cursor-not-allowed disabled:opacity-40 ${
                  period === p
                    ? 'border-ink bg-ink text-white'
                    : 'border-line bg-surface text-fg-3 hover:border-ink hover:text-ink'
                }`}
              >
                {periodLabels[p]}
              </button>
            )
          })}
        </div>

        {/* Total Return */}
        <div className="mt-6 border border-line-soft bg-surface px-4 py-4">
          <Label size="sm" tone="faint">전체 수익률 (매입 원가 기준)</Label>
          <Num tone={dirTone(Number(data.totalReturn))} className="mt-1.5 block text-[26px]">
            {fmtPct(Number(data.totalReturn))}
          </Num>
          {data.twr !== null && (
            <Num className="mt-0.5 block text-[11.5px] text-fg-faint">TWR: {fmtPct(Number(data.twr))}</Num>
          )}
        </div>

        {/* Period Returns Grid */}
        <div className="mt-3 grid grid-cols-2 gap-px border border-line-soft bg-line-soft sm:grid-cols-5">
          {PERIODS.map((p) => {
            const val = data.periodReturns[p]
            const n = val !== null && val !== undefined ? Number(val) : null
            return (
              <div key={p} className="bg-surface px-3.5 py-3">
                <Label size="sm" tone={period === p ? 'ink' : 'faint'}>{periodLabels[p]}</Label>
                {/* 커버리지 미달 기간은 왜곡 수치 대신 명시 표기 (QA P2) */}
                {n === null ? (
                  <span className="mt-1 block font-mono text-[10px] tracking-label text-fg-faint">
                    데이터 부족 ({coverageDays}일)
                  </span>
                ) : (
                  <Num tone={dirTone(n)} className="mt-1 block text-[16px]">{fmtPct(n)}</Num>
                )}
              </div>
            )
          })}
        </div>

        {/* Cumulative Return Chart */}
        <section className="mt-8">
          <SectionHeader label="누적 수익률 시계열" />
          {chartData.length > 0 ? (
            <ResponsiveContainer width="100%" height={320}>
              <LineChart data={chartData} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="var(--c-line)" />
                <XAxis dataKey="date" tick={TICK_STYLE} tickLine={false} />
                <YAxis
                  tickFormatter={(v) => `${v.toFixed(1)}%`}
                  tick={TICK_STYLE}
                  tickLine={false}
                  axisLine={false}
                />
                <Tooltip
                  formatter={(v: number) => [`${v.toFixed(2)}%`]}
                  contentStyle={TOOLTIP_STYLE}
                  labelStyle={{ color: 'var(--c-fg-muted)' }}
                />
                <ReferenceLine y={0} stroke="var(--c-line)" strokeDasharray="4 4" />
                <Line
                  type="monotone" dataKey="cumReturn" name="누적 수익률"
                  stroke="var(--c-ink)" strokeWidth={2} dot={false}
                />
              </LineChart>
            </ResponsiveContainer>
          ) : (
            <Empty message="성과 이력이 없습니다. 자산을 추가하고 sync 해주세요." />
          )}
        </section>

        {/* Daily NAV Chart */}
        {chartData.length > 0 && (
          <section className="mt-8">
            <SectionHeader label="일별 NAV" />
            <ResponsiveContainer width="100%" height={240}>
              <LineChart data={chartData} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="var(--c-line)" />
                <XAxis dataKey="date" tick={TICK_STYLE} tickLine={false} />
                <YAxis
                  tickFormatter={(v) => `₩${(v / 1_000_000).toFixed(0)}M`}
                  tick={TICK_STYLE}
                  tickLine={false}
                  axisLine={false}
                />
                <Tooltip
                  formatter={(v: number) => [new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW', maximumFractionDigits: 0 }).format(v)]}
                  contentStyle={TOOLTIP_STYLE}
                />
                <Line type="monotone" dataKey="nav" name="NAV" stroke="var(--c-ink)" strokeWidth={2} dot={false} />
              </LineChart>
            </ResponsiveContainer>
          </section>
        )}

        {data.benchmarkAlpha !== null && (
          <div className="mt-8 border border-line-soft bg-surface px-4 py-4">
            <Label size="sm" tone="faint">벤치마크 대비 알파</Label>
            <Num tone={dirTone(Number(data.benchmarkAlpha))} className="mt-1 block text-[18px]">
              {fmtPct(Number(data.benchmarkAlpha))}
            </Num>
          </div>
        )}
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
function Empty({ message }: { message: string }) {
  return <EmptyState title="데이터 없음" description={message} />
}
