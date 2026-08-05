'use client'

import { useQuery } from '@tanstack/react-query'
import { getTrades } from '@/lib/api'
import { queryKeys } from '@/lib/queryClient'
import TradeTable from '@/components/TradeTable'
import PageHeader from '@/components/ui/PageHeader'
import { ErrorState, LoadingState } from '@/components/ui/states'

const PORTFOLIO_ID = process.env.NEXT_PUBLIC_DEFAULT_PORTFOLIO_ID ?? ''

export default function TradesPage() {
  const { data, isLoading, isError, error } = useQuery({
    queryKey: queryKeys.trades(PORTFOLIO_ID),
    queryFn:  () => getTrades(PORTFOLIO_ID),
    enabled:  Boolean(PORTFOLIO_ID),
  })

  return (
    <div className="border border-line-card bg-surface">
      <PageHeader
        className="px-5 pt-5 sm:px-7"
        title="거래 내역"
        meta={data ? `최근 ${data.length}건` : undefined}
      />

      <div className="px-5 py-5 pb-10 sm:px-7">
        {isLoading && <LoadingState label="거래 내역 불러오는 중" />}

        {isError && <ErrorState message={`API 오류: ${(error as Error).message}`} />}

        {data && <TradeTable trades={data} />}
      </div>
    </div>
  )
}
