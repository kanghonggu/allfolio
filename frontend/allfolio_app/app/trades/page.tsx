'use client'

import { useQuery } from '@tanstack/react-query'
import { getTrades } from '@/lib/api'
import { queryKeys } from '@/lib/queryClient'
import TradeTable from '@/components/TradeTable'

const PORTFOLIO_ID = process.env.NEXT_PUBLIC_DEFAULT_PORTFOLIO_ID ?? ''

export default function TradesPage() {
  const { data, isLoading, isError, error } = useQuery({
    queryKey: queryKeys.trades(PORTFOLIO_ID),
    queryFn:  () => getTrades(PORTFOLIO_ID),
    enabled:  Boolean(PORTFOLIO_ID),
  })

  return (
    <div className="space-y-6">
      <header className="flex items-baseline justify-between">
        <h1 className="text-2xl font-bold">거래 내역</h1>
        {data && (
          <span className="text-sm text-gray-400">
            최근 {data.length}건
          </span>
        )}
      </header>

      {isLoading && (
        <div className="space-y-2">
          {Array.from({ length: 8 }).map((_, i) => (
            <div
              key={i}
              className="h-10 animate-pulse rounded bg-gray-800"
              style={{ opacity: 1 - i * 0.1 }}
            />
          ))}
        </div>
      )}

      {isError && (
        <div className="rounded-lg border border-red-800 bg-red-950 p-4 text-sm text-red-400">
          API 오류: {(error as Error).message}
        </div>
      )}

      {data && (
        <div className="rounded-lg border border-gray-700 bg-gray-900 p-4">
          <TradeTable trades={data} />
        </div>
      )}
    </div>
  )
}
