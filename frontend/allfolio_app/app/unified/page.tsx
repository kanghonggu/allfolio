'use client'

import { useEffect, useRef } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import Link from 'next/link'
import { useUnifiedApi } from '@/lib/useApi'
import { useSyncStatus } from '@/lib/useSyncStatus'
import { useLivePrices } from '@/lib/useLivePrices'
import NetWorthBar from '@/components/dashboard/NetWorthBar'
import MetricTable from '@/components/dashboard/MetricTable'
import PositionTable from '@/components/dashboard/PositionTable'
import AllocationBar from '@/components/dashboard/AllocationBar'
import PageHeader from '@/components/ui/PageHeader'
import SectionHeader from '@/components/ui/SectionHeader'
import Badge from '@/components/ui/Badge'
import Num from '@/components/ui/Num'
import DataTable, { type Column } from '@/components/ui/DataTable'
import { EmptyState, ErrorState } from '@/components/ui/states'
import { won } from '@/lib/format'
import type { DashboardResponse, RealAsset } from '@/types/dashboard'

// QA: 가장 최근 계좌 동기화 시각을 'N일 전'으로 표기 (실시간 가격배지와 구분)
function lastSyncLabel(iso: string | null): { text: string; stale: boolean } | null {
  if (!iso) return null
  const days = Math.floor((Date.now() - new Date(iso).getTime()) / 86_400_000)
  const stale = days >= 2
  const text = days <= 0 ? '오늘' : `${days}일 전`
  return { text, stale }
}

export default function UnifiedDashboard() {
  const api = useUnifiedApi()
  const qc  = useQueryClient()
  const { connected: liveConnected } = useLivePrices()

  const { data, isLoading, isError, error, refetch } = useQuery<DashboardResponse>({
    queryKey: ['dashboard'],
    queryFn:  () => api!.dashboard.get(),
    enabled:  !!api,
    staleTime: 60_000,
  })

  // AF-90: 거래 저장·계좌 등록이 백엔드 자동 동기화를 건다. 도는 동안 "반영 중"을 표시하고,
  // 끝나는 순간 대시보드를 다시 불러와 사용자가 새로고침하지 않아도 숫자가 잡히게 한다.
  const { statuses: syncStatus, syncing } = useSyncStatus()
  const wasSyncing = useRef(false)
  useEffect(() => {
    if (wasSyncing.current && !syncing) {
      qc.invalidateQueries({ queryKey: ['dashboard'], exact: true })
    }
    wasSyncing.current = syncing
  }, [syncing, qc])

  const lastSync = lastSyncLabel(
    (syncStatus ?? [])
      .map((s) => s.lastSyncedAt)
      .filter((d): d is string => !!d)
      .sort()
      .at(-1) ?? null,
  )

  if (isLoading || !api) return <PageSkeleton />
  if (isError) {
    return (
      <div className="border border-line-card bg-surface px-7">
        <ErrorState message={(error as Error).message} onRetry={() => refetch()} />
      </div>
    )
  }
  if (!data) return null

  const { netWorth, portfolio, realAssets } = data
  const hasMetrics = Object.values(portfolio.metrics).some(Boolean)
  const accountCount = (syncStatus ?? []).length

  return (
    <div className="border border-line-card bg-surface">
      <PageHeader
        className="px-5 pt-5 sm:px-7"
        title="통합 자산 · 원장 요약"
        meta={
          <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
            {accountCount > 0 && <span>계좌 {accountCount}</span>}
            <span>포지션 {portfolio.positions.length}</span>
            {/* AF-90: 자동 동기화 진행 중 — 끝나면 숫자가 저절로 갱신된다 */}
            {syncing && (
              <span className="text-warn" title="거래·계좌 변경을 포지션에 반영하는 중입니다">
                반영 중 …
              </span>
            )}
            {lastSync && (
              <span className={lastSync.stale ? 'text-warn' : undefined} title="계좌 마지막 동기화 시각">
                최종 동기화 {lastSync.text}
              </span>
            )}
            {/* 실시간 = 시세 스트림 연결 상태 (계좌 동기화 시각과 별개) */}
            <span className="flex items-center gap-1.5" title="실시간 시세 스트림 연결 상태">
              <span className={`block h-[5px] w-[5px] ${liveConnected ? 'bg-ok' : 'bg-fg-ghost'}`} />
              {liveConnected ? '시세 연결됨' : '시세 연결 중'}
            </span>
          </span>
        }
        actions={
          <>
            <Link
              href="/unified/recon"
              className="border border-line bg-surface px-3.5 py-2 text-[12.5px] text-fg-2 transition-colors hover:border-ink hover:text-ink"
            >
              대사 내역
            </Link>
            <Link
              href="/unified/accounts"
              className="border border-ink bg-ink px-3.5 py-2 text-[12.5px] text-white transition-colors hover:bg-fg-2"
            >
              계좌 관리
            </Link>
          </>
        }
      />

      {/* 순자산 요약 밴드 */}
      <NetWorthBar
        total={netWorth.total}
        liquid={netWorth.liquid}
        illiquid={netWorth.illiquid}
        debt={netWorth.debt}
        change30d={netWorth.change30d}
        changeRate30d={netWorth.changeRate30d}
      />

      {/* 수익률·리스크 + 자산군 배분 */}
      <div className="grid grid-cols-1 border-b border-line lg:grid-cols-2">
        <section className="border-b border-line px-5 py-5 sm:px-7 lg:border-b-0 lg:border-r">
          <SectionHeader label="수익률 · 리스크 지표" note="코스피 대비" />
          {hasMetrics ? (
            <MetricTable metrics={portfolio.metrics} />
          ) : (
            <EmptyState
              title="수익률 지표 없음"
              description="자산을 sync하면 수익률 지표가 표시됩니다"
            />
          )}
        </section>

        <section className="px-5 py-5 sm:px-7">
          <SectionHeader label="자산군 배분 · 집중도" />
          {portfolio.allocation.length > 0 ? (
            <AllocationBar allocation={portfolio.allocation} />
          ) : (
            <EmptyState title="배분 데이터 없음" description="자산을 sync하면 자산군 배분이 표시됩니다" />
          )}
        </section>
      </div>

      {/* 실물·고정 자산 */}
      {realAssets.length > 0 && (
        <section className="border-b border-line px-5 py-5 sm:px-7">
          <SectionHeader label="실물·고정 자산" />
          <RealAssetTable assets={realAssets} />
        </section>
      )}

      {/* 포지션 명세 */}
      <section className="px-5 py-5 pb-10 sm:px-7">
        <PositionTable positions={portfolio.positions} />
      </section>
    </div>
  )
}

