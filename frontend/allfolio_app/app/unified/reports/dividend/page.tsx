'use client'

import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useReportApi } from '@/lib/useApi'
import type { MonthlyDividend, SymbolDividend, DividendEntry } from '@/types/dividend'
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid,
  Tooltip, ResponsiveContainer,
} from 'recharts'

type Period = 'YTD' | '1Y' | '전체'
const PERIODS: Period[] = ['YTD', '1Y', '전체']

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

  if (isLoading) return <div className="h-96 animate-pulse rounded-xl bg-gray-800" />
  if (isError || !data) return (
    <div className="rounded-xl border border-red-800 bg-red-950 p-6 text-sm text-red-400">
      보고서를 불러올 수 없습니다.
    </div>
  )

  const chartData = data.monthlySeries.map((m: MonthlyDividend) => ({
    month: m.month,
    amount: Number(m.amount),
  }))

  const isEmpty = data.receiptCount === 0

  return (
    <div className="space-y-8">
      {/* 헤더 + 기간 탭 */}
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div className="flex items-center gap-3">
          <Link href="/unified/reports" className="text-sm text-gray-500 hover:text-gray-300">
            ← 보고서
          </Link>
          <h1 className="text-2xl font-bold">배당금 보고서</h1>
        </div>
        <div className="flex gap-2">
          {PERIODS.map((p) => (
            <button
              key={p}
              onClick={() => setPeriod(p)}
              className={`rounded-lg px-4 py-1.5 text-sm font-medium transition-colors ${
                period === p
                  ? 'bg-yellow-600 text-white'
                  : 'bg-gray-800 text-gray-400 hover:bg-gray-700'
              }`}
            >
              {p}
            </button>
          ))}
        </div>
      </div>

      <p className="text-xs text-gray-500">생성: {new Date(data.generatedAt).toLocaleString('ko-KR')}</p>

      {/* KPI 카드 — 배당 내역 없으면 빈 카드 대신 아래 빈 상태만 노출 (QA P2) */}
      {!isEmpty && (
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <div className="rounded-xl border border-yellow-800 bg-gray-900 p-5">
          <p className="text-xs text-gray-500">총 수령액</p>
          <p className="mt-2 text-2xl font-bold tabular-nums text-yellow-400">
            {fmt(Number(data.totalDividend))}
          </p>
        </div>
        <div className="rounded-xl border border-gray-700 bg-gray-900 p-5">
          <p className="text-xs text-gray-500">수령 횟수</p>
          <p className="mt-2 text-2xl font-bold tabular-nums">{data.receiptCount}회</p>
        </div>
        <div className="rounded-xl border border-gray-700 bg-gray-900 p-5">
          <p className="text-xs text-gray-500">월 평균</p>
          <p className="mt-2 text-2xl font-bold tabular-nums">
            {fmt(Number(data.monthlyAvg))}
          </p>
        </div>
        <div className="rounded-xl border border-emerald-800 bg-gray-900 p-5">
          <p className="text-xs text-gray-500">연환산 예상</p>
          <p className="mt-2 text-2xl font-bold tabular-nums text-emerald-400">
            {fmt(Number(data.annualProjected))}
          </p>
          <p className="mt-0.5 text-xs text-gray-600">최근 12개월 기준</p>
        </div>
      </div>
      )}

      {isEmpty ? (
        <div className="flex flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-gray-700 py-16 text-center">
          <p className="text-gray-400 font-medium">아직 배당 내역이 없습니다</p>
          <p className="text-sm text-gray-600">
            거래 내역 입력 시 유형을 <span className="text-gray-400 font-medium">배당</span>으로 선택하면 여기에 집계됩니다.
          </p>
          <Link
            href="/unified/accounts"
            className="mt-2 rounded-lg bg-gray-700 px-4 py-2 text-sm font-medium text-gray-300 hover:bg-gray-600 transition-colors"
          >
            계좌로 이동
          </Link>
        </div>
      ) : (
        <>
          {/* 월별 수령액 차트 */}
          <div className="rounded-xl border border-gray-700 bg-gray-900 p-6">
            <h2 className="mb-4 text-sm font-semibold text-gray-300">월별 수령액</h2>
            {chartData.length >= 1 ? (
              <ResponsiveContainer width="100%" height={260}>
                <BarChart data={chartData} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#1f2937" />
                  <XAxis
                    dataKey="month"
                    tick={{ fontSize: 11, fill: '#6b7280' }}
                    tickLine={false}
                  />
                  <YAxis
                    tickFormatter={(v) =>
                      v >= 1_000_000
                        ? `${(v / 1_000_000).toFixed(0)}M`
                        : v >= 10_000
                          ? `${(v / 10_000).toFixed(0)}만`
                          : String(v)
                    }
                    tick={{ fontSize: 11, fill: '#6b7280' }}
                    tickLine={false}
                    axisLine={false}
                    width={60}
                  />
                  <Tooltip
                    formatter={(v: number) => [fmt(v), '배당 수령액']}
                    contentStyle={{
                      background: '#111827',
                      border: '1px solid #374151',
                      borderRadius: 8,
                    }}
                    labelStyle={{ color: '#d1d5db' }}
                  />
                  <Bar dataKey="amount" name="수령액" fill="#ca8a04" radius={[4, 4, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            ) : (
              <div className="flex h-48 items-center justify-center text-sm text-gray-500">
                차트 데이터가 부족합니다.
              </div>
            )}
          </div>

          {/* 종목별 배당 */}
          <div className="rounded-xl border border-gray-700 bg-gray-900 p-6">
            <h2 className="mb-4 text-sm font-semibold text-gray-300">종목별 배당</h2>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-xs text-gray-500 border-b border-gray-800">
                    <th className="pb-2 font-normal">종목</th>
                    <th className="pb-2 font-normal text-right">수령 횟수</th>
                    <th className="pb-2 font-normal text-right">합계</th>
                    <th className="pb-2 font-normal text-right">비중</th>
                    <th className="pb-2 font-normal text-right">최근 수령일</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-800">
                  {data.bySymbol.map((s: SymbolDividend) => (
                    <tr key={`${s.stockName}-${s.symbol}`}>
                      <td className="py-2.5">
                        <span className="font-medium text-gray-200">{s.stockName}</span>
                        {s.symbol && (
                          <span className="ml-1.5 text-xs text-gray-500">{s.symbol}</span>
                        )}
                      </td>
                      <td className="py-2.5 text-right text-gray-400">{s.receiptCount}회</td>
                      <td className="py-2.5 text-right font-semibold tabular-nums text-yellow-400">
                        {fmt(Number(s.totalAmount))}
                      </td>
                      <td className="py-2.5 text-right tabular-nums text-gray-500">
                        {Number(s.pct).toFixed(1)}%
                      </td>
                      <td className="py-2.5 text-right tabular-nums text-gray-500">
                        {s.lastReceivedAt}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          {/* 최근 수령 이력 */}
          <div className="rounded-xl border border-gray-700 bg-gray-900 p-6">
            <h2 className="mb-4 text-sm font-semibold text-gray-300">최근 수령 이력</h2>
            <div className="space-y-2">
              {data.recentHistory.map((e: DividendEntry, i: number) => (
                <div
                  key={i}
                  className="flex items-center justify-between rounded-lg bg-gray-800/50 px-4 py-2.5"
                >
                  <div className="flex items-center gap-3">
                    <span className="text-xs tabular-nums text-gray-500">{e.tradedAt}</span>
                    <span className="text-sm text-gray-200">{e.stockName}</span>
                    {e.symbol && (
                      <span className="text-xs text-gray-500">{e.symbol}</span>
                    )}
                    {e.memo && (
                      <span className="text-xs text-gray-600 italic">{e.memo}</span>
                    )}
                  </div>
                  <span className="text-sm font-semibold tabular-nums text-yellow-400">
                    +{fmt(Number(e.amount))}
                  </span>
                </div>
              ))}
            </div>
          </div>
        </>
      )}
    </div>
  )
}
