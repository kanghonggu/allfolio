'use client'

import { useQuery } from '@tanstack/react-query'
import { money } from '@/lib/format'
import Link from 'next/link'
import { useReportApi } from '@/lib/useApi'
import type { TypeBreakdown, CurrencyBreakdown, TopHolding } from '@/types/report'
import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer, Legend, BarChart, Bar, XAxis, YAxis, CartesianGrid } from 'recharts'
import PageHeader from '@/components/ui/PageHeader'
import SectionHeader from '@/components/ui/SectionHeader'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import { LoadingState, ErrorState, EmptyState } from '@/components/ui/states'

// 모노크롬 시리즈 램프 — 차트 계열색은 CSS 변수만 사용
const SERIES = ['var(--c-ink)', 'var(--c-fg-muted)', 'var(--c-fg-ghost)', 'var(--c-line)']
const TYPE_COLORS: Record<string, string> = {
  CRYPTO: SERIES[0], STOCK: SERIES[1], REAL_ESTATE: SERIES[2],
  VEHICLE: SERIES[3], GOLD: SERIES[0], CASH: SERIES[1], ETC: SERIES[2],
}
const TYPE_KO: Record<string, string> = {
  CRYPTO: '암호화폐', STOCK: '주식', REAL_ESTATE: '부동산',
  VEHICLE: '자동차', GOLD: '금', CASH: '현금', ETC: '기타',
}

const TOOLTIP_STYLE = {
  background: 'var(--c-surface)',
  border: '1px solid var(--c-line-card)',
  borderRadius: 0,
  color: 'var(--c-ink)',
} as const
const TICK_STYLE = { fontSize: 10, fill: 'var(--c-fg-faint)', fontFamily: 'monospace' } as const

// 통화 포맷은 `lib/format`의 money를 쓴다. 이 화면들은 오늘 KRW로만 부르지만 `currency`
// 파라미터가 열려 있어, 넘기는 순간 계좌 상세와 같은 방식으로 죽는다 (AF-158).
const fmt = money

