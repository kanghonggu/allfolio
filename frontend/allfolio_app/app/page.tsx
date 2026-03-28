'use client'

import { useQuery } from '@tanstack/react-query'
import { getLatestSnapshot, getPositions } from '@/lib/api'
import { queryKeys } from '@/lib/queryClient'
import PortfolioSummary from '@/components/PortfolioSummary'
import AssetAllocationChart from '@/components/AssetAllocationChart'

const PORTFOLIO_ID = process.env.NEXT_PUBLIC_DEFAULT_PORTFOLIO_ID ?? ''
const TENANT_ID    = process.env.NEXT_PUBLIC_DEFAULT_TENANT_ID    ?? ''

export default function DashboardPage() {
  const { data, isLoading, isError, error } = useQuery({
    queryKey: queryKeys.snapshot(PORTFOLIO_ID, TENANT_ID),
    queryFn:  () => getLatestSnapshot(PORTFOLIO_ID, TENANT_ID),
    enabled:  Boolean(PORTFOLIO_ID && TENANT_ID),
  })

  const positionsQuery = useQuery({
    queryKey: queryKeys.positions(PORTFOLIO_ID),
    queryFn:  () => getPositions(PORTFOLIO_ID),
    enabled:  Boolean(PORTFOLIO_ID),
  })

  return (
    <div className="space-y-6">
      <header>
        <h1 className="text-2xl font-bold">포트폴리오 대시보드</h1>
        <p className="mt-1 text-sm text-gray-500">
          포트폴리오 ID: <code className="font-mono text-gray-400">{PORTFOLIO_ID}</code>
        </p>
      </header>

      {isLoading && <Skeleton />}

      {isError && (
        <ErrorBox message={(error as Error).message} />
      )}

      {data && <PortfolioSummary snapshot={data} />}

      {positionsQuery.data && positionsQuery.data.length > 0 && (
        <AssetAllocationChart positions={positionsQuery.data} />
      )}
    </div>
  )
}

function Skeleton() {
  return (
    <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
      {Array.from({ length: 4 }).map((_, i) => (
        <div
          key={i}
          className="h-24 animate-pulse rounded-lg border border-gray-700 bg-gray-800"
        />
      ))}
    </div>
  )
}

function ErrorBox({ message }: { message: string }) {
  return (
    <div className="rounded-lg border border-red-800 bg-red-950 p-4 text-sm text-red-400">
      API 오류: {message}
    </div>
  )
}
