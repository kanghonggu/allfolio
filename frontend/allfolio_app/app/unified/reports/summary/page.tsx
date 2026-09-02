'use client'

import { useQuery } from '@tanstack/react-query'
import { money } from '@/lib/format'
import Link from 'next/link'
import { useReportApi } from '@/lib/useApi'
import type { TypeBreakdown, TopHolding } from '@/types/report'
import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer, Legend } from 'recharts'
import PageHeader from '@/components/ui/PageHeader'
import SectionHeader from '@/components/ui/SectionHeader'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import { LoadingState, ErrorState, EmptyState } from '@/components/ui/states'
import { dirTone, toneText } from '@/lib/format'

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

// 통화 포맷은 `lib/format`의 money를 쓴다. 이 화면들은 오늘 KRW로만 부르지만 `currency`
// 파라미터가 열려 있어, 넘기는 순간 계좌 상세와 같은 방식으로 죽는다 (AF-158).
const fmt = money
function fmtPct(n: number) { return `${n >= 0 ? '+' : ''}${n.toFixed(2)}%` }

export default function SummaryReportPage() {
  const reportApi = useReportApi()
  const { data, isLoading, isError } = useQuery({
    queryKey: ['report', 'summary'],
    queryFn: () => reportApi!.summary(),
    enabled: !!reportApi,
  })

  if (isLoading) return <Skeleton />
  if (isError || !data) return <Err />

  const pieData = data.byType.map((t: TypeBreakdown) => ({
    name:  TYPE_KO[t.type] ?? t.type,
    value: t.value,
    color: TYPE_COLORS[t.type] ?? 'var(--c-fg-muted)',
    pct:   t.pct,
  }))

  const pnlClass = toneText[dirTone(data.unrealizedPnl)]

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
        title="포트폴리오 요약"
        meta={`B-01 · 스냅샷 기반 자동 산출 · 생성 ${new Date(data.generatedAt).toLocaleString('ko-KR')}`}
      />

      <div className="px-5 py-5 pb-10 sm:px-7">
        {/* KPI 타일 */}
        <div className="grid grid-cols-2 gap-px border border-line-soft bg-line-soft lg:grid-cols-4">
          <KpiCard label="총 자산 (NAV)" value={fmt(data.nav)} />
          <KpiCard label="총 매입 원가" value={fmt(data.totalPurchaseCost)} />
          <KpiCard label="미실현 손익" value={fmt(data.unrealizedPnl)} valueClass={pnlClass} />
          <KpiCard label="수익률" value={fmtPct(data.unrealizedPnlPct)} valueClass={pnlClass} />
        </div>

        <div className="mt-3 grid grid-cols-2 gap-px border border-line-soft bg-line-soft">
          <KpiCard label="보유 자산 수" value={`${data.assetCount}개`} />
          <KpiCard label="연결 계좌 수" value={`${data.accountCount}개`} />
        </div>

        {/* Pie + Type table */}
        <div className="mt-8 grid gap-8 lg:grid-cols-2">
          <section>
            <SectionHeader label="자산 유형별 비중" />
            {pieData.length > 0 ? (
              <ResponsiveContainer width="100%" height={260}>
                <PieChart>
                  <Pie data={pieData} dataKey="value" cx="50%" cy="50%" innerRadius={60} outerRadius={110} paddingAngle={2}>
                    {pieData.map((e, i) => <Cell key={i} fill={e.color} />)}
                  </Pie>
                  <Tooltip
                    formatter={(v: number) => [fmt(v), '가치']}
                    contentStyle={TOOLTIP_STYLE}
                  />
                  <Legend formatter={(v) => <span className="font-mono text-[10px] text-fg-3">{v}</span>} />
                </PieChart>
              </ResponsiveContainer>
            ) : <Empty />}
          </section>

          <section>
            <SectionHeader label="유형별 상세" />
            <div className="space-y-3 border-t-[1.5px] border-ink pt-3">
              {data.byType.map((t: TypeBreakdown) => (
                <div key={t.type} className="flex items-center gap-3" aria-label={TYPE_KO[t.type] ?? t.type}>
                  <span className="h-2.5 w-2.5 shrink-0" style={{ background: TYPE_COLORS[t.type] ?? 'var(--c-fg-muted)' }} />
                  <span className="flex-1 text-[13px] text-fg-2">{TYPE_KO[t.type] ?? t.type}</span>
                  <div className="h-[6px] flex-1 overflow-hidden bg-line-soft">
                    <div className="h-full" style={{ width: `${t.pct}%`, background: TYPE_COLORS[t.type] ?? 'var(--c-fg-muted)' }} />
                  </div>
                  <Num className="w-14 text-right text-[12px] text-fg-3">{t.pct.toFixed(1)}%</Num>
                  <Num className="w-24 text-right text-[11px] text-fg-faint">{t.count}종</Num>
                </div>
              ))}
            </div>
          </section>
        </div>

        {/* Currency Breakdown */}
        <section className="mt-8">
          <SectionHeader label="통화별 비중" />
          <div className="grid grid-cols-2 gap-px border border-line-soft bg-line-soft sm:grid-cols-3 lg:grid-cols-4">
            {data.byCurrency.map((c) => (
              <div key={c.currency} className="bg-surface px-3.5 py-3">
                <Label size="sm" tone="faint">{c.currency}</Label>
                {/* c.value는 백엔드에서 KRW로 환산된 통화별 기여액 → KRW로 표시(원통화 심볼 금지) */}
                <Num className="mt-1 block text-[14px]">{fmt(c.value)}</Num>
                <Num className="mt-0.5 block text-[11px] text-fg-faint">{c.pct.toFixed(1)}%</Num>
              </div>
            ))}
          </div>
        </section>

        {/* Top Holdings */}
        <section className="mt-8">
          <SectionHeader label="상위 보유 자산" />
          <div className="overflow-x-auto">
            <div className="min-w-[560px] border-t-[1.5px] border-ink">
              <div className="grid grid-cols-[32px_1.8fr_1fr_1.2fr_0.7fr] gap-3 border-b border-line py-2">
                <Label size="sm" tone="faint">#</Label>
                <Label size="sm" tone="faint">자산명</Label>
                <Label size="sm" tone="faint">유형</Label>
                <Label size="sm" tone="faint" className="text-right">현재 가치</Label>
                <Label size="sm" tone="faint" className="text-right">비중</Label>
              </div>
              {data.topHoldings.map((h: TopHolding, i) => (
                <div
                  key={i}
                  className="grid grid-cols-[32px_1.8fr_1fr_1.2fr_0.7fr] items-baseline gap-3 border-b border-line-hair py-2.5 hover:bg-surface-muted"
                >
                  <Num className="text-[11px] text-fg-ghost">{i + 1}</Num>
                  <span className="min-w-0">
                    <span className="block text-[13.5px]">{h.name}</span>
                    {h.symbol && <span className="block font-mono text-[10.5px] text-fg-faint">{h.symbol}</span>}
                  </span>
                  <span className="text-[12.5px] text-fg-3">{TYPE_KO[h.type] ?? h.type}</span>
                  <Num className="text-right text-[12.5px]">{fmt(h.value)}</Num>
                  <Num className="text-right text-[12.5px] text-fg-muted">{h.pct.toFixed(1)}%</Num>
                </div>
              ))}
            </div>
          </div>
        </section>
      </div>
    </div>
  )
}

function KpiCard({ label, value, valueClass }: { label: string; value: string; valueClass?: string }) {
  return (
    <div className="bg-surface px-3.5 py-3">
      <Label size="sm" tone="faint">{label}</Label>
      <Num className={`mt-1 block text-[16px] ${valueClass ?? ''}`}>{value}</Num>
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
