'use client'

import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
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
import Button from '@/components/ui/Button'
import WelcomeModal from '@/components/onboarding/WelcomeModal'
import StartChecklist from '@/components/onboarding/StartChecklist'
import Badge from '@/components/ui/Badge'
import Num from '@/components/ui/Num'
import DataTable, { type Column } from '@/components/ui/DataTable'
import { EmptyState, ErrorState } from '@/components/ui/states'
import { won } from '@/lib/format'
import type { DashboardResponse, RealAsset } from '@/types/dashboard'

/** 온보딩 모달을 이미 닫았는지 — 계정이 아니라 브라우저 단위로 기억한다 (AF-92) */
const WELCOME_DISMISSED_KEY = 'allfolio_welcome_dismissed'

/**
 * AF-91: 대시보드가 비어 있는 이유는 세 가지로 갈리고, 사용자가 할 수 있는 일도 다르다.
 * 하나로 뭉뚱그린 예전 문구는 이미 동기화를 마친 사용자에게 거짓 안내가 됐다.
 */
function describeEmptyState(s: {
  accountCount: number
  syncableCount: number
  hasSyncHistory: boolean
  syncing: boolean
}): { title: string; description: string; cta: 'connect' | 'sync' | 'accounts' | null } {
  if (s.syncing) {
    return {
      title: '자산을 불러오는 중입니다',
      description: '동기화가 끝나면 이 화면에 자동으로 반영됩니다',
      cta: null,
    }
  }
  if (s.accountCount === 0) {
    return {
      title: '자산을 등록해 주세요',
      description: '증권사·거래소를 연결하거나 자산을 직접 등록하면 여기에 집계됩니다',
      cta: 'connect',
    }
  }
  // 수동·CSV 계좌만 있으면 눌러도 불러올 외부 소스가 없다 — 동기화 버튼을 내밀지 않는다
  if (!s.hasSyncHistory && s.syncableCount > 0) {
    return {
      title: '아직 동기화되지 않았습니다',
      description: '연결한 계좌의 자산을 아직 한 번도 불러오지 않았습니다',
      cta: 'sync',
    }
  }
  return {
    title: '집계된 자산이 없습니다',
    description: '동기화했지만 조회된 자산이 없습니다. 증권 계좌는 거래를 입력하면 포지션이 만들어집니다',
    cta: 'accounts',
  }
}

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
  // AF-91: 계좌는 있는데 동기화 이력이 없는 상태에서 사용자가 직접 시작할 수 있게 한다
  const syncNow = useMutation({
    mutationFn: async () => {
      const targets = syncStatus.filter((s) => s.syncable)
      for (const t of targets) await api!.accounts.sync(t.accountId)
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['dashboard'] }),
  })

  // AF-92: 계좌가 없는 사용자에게 첫 등록 경로를 묻는다. "나중에"는 기억해 다시 묻지 않는다
  const [welcomeDismissed, setWelcomeDismissed] = useState(true)
  useEffect(() => {
    setWelcomeDismissed(localStorage.getItem(WELCOME_DISMISSED_KEY) === '1')
  }, [])
  const dismissWelcome = () => {
    localStorage.setItem(WELCOME_DISMISSED_KEY, '1')
    setWelcomeDismissed(true)
  }

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

  const { netWorth, portfolio, realAssets, fxSources } = data
  const hasMetrics = Object.values(portfolio.metrics).some(Boolean)
  const accountCount = syncStatus.length

  // AF-91: 빈 상태를 원인별로 나눈다. 예전 문구("자산을 sync하면 표시됩니다")는 이미
  // 동기화를 끝낸 사용자에게 거짓말이 됐다 — 지표가 없는 진짜 이유는 스냅샷 부족이다.
  const emptyState = describeEmptyState({
    accountCount,
    syncableCount: syncStatus.filter((s) => s.syncable).length,
    hasSyncHistory: syncStatus.some((s) => s.lastSyncedAt !== null),
    syncing,
  })

  const emptyAction = emptyState.cta === 'connect' ? (
    <Link
      href="/unified/accounts/new"
      className="border border-ink bg-ink px-4 py-2 text-sm text-white transition-colors hover:bg-fg-2"
    >
      계좌 연결하기
    </Link>
  ) : emptyState.cta === 'sync' ? (
    <Button variant="primary" size="sm" disabled={syncNow.isPending} onClick={() => syncNow.mutate()}>
      {syncNow.isPending ? '동기화 중 …' : '지금 동기화'}
    </Button>
  ) : emptyState.cta === 'accounts' ? (
    <Link
      href="/unified/accounts"
      className="border border-ink px-4 py-2 text-sm text-ink transition-colors hover:bg-surface-muted"
    >
      계좌 관리로 이동
    </Link>
  ) : undefined

  // 포지션은 있는데 지표만 비어 있으면 원인은 자산이 아니라 스냅샷 축적 기간이다
  //
  // **"내일부터"·"매일 자정"을 다시 쓰지 말 것.** 자정 적재는 마감 워크플로우(S030)가 하는데
  // 그 트리거는 운영에서 성립한 적이 없다 — 무료 인스턴스가 자정에 잠들어 있고, 깨어 있던 날은
  // wf_ 테이블이 없어 첫 쿼리에서 죽었다(2026-08-14 확인). 사용자에게 "내일"을 약속하면
  // 영원히 오지 않는 내일이 된다. 지금 실제로 스냅샷을 남기는 경로는 동기화 하나뿐이라
  // (SyncAccountUseCase → PerformanceSnapshotService UPSERT) 그것만 말한다.
  //
  // PR #168이 트리거를 고치는 중이다(외부 GitHub Actions 크론 + wf_ 마이그레이션). 그게 머지·적용되고
  // performance_daily에 이틀 연속 행이 쌓이는 것을 **운영에서 눈으로 확인한 뒤에** 문구를 되돌릴 것.
  // 머지됐다는 사실만으로 되돌리지 말 것 — 마이그레이션은 코드가 아니라 운영자 액션이라 따로 논다.
  const metricsEmpty = portfolio.positions.length > 0
    ? {
        title: '스냅샷이 2건 쌓이면 수익률이 표시됩니다',
        description:
          '일별 NAV 스냅샷이 2건 이상 쌓여야 수익률을 계산할 수 있습니다. ' +
          '동기화할 때마다 그날의 스냅샷이 기록됩니다.',
        action: undefined as React.ReactNode,
      }
    : { title: emptyState.title, description: emptyState.description, action: emptyAction }

  const checklist = {
    accountRegistered: accountCount > 0,
    assetEntered:      portfolio.positions.length > 0 || realAssets.length > 0,
    synced:            syncStatus.some((s) => s.lastSyncedAt !== null),
  }

  return (
    <div className="border border-line-card bg-surface">
      {accountCount === 0 && !welcomeDismissed && <WelcomeModal onDismiss={dismissWelcome} />}
      <StartChecklist state={checklist} />
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
        netFlow30d={netWorth.netFlow30d}
        fxSources={fxSources}
      />

      {/* 수익률·리스크 + 자산군 배분 */}
      <div className="grid grid-cols-1 border-b border-line lg:grid-cols-2">
        <section className="border-b border-line px-5 py-5 sm:px-7 lg:border-b-0 lg:border-r">
          <SectionHeader label="수익률 · 리스크 지표" note="코스피 대비" />
          {hasMetrics ? (
            <MetricTable metrics={portfolio.metrics} />
          ) : (
            <EmptyState {...metricsEmpty} />
          )}
        </section>

        <section className="px-5 py-5 sm:px-7">
          <SectionHeader label="자산군 배분 · 집중도" />
          {portfolio.allocation.length > 0 ? (
            <AllocationBar allocation={portfolio.allocation} />
          ) : (
            <EmptyState title={emptyState.title} description={emptyState.description} />
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
        <PositionTable
          positions={portfolio.positions}
          empty={
            <EmptyState
              title={emptyState.title}
              description={emptyState.description}
              action={emptyAction}
            />
          }
        />
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
