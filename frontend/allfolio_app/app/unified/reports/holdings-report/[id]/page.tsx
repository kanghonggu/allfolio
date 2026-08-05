// app/unified/reports/holdings-report/[id]/page.tsx
'use client'

import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useParams } from 'next/navigation'
import { useReportArchiveApi } from '@/lib/useApi'
import { parseReportBody, HOLDINGS } from '@/lib/report-archive-api'
import { fmtKrw } from '@/lib/report-format'
import { dirTone } from '@/lib/format'
import type { HoldingsReportBody } from '@/types/holdings-report'
import Badge from '@/components/ui/Badge'
import Button from '@/components/ui/Button'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import { ErrorState, LoadingState } from '@/components/ui/states'
import { HoldingsSummary } from '@/components/holdings-report/HoldingsSummary'
import { HoldingsGrid } from '@/components/holdings-report/HoldingsGrid'
import { ByAccountTable } from '@/components/holdings-report/ByAccountTable'
import { ByTypeTable } from '@/components/holdings-report/ByTypeTable'
import { RegionExposure } from '@/components/holdings-report/RegionExposure'
import { CashTable } from '@/components/holdings-report/CashTable'
import { MonthlyChange } from '@/components/holdings-report/MonthlyChange'

const REALIZED_GRID = 'grid grid-cols-[1.6fr_1fr] gap-3'

export default function HoldingsReportDetailPage() {
  const { id } = useParams<{ id: string }>()
  const api = useReportArchiveApi(HOLDINGS)
  const { data, isLoading, isError } = useQuery({
    queryKey: ['holdings-report', id],
    queryFn: async () => {
      const detail = await api!.detail(id)
      return { meta: detail.meta, body: parseReportBody<HoldingsReportBody>(detail.body) }
    },
    enabled: !!api && !!id,
    retry: false,
  })

  if (!api || isLoading) {
    return (
      <div className="border border-line-card bg-surface px-5 py-5 sm:px-7">
        <LoadingState label="보고서 불러오는 중" />
      </div>
    )
  }
  if (isError || !data) {
    return (
      <div className="border border-line-card bg-surface px-5 py-5 pb-10 sm:px-7">
        <ErrorState message="보고서를 찾을 수 없습니다." />
        <div className="mt-4 text-center">
          <Link
            href="/unified/reports/holdings-report"
            className="font-mono text-[10px] tracking-label text-fg-faint transition-colors hover:text-ink"
          >
            ← 목록
          </Link>
        </div>
      </div>
    )
  }

  const { meta } = data
  const body = data.body
  const [y, m] = [meta.periodStart.slice(0, 4), meta.periodStart.slice(5, 7)]

  return (
    <div className="border border-line-card bg-surface print-invert">
      <div className="no-print flex items-center justify-between gap-3 px-5 pt-4 sm:px-7">
        <Link
          href="/unified/reports/holdings-report"
          className="font-mono text-[10px] tracking-label text-fg-faint transition-colors hover:text-ink"
        >
          ← 목록
        </Link>
        <Button variant="outline" size="sm" onClick={() => window.print()}>
          인쇄 / PDF
        </Button>
      </div>

      <div className="px-5 py-5 pb-10 sm:px-7">
        <div className="border-b-2 border-ink pb-4">
          <div className="flex flex-wrap items-baseline justify-between gap-2">
            <Label size="sm" tone="ghost">REPORT R-05 · 월말 보유 명세서</Label>
            {meta.status === 'WARNING'
              ? <Badge variant="warn">잠정/경고</Badge>
              : <Badge variant="ok">확정</Badge>}
          </div>
          <h1 className="m-0 mt-2 font-serif text-[22px] font-medium tracking-[-0.01em]">
            {y}년 {Number(m)}월 보유 명세서
          </h1>
          <div className="mt-3 flex flex-wrap gap-x-5 gap-y-1 font-mono text-[10px] tracking-label text-fg-muted">
            <span>기간 {meta.periodStart} ~ {meta.periodEnd}</span>
            <span>기준일 {meta.asOfDate}</span>
            <span>생성 {new Date(meta.createdAt).toLocaleString('ko-KR')}</span>
          </div>
        </div>

        {meta.status === 'WARNING' && meta.warnings.length > 0 && (
          <div className="mt-5 border border-warn-line bg-warn-bg px-4 py-3">
            <Label size="sm" className="text-warn">경고</Label>
            <ul className="m-0 mt-1.5 list-inside list-disc space-y-0.5 p-0 text-[12.5px] text-fg-2">
              {meta.warnings.map((w) => <li key={w.code}>{w.message}</li>)}
            </ul>
          </div>
        )}

        <div className="mt-6 space-y-8">
          <HoldingsSummary summary={body.summary} />
          <HoldingsGrid holdings={body.holdings} />
          {(body.realized ?? []).length > 0 && (
            <section>
              <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2 border-b border-ink pb-2">
                <h2 className="m-0 font-serif text-[16px] font-medium">당월 실현손익</h2>
                <Label size="sm" tone="faint">종목별 · 전량매도 포함</Label>
              </div>
              <div className="overflow-x-auto">
                <div className="min-w-[420px]">
                  <div className={`${REALIZED_GRID} border-b border-line py-2`}>
                    <Label size="sm" tone="faint">종목</Label>
                    <Label size="sm" tone="faint" className="text-right">당월 실현손익</Label>
                  </div>
                  {body.realized.map((r) => (
                    <div key={r.symbol} className={`${REALIZED_GRID} items-baseline border-b border-line-hair py-2.5`}>
                      <span className="text-[13px] text-ink">
                        {r.name}
                        <Num className="ml-2 text-[10.5px] text-fg-faint">{r.symbol}</Num>
                      </span>
                      <span className="text-right">
                        <Num className="text-[12.5px]" tone={dirTone(r.realizedPnl)}>{fmtKrw(r.realizedPnl)}</Num>
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            </section>
          )}
          {body.monthlyChange && <MonthlyChange data={body.monthlyChange} />}
          <div className="grid gap-4 lg:grid-cols-2">
            <ByAccountTable rows={body.byAccount} />
            <ByTypeTable rows={body.byType} />
          </div>
          {body.byRegion && body.byRegion.length > 0 && <RegionExposure rows={body.byRegion} />}
          <CashTable rows={body.cash} />
        </div>
      </div>
    </div>
  )
}
