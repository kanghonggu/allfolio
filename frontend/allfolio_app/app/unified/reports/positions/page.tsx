'use client'

import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useReportApi } from '@/lib/useApi'
import type { PositionRow } from '@/types/report'
import PageHeader from '@/components/ui/PageHeader'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import { LoadingState, ErrorState } from '@/components/ui/states'
import { dirTone, toneText } from '@/lib/format'

const TYPE_KO: Record<string, string> = {
  CRYPTO: '암호화폐', STOCK: '주식', REAL_ESTATE: '부동산',
  VEHICLE: '자동차', GOLD: '금', CASH: '현금', ETC: '기타',
}

function fmt(n: number, currency = 'KRW') {
  // KRW는 정수, 그 외 통화는 소수 2자리 — US$0 같은 과반올림 방지 (QA P2)
  return new Intl.NumberFormat('ko-KR', {
    style: 'currency', currency,
    maximumFractionDigits: currency === 'KRW' ? 0 : 2,
  }).format(n)
}

type SortKey = 'currentValue' | 'unrealizedPnl' | 'unrealizedPnlPct' | 'purchaseCost'

const TABLE_GRID = 'grid grid-cols-[1.6fr_0.9fr_0.7fr_0.8fr_1fr_1.1fr_1.2fr_1.1fr_0.8fr] gap-3'

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
      // 현재 가치 정렬은 통화 혼재를 피해 KRW 환산 기준 (QA P2)
      const key = sortKey === 'currentValue' ? 'currentValueKrw' : sortKey
      const av = a[key] as number
      const bv = b[key] as number
      return sortDir === 'desc' ? bv - av : av - bv
    })

  function toggleSort(key: SortKey) {
    if (sortKey === key) setSortDir((d) => (d === 'desc' ? 'asc' : 'desc'))
    else { setSortKey(key); setSortDir('desc') }
  }

  const sortIcon = (key: SortKey) => sortKey === key ? (sortDir === 'desc' ? ' ↓' : ' ↑') : ''

  const totalPnlClass = toneText[dirTone(Number(data.totalUnrealizedPnl))]
  const totalRetClass = toneText[dirTone(Number(data.totalReturnPct))]

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
        title="포지션 & 손익"
        meta={`B-05 · 스냅샷 기반 자동 산출 · 생성 ${new Date(data.generatedAt).toLocaleString('ko-KR')}`}
      />

      <div className="px-5 py-5 pb-10 sm:px-7">
        {/* Summary KPIs */}
        <div className="grid grid-cols-2 gap-px border border-line-soft bg-line-soft lg:grid-cols-4">
          <KpiCard label="총 현재 가치" value={fmt(Number(data.totalCurrentValue))} />
          <KpiCard label="총 매입 원가" value={fmt(Number(data.totalPurchaseCost))} />
          <KpiCard
            label="미실현 총 손익"
            value={`${Number(data.totalUnrealizedPnl) >= 0 ? '+' : ''}${fmt(Number(data.totalUnrealizedPnl))}`}
            valueClass={totalPnlClass}
          />
          <KpiCard
            label="전체 수익률"
            value={`${Number(data.totalReturnPct) >= 0 ? '+' : ''}${Number(data.totalReturnPct).toFixed(2)}%`}
            valueClass={totalRetClass}
          />
        </div>

        {/* Filters */}
        <div className="mt-6 flex flex-wrap gap-2">
          {types.map((t) => (
            <button
              key={t}
              onClick={() => setFilterType(t)}
              className={`border px-3.5 py-1.5 font-mono text-[10px] tracking-label transition-colors ${
                filterType === t
                  ? 'border-ink bg-ink text-white'
                  : 'border-line bg-surface text-fg-3 hover:border-ink hover:text-ink'
              }`}
            >
              {t === 'ALL' ? '전체' : (TYPE_KO[t] ?? t)}
            </button>
          ))}
        </div>

        {/* Position Table */}
        <div className="mt-4 overflow-x-auto">
          <div className="min-w-[980px] border-t-[1.5px] border-ink">
            <div className={`${TABLE_GRID} border-b border-line py-2`}>
              <Label size="sm" tone="faint">자산명</Label>
              <Label size="sm" tone="faint">계좌</Label>
              <Label size="sm" tone="faint">유형</Label>
              <Label size="sm" tone="faint" className="text-right">수량</Label>
              <Label size="sm" tone="faint" className="text-right">평균 매입가</Label>
              <button
                type="button"
                onClick={() => toggleSort('purchaseCost')}
                className="text-right font-mono text-[9px] uppercase tracking-label text-fg-faint transition-colors hover:text-ink"
              >
                매입 원가{sortIcon('purchaseCost')}
              </button>
              <button
                type="button"
                onClick={() => toggleSort('currentValue')}
                className="text-right font-mono text-[9px] uppercase tracking-label text-fg-faint transition-colors hover:text-ink"
              >
                현재 가치{sortIcon('currentValue')}
              </button>
              <button
                type="button"
                onClick={() => toggleSort('unrealizedPnl')}
                className="text-right font-mono text-[9px] uppercase tracking-label text-fg-faint transition-colors hover:text-ink"
              >
                미실현 손익{sortIcon('unrealizedPnl')}
              </button>
              <button
                type="button"
                onClick={() => toggleSort('unrealizedPnlPct')}
                className="text-right font-mono text-[9px] uppercase tracking-label text-fg-faint transition-colors hover:text-ink"
              >
                수익률{sortIcon('unrealizedPnlPct')}
              </button>
            </div>

            {filtered.length === 0 && (
              <div className="py-12 text-center text-[13px] text-fg-faint">포지션 없음</div>
            )}
            {filtered.map((p: PositionRow, i) => {
              const pnl = Number(p.unrealizedPnl)
              const ret = Number(p.unrealizedPnlPct)
              const pnlClass = toneText[dirTone(pnl)]
              return (
                <div key={i} className={`${TABLE_GRID} items-baseline border-b border-line-hair py-2.5 hover:bg-surface-muted`}>
                  <span className="min-w-0">
                    <span className="block text-[13.5px]">{p.name}</span>
                    {p.symbol && <span className="block font-mono text-[10.5px] text-fg-faint">{p.symbol}</span>}
                    <span className="mt-0.5 block font-mono text-[9.5px] tracking-label text-fg-ghost">{p.confidenceLevel}</span>
                  </span>
                  <span className="text-[11.5px] text-fg-3">{p.accountName}</span>
                  <span className="text-[12px] text-fg-3">{TYPE_KO[p.type] ?? p.type}</span>
                  <Num className="text-right text-[12px] text-fg-2">
                    {Number(p.quantity).toLocaleString('ko-KR', { maximumFractionDigits: 8 })}
                  </Num>
                  <Num className="text-right text-[12px] text-fg-3">
                    {fmt(Number(p.avgCost), p.currency)}
                  </Num>
                  <Num className="text-right text-[12.5px] text-fg-2">
                    {fmt(Number(p.purchaseCost), p.currency)}
                  </Num>
                  <span className="text-right">
                    {/* 표시 통화는 KRW로 통일, 원통화 값은 보조 표기 (QA P2) */}
                    <Num className="text-[12.5px]">{fmt(Number(p.currentValueKrw))}</Num>
                    {p.currency !== 'KRW' && (
                      <Num className="block text-[10.5px] text-fg-faint">{fmt(Number(p.currentValue), p.currency)}</Num>
                    )}
                  </span>
                  <Num className={`text-right text-[12.5px] ${pnlClass}`}>
                    {pnl >= 0 ? '+' : ''}{fmt(pnl, p.currency)}
                  </Num>
                  <Num className={`text-right text-[12.5px] font-medium ${pnlClass}`}>
                    {ret >= 0 ? '+' : ''}{ret.toFixed(2)}%
                  </Num>
                </div>
              )
            })}
          </div>
        </div>

        <p className="mt-4 text-[11.5px] leading-relaxed text-fg-faint">
          * 평균 매입가는 입력된 매입가 기준이며, FIFO 실현 손익은 거래 이력이 쌓이면 자동 계산됩니다.
        </p>
      </div>
    </div>
  )
}

function KpiCard({ label, value, valueClass }: { label: string; value: string; valueClass?: string }) {
  return (
    <div className="bg-surface px-3.5 py-3">
      <Label size="sm" tone="faint">{label}</Label>
      <Num className={`mt-1 block text-[15px] ${valueClass ?? ''}`}>{value}</Num>
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
