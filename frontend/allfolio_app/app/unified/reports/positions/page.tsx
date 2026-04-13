'use client'

import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useReportApi } from '@/lib/useApi'
import type { PositionRow } from '@/types/report'

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

type SortKey = 'currentValue' | 'unrealizedPnl' | 'unrealizedPnlPct' | 'purchaseCost'

export default function PositionsPage() {
  const reportApi = useReportApi()
  const [sortKey, setSortKey] = useState<SortKey>('currentValue')
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('desc')
  const [filterType, setFilterType] = useState<string>('ALL')

  const { data, isLoading, isError } = useQuery({
    queryKey: ['report', 'positions'],
    queryFn: () => reportApi!.positions(),
    enabled: !!reportApi,
  })

  if (isLoading) return <Skeleton />
  if (isError || !data) return <Err />

  const types = ['ALL', ...Array.from(new Set(data.positions.map((p) => p.type)))]

  const filtered = data.positions
    .filter((p) => filterType === 'ALL' || p.type === filterType)
    .sort((a, b) => {
      const av = a[sortKey] as number
      const bv = b[sortKey] as number
      return sortDir === 'desc' ? bv - av : av - bv
    })

  function toggleSort(key: SortKey) {
    if (sortKey === key) setSortDir((d) => (d === 'desc' ? 'asc' : 'desc'))
    else { setSortKey(key); setSortDir('desc') }
  }

  const sortIcon = (key: SortKey) => sortKey === key ? (sortDir === 'desc' ? ' ↓' : ' ↑') : ''

  const totalPnlColor = Number(data.totalUnrealizedPnl) >= 0 ? 'text-emerald-400' : 'text-red-400'
  const totalRetColor = Number(data.totalReturnPct) >= 0 ? 'text-emerald-400' : 'text-red-400'

  return (
    <div className="space-y-8">
      <div className="flex items-center gap-3">
        <Link href="/unified/reports" className="text-sm text-gray-500 hover:text-gray-300">← 보고서</Link>
        <h1 className="text-2xl font-bold">포지션 & 손익</h1>
      </div>
      <p className="text-xs text-gray-500">생성: {new Date(data.generatedAt).toLocaleString('ko-KR')}</p>

      {/* Summary KPIs */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <KpiCard label="총 현재 가치" value={fmt(Number(data.totalCurrentValue))} />
        <KpiCard label="총 매입 원가" value={fmt(Number(data.totalPurchaseCost))} />
        <KpiCard
          label="미실현 총 손익"
          value={`${Number(data.totalUnrealizedPnl) >= 0 ? '+' : ''}${fmt(Number(data.totalUnrealizedPnl))}`}
          valueClass={totalPnlColor}
        />
        <KpiCard
          label="전체 수익률"
          value={`${Number(data.totalReturnPct) >= 0 ? '+' : ''}${Number(data.totalReturnPct).toFixed(2)}%`}
          valueClass={totalRetColor}
        />
      </div>

      {/* Filters */}
      <div className="flex flex-wrap gap-2">
        {types.map((t) => (
          <button
            key={t}
            onClick={() => setFilterType(t)}
            className={`px-3 py-1 rounded-full text-xs font-medium transition-colors ${
              filterType === t ? 'bg-blue-600 text-white' : 'bg-gray-800 text-gray-400 hover:bg-gray-700'
            }`}
          >
            {t === 'ALL' ? '전체' : (TYPE_KO[t] ?? t)}
          </button>
        ))}
      </div>

      {/* Position Table */}
      <div className="rounded-xl border border-gray-700 bg-gray-900 overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-xs text-gray-500 border-b border-gray-800">
              <th className="px-6 py-3 font-medium">자산명</th>
              <th className="px-4 py-3 font-medium">계좌</th>
              <th className="px-4 py-3 font-medium">유형</th>
              <th className="px-4 py-3 text-right font-medium">수량</th>
              <th className="px-4 py-3 text-right font-medium">평균 매입가</th>
              <th
                className="px-4 py-3 text-right font-medium cursor-pointer hover:text-gray-300"
                onClick={() => toggleSort('purchaseCost')}
              >
                매입 원가{sortIcon('purchaseCost')}
              </th>
              <th
                className="px-4 py-3 text-right font-medium cursor-pointer hover:text-gray-300"
                onClick={() => toggleSort('currentValue')}
              >
                현재 가치{sortIcon('currentValue')}
              </th>
              <th
                className="px-4 py-3 text-right font-medium cursor-pointer hover:text-gray-300"
                onClick={() => toggleSort('unrealizedPnl')}
              >
                미실현 손익{sortIcon('unrealizedPnl')}
              </th>
              <th
                className="px-4 py-3 text-right font-medium cursor-pointer hover:text-gray-300"
                onClick={() => toggleSort('unrealizedPnlPct')}
              >
                수익률{sortIcon('unrealizedPnlPct')}
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-800">
            {filtered.length === 0 && (
              <tr>
                <td colSpan={9} className="py-12 text-center text-sm text-gray-500">포지션 없음</td>
              </tr>
            )}
            {filtered.map((p: PositionRow, i) => {
              const pnl = Number(p.unrealizedPnl)
              const ret = Number(p.unrealizedPnlPct)
              const pnlColor = pnl >= 0 ? 'text-emerald-400' : 'text-red-400'
              return (
                <tr key={i} className="hover:bg-gray-800/50 transition-colors">
                  <td className="px-6 py-3">
                    <div className="font-medium text-gray-200">{p.name}</div>
                    {p.symbol && <div className="text-xs text-gray-500">{p.symbol}</div>}
                    <div className="text-xs text-gray-600 mt-0.5">{p.confidenceLevel}</div>
                  </td>
                  <td className="px-4 py-3 text-gray-400 text-xs">{p.accountName}</td>
                  <td className="px-4 py-3">
                    <span
                      className="rounded-full px-2 py-0.5 text-xs"
                      style={{ background: `${TYPE_COLORS[p.type]}25`, color: TYPE_COLORS[p.type] ?? '#9ca3af' }}
                    >
                      {TYPE_KO[p.type] ?? p.type}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums text-gray-300 text-xs">
                    {Number(p.quantity).toLocaleString('ko-KR', { maximumFractionDigits: 8 })}
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums text-gray-400 text-xs">
                    {fmt(Number(p.avgCost), p.currency)}
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums text-gray-300">
                    {fmt(Number(p.purchaseCost), p.currency)}
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums text-gray-200">
                    {fmt(Number(p.currentValue), p.currency)}
                  </td>
                  <td className={`px-4 py-3 text-right tabular-nums ${pnlColor}`}>
                    {pnl >= 0 ? '+' : ''}{fmt(pnl, p.currency)}
                  </td>
                  <td className={`px-4 py-3 text-right tabular-nums font-medium ${pnlColor}`}>
                    {ret >= 0 ? '+' : ''}{ret.toFixed(2)}%
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>

      <p className="text-xs text-gray-600">
        * 평균 매입가는 입력된 매입가 기준이며, FIFO 실현 손익은 거래 이력이 쌓이면 자동 계산됩니다.
      </p>
    </div>
  )
}

function KpiCard({ label, value, valueClass = 'text-white' }: { label: string; value: string; valueClass?: string }) {
  return (
    <div className="rounded-xl border border-gray-700 bg-gray-900 p-5">
      <p className="text-xs text-gray-500">{label}</p>
      <p className={`mt-2 text-xl font-bold tabular-nums ${valueClass}`}>{value}</p>
    </div>
  )
}
function Skeleton() { return <div className="h-96 animate-pulse rounded-xl bg-gray-800" /> }
function Err() { return <div className="rounded-xl border border-red-800 bg-red-950 p-6 text-sm text-red-400">보고서를 불러올 수 없습니다.</div> }
