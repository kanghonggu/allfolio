'use client'

import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useReportApi } from '@/lib/useApi'
import PageHeader from '@/components/ui/PageHeader'
import SectionHeader from '@/components/ui/SectionHeader'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import { EmptyState, ErrorState, LoadingState } from '@/components/ui/states'
import type { MonthlyPnlRow } from '@/types/report'
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid,
  Tooltip, ResponsiveContainer, Cell, ReferenceLine,
} from 'recharts'

const TICK = { fontSize: 10, fill: 'var(--c-fg-faint)', fontFamily: 'var(--font-mono), monospace' } as const
const TOOLTIP_STYLE = {
  background: 'var(--c-surface)',
  border: '1px solid var(--c-line-card)',
  borderRadius: 0,
  color: 'var(--c-ink)',
} as const

function fmt(n: number) {
  return new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW', maximumFractionDigits: 0 }).format(n)
}
// 부호 포맷터 통일 (QA P2) — 음수는 fmt가 -를 붙이므로 양수에만 + 부여
function fmtSigned(n: number) {
  return `${n >= 0 ? '+' : ''}${fmt(n)}`
}
function fmtSignedPct(n: number) {
  return `${n >= 0 ? '+' : ''}${n.toFixed(2)}%`
}
function fmtShort(n: number) {
  if (Math.abs(n) >= 100_000_000) return `${(n / 100_000_000).toFixed(1)}억`
  if (Math.abs(n) >= 10_000) return `${(n / 10_000).toFixed(0)}만`
  return n.toLocaleString('ko-KR')
}
function monthLabel(ym: string) {
  const [y, m] = ym.split('-')
  return `${y.slice(2)}/${m}`
}

