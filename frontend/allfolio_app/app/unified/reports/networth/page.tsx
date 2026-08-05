'use client'

import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useReportApi } from '@/lib/useApi'
import PageHeader from '@/components/ui/PageHeader'
import SectionHeader from '@/components/ui/SectionHeader'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import { EmptyState, ErrorState, LoadingState } from '@/components/ui/states'
import type { NetWorthBreakdown, NetWorthPoint } from '@/types/report'
import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid,
  Tooltip, ResponsiveContainer,
} from 'recharts'

const TYPE_KO: Record<string, string> = {
  STOCK: '주식', CRYPTO: '암호화폐', REAL_ESTATE: '부동산',
  VEHICLE: '자동차', GOLD: '금', CASH: '현금', ETC: '기타',
}
// 토큰 기반 그레이스케일 램프 — 유형 순서대로 진한 → 옅은
const TONES = ['var(--c-ink)', 'var(--c-fg-muted)', 'var(--c-fg-ghost)', 'var(--c-line)']

const TICK = { fontSize: 10, fill: 'var(--c-fg-faint)', fontFamily: 'var(--font-mono), monospace' } as const
const TOOLTIP_STYLE = {
  background: 'var(--c-surface)',
  border: '1px solid var(--c-line-card)',
  borderRadius: 0,
  color: 'var(--c-ink)',
} as const

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

  const loanRatio = data.totalAssets > 0
    ? ((data.totalLoan / data.totalAssets) * 100).toFixed(1)
    : '0.0'

  const chartData = data.trend.map((p: NetWorthPoint) => ({
    date: p.date,
    nav: Number(p.nav),
  }))

  const toneByType = new Map(data.byType.map((b: NetWorthBreakdown, i: number) => [b.type, TONES[i % TONES.length]]))

  return (
    <div className="border border-line-card bg-surface">
      <div className="px-5 pt-5 sm:px-7">
        <Link
          href="/unified/reports"
          className="font-mono text-[10px] uppercase tracking-label text-fg-muted transition-colors hover:text-ink"
        >
          ← 보고서
        </Link>
      </div>
      <PageHeader
        className="px-5 pt-2 sm:px-7"
        title="순자산 추이"
        meta={<span>B-07 · 생성 {new Date(data.generatedAt).toLocaleString('ko-KR')}</span>}
      />

      <div className="px-5 py-5 pb-10 sm:px-7">
        {/* 핵심 KPI */}
        <div className="grid grid-cols-1 gap-px border border-line-soft bg-line-soft sm:grid-cols-3">
          <div className="bg-surface px-3.5 py-3">
            <Label size="sm" tone="faint">총 자산</Label>
            <Num className="mt-1 block text-[20px]">{fmt(data.totalAssets)}</Num>
          </div>
          <div className="bg-surface px-3.5 py-3">
            <Label size="sm" tone="faint">총 부채</Label>
            <Num tone={data.totalLoan > 0 ? 'loss' : 'flat'} className="mt-1 block text-[20px]">
              {data.totalLoan > 0 ? `-${fmt(data.totalLoan)}` : '—'}
            </Num>
            {data.totalLoan > 0 && (
              <p className="mt-0.5 text-[11px] text-fg-faint">자산 대비 {loanRatio}%</p>
            )}
          </div>
          <div className="bg-surface px-3.5 py-3">
            <Label size="sm" tone="faint">순자산 (NAV - 부채)</Label>
            <Num className={`mt-1 block text-[20px] ${data.netWorth >= 0 ? '' : 'text-loss'}`}>
              {fmt(data.netWorth)}
            </Num>
          </div>
        </div>

        {/* 순자산 추이 차트 */}
        <section className="mt-8">
          <SectionHeader label="총 자산(NAV) 추이" />
          {chartData.length >= 2 ? (
            <ResponsiveContainer width="100%" height={280}>
              <AreaChart data={chartData} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="var(--c-line)" />
                <XAxis dataKey="date" tick={TICK} tickLine={false} axisLine={{ stroke: 'var(--c-line)' }} />
                <YAxis
                  tickFormatter={(v) => `₩${fmtShort(v)}`}
                  tick={TICK}
                  tickLine={false}
                  axisLine={false}
                  width={70}
                />
                <Tooltip
                  formatter={(v: number) => [fmt(v), '총 자산']}
                  contentStyle={TOOLTIP_STYLE}
                  labelStyle={{ color: 'var(--c-fg-3)' }}
                />
                <Area
                  type="monotone"
                  dataKey="nav"
                  name="총 자산"
                  stroke="var(--c-ink)"
                  strokeWidth={1.5}
                  fill="var(--c-ink)"
                  fillOpacity={0.06}
                  dot={false}
                />
              </AreaChart>
            </ResponsiveContainer>
          ) : (
            <EmptyState
              title="자산 이력이 부족합니다"
              description="매일 Sync를 실행하면 추이 그래프가 채워집니다."
            />
          )}
        </section>

        {/* 유형별 순자산 */}
        <section className="mt-8">
          <SectionHeader label="유형별 순자산" />
          <div className="overflow-x-auto">
            <div className="min-w-[560px] border-t-[1.5px] border-ink">
              <div className="grid grid-cols-[14px_1.2fr_1fr_1fr_1.2fr_0.7fr] items-baseline gap-2.5 border-b border-line py-2">
                <span />
                <Label size="sm" tone="faint">유형</Label>
                <Label size="sm" tone="faint" className="text-right">자산</Label>
                <Label size="sm" tone="faint" className="text-right">부채</Label>
                <Label size="sm" tone="faint" className="text-right">순자산</Label>
                <Label size="sm" tone="faint" className="text-right">비중</Label>
              </div>
              {data.byType.map((b: NetWorthBreakdown) => {
                const nw = Number(b.netWorth)
                const isPos = nw >= 0
                return (
                  <div
                    key={b.type}
                    className="grid grid-cols-[14px_1.2fr_1fr_1fr_1.2fr_0.7fr] items-center gap-2.5 border-b border-line-hair py-2.5 hover:bg-surface-muted"
                    aria-label={TYPE_KO[b.type] ?? b.type}
                  >
                    <span
                      className="block h-[7px] w-[7px] shrink-0"
                      aria-hidden="true"
                      style={{ background: toneByType.get(b.type) }}
                    />
                    <span className="text-[13px]">{TYPE_KO[b.type] ?? b.type}</span>
                    <Num className="text-right text-xs text-fg-3">{fmt(Number(b.assets))}</Num>
                    <span className="text-right">
                      {Number(b.loan) > 0 ? (
                        <Num tone="loss" className="text-xs">-{fmt(Number(b.loan))}</Num>
                      ) : (
                        <span className="text-xs text-fg-ghost">—</span>
                      )}
                    </span>
                    <Num className={`text-right text-[12.5px] font-medium ${isPos ? '' : 'text-loss'}`}>
                      {fmt(nw)}
                    </Num>
                    <Num className="text-right text-xs text-fg-faint">{Number(b.pct).toFixed(1)}%</Num>
                  </div>
                )
              })}
            </div>
          </div>

          {/* 비중 바 */}
          <div className="mt-4 flex h-2 bg-surface-muted">
            {data.byType.filter(b => Number(b.netWorth) > 0).map((b: NetWorthBreakdown) => (
              <div
                key={b.type}
                style={{ width: `${Number(b.pct)}%`, background: toneByType.get(b.type) }}
                title={`${TYPE_KO[b.type] ?? b.type}: ${Number(b.pct).toFixed(1)}%`}
              />
            ))}
          </div>
        </section>

        {/* 부채 안내 */}
        {data.totalLoan > 0 && (
          <div className="mt-8 border border-warn-line bg-warn-bg px-3.5 py-2.5 text-xs leading-relaxed text-warn">
            부채 비율 {loanRatio}% — 일반적으로 자산 대비 부채 비율 40% 이하를 권장합니다.
          </div>
        )}
      </div>
    </div>
  )
}

function Skeleton() {
  return (
    <div className="border border-line-card bg-surface px-5 sm:px-7">
      <LoadingState label="보고서 불러오는 중" />
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
