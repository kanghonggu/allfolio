'use client'

import { useQuery } from '@tanstack/react-query'
import { getLatestSnapshot, getPositions } from '@/lib/api'
import { queryKeys } from '@/lib/queryClient'
import PortfolioSummary from '@/components/PortfolioSummary'
import PositionList from '@/components/PositionList'
import AssetAllocationChart from '@/components/AssetAllocationChart'
import PageHeader from '@/components/ui/PageHeader'
import SectionHeader from '@/components/ui/SectionHeader'
import { ErrorState, LoadingState } from '@/components/ui/states'

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
    <div className="border border-line-card bg-surface">
      <PageHeader
        className="px-5 pt-5 sm:px-7"
        title="포트폴리오 상세"
        meta={portfolioId}
      />

      <div className="px-5 py-5 pb-10 sm:px-7">
        {/* 스냅샷 요약 */}
        <section>
          <SectionHeader label="성과 요약" />
          {snapshotQuery.isLoading && <LoadingState label="스냅샷 불러오는 중" />}
          {snapshotQuery.isError && (
            <ErrorState message={(snapshotQuery.error as Error).message} />
          )}
          {snapshotQuery.data && <PortfolioSummary snapshot={snapshotQuery.data} />}
        </section>

        {/* 자산 비중 차트 */}
        {positionsQuery.data && positionsQuery.data.length > 0 && (
          <section className="mt-8">
            <SectionHeader label="자산 비중" />
            <AssetAllocationChart positions={positionsQuery.data} />
          </section>
        )}

        {/* 포지션 목록 */}
        <section className="mt-8">
          <SectionHeader
            label="현재 포지션"
            note={positionsQuery.data ? `총 ${positionsQuery.data.length}종목 · Redis 포지션 캐시 기준` : undefined}
          />
          {positionsQuery.isLoading && <LoadingState label="포지션 불러오는 중" />}
          {positionsQuery.isError && (
            <ErrorState message={(positionsQuery.error as Error).message} />
          )}
          {positionsQuery.data && <PositionList positions={positionsQuery.data} />}
        </section>
      </div>
    </div>
  )
}
