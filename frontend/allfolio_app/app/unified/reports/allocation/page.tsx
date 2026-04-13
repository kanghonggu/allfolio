'use client'

import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useReportApi } from '@/lib/useApi'
import type { TypeBreakdown, CurrencyBreakdown, TopHolding } from '@/types/report'
import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer, Legend, BarChart, Bar, XAxis, YAxis, CartesianGrid } from 'recharts'

const TYPE_COLORS: Record<string, string> = {
  CRYPTO: '#f59e0b', STOCK: '#3b82f6', REAL_ESTATE: '#10b981',
  VEHICLE: '#8b5cf6', GOLD: '#eab308', CASH: '#6b7280', ETC: '#ec4899',
}
const TYPE_KO: Record<string, string> = {
  CRYPTO: '암호화폐', STOCK: '주식', REAL_ESTATE: '부동산',
  VEHICLE: '자동차', GOLD: '금', CASH: '현금', ETC: '기타',
}
const CURRENCY_COLORS = ['#3b82f6', '#10b981', '#f59e0b', '#8b5cf6', '#ec4899']

function fmt(n: number, currency = 'USD') {
  return new Intl.NumberFormat('ko-KR', { style: 'currency', currency, maximumFractionDigits: 0 }).format(n)
}

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
    color: TYPE_COLORS[t.type] ?? '#6b7280',
  }))

  const currencyPie = data.byCurrency.map((c: CurrencyBreakdown, i: number) => ({
    name: c.currency,
    value: c.value,
    color: CURRENCY_COLORS[i % CURRENCY_COLORS.length],
  }))

  const hhiRisk = data.concentrationHHI > 0.25 ? '고위험' : data.concentrationHHI > 0.15 ? '중간' : '분산'
  const hhiColor = data.concentrationHHI > 0.25 ? 'text-red-400' : data.concentrationHHI > 0.15 ? 'text-amber-400' : 'text-emerald-400'

  return (
    <div className="space-y-8">
      <div className="flex items-center gap-3">
        <Link href="/unified/reports" className="text-sm text-gray-500 hover:text-gray-300">← 보고서</Link>
        <h1 className="text-2xl font-bold">자산 배분</h1>
      </div>

      {/* Summary KPIs */}
      <div className="grid gap-4 sm:grid-cols-3">
        <div className="rounded-xl border border-gray-700 bg-gray-900 p-5">
          <p className="text-xs text-gray-500">총 자산</p>
          <p className="mt-2 text-2xl font-bold tabular-nums">{fmt(data.totalValue)}</p>
        </div>
        <div className="rounded-xl border border-gray-700 bg-gray-900 p-5">
          <p className="text-xs text-gray-500">HHI 집중도</p>
          <p className={`mt-2 text-2xl font-bold tabular-nums ${hhiColor}`}>
            {data.concentrationHHI.toFixed(4)}
          </p>
          <p className={`text-xs mt-0.5 ${hhiColor}`}>{hhiRisk}</p>
        </div>
        <div className="rounded-xl border border-gray-700 bg-gray-900 p-5">
          <p className="text-xs text-gray-500">상위 5개 비중</p>
          <p className="mt-2 text-2xl font-bold tabular-nums text-amber-400">
            {data.top5Concentration.toFixed(1)}%
          </p>
        </div>
      </div>

      {/* Pie Charts */}
      <div className="grid gap-6 lg:grid-cols-2">
        <div className="rounded-xl border border-gray-700 bg-gray-900 p-6">
          <h2 className="mb-4 text-sm font-semibold text-gray-300">자산 유형별</h2>
          {typePie.length > 0 ? (
            <ResponsiveContainer width="100%" height={260}>
              <PieChart>
                <Pie data={typePie} dataKey="value" cx="50%" cy="50%" innerRadius={60} outerRadius={110} paddingAngle={2}>
                  {typePie.map((e, i) => <Cell key={i} fill={e.color} />)}
                </Pie>
                <Tooltip formatter={(v: number) => [fmt(v), '가치']} contentStyle={{ background: '#111827', border: '1px solid #374151', borderRadius: 8 }} />
                <Legend formatter={(v) => <span className="text-xs text-gray-300">{v}</span>} />
              </PieChart>
            </ResponsiveContainer>
          ) : <Empty />}
        </div>

        <div className="rounded-xl border border-gray-700 bg-gray-900 p-6">
          <h2 className="mb-4 text-sm font-semibold text-gray-300">통화별</h2>
          {currencyPie.length > 0 ? (
            <ResponsiveContainer width="100%" height={260}>
              <PieChart>
                <Pie data={currencyPie} dataKey="value" cx="50%" cy="50%" innerRadius={60} outerRadius={110} paddingAngle={2}>
                  {currencyPie.map((e, i) => <Cell key={i} fill={e.color} />)}
                </Pie>
                <Tooltip formatter={(v: number) => [v.toFixed(2), '가치']} contentStyle={{ background: '#111827', border: '1px solid #374151', borderRadius: 8 }} />
                <Legend formatter={(v) => <span className="text-xs text-gray-300">{v}</span>} />
              </PieChart>
            </ResponsiveContainer>
          ) : <Empty />}
        </div>
      </div>

      {/* Top Holdings Bar */}
      <div className="rounded-xl border border-gray-700 bg-gray-900 p-6">
        <h2 className="mb-4 text-sm font-semibold text-gray-300">상위 보유 자산 비중</h2>
        {data.topHoldings.length > 0 ? (
          <ResponsiveContainer width="100%" height={300}>
            <BarChart
              data={data.topHoldings.map((h: TopHolding) => ({ name: h.name, pct: h.pct }))}
              layout="vertical"
              margin={{ left: 20, right: 30 }}
            >
              <CartesianGrid strokeDasharray="3 3" stroke="#1f2937" horizontal={false} />
              <XAxis type="number" tickFormatter={(v) => `${v}%`} tick={{ fontSize: 11, fill: '#6b7280' }} axisLine={false} tickLine={false} />
              <YAxis type="category" dataKey="name" tick={{ fontSize: 11, fill: '#9ca3af' }} width={100} axisLine={false} tickLine={false} />
              <Tooltip formatter={(v: number) => [`${v.toFixed(2)}%`, '비중']} contentStyle={{ background: '#111827', border: '1px solid #374151', borderRadius: 8 }} />
              <Bar dataKey="pct" name="비중" fill="#3b82f6" radius={[0, 4, 4, 0]} />
            </BarChart>
          </ResponsiveContainer>
        ) : <Empty />}
      </div>

      {/* Top Holdings Table */}
      <div className="rounded-xl border border-gray-700 bg-gray-900">
        <div className="px-6 py-4 border-b border-gray-700">
          <h2 className="text-sm font-semibold text-gray-300">상위 보유 자산 목록</h2>
        </div>
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-xs text-gray-500 border-b border-gray-800">
              <th className="px-6 py-3">#</th>
              <th className="px-4 py-3">자산명</th>
              <th className="px-4 py-3">유형</th>
              <th className="px-4 py-3 text-right">현재 가치</th>
              <th className="px-4 py-3 text-right">비중</th>
              <th className="px-4 py-3">비중 바</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-800">
            {data.topHoldings.map((h: TopHolding, i: number) => (
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
                <td className="px-4 py-3 text-right tabular-nums text-gray-400 w-16">{h.pct.toFixed(1)}%</td>
                <td className="px-4 py-3 w-32">
                  <div className="h-2 rounded-full bg-gray-800">
                    <div className="h-full rounded-full bg-blue-500" style={{ width: `${Math.min(h.pct, 100)}%` }} />
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function Skeleton() { return <div className="h-96 animate-pulse rounded-xl bg-gray-800" /> }
function Err() { return <div className="rounded-xl border border-red-800 bg-red-950 p-6 text-sm text-red-400">보고서를 불러올 수 없습니다.</div> }
function Empty() { return <div className="flex h-48 items-center justify-center text-sm text-gray-500">데이터 없음</div> }
