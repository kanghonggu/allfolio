// app/unified/reports/cost-report/[id]/page.tsx
'use client'

import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useParams } from 'next/navigation'
import { useReportArchiveApi } from '@/lib/useApi'
import { parseReportBody, COST } from '@/lib/report-archive-api'
import type { CostReportBody } from '@/types/cost-report'
import Badge from '@/components/ui/Badge'
import Button from '@/components/ui/Button'
import Label from '@/components/ui/Label'
import { ErrorState, LoadingState } from '@/components/ui/states'
import { CostSummary } from '@/components/cost-report/CostSummary'
import { CostInsights } from '@/components/cost-report/CostInsights'
import { ByTypeTable } from '@/components/cost-report/ByTypeTable'
import { ByBrokerMatrix } from '@/components/cost-report/ByBrokerMatrix'
import { MonthlyCostTrend } from '@/components/cost-report/MonthlyCostTrend'
import { CostDetailsTable } from '@/components/cost-report/CostDetailsTable'

export default function CostReportDetailPage() {
  const { id } = useParams<{ id: string }>()
  const api = useReportArchiveApi(COST)
  const { data, isLoading, isError } = useQuery({
    queryKey: ['cost-report', id],
    queryFn: async () => {
      const detail = await api!.detail(id)
      return { meta: detail.meta, body: parseReportBody<CostReportBody>(detail.body) }
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
            href="/unified/reports/cost-report"
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
          href="/unified/reports/cost-report"
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
            <Label size="sm" tone="ghost">REPORT R-04 · 비용 보고서</Label>
            {meta.status === 'WARNING'
              ? <Badge variant="warn">잠정/경고</Badge>
              : <Badge variant="ok">확정</Badge>}
          </div>
          <h1 className="m-0 mt-2 font-serif text-[22px] font-medium tracking-[-0.01em]">
            {y}년 {Number(m)}월 비용 보고서
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
          <CostSummary summary={body.summary} />
          {body.insights && body.insights.length > 0 && <CostInsights items={body.insights} />}
          <ByTypeTable rows={body.byType} />
          <ByBrokerMatrix rows={body.byBroker} />
          <MonthlyCostTrend rows={body.monthly} />
          <CostDetailsTable rows={body.details} />

          <p className="m-0 text-[11.5px] leading-[1.8] text-fg-faint">
            ※ 매매수수료는 손익 계산에 이미 반영되어 있습니다 — 본 보고서는 비용 가시화용이며 수익률을 다시 차감하지 않습니다.
          </p>
        </div>
      </div>
    </div>
  )
}
