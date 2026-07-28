// app/unified/reports/cost-report/[id]/page.tsx
'use client'

import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useParams } from 'next/navigation'
import { useReportArchiveApi } from '@/lib/useApi'
import { parseReportBody, COST } from '@/lib/report-archive-api'
import type { CostReportBody } from '@/types/cost-report'
import { CostSummary } from '@/components/cost-report/CostSummary'
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

  if (!api || isLoading) return <div className="h-96 animate-pulse rounded-xl bg-gray-800" />
  if (isError || !data) {
    return (
      <div className="space-y-4">
        <div className="rounded-xl border border-red-800 bg-red-950 p-6 text-sm text-red-400">
          보고서를 찾을 수 없습니다.
        </div>
        <Link href="/unified/reports/cost-report" className="text-sm text-gray-400 hover:text-gray-200">← 목록</Link>
      </div>
    )
  }

  const { meta } = data
  const body = data.body
  const [y, m] = [meta.periodStart.slice(0, 4), meta.periodStart.slice(5, 7)]

  return (
    <div className="space-y-8 print-invert">
      <div className="flex items-center justify-between gap-3 no-print">
        <div className="flex items-center gap-3">
          <Link href="/unified/reports/cost-report" className="text-sm text-gray-500 hover:text-gray-300">← 목록</Link>
          <h1 className="text-2xl font-bold">{y}년 {Number(m)}월 비용 보고서</h1>
        </div>
        <button
          onClick={() => window.print()}
          className="rounded-lg bg-gray-800 px-4 py-2 text-sm font-medium text-gray-100 hover:bg-gray-700"
        >
          🖨 인쇄 / PDF
        </button>
      </div>

      <p className="text-xs text-gray-500">
        기준일 {meta.asOfDate} · 생성 {new Date(meta.createdAt).toLocaleString('ko-KR')}
      </p>

      {meta.status === 'WARNING' && meta.warnings.length > 0 && (
        <div className="rounded-xl border border-yellow-700 bg-yellow-950/40 p-4 text-sm text-yellow-300">
          <p className="mb-1 font-medium">경고</p>
          <ul className="list-inside list-disc space-y-0.5">
            {meta.warnings.map((w) => <li key={w.code}>{w.message}</li>)}
          </ul>
        </div>
      )}

      <CostSummary summary={body.summary} />
      <ByTypeTable rows={body.byType} />
      <ByBrokerMatrix rows={body.byBroker} />
      <MonthlyCostTrend rows={body.monthly} />
      <CostDetailsTable rows={body.details} />

      <p className="text-xs text-gray-500">
        ※ 매매수수료는 손익 계산에 이미 반영되어 있습니다 — 본 보고서는 비용 가시화용이며 수익률을 다시 차감하지 않습니다.
      </p>
    </div>
  )
}
