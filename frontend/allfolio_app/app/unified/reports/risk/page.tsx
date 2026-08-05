'use client'

import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useReportApi } from '@/lib/useApi'
import type { DailyRisk } from '@/types/report'
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, ReferenceLine } from 'recharts'
import PageHeader from '@/components/ui/PageHeader'
import SectionHeader from '@/components/ui/SectionHeader'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import { LoadingState, ErrorState } from '@/components/ui/states'

const TOOLTIP_STYLE = {
  background: 'var(--c-surface)',
  border: '1px solid var(--c-line-card)',
  borderRadius: 0,
  color: 'var(--c-ink)',
} as const
const TICK_STYLE = { fontSize: 10, fill: 'var(--c-fg-faint)', fontFamily: 'monospace' } as const

function fmtPct(n: number | null | undefined, decimals = 2) {
  if (n === null || n === undefined) return '—'
  return `${n.toFixed(decimals)}%`
}
function fmtN(n: number | null | undefined, decimals = 4) {
  if (n === null || n === undefined) return '—'
  return n.toFixed(decimals)
}

export default function RiskPage() {
  const reportApi = useReportApi()
  const { data, isLoading, isError } = useQuery({
    queryKey: ['report', 'risk'],
    queryFn: () => reportApi!.risk(),
    enabled: !!reportApi,
  })

  if (isLoading) return <Skeleton />
  if (isError || !data) return <Err />

  const hasData = data.volatility !== null

  const mddColor = !data.maxDrawdown ? 'text-fg-faint'
    : data.maxDrawdown < -0.2 ? 'text-danger'
    : data.maxDrawdown < -0.1 ? 'text-warn'
    : 'text-ok'

  const volColor = !data.annualizedVolatility ? 'text-fg-faint'
    : data.annualizedVolatility > 0.3 ? 'text-danger'
    : data.annualizedVolatility > 0.15 ? 'text-warn'
    : 'text-ok'

  const chartData = data.series.map((d: DailyRisk) => ({
    date: d.date,
    vol: Number(d.annualizedVolatility) * 100,
    var95: Number(d.var95) * 100,
    mdd: Number(d.maxDrawdown) * 100,
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
        title="리스크 분석"
        meta={`B-04 · 스냅샷 기반 자동 산출${data.latestDate ? ` · 기준일 ${data.latestDate}` : ''}`}
      />

      <div className="px-5 py-5 pb-10 sm:px-7">
        {!hasData && (
          <div className="border border-warn-line bg-warn-bg px-4 py-3 text-[12.5px] leading-relaxed text-warn">
            리스크 이력 데이터가 없습니다. 성과 데이터가 쌓이면 지표가 자동으로 계산됩니다.
          </div>
        )}

        {/* KPI Grid — 데이터 없으면 빈 카드 대신 배너만 노출 (QA P2) */}
        {hasData && (
        <div className="grid grid-cols-2 gap-px border border-line-soft bg-line-soft lg:grid-cols-4">
          <RiskCard
            label="변동성 (일)"
            value={fmtPct(data.volatility !== null ? Number(data.volatility) * 100 : null)}
            desc="일별 수익률 표준편차"
          />
          <RiskCard
            label="변동성 (연환산)"
            value={fmtPct(data.annualizedVolatility !== null ? Number(data.annualizedVolatility) * 100 : null)}
            valueClass={volColor}
            desc="√252 환산"
          />
          <RiskCard
            label="VaR 95%"
            value={fmtPct(data.var95 !== null ? Number(data.var95) * 100 : null)}
            valueClass="text-danger"
            desc="95% 신뢰수준 최대 손실"
          />
          <RiskCard
            label="최대 낙폭 (MDD)"
            value={fmtPct(data.maxDrawdown !== null ? Number(data.maxDrawdown) * 100 : null)}
            valueClass={mddColor}
            desc="고점 대비 최대 하락"
          />
        </div>
        )}

        {hasData && (
        <div className="mt-3 grid gap-px border border-line-soft bg-line-soft sm:grid-cols-2">
          <RiskCard
            label="Sharpe Ratio"
            value={fmtN(data.sharpeRatio)}
            desc="위험 대비 수익 (무위험 5% 기준)"
            valueClass={data.sharpeRatio !== null ? (Number(data.sharpeRatio) > 1 ? 'text-ok' : Number(data.sharpeRatio) > 0 ? 'text-warn' : 'text-danger') : 'text-fg-faint'}
          />
          <RiskCard
            label="Calmar Ratio"
            value={fmtN(data.calmarRatio)}
            desc="연수익 / MDD"
            valueClass={data.calmarRatio !== null ? (Number(data.calmarRatio) > 1 ? 'text-ok' : 'text-warn') : 'text-fg-faint'}
          />
        </div>
        )}

        {/* Risk Metrics Guide */}
        <section className="mt-8">
          <SectionHeader label="리스크 지표 해석 가이드" />
          <div className="grid gap-x-6 gap-y-2 border-t-[1.5px] border-ink pt-3 text-[11.5px] leading-relaxed text-fg-faint sm:grid-cols-2">
            <div><span className="font-medium text-fg-2">변동성</span> — 낮을수록 안정적. 15% 이하: 양호, 30% 이상: 고위험</div>
            <div><span className="font-medium text-fg-2">VaR 95%</span> — &quot;95% 확률로 하루에 이 이상 잃지 않음&quot;</div>
            <div><span className="font-medium text-fg-2">MDD</span> — 고점 대비 최대 하락폭. -20% 이하: 위험 주의</div>
            <div><span className="font-medium text-fg-2">Sharpe</span> — 1 이상: 우수, 0~1: 보통, 0 미만: 부진</div>
            <div><span className="font-medium text-fg-2">Calmar</span> — MDD 대비 수익. 1 이상: 양호</div>
            <div><span className="font-medium text-fg-2">HHI</span> — 0.25 초과 시 집중 위험</div>
          </div>
        </section>

        {/* Historical Charts */}
        {chartData.length > 0 && (
          <>
            <section className="mt-8">
              <SectionHeader label="연환산 변동성 추이" />
              <ResponsiveContainer width="100%" height={240}>
                <LineChart data={chartData} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="var(--c-line)" />
                  <XAxis dataKey="date" tick={TICK_STYLE} tickLine={false} />
                  <YAxis tickFormatter={(v) => `${v.toFixed(0)}%`} tick={TICK_STYLE} axisLine={false} tickLine={false} />
                  <Tooltip formatter={(v: number) => [`${v.toFixed(2)}%`]} contentStyle={TOOLTIP_STYLE} />
                  <Line type="monotone" dataKey="vol" name="연환산 변동성" stroke="var(--c-ink)" strokeWidth={2} dot={false} />
                </LineChart>
              </ResponsiveContainer>
            </section>

            <section className="mt-8">
              <SectionHeader label="최대 낙폭 (MDD) 추이" />
              <ResponsiveContainer width="100%" height={240}>
                <LineChart data={chartData} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="var(--c-line)" />
                  <XAxis dataKey="date" tick={TICK_STYLE} tickLine={false} />
                  <YAxis tickFormatter={(v) => `${v.toFixed(0)}%`} tick={TICK_STYLE} axisLine={false} tickLine={false} />
                  <Tooltip formatter={(v: number) => [`${v.toFixed(2)}%`]} contentStyle={TOOLTIP_STYLE} />
                  <ReferenceLine y={0} stroke="var(--c-line)" />
                  <Line type="monotone" dataKey="mdd" name="MDD" stroke="var(--c-loss)" strokeWidth={2} dot={false} />
                  <Line type="monotone" dataKey="var95" name="VaR 95%" stroke="var(--c-fg-muted)" strokeWidth={1.5} dot={false} strokeDasharray="5 5" />
                </LineChart>
              </ResponsiveContainer>
            </section>
          </>
        )}
      </div>
    </div>
  )
}

function RiskCard({ label, value, desc, valueClass }: {
  label: string; value: string; desc?: string; valueClass?: string
}) {
  return (
    <div className="bg-surface px-3.5 py-3">
      <Label size="sm" tone="faint">{label}</Label>
      <Num className={`mt-1 block text-[16px] ${valueClass ?? ''}`}>{value}</Num>
      {desc && <p className="mt-0.5 text-[11px] text-fg-faint">{desc}</p>}
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
