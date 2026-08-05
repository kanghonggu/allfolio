// app/unified/reports/holdings-report/page.tsx
'use client'

import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { useReportArchiveApi } from '@/lib/useApi'
import { HOLDINGS } from '@/lib/report-archive-api'
import type { ArchiveMeta } from '@/types/report-archive'
import PageHeader from '@/components/ui/PageHeader'
import SectionHeader from '@/components/ui/SectionHeader'
import Badge from '@/components/ui/Badge'
import Button from '@/components/ui/Button'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import Field, { Select } from '@/components/ui/Field'
import { EmptyState, LoadingState } from '@/components/ui/states'

const NOW = new Date()
const YEARS = Array.from({ length: 6 }, (_, i) => NOW.getFullYear() - i)
const MONTHS = Array.from({ length: 12 }, (_, i) => i + 1)

const ROW_GRID = 'grid grid-cols-[1.1fr_0.9fr_0.7fr_1.3fr] gap-3'

export default function HoldingsReportListPage() {
  const api = useReportArchiveApi(HOLDINGS)
  const router = useRouter()
  const qc = useQueryClient()
  const [year, setYear] = useState(NOW.getMonth() === 0 ? NOW.getFullYear() - 1 : NOW.getFullYear())
  const [month, setMonth] = useState(NOW.getMonth() === 0 ? 12 : NOW.getMonth())
  const [error, setError] = useState<string | null>(null)

  const { data: list, isLoading } = useQuery({
    queryKey: ['holdings-report', 'list'],
    queryFn: () => api!.list(),
    enabled: !!api,
    retry: false,
  })

  const gen = useMutation({
    mutationFn: () => api!.generate(year, month),
    onSuccess: (meta) => {
      qc.invalidateQueries({ queryKey: ['holdings-report', 'list'] })
      router.push(`/unified/reports/holdings-report/${meta.id}`)
    },
    onError: (e: unknown) => {
      const msg =
        (e as { response?: { data?: { error?: string } } })?.response?.data?.error ??
        '생성에 실패했습니다.'
      setError(msg)
    },
  })

  return (
    <div className="border border-line-card bg-surface">
      <div className="px-5 pt-4 sm:px-7">
        <Link
          href="/unified/reports"
          className="font-mono text-[10px] tracking-label text-fg-faint transition-colors hover:text-ink"
        >
          ← 보고서
        </Link>
      </div>
      <PageHeader
        className="px-5 pt-3 sm:px-7"
        title="월말 보유 명세서"
        meta="R-05 · 월말 확정 후 재산출 · PDF 출력"
      />

      <div className="px-5 py-5 pb-10 sm:px-7">
        <div className="mb-6 flex flex-wrap items-end gap-3 border border-ink bg-surface-muted p-4">
          <Field id="hr-year" label="연도" className="w-28">
            <Select value={year} onChange={(e) => setYear(Number(e.target.value))}>
              {YEARS.map((y) => <option key={y} value={y}>{y}</option>)}
            </Select>
          </Field>
          <Field id="hr-month" label="월" className="w-20">
            <Select value={month} onChange={(e) => setMonth(Number(e.target.value))}>
              {MONTHS.map((mm) => <option key={mm} value={mm}>{mm}</option>)}
            </Select>
          </Field>
          <Button
            variant="primary"
            onClick={() => { setError(null); gen.mutate() }}
            disabled={gen.isPending || !api}
          >
            {gen.isPending ? '생성 중…' : '보고서 생성'}
          </Button>
        </div>

        {error && (
          <div role="alert" className="mb-6 flex items-center gap-3 border border-warn-line bg-warn-bg px-4 py-2.5">
            <Label size="sm" className="text-warn">주의</Label>
            <span className="text-[12.5px] text-fg-2">{error}</span>
          </div>
        )}

        <SectionHeader label="생성 이력" note={list ? `${list.length}건` : undefined} />
        {!api || isLoading ? (
          <LoadingState label="이력 불러오는 중" />
        ) : !list || list.length === 0 ? (
          <EmptyState
            title="아직 생성된 보유 명세서가 없습니다"
            description="위에서 연·월을 골라 생성하세요"
          />
        ) : (
          <div className="overflow-x-auto">
            <div className="min-w-[560px] border-t-[1.5px] border-ink">
              <div className={`${ROW_GRID} border-b border-line py-2`}>
                <Label size="sm" tone="faint">기간</Label>
                <Label size="sm" tone="faint">기준일</Label>
                <Label size="sm" tone="faint">상태</Label>
                <Label size="sm" tone="faint">생성일시</Label>
              </div>
              {list.map((r: ArchiveMeta) => (
                <div
                  key={r.id}
                  role="button"
                  tabIndex={0}
                  onClick={() => router.push(`/unified/reports/holdings-report/${r.id}`)}
                  onKeyDown={(e) => { if (e.key === 'Enter') router.push(`/unified/reports/holdings-report/${r.id}`) }}
                  className={`${ROW_GRID} cursor-pointer items-baseline border-b border-line-hair py-2.5 transition-colors hover:bg-surface-muted focus:bg-surface-muted focus:outline-none`}
                >
                  <span className="text-[13px] font-medium text-ink">
                    {r.periodStart.slice(0, 4)}년 {Number(r.periodStart.slice(5, 7))}월
                  </span>
                  <Num className="text-[12px] text-fg-3">{r.asOfDate}</Num>
                  <span>
                    {r.status === 'WARNING'
                      ? <Badge variant="warn">잠정/경고</Badge>
                      : <Badge variant="ok">확정</Badge>}
                  </span>
                  <Num className="text-[11px] text-fg-faint">{new Date(r.createdAt).toLocaleString('ko-KR')}</Num>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
