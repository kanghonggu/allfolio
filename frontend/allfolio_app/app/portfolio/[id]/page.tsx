'use client'

import { useQuery } from '@tanstack/react-query'
import { getLatestSnapshot, getPositions } from '@/lib/api'
import { queryKeys } from '@/lib/queryClient'
import PortfolioSummary from '@/components/PortfolioSummary'
import PositionList from '@/components/PositionList'
import AssetAllocationChart from '@/components/AssetAllocationChart'

const TENANT_ID = process.env.NEXT_PUBLIC_DEFAULT_TENANT_ID ?? ''

type Props = { params: { id: string } }

export default function PortfolioDetailPage({ params }: Props) {
  const portfolioId = params.id

  const snapshotQuery = useQuery({
    queryKey: queryKeys.snapshot(portfolioId, TENANT_ID),
    queryFn:  () => getLatestSnapshot(portfolioId, TENANT_ID),
    enabled:  Boolean(portfolioId && TENANT_ID),
  })

  const positionsQuery = useQuery({
    queryKey: queryKeys.positions(portfolioId),
    queryFn:  () => getPositions(portfolioId),
    enabled:  Boolean(portfolioId),
  })

  return (
    <div className="space-y-8">
      <header>
        <h1 className="text-2xl font-bold">포트폴리오 상세</h1>
        <p className="mt-1 font-mono text-sm text-gray-500">{portfolioId}</p>
      </header>

      {/* 스냅샷 요약 */}
      <section>
        <h2 className="mb-4 text-lg font-semibold">성과 요약</h2>
        {snapshotQuery.isLoading && <LoadingRow />}
        {snapshotQuery.isError && (
          <ErrorBox message={(snapshotQuery.error as Error).message} />
        )}
        {snapshotQuery.data && <PortfolioSummary snapshot={snapshotQuery.data} />}
      </section>

      {/* 자산 비중 차트 */}
      {positionsQuery.data && positionsQuery.data.length > 0 && (
        <section>
          <h2 className="mb-4 text-lg font-semibold">자산 비중</h2>
          <AssetAllocationChart positions={positionsQuery.data} />
        </section>
      )}

      {/* 포지션 목록 */}
      <section>
        <h2 className="mb-4 text-lg font-semibold">현재 포지션</h2>
        {positionsQuery.isLoading && <LoadingRow />}
        {positionsQuery.isError && (
          <ErrorBox message={(positionsQuery.error as Error).message} />
        )}
        {positionsQuery.data && (
          <div className="rounded-lg border border-gray-700 bg-gray-900 p-4">
            <p className="mb-3 text-xs text-gray-500">
              총 {positionsQuery.data.length}종목 (Redis 포지션 캐시 기준)
            </p>
            <PositionList positions={positionsQuery.data} />
          </div>
        )}
      </section>
    </div>
  )
}

function LoadingRow() {
  return (
    <div className="h-16 animate-pulse rounded-lg border border-gray-700 bg-gray-800" />
  )
}

function ErrorBox({ message }: { message: string }) {
  return (
    <div className="rounded-lg border border-red-800 bg-red-950 p-4 text-sm text-red-400">
      {message}
    </div>
  )
}
