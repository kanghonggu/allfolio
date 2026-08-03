'use client'

import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useReportApi } from '@/lib/useApi'
import type { MonthlyPnlRow } from '@/types/report'
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid,
  Tooltip, ResponsiveContainer, Cell, ReferenceLine,
} from 'recharts'

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

  const totalColor = data.totalAbsolutePnl >= 0 ? 'text-emerald-400' : 'text-red-400'

  return (
    <div className="space-y-8">
      <div className="flex items-center gap-3">
        <Link href="/unified/reports" className="text-sm text-gray-500 hover:text-gray-300">← 보고서</Link>
        <h1 className="text-2xl font-bold">월별 손익 정산</h1>
      </div>
      <p className="text-xs text-gray-500">생성: {new Date(data.generatedAt).toLocaleString('ko-KR')}</p>

      {/* KPI */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <div className="rounded-xl border border-gray-700 bg-gray-900 p-5">
          <p className="text-xs text-gray-500">분석 기간</p>
          <p className="mt-2 text-xl font-bold">{data.months.length}개월</p>
        </div>
        <div className="rounded-xl border border-gray-700 bg-gray-900 p-5">
          <p className="text-xs text-gray-500">누적 손익</p>
          <p className={`mt-2 text-xl font-bold tabular-nums ${totalColor}`}>
            {data.totalAbsolutePnl >= 0 ? '+' : ''}{fmt(data.totalAbsolutePnl)}
          </p>
        </div>
        <div className="rounded-xl border border-emerald-900 bg-gray-900 p-5">
          <p className="text-xs text-gray-500">수익 달</p>
          <p className="mt-2 text-xl font-bold text-emerald-400">{data.winMonths}개월</p>
        </div>
        <div className="rounded-xl border border-red-900 bg-gray-900 p-5">
          <p className="text-xs text-gray-500">손실 달</p>
          <p className="mt-2 text-xl font-bold text-red-400">{data.loseMonths}개월</p>
        </div>
      </div>

      {/* 베스트 / 워스트 — 데이터 1개월이면 최고=최저라 의미 없으므로 숨김 (부족 배너와 정책 일치, QA P2) */}
      {data.months.length >= 2 && (data.bestMonth || data.worstMonth) && (
        <div className="grid gap-4 sm:grid-cols-2">
          {data.bestMonth && (
            <div className="rounded-xl border border-emerald-800 bg-gray-900 p-5">
              <p className="text-xs text-gray-500">최고 달</p>
              <p className="mt-1 text-lg font-bold text-emerald-400">{data.bestMonth.yearMonth}</p>
              <p className="text-sm text-emerald-300">
                {fmtSigned(Number(data.bestMonth.absolutePnl))}
                <span className="ml-2 text-xs text-emerald-500">({fmtSignedPct(Number(data.bestMonth.returnPct))})</span>
              </p>
            </div>
          )}
          {data.worstMonth && (
            <div className="rounded-xl border border-red-800 bg-gray-900 p-5">
              <p className="text-xs text-gray-500">최저 달</p>
              <p className="mt-1 text-lg font-bold text-red-400">{data.worstMonth.yearMonth}</p>
              <p className="text-sm text-red-300">
                {fmtSigned(Number(data.worstMonth.absolutePnl))}
                <span className="ml-2 text-xs text-red-500">({fmtSignedPct(Number(data.worstMonth.returnPct))})</span>
              </p>
            </div>
          )}
        </div>
      )}

      {/* 월별 손익 바차트 */}
      <div className="rounded-xl border border-gray-700 bg-gray-900 p-6">
        <h2 className="mb-4 text-sm font-semibold text-gray-300">월별 손익 (원)</h2>
        {chartData.length >= 2 ? (
          <ResponsiveContainer width="100%" height={280}>
            <BarChart data={chartData} margin={{ top: 5, right: 20, left: 10, bottom: 5 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#1f2937" vertical={false} />
              <XAxis dataKey="label" tick={{ fontSize: 11, fill: '#6b7280' }} axisLine={false} tickLine={false} />
              <YAxis
                tickFormatter={(v) => `₩${fmtShort(v)}`}
                tick={{ fontSize: 11, fill: '#6b7280' }}
                axisLine={false}
                tickLine={false}
                width={70}
              />
              <Tooltip
                formatter={(v: number) => [fmt(v), '손익']}
                contentStyle={{ background: '#111827', border: '1px solid #374151', borderRadius: 8 }}
                labelStyle={{ color: '#d1d5db' }}
              />
              <ReferenceLine y={0} stroke="#374151" />
              <Bar dataKey="pnl" name="손익" radius={[4, 4, 0, 0]}>
                {chartData.map((entry, i) => (
                  <Cell key={i} fill={entry.pnl >= 0 ? '#10b981' : '#ef4444'} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        ) : (
          <Empty />
        )}
      </div>

      {/* 월별 수익률 바차트 */}
      {chartData.length >= 2 && (
        <div className="rounded-xl border border-gray-700 bg-gray-900 p-6">
          <h2 className="mb-4 text-sm font-semibold text-gray-300">월별 수익률 (%)</h2>
          <ResponsiveContainer width="100%" height={200}>
            <BarChart data={chartData} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#1f2937" vertical={false} />
              <XAxis dataKey="label" tick={{ fontSize: 11, fill: '#6b7280' }} axisLine={false} tickLine={false} />
              <YAxis
                tickFormatter={(v) => `${v.toFixed(1)}%`}
                tick={{ fontSize: 11, fill: '#6b7280' }}
                axisLine={false}
                tickLine={false}
              />
              <Tooltip
                formatter={(v: number) => [`${v.toFixed(2)}%`, '수익률']}
                contentStyle={{ background: '#111827', border: '1px solid #374151', borderRadius: 8 }}
              />
              <ReferenceLine y={0} stroke="#374151" />
              <Bar dataKey="ret" name="수익률" radius={[4, 4, 0, 0]}>
                {chartData.map((entry, i) => (
                  <Cell key={i} fill={entry.ret >= 0 ? '#10b981' : '#ef4444'} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}

      {/* 월별 상세 테이블 */}
      <div className="rounded-xl border border-gray-700 bg-gray-900 overflow-x-auto">
        <div className="px-6 py-4 border-b border-gray-700">
          <h2 className="text-sm font-semibold text-gray-300">월별 상세</h2>
        </div>
        {data.months.length === 0 ? (
          <Empty />
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-xs text-gray-500 border-b border-gray-800">
                <th className="px-6 py-3">월</th>
                <th className="px-4 py-3 text-right">시작 NAV</th>
                <th className="px-4 py-3 text-right">종료 NAV</th>
                <th className="px-4 py-3 text-right">손익</th>
                <th className="px-4 py-3 text-right">수익률</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-800">
              {[...data.months].reverse().map((m: MonthlyPnlRow) => {
                const pnl = Number(m.absolutePnl)
                const ret = Number(m.returnPct)
                const color = pnl >= 0 ? 'text-emerald-400' : 'text-red-400'
                return (
                  <tr key={m.yearMonth} className="hover:bg-gray-800/40">
                    <td className="px-6 py-3 font-medium">{m.yearMonth}</td>
                    <td className="px-4 py-3 text-right tabular-nums text-gray-400 text-xs">{fmt(Number(m.startNav))}</td>
                    <td className="px-4 py-3 text-right tabular-nums text-gray-300 text-xs">{fmt(Number(m.endNav))}</td>
                    <td className={`px-4 py-3 text-right tabular-nums ${color}`}>
                      {pnl >= 0 ? '+' : ''}{fmt(pnl)}
                    </td>
                    <td className={`px-4 py-3 text-right tabular-nums font-semibold ${color}`}>
                      {ret >= 0 ? '+' : ''}{ret.toFixed(2)}%
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}

function Skeleton() { return <div className="h-96 animate-pulse rounded-xl bg-gray-800" /> }
function Err() { return <div className="rounded-xl border border-red-800 bg-red-950 p-6 text-sm text-red-400">보고서를 불러올 수 없습니다.</div> }
function Empty() {
  return (
    <div className="flex h-48 flex-col items-center justify-center gap-2 text-center text-sm text-gray-500">
      <p>월별 데이터가 부족합니다.</p>
      <p className="text-xs text-gray-600">매일 Sync를 실행하면 이력이 쌓입니다.</p>
    </div>
  )
}
