'use client'

import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useReportApi } from '@/lib/useApi'
import type { TypeBreakdown, TopHolding } from '@/types/report'
import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer, Legend } from 'recharts'

const TYPE_COLORS: Record<string, string> = {
  CRYPTO: '#f59e0b', STOCK: '#3b82f6', REAL_ESTATE: '#10b981',
  VEHICLE: '#8b5cf6', GOLD: '#eab308', CASH: '#6b7280', ETC: '#ec4899',
}
const TYPE_KO: Record<string, string> = {
  CRYPTO: '암호화폐', STOCK: '주식', REAL_ESTATE: '부동산',
  VEHICLE: '자동차', GOLD: '금', CASH: '현금', ETC: '기타',
}

function fmt(n: number, currency = 'USD') {
  return new Intl.NumberFormat('ko-KR', { style: 'currency', currency, maximumFractionDigits: 0 }).format(n)
}
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
    color: TYPE_COLORS[t.type] ?? '#6b7280',
    pct:   t.pct,
  }))

  const pnlColor = data.unrealizedPnl >= 0 ? 'text-emerald-400' : 'text-red-400'

  return (
    <div className="space-y-8">
      <div className="flex items-center gap-3">
        <Link href="/unified/reports" className="text-sm text-gray-500 hover:text-gray-300">← 보고서</Link>
        <h1 className="text-2xl font-bold">포트폴리오 요약</h1>
      </div>
      <p className="text-xs text-gray-500">생성: {new Date(data.generatedAt).toLocaleString('ko-KR')}</p>

      {/* KPI Cards */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <KpiCard label="총 자산 (NAV)" value={fmt(data.nav)} />
        <KpiCard label="총 매입 원가" value={fmt(data.totalPurchaseCost)} />
        <KpiCard label="미실현 손익" value={fmt(data.unrealizedPnl)} valueClass={pnlColor} />
        <KpiCard label="수익률" value={fmtPct(data.unrealizedPnlPct)} valueClass={pnlColor} />
      </div>

      <div className="grid gap-4 sm:grid-cols-2">
        <KpiCard label="보유 자산 수" value={`${data.assetCount}개`} />
        <KpiCard label="연결 계좌 수" value={`${data.accountCount}개`} />
      </div>

      {/* Pie + Type table */}
      <div className="grid gap-6 lg:grid-cols-2">
        <div className="rounded-xl border border-gray-700 bg-gray-900 p-6">
          <h2 className="mb-4 text-sm font-semibold text-gray-300">자산 유형별 비중</h2>
          {pieData.length > 0 ? (
            <ResponsiveContainer width="100%" height={260}>
              <PieChart>
                <Pie data={pieData} dataKey="value" cx="50%" cy="50%" innerRadius={60} outerRadius={110} paddingAngle={2}>
                  {pieData.map((e, i) => <Cell key={i} fill={e.color} />)}
                </Pie>
                <Tooltip
                  formatter={(v: number) => [fmt(v), '가치']}
                  contentStyle={{ background: '#111827', border: '1px solid #374151', borderRadius: 8 }}
                />
                <Legend formatter={(v) => <span className="text-xs text-gray-300">{v}</span>} />
              </PieChart>
            </ResponsiveContainer>
          ) : <Empty />}
        </div>

        <div className="rounded-xl border border-gray-700 bg-gray-900 p-6">
          <h2 className="mb-4 text-sm font-semibold text-gray-300">유형별 상세</h2>
          <div className="space-y-3">
            {data.byType.map((t: TypeBreakdown) => (
              <div key={t.type} className="flex items-center gap-3">
                <span className="h-3 w-3 shrink-0 rounded-full" style={{ background: TYPE_COLORS[t.type] ?? '#6b7280' }} />
                <span className="flex-1 text-sm text-gray-300">{TYPE_KO[t.type] ?? t.type}</span>
                <div className="flex-1 h-2 rounded-full bg-gray-800 overflow-hidden">
                  <div className="h-full rounded-full" style={{ width: `${t.pct}%`, background: TYPE_COLORS[t.type] ?? '#6b7280' }} />
                </div>
                <span className="w-14 text-right text-sm tabular-nums text-gray-400">{t.pct.toFixed(1)}%</span>
                <span className="w-24 text-right text-xs text-gray-500">{t.count}종</span>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Currency Breakdown */}
      <div className="rounded-xl border border-gray-700 bg-gray-900 p-6">
        <h2 className="mb-4 text-sm font-semibold text-gray-300">통화별 비중</h2>
        <div className="flex flex-wrap gap-4">
          {data.byCurrency.map((c) => (
            <div key={c.currency} className="flex-1 min-w-[120px] rounded-lg border border-gray-700 p-4 text-center">
              <div className="text-lg font-bold text-gray-200">{c.currency}</div>
              <div className="text-sm text-gray-400 tabular-nums">{fmt(c.value, c.currency)}</div>
              <div className="text-xs text-gray-500 mt-0.5">{c.pct.toFixed(1)}%</div>
            </div>
          ))}
        </div>
      </div>

      {/* Top Holdings */}
      <div className="rounded-xl border border-gray-700 bg-gray-900">
        <div className="flex items-center justify-between px-6 py-4 border-b border-gray-700">
          <h2 className="text-sm font-semibold text-gray-300">상위 보유 자산</h2>
        </div>
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-xs text-gray-500 border-b border-gray-800">
              <th className="px-6 py-3">#</th>
              <th className="px-4 py-3">자산명</th>
              <th className="px-4 py-3">유형</th>
              <th className="px-4 py-3 text-right">현재 가치</th>
              <th className="px-4 py-3 text-right">비중</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-800">
            {data.topHoldings.map((h: TopHolding, i) => (
              <tr key={i} className="hover:bg-gray-800/50">
                <td className="px-6 py-3 text-gray-500 text-xs">{i + 1}</td>
                <td className="px-4 py-3">
                  <div className="font-medium text-gray-200">{h.name}</div>
                  {h.symbol && <div className="text-xs text-gray-500">{h.symbol}</div>}
                </td>
                <td className="px-4 py-3">
                  <span className="rounded-full px-2 py-0.5 text-xs" style={{ background: `${TYPE_COLORS[h.type]}25`, color: TYPE_COLORS[h.type] ?? '#9ca3af' }}>
                    {TYPE_KO[h.type] ?? h.type}
                  </span>
                </td>
                <td className="px-4 py-3 text-right tabular-nums text-gray-300">{fmt(h.value)}</td>
                <td className="px-4 py-3 text-right tabular-nums text-gray-400">{h.pct.toFixed(1)}%</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function KpiCard({ label, value, valueClass = 'text-white' }: { label: string; value: string; valueClass?: string }) {
  return (
    <div className="rounded-xl border border-gray-700 bg-gray-900 p-5">
      <p className="text-xs text-gray-500">{label}</p>
      <p className={`mt-2 text-2xl font-bold tabular-nums ${valueClass}`}>{value}</p>
    </div>
  )
}
function Skeleton() { return <div className="h-96 animate-pulse rounded-xl bg-gray-800" /> }
function Err() { return <div className="rounded-xl border border-red-800 bg-red-950 p-6 text-sm text-red-400">보고서를 불러올 수 없습니다.</div> }
function Empty() { return <div className="flex h-48 items-center justify-center text-sm text-gray-500">데이터 없음</div> }
