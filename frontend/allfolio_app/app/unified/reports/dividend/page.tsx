'use client'

import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useReportApi } from '@/lib/useApi'
import PageHeader from '@/components/ui/PageHeader'
import SectionHeader from '@/components/ui/SectionHeader'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import { EmptyState, ErrorState, LoadingState } from '@/components/ui/states'
import type { MonthlyDividend, SymbolDividend, DividendEntry } from '@/types/dividend'
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid,
  Tooltip, ResponsiveContainer,
} from 'recharts'

type Period = 'YTD' | '1Y' | '전체'
const PERIODS: Period[] = ['YTD', '1Y', '전체']

const TICK = { fontSize: 10, fill: 'var(--c-fg-faint)', fontFamily: 'var(--font-mono), monospace' } as const
const TOOLTIP_STYLE = {
  background: 'var(--c-surface)',
  border: '1px solid var(--c-line-card)',
  borderRadius: 0,
  color: 'var(--c-ink)',
} as const

function fmt(n: number) {
  return new Intl.NumberFormat('ko-KR', {
    style: 'currency', currency: 'KRW', maximumFractionDigits: 0,
  }).format(n)
}

export default function DividendPage() {
  const reportApi = useReportApi()
  const [period, setPeriod] = useState<Period>('YTD')

  const { data, isLoading, isError } = useQuery({
    queryKey: ['report', 'dividend', period],
    queryFn: () => reportApi!.dividend(period),
    enabled: !!reportApi,
  })

  if (isLoading) return (
    <div className="border border-line-card bg-surface px-5 sm:px-7">
      <LoadingState label="보고서 불러오는 중" />
    </div>
  )
  if (isError || !data) return (
    <div className="border border-line-card bg-surface px-5 sm:px-7">
      <ErrorState message="보고서를 불러올 수 없습니다." />
    </div>
  )

  const chartData = data.monthlySeries.map((m: MonthlyDividend) => ({
    month: m.month,
    amount: Number(m.amount),
  }))

  const isEmpty = data.receiptCount === 0

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
        title="배당금 보고서"
        meta={<span>B-09 · 생성 {new Date(data.generatedAt).toLocaleString('ko-KR')}</span>}
        actions={
          <div className="flex border border-line bg-surface">
            {PERIODS.map((p) => (
              <button
                key={p}
                onClick={() => setPeriod(p)}
                className={`px-3.5 py-1.5 font-mono text-xs transition-colors ${
                  period === p ? 'bg-ink text-white' : 'text-fg-3 hover:text-ink'
                }`}
              >
                {p}
              </button>
            ))}
          </div>
        }
      />

      <div className="px-5 py-5 pb-10 sm:px-7">
        {/* KPI 카드 — 배당 내역 없으면 빈 카드 대신 아래 빈 상태만 노출 (QA P2) */}
        {!isEmpty && (
          <div className="grid grid-cols-1 gap-px border border-line-soft bg-line-soft sm:grid-cols-2 lg:grid-cols-4">
            <div className="bg-surface px-3.5 py-3">
              <Label size="sm" tone="faint">총 수령액</Label>
              <Num className="mt-1 block text-[20px]">{fmt(Number(data.totalDividend))}</Num>
            </div>
            <div className="bg-surface px-3.5 py-3">
              <Label size="sm" tone="faint">수령 횟수</Label>
              <Num className="mt-1 block text-[20px]">{data.receiptCount}회</Num>
            </div>
            <div className="bg-surface px-3.5 py-3">
              <Label size="sm" tone="faint">월 평균</Label>
              <Num className="mt-1 block text-[20px]">{fmt(Number(data.monthlyAvg))}</Num>
            </div>
            <div className="bg-surface px-3.5 py-3">
              <Label size="sm" tone="faint">연환산 예상</Label>
              <Num className="mt-1 block text-[20px]">{fmt(Number(data.annualProjected))}</Num>
              <p className="mt-0.5 text-[11px] text-fg-faint">최근 12개월 기준</p>
            </div>
          </div>
        )}

        {isEmpty ? (
          <EmptyState
            title="아직 배당 내역이 없습니다"
            description="거래 내역 입력 시 유형을 '배당'으로 선택하면 여기에 집계됩니다."
            action={
              <Link
                href="/unified/accounts"
                className="border border-line bg-surface px-3.5 py-2 text-[12.5px] text-fg-2 transition-colors hover:border-ink hover:text-ink"
              >
                계좌로 이동
              </Link>
            }
          />
        ) : (
          <>
            {/* 월별 수령액 차트 */}
            <section className="mt-8">
              <SectionHeader label="월별 수령액" />
              {chartData.length >= 1 ? (
                <ResponsiveContainer width="100%" height={260}>
                  <BarChart data={chartData} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="var(--c-line)" />
                    <XAxis dataKey="month" tick={TICK} tickLine={false} axisLine={{ stroke: 'var(--c-line)' }} />
                    <YAxis
                      tickFormatter={(v) =>
                        v >= 1_000_000
                          ? `${(v / 1_000_000).toFixed(0)}M`
                          : v >= 10_000
                            ? `${(v / 10_000).toFixed(0)}만`
                            : String(v)
                      }
                      tick={TICK}
                      tickLine={false}
                      axisLine={false}
                      width={60}
                    />
                    <Tooltip
                      formatter={(v: number) => [fmt(v), '배당 수령액']}
                      contentStyle={TOOLTIP_STYLE}
                      labelStyle={{ color: 'var(--c-fg-3)' }}
                    />
                    <Bar dataKey="amount" name="수령액" fill="var(--c-ink)" />
                  </BarChart>
                </ResponsiveContainer>
              ) : (
                <EmptyState title="차트 데이터가 부족합니다" />
              )}
            </section>

            {/* 종목별 배당 */}
            <section className="mt-8">
              <SectionHeader label="종목별 배당" />
              <div className="overflow-x-auto">
                <table className="w-full min-w-[560px] border-t-[1.5px] border-ink text-sm">
                  <thead>
                    <tr className="border-b border-line text-left">
                      <th className="py-2 font-normal"><Label size="sm" tone="faint">종목</Label></th>
                      <th className="py-2 text-right font-normal"><Label size="sm" tone="faint">수령 횟수</Label></th>
                      <th className="py-2 text-right font-normal"><Label size="sm" tone="faint">합계</Label></th>
                      <th className="py-2 text-right font-normal"><Label size="sm" tone="faint">비중</Label></th>
                      <th className="py-2 text-right font-normal"><Label size="sm" tone="faint">최근 수령일</Label></th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.bySymbol.map((s: SymbolDividend) => (
                      <tr key={`${s.stockName}-${s.symbol}`} className="border-b border-line-hair hover:bg-surface-muted">
                        <td className="py-2.5">
                          <span className="text-[13px] text-fg-2">{s.stockName}</span>
                          {s.symbol && (
                            <span className="ml-1.5 font-mono text-[10px] text-fg-faint">{s.symbol}</span>
                          )}
                        </td>
                        <td className="py-2.5 text-right"><Num className="text-xs text-fg-3">{s.receiptCount}회</Num></td>
                        <td className="py-2.5 text-right"><Num className="text-[12.5px] font-medium">{fmt(Number(s.totalAmount))}</Num></td>
                        <td className="py-2.5 text-right"><Num className="text-xs text-fg-faint">{Number(s.pct).toFixed(1)}%</Num></td>
                        <td className="py-2.5 text-right"><Num className="text-xs text-fg-faint">{s.lastReceivedAt}</Num></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>

            {/* 최근 수령 이력 */}
            <section className="mt-8">
              <SectionHeader label="최근 수령 이력" />
              <div className="border-t-[1.5px] border-ink">
                {data.recentHistory.map((e: DividendEntry, i: number) => (
                  <div
                    key={i}
                    className="flex items-baseline justify-between gap-3 border-b border-line-hair py-2.5 hover:bg-surface-muted"
                  >
                    <div className="flex min-w-0 flex-wrap items-baseline gap-x-3 gap-y-0.5">
                      <Num className="text-[11.5px] text-fg-3">{e.tradedAt}</Num>
                      <span className="text-[13px] text-fg-2">{e.stockName}</span>
                      {e.symbol && (
                        <span className="font-mono text-[10px] text-fg-faint">{e.symbol}</span>
                      )}
                      {e.memo && (
                        <span className="text-xs italic text-fg-ghost">{e.memo}</span>
                      )}
                    </div>
                    <Num tone="gain" className="text-[12.5px]">
                      +{fmt(Number(e.amount))}
                    </Num>
                  </div>
                ))}
              </div>
            </section>
          </>
        )}
      </div>
    </div>
  )
}