export default function AllocationPage() {
  const reportApi = useReportApi()
  const { data, isLoading, isError } = useQuery({
    queryKey: ['report', 'allocation'],
    queryFn: () => reportApi!.allocation(),
    enabled: !!reportApi,
  })

  if (isLoading) return <Skeleton />
  if (isError || !data) return <Err />

  const typePie = data.byType.map((t: TypeBreakdown) => ({
    name: TYPE_KO[t.type] ?? t.type,
    value: t.value,
    color: TYPE_COLORS[t.type] ?? 'var(--c-fg-muted)',
  }))

  const currencyPie = data.byCurrency.map((c: CurrencyBreakdown, i: number) => ({
    name: c.currency,
    value: c.value,
    color: SERIES[i % SERIES.length],
  }))

  const hhiRisk = data.concentrationHHI > 0.25 ? '고위험' : data.concentrationHHI > 0.15 ? '중간' : '분산'
  const hhiColor = data.concentrationHHI > 0.25 ? 'text-danger' : data.concentrationHHI > 0.15 ? 'text-warn' : 'text-ok'

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
        title="자산 배분"
        meta="B-03 · 스냅샷 기반 자동 산출"
      />

      <div className="px-5 py-5 pb-10 sm:px-7">
        {/* Summary KPIs */}
        <div className="grid gap-px border border-line-soft bg-line-soft sm:grid-cols-3">
          <div className="bg-surface px-3.5 py-3">
            <Label size="sm" tone="faint">총 자산</Label>
            <Num className="mt-1 block text-[16px]">{fmt(data.totalValue)}</Num>
          </div>
          <div className="bg-surface px-3.5 py-3">
            <Label size="sm" tone="faint">HHI 집중도</Label>
            <Num className={`mt-1 block text-[16px] ${hhiColor}`}>{data.concentrationHHI.toFixed(4)}</Num>
            <p className={`mt-0.5 text-[11px] ${hhiColor}`}>{hhiRisk}</p>
          </div>
          <div className="bg-surface px-3.5 py-3">
            <Label size="sm" tone="faint">상위 5개 비중</Label>
            <Num className="mt-1 block text-[16px]">{data.top5Concentration.toFixed(1)}%</Num>
          </div>
        </div>

        {/* Pie Charts */}
        <div className="mt-8 grid gap-8 lg:grid-cols-2">
          <section>
            <SectionHeader label="자산 유형별" />
            {typePie.length > 0 ? (
              <ResponsiveContainer width="100%" height={260}>
                <PieChart>
                  <Pie data={typePie} dataKey="value" cx="50%" cy="50%" innerRadius={60} outerRadius={110} paddingAngle={2}>
                    {typePie.map((e, i) => <Cell key={i} fill={e.color} />)}
                  </Pie>
                  <Tooltip formatter={(v: number) => [fmt(v), '가치']} contentStyle={TOOLTIP_STYLE} />
                  <Legend formatter={(v) => <span className="font-mono text-[10px] text-fg-3">{v}</span>} />
                </PieChart>
              </ResponsiveContainer>
            ) : <Empty />}
          </section>

          <section>
            <SectionHeader label="통화별" />
            {currencyPie.length > 0 ? (
              <ResponsiveContainer width="100%" height={260}>
                <PieChart>
                  <Pie data={currencyPie} dataKey="value" cx="50%" cy="50%" innerRadius={60} outerRadius={110} paddingAngle={2}>
                    {currencyPie.map((e, i) => <Cell key={i} fill={e.color} />)}
                  </Pie>
                  <Tooltip formatter={(v: number) => [v.toFixed(2), '가치']} contentStyle={TOOLTIP_STYLE} />
                  <Legend formatter={(v) => <span className="font-mono text-[10px] text-fg-3">{v}</span>} />
                </PieChart>
              </ResponsiveContainer>
            ) : <Empty />}
          </section>
        </div>

        {/* Top Holdings Bar */}
        <section className="mt-8">
          <SectionHeader label="상위 보유 자산 비중" />
          {data.topHoldings.length > 0 ? (
            <ResponsiveContainer width="100%" height={300}>
              <BarChart
                data={data.topHoldings.map((h: TopHolding) => ({ name: h.name, pct: h.pct }))}
                layout="vertical"
                margin={{ left: 20, right: 30 }}
              >
                <CartesianGrid strokeDasharray="3 3" stroke="var(--c-line)" horizontal={false} />
                <XAxis type="number" tickFormatter={(v) => `${v}%`} tick={TICK_STYLE} axisLine={false} tickLine={false} />
                <YAxis type="category" dataKey="name" tick={TICK_STYLE} width={100} axisLine={false} tickLine={false} />
                <Tooltip formatter={(v: number) => [`${v.toFixed(2)}%`, '비중']} contentStyle={TOOLTIP_STYLE} />
                <Bar dataKey="pct" name="비중" fill="var(--c-ink)" />
              </BarChart>
            </ResponsiveContainer>
          ) : <Empty />}
        </section>

        {/* Top Holdings Table */}
        <section className="mt-8">
          <SectionHeader label="상위 보유 자산 목록" />
          <div className="overflow-x-auto">
            <div className="min-w-[640px] border-t-[1.5px] border-ink">
              <div className="grid grid-cols-[32px_1.8fr_1fr_1.2fr_0.7fr_1fr] gap-3 border-b border-line py-2">
                <Label size="sm" tone="faint">#</Label>
                <Label size="sm" tone="faint">자산명</Label>
                <Label size="sm" tone="faint">유형</Label>
                <Label size="sm" tone="faint" className="text-right">현재 가치</Label>
                <Label size="sm" tone="faint" className="text-right">비중</Label>
                <Label size="sm" tone="faint">비중 바</Label>
              </div>
              {data.topHoldings.map((h: TopHolding, i: number) => (
                <div
                  key={i}
                  className="grid grid-cols-[32px_1.8fr_1fr_1.2fr_0.7fr_1fr] items-center gap-3 border-b border-line-hair py-2.5 hover:bg-surface-muted"
                >
                  <Num className="text-[11px] text-fg-ghost">{i + 1}</Num>
                  <span className="min-w-0">
                    <span className="block text-[13.5px]">{h.name}</span>
                    {h.symbol && <span className="block font-mono text-[10.5px] text-fg-faint">{h.symbol}</span>}
                  </span>
                  <span className="text-[12.5px] text-fg-3">{TYPE_KO[h.type] ?? h.type}</span>
                  <Num className="text-right text-[12.5px]">{fmt(h.value)}</Num>
                  <Num className="text-right text-[12.5px] text-fg-muted">{h.pct.toFixed(1)}%</Num>
                  <div className="h-[6px] bg-line-soft">
                    <div className="h-full bg-ink" style={{ width: `${Math.min(h.pct, 100)}%` }} />
                  </div>
                </div>
              ))}
            </div>
          </div>
        </section>
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
function Empty() { return <EmptyState title="데이터 없음" /> }