function RealAssetTable({ assets }: { assets: RealAsset[] }) {
  const columns: Column<RealAsset>[] = [
    {
      key: 'name',
      header: '자산',
      width: '1.8fr',
      cell: (a) => <span className="text-[13.5px]">{a.name}</span>,
    },
    {
      key: 'type',
      header: '구분',
      width: '1fr',
      cell: (a) => <span className="text-[12.5px] text-fg-3">{a.type}</span>,
    },
    {
      key: 'value',
      header: '평가액',
      width: '1.2fr',
      align: 'right',
      cell: (a) => <Num className="text-[12.5px]">{won(a.value)}</Num>,
    },
    {
      key: 'due',
      header: '만기',
      width: '1fr',
      align: 'right',
      cell: (a) => {
        const days = a.daysUntilMaturity
        if (days == null) return <span className="text-xs text-fg-faint">—</span>
        const urgent = days <= 7
        const warn = days <= 30 && !urgent
        return (
          <span className="inline-flex items-baseline gap-2">
            {urgent && <Badge variant="danger">만기 임박</Badge>}
            <Num className={`text-xs ${urgent ? 'text-danger' : warn ? 'text-warn' : 'text-fg-3'}`}>
              D-{days}
            </Num>
          </span>
        )
      },
    },
  ]
  return <DataTable columns={columns} rows={assets} rowKey={(a) => a.id} minWidth={520} />
}

function PageSkeleton() {
  return (
    <div className="border border-line-card bg-surface px-5 py-5 sm:px-7" role="status" aria-label="불러오는 중">
      <div className="h-7 w-56 animate-pulse bg-line-soft" />
      <div className="mt-6 h-28 animate-pulse bg-line-hair" />
      <div className="mt-6 grid gap-4 sm:grid-cols-2">
        <div className="h-44 animate-pulse bg-line-hair" />
        <div className="h-44 animate-pulse bg-line-hair" />
      </div>
      <div className="mt-6 h-56 animate-pulse bg-line-hair" />
    </div>
  )
}
