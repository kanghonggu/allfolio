'use client'

import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useUnifiedApi } from '@/lib/useApi'
import { useLivePrices } from '@/lib/useLivePrices'
import NetWorthBar from '@/components/dashboard/NetWorthBar'
import MetricCard, { EmptyMetricCard } from '@/components/dashboard/MetricCard'
import PositionTable from '@/components/dashboard/PositionTable'
import AllocationBar from '@/components/dashboard/AllocationBar'
import type { DashboardResponse } from '@/types/dashboard'

const MDD_DESC = (v: number) =>
  `최근 1년 중 가장 크게 떨어졌을 때 ${v.toFixed(1)}%였어요. 낮을수록 손실 관리가 잘 된 포트폴리오예요.`

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
  const { connected: liveConnected } = useLivePrices()

  const { data, isLoading, isError, error } = useQuery<DashboardResponse>({
    queryKey: ['dashboard'],
    queryFn:  () => api!.dashboard.get(),
    enabled:  !!api,
    staleTime: 60_000,
  })

  const { data: syncStatus } = useQuery({
    queryKey: ['dashboard', 'sync-status'],
    queryFn:  () => api!.accounts.syncStatus(),
    enabled:  !!api,
    staleTime: 60_000,
  })
  const lastSync = lastSyncLabel(
    (syncStatus ?? [])
      .map((s) => s.lastSyncedAt)
      .filter((d): d is string => !!d)
      .sort()
      .at(-1) ?? null,
  )

  if (isLoading || !api) return <PageSkeleton />
  if (isError)   return <ErrorBox message={(error as Error).message} />
  if (!data)     return null

  const { netWorth, portfolio, realAssets } = data
  const hasMetrics = Object.values(portfolio.metrics).some(Boolean)

  return (
    <div className="space-y-8">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">통합 자산 대시보드</h1>
          <p className="mt-1 text-sm text-gray-400">모든 자산을 한눈에</p>
        </div>
        <div className="flex items-center gap-3">
          {/* 실시간 = 시세 스트림 연결 상태 (계좌 동기화 시각과 별개) */}
          <span className="flex items-center gap-1.5 text-xs text-gray-500" title="실시간 시세 스트림 연결 상태">
            <span className={`h-2 w-2 rounded-full ${liveConnected ? 'bg-emerald-400 animate-pulse' : 'bg-gray-600'}`} />
            {liveConnected ? '시세 실시간' : '시세 연결 중'}
          </span>
          {lastSync && (
            <span className={`text-xs ${lastSync.stale ? 'text-amber-500' : 'text-gray-500'}`}
              title="계좌 마지막 동기화 시각">
              마지막 동기화: {lastSync.text}
            </span>
          )}
          <Link
            href="/unified/accounts"
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium hover:bg-blue-500 transition-colors"
          >
            계좌 관리
          </Link>
        </div>
      </div>

      {/* 순자산 바 */}
      <NetWorthBar
        total={netWorth.total}
        liquid={netWorth.liquid}
        illiquid={netWorth.illiquid}
        debt={netWorth.debt}
        change30d={netWorth.change30d}
        changeRate30d={netWorth.changeRate30d}
      />

      {/* 섹션 1: 투자 포트폴리오 */}
      <section>
        <div className="mb-4 flex items-center gap-2">
          <span className="h-4 w-1 rounded-full bg-blue-500" />
          <h2 className="text-sm font-semibold text-gray-300 uppercase tracking-wider">
            투자 포트폴리오
          </h2>
          <span className="text-xs text-gray-600">
            ₩{portfolio.totalValue.toLocaleString('ko-KR')}
          </span>
        </div>

        {/* 지표 카드 */}
        {hasMetrics ? (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4 mb-6">
            {/* null = 커버리지 미달 — 카드를 숨기지 않고 '데이터 부족'으로 명시 (QA 후속 #3) */}
            {portfolio.metrics.returnYtd ? (
              <MetricCard
                label="연간 수익률 (YTD)"
                metric={portfolio.metrics.returnYtd}
                benchmarkLabel="코스피 대비"
              />
            ) : (
              <EmptyMetricCard label="연간 수익률 (YTD)" note="연초부터의 스냅샷이 쌓이면 표시됩니다" />
            )}
            {portfolio.metrics.return1m ? (
              <MetricCard
                label="1개월 수익률"
                metric={portfolio.metrics.return1m}
              />
            ) : (
              <EmptyMetricCard label="1개월 수익률" note="30일 이상 스냅샷이 쌓이면 표시됩니다" />
            )}
            {portfolio.metrics.return3m ? (
              <MetricCard
                label="3개월 수익률"
                metric={portfolio.metrics.return3m}
              />
            ) : (
              <EmptyMetricCard label="3개월 수익률" note="90일 이상 스냅샷이 쌓이면 표시됩니다" />
            )}
            {portfolio.metrics.mdd && (
              <MetricCard
                label="최대 낙폭 (MDD)"
                metric={portfolio.metrics.mdd}
                description={MDD_DESC(portfolio.metrics.mdd.value)}
              />
            )}
          </div>
        ) : (
          <div className="mb-6 rounded-xl border border-gray-800 bg-gray-900/50 py-8 text-center text-sm text-gray-500">
            자산을 sync하면 수익률 지표가 표시됩니다
          </div>
        )}

        {/* 리스크 지표 카드 (Phase 3) */}
        {(portfolio.metrics.sharpe || portfolio.metrics.var95 || portfolio.metrics.volatility) && (
          <div className="mt-4 mb-6">
            <div className="mb-3 flex items-center gap-2">
              <span className="h-3 w-1 rounded-full bg-red-500" />
              <p className="text-xs font-medium uppercase tracking-wider text-gray-500">리스크 분석</p>
            </div>
            <div className="grid gap-4 sm:grid-cols-3">
              {portfolio.metrics.sharpe && (
                <MetricCard
                  label="샤프 지수 (Sharpe)"
                  metric={portfolio.metrics.sharpe}
                  formatValue={(v) => v.toFixed(2)}
                  description="1.0 이상이면 리스크 대비 수익이 양호해요. (무위험수익률 3.5% 기준)"
                />
              )}
              {portfolio.metrics.var95 && (
                <MetricCard
                  label="VaR 95%"
                  metric={portfolio.metrics.var95}
                  formatValue={(v) => `₩${Math.abs(v).toLocaleString('ko-KR')}`}
                  description="최악의 날 (5% 확률) 예상 최대 손실액이에요."
                />
              )}
              {portfolio.metrics.volatility && (
                <MetricCard
                  label="연간 변동성"
                  metric={portfolio.metrics.volatility}
                  description="포트폴리오의 가격 변동 폭이에요. 낮을수록 안정적이에요."
                />
              )}
            </div>
          </div>
        )}

        {portfolio.allocation.length > 0 && (
          <div className="mb-6">
            <AllocationBar allocation={portfolio.allocation} />
          </div>
        )}
        <PositionTable positions={portfolio.positions} />
      </section>

      {/* 섹션 2: 실물·고정 자산 */}
      {realAssets.length > 0 && (
        <section>
          <div className="mb-4 flex items-center gap-2">
            <span className="h-4 w-1 rounded-full bg-yellow-500" />
            <h2 className="text-sm font-semibold text-gray-300 uppercase tracking-wider">
              실물·고정 자산
            </h2>
          </div>
          <div className="space-y-3">
            {realAssets.map((a) => {
              const days = a.daysUntilMaturity
              const urgent = days != null && days <= 7
              const warn   = days != null && days <= 30 && !urgent
              return (
                <div
                  key={a.id}
                  className={`rounded-xl border bg-gray-900 px-5 py-4 flex items-center justify-between
                    ${urgent ? 'border-red-700' : warn ? 'border-yellow-700' : 'border-gray-700'}`}
                >
                  <div>
                    <p className="font-medium text-gray-100">{a.name}</p>
                    <p className="text-xs text-gray-500 mt-0.5">{a.type}</p>
                  </div>
                  <div className="text-right">
                    <p className="font-semibold text-yellow-400 tabular-nums">
                      ₩{a.value.toLocaleString('ko-KR')}
                    </p>
                    {days != null && (
                      <p className={`text-xs mt-0.5 ${urgent ? 'text-red-400 font-semibold' : warn ? 'text-yellow-400' : 'text-gray-500'}`}>
                        만기 D-{days}
                        {urgent && <span className="ml-1 rounded bg-red-900 px-1 py-0.5 text-xs">만기 임박</span>}
                      </p>
                    )}
                  </div>
                </div>
              )
            })}
          </div>
        </section>
      )}
    </div>
  )
}

function PageSkeleton() {
  return (
    <div className="space-y-8">
      <div className="h-8 w-48 animate-pulse rounded bg-gray-800" />
      <div className="h-28 animate-pulse rounded-xl bg-gray-800" />
      <div className="grid gap-4 sm:grid-cols-4">
        {[1,2,3,4].map(i => <div key={i} className="h-36 animate-pulse rounded-xl bg-gray-800" />)}
      </div>
      <div className="h-48 animate-pulse rounded-xl bg-gray-800" />
    </div>
  )
}

function ErrorBox({ message }: { message: string }) {
  return (
    <div className="rounded-xl border border-red-800 bg-red-950 p-6">
      <p className="text-sm font-medium text-red-400">오류 발생</p>
      <p className="mt-1 text-sm text-red-500">{message}</p>
    </div>
  )
}