export default function MonthlyPnlPage() {
  const reportApi = useReportApi()
  const { data, isLoading, isError } = useQuery({
    queryKey: ['report', 'monthly-pnl'],
    queryFn: () => reportApi!.monthlyPnl(),
    enabled: !!reportApi,
  })

  if (isLoading) return <Skeleton />
  if (isError || !data) return <Err />

  const chartData = data.months.map((m: MonthlyPnlRow) => ({
    label: monthLabel(m.yearMonth),
    pnl: Number(m.absolutePnl),
    ret: Number(m.returnPct),
  }))

  // 손익 방향 — 한국 관례: 양수 빨강(gain) / 음수 파랑(loss)
  const totalTone = data.totalAbsolutePnl >= 0 ? 'text-gain' : 'text-loss'

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
        title="월별 손익 정산"
        meta={<span>B-08 · 생성 {new Date(data.generatedAt).toLocaleString('ko-KR')}</span>}
      />

      <div className="px-5 py-5 pb-10 sm:px-7">
        {/* KPI */}
        <div className="grid grid-cols-1 gap-px border border-line-soft bg-line-soft sm:grid-cols-2 lg:grid-cols-4">
          <div className="bg-surface px-3.5 py-3">
            <Label size="sm" tone="faint">분석 기간</Label>
            <Num className="mt-1 block text-[18px]">{data.months.length}개월</Num>
          </div>
          <div className="bg-surface px-3.5 py-3">
            <Label size="sm" tone="faint">누적 손익</Label>
            <Num className={`mt-1 block text-[18px] ${totalTone}`}>
              {data.totalAbsolutePnl >= 0 ? '+' : ''}{fmt(data.totalAbsolutePnl)}
            </Num>
          </div>
          <div className="bg-surface px-3.5 py-3">
            <Label size="sm" tone="faint">수익 달</Label>
            <Num className="mt-1 block text-[18px] text-gain">{data.winMonths}개월</Num>
          </div>
          <div className="bg-surface px-3.5 py-3">
            <Label size="sm" tone="faint">손실 달</Label>
            <Num className="mt-1 block text-[18px] text-loss">{data.loseMonths}개월</Num>
          </div>
        </div>

        {/* 베스트 / 워스트 — 데이터 1개월이면 최고=최저라 의미 없으므로 숨김 (부족 배너와 정책 일치, QA P2) */}
        {data.months.length >= 2 && (data.bestMonth || data.worstMonth) && (
          <div className="mt-8 grid grid-cols-1 gap-px border border-line-soft bg-line-soft sm:grid-cols-2">
            {data.bestMonth && (
              <div className="bg-surface px-3.5 py-3">
                <Label size="sm" tone="faint">최고 달</Label>
                <Num className="mt-1 block text-[15px] font-medium">{data.bestMonth.yearMonth}</Num>
                <Num tone="gain" className="mt-0.5 block text-[13px]">
                  {fmtSigned(Number(data.bestMonth.absolutePnl))}
                  <span className="ml-2 text-[11px]">({fmtSignedPct(Number(data.bestMonth.returnPct))})</span>
                </Num>
              </div>
            )}
            {data.worstMonth && (
              <div className="bg-surface px-3.5 py-3">
                <Label size="sm" tone="faint">최저 달</Label>
                <Num className="mt-1 block text-[15px] font-medium">{data.worstMonth.yearMonth}</Num>
                <Num tone="loss" className="mt-0.5 block text-[13px]">
                  {fmtSigned(Number(data.worstMonth.absolutePnl))}
                  <span className="ml-2 text-[11px]">({fmtSignedPct(Number(data.worstMonth.returnPct))})</span>
                </Num>
              </div>
            )}
          </div>
        )}

        {/* 월별 손익 바차트 */}
        <section className="mt-8">
          <SectionHeader label="월별 손익 (원)" />
          {chartData.length >= 2 ? (
            <ResponsiveContainer width="100%" height={280}>
              <BarChart data={chartData} margin={{ top: 5, right: 20, left: 10, bottom: 5 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="var(--c-line)" vertical={false} />
                <XAxis dataKey="label" tick={TICK} axisLine={false} tickLine={false} />
                <YAxis
                  tickFormatter={(v) => `₩${fmtShort(v)}`}
                  tick={TICK}
                  axisLine={false}
                  tickLine={false}
                  width={70}
                />
                <Tooltip
                  formatter={(v: number) => [fmt(v), '손익']}
                  contentStyle={TOOLTIP_STYLE}
                  labelStyle={{ color: 'var(--c-fg-3)' }}
                />
                <ReferenceLine y={0} stroke="var(--c-line)" />
                <Bar dataKey="pnl" name="손익">
                  {chartData.map((entry, i) => (
                    <Cell key={i} fill={entry.pnl >= 0 ? 'var(--c-gain)' : 'var(--c-loss)'} />
                  ))}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          ) : (
            <Empty />
          )}
        </section>

        {/* 월별 수익률 바차트 */}
        {chartData.length >= 2 && (
          <section className="mt-8">
            <SectionHeader label="월별 수익률 (%)" />
            <ResponsiveContainer width="100%" height={200}>
              <BarChart data={chartData} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="var(--c-line)" vertical={false} />
                <XAxis dataKey="label" tick={TICK} axisLine={false} tickLine={false} />
                <YAxis
                  tickFormatter={(v) => `${v.toFixed(1)}%`}
                  tick={TICK}
                  axisLine={false}
                  tickLine={false}
                />
                <Tooltip
                  formatter={(v: number) => [`${v.toFixed(2)}%`, '수익률']}
                  contentStyle={TOOLTIP_STYLE}
                />
                <ReferenceLine y={0} stroke="var(--c-line)" />
                <Bar dataKey="ret" name="수익률">
                  {chartData.map((entry, i) => (
                    <Cell key={i} fill={entry.ret >= 0 ? 'var(--c-gain)' : 'var(--c-loss)'} />
                  ))}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          </section>
        )}

        {/* 월별 상세 테이블 */}
        <section className="mt-8">
          <SectionHeader label="월별 상세" />
          {data.months.length === 0 ? (
            <Empty />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[560px] border-t-[1.5px] border-ink text-sm">
                <thead>
                  <tr className="border-b border-line text-left">
                    <th className="py-2 font-normal"><Label size="sm" tone="faint">월</Label></th>
                    <th className="py-2 text-right font-normal"><Label size="sm" tone="faint">시작 NAV</Label></th>
                    <th className="py-2 text-right font-normal"><Label size="sm" tone="faint">종료 NAV</Label></th>
                    <th className="py-2 text-right font-normal"><Label size="sm" tone="faint">손익</Label></th>
                    <th className="py-2 text-right font-normal"><Label size="sm" tone="faint">수익률</Label></th>
                  </tr>
                </thead>
                <tbody>
                  {[...data.months].reverse().map((m: MonthlyPnlRow) => {
                    const pnl = Number(m.absolutePnl)
                    const ret = Number(m.returnPct)
                    const tone = pnl >= 0 ? 'text-gain' : 'text-loss'
                    return (
                      <tr key={m.yearMonth} className="border-b border-line-hair hover:bg-surface-muted">
                        <td className="py-2.5"><Num className="text-[12.5px]">{m.yearMonth}</Num></td>
                        <td className="py-2.5 text-right"><Num className="text-xs text-fg-3">{fmt(Number(m.startNav))}</Num></td>
                        <td className="py-2.5 text-right"><Num className="text-xs text-fg-2">{fmt(Number(m.endNav))}</Num></td>
                        <td className="py-2.5 text-right">
                          <Num className={`text-[12.5px] ${tone}`}>
                            {pnl >= 0 ? '+' : ''}{fmt(pnl)}
                          </Num>
                        </td>
                        <td className="py-2.5 text-right">
                          <Num className={`text-[12.5px] font-medium ${tone}`}>
                            {ret >= 0 ? '+' : ''}{ret.toFixed(2)}%
                          </Num>
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          )}
        </section>
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
function Empty() {
  return (
    <EmptyState
      title="월별 데이터가 부족합니다"
      description="매일 Sync를 실행하면 이력이 쌓입니다."
    />
  )
}
