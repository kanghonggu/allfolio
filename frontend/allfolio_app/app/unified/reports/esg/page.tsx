'use client'

import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useReportApi } from '@/lib/useApi'
import PageHeader from '@/components/ui/PageHeader'
import SectionHeader from '@/components/ui/SectionHeader'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import { ErrorState, LoadingState } from '@/components/ui/states'
import type { AssetEsgRow, EsgReport } from '@/types/report'

// 등급 → 토큰 톤 (A대=ok, B대=ink, C+=warn, C=danger)
const RATING_TONE: Record<string, string> = {
  'A+': 'text-ok',
  'A':  'text-ok',
  'B+': 'text-ink',
  'B':  'text-ink',
  'C+': 'text-warn',
  'C':  'text-danger',
}

const TYPE_KO: Record<string, string> = {
  CRYPTO: '암호화폐', STOCK: '주식', REAL_ESTATE: '부동산',
  JEONSE: '전세', VEHICLE: '차량', GOLD: '금', CASH: '현금', ETC: '기타',
}

function ScoreBar({ label, score }: { label: string; score: number }) {
  const pct = Math.min(100, Math.max(0, score))
  const fill = score >= 75 ? 'bg-ok' : score >= 55 ? 'bg-ink' : 'bg-warn'
  return (
    <div className="space-y-1">
      <div className="flex items-baseline justify-between">
        <span className="text-[12.5px] text-fg-3">{label}</span>
        <Num className="text-[12.5px] font-medium">{score.toFixed(1)}</Num>
      </div>
      <div className="h-1.5 bg-line-soft">
        <div className={`h-1.5 ${fill}`} style={{ width: `${pct}%` }} />
      </div>
    </div>
  )
}

function RatingBadge({ rating }: { rating: string }) {
  const cls = RATING_TONE[rating] ?? 'text-fg-3'
  return (
    <span className={`inline-flex items-center border border-current px-4 py-1 font-mono text-[22px] font-medium ${cls}`}>
      {rating}
    </span>
  )
}

function AssetRow({ row }: { row: AssetEsgRow }) {
  const pct = (row.weight * 100).toFixed(1)
  const ratingCls = RATING_TONE[row.rating] ?? 'text-fg-3'
  return (
    <tr className="border-b border-line-hair hover:bg-surface-muted">
      <td className="py-2.5 pr-4 text-[13px] text-fg-2">{row.name}</td>
      <td className="py-2.5 pr-4 text-xs text-fg-faint">{TYPE_KO[row.type] ?? row.type}</td>
      <td className="py-2.5 pr-4 text-right"><Num className="text-xs text-fg-faint">{pct}%</Num></td>
      <td className="py-2.5 pr-4 text-right"><Num className="text-[12.5px]">{Number(row.environmental).toFixed(0)}</Num></td>
      <td className="py-2.5 pr-4 text-right"><Num className="text-[12.5px]">{Number(row.social).toFixed(0)}</Num></td>
      <td className="py-2.5 pr-4 text-right"><Num className="text-[12.5px]">{Number(row.governance).toFixed(0)}</Num></td>
      <td className="py-2.5 pr-4 text-right"><Num className="text-[12.5px] font-medium">{Number(row.total).toFixed(1)}</Num></td>
      <td className="py-2.5 text-right"><span className={`font-mono text-xs font-medium ${ratingCls}`}>{row.rating}</span></td>
    </tr>
  )
}

function Skeleton() {
  return (
    <div className="border border-line-card bg-surface px-5 sm:px-7">
      <LoadingState label="보고서 불러오는 중" />
    </div>
  )
}

function ErrorBox() {
  return (
    <div className="border border-line-card bg-surface px-5 sm:px-7">
      <ErrorState message="ESG 보고서를 불러올 수 없습니다. 자산을 먼저 등록해주세요." />
    </div>
  )
}

export default function EsgPage() {
  const reportApi = useReportApi()

  const { data, isLoading, isError } = useQuery<EsgReport>({
    queryKey: ['report', 'esg'],
    queryFn:  () => reportApi!.esg(),
    enabled:  !!reportApi,
  })

  if (isLoading) return <Skeleton />
  if (isError || !data) return <ErrorBox />

  return (
    <div className="border border-line-card bg-surface">
      <div className="px-5 pt-5 sm:px-7">
        <Link
          href="/unified/reports"
          className="font-mono text-[10px] uppercase tracking-label text-fg-muted transition-colors hover:text-ink"
        >
          ← 보고서
        </Link>
      </div>
      <PageHeader
        className="px-5 pt-2 sm:px-7"
        title="ESG 점수"
        meta={<span>B-10 · 생성 {new Date(data.generatedAt).toLocaleString('ko-KR')}</span>}
      />

      <div className="px-5 py-5 pb-10 sm:px-7">
        {/* 등급 + 총점 */}
        <div className="flex flex-col gap-6 border-b border-line pb-6 sm:flex-row sm:items-center">
          <div className="flex flex-col items-center gap-2">
            <RatingBadge rating={data.rating} />
            <Label size="sm" tone="faint">포트폴리오 등급</Label>
          </div>
          <div className="flex-1 space-y-3">
            <ScoreBar label="환경 (E)" score={Number(data.environmentalScore)} />
            <ScoreBar label="사회 (S)" score={Number(data.socialScore)} />
            <ScoreBar label="지배구조 (G)" score={Number(data.governanceScore)} />
          </div>
          <div className="text-center">
            <Num className="block text-[32px] font-medium leading-[1.1]">{Number(data.totalScore).toFixed(1)}</Num>
            <Label size="sm" tone="faint" className="mt-1 block">ESG 총점</Label>
          </div>
        </div>

        {/* 우수 / 개선 */}
        {(data.topAssets.length > 0 || data.bottomAssets.length > 0) && (
          <div className="mt-8 grid grid-cols-1 gap-px border border-line-soft bg-line-soft sm:grid-cols-2">
            {data.topAssets.length > 0 && (
              <div className="bg-surface px-3.5 py-3">
                <Label size="sm" tone="faint">ESG 우수 자산</Label>
                <ul className="mt-2 space-y-1.5">
                  {data.topAssets.map((a, i) => (
                    <li key={i} className="flex items-baseline justify-between gap-3 text-[13px]">
                      <span className="text-fg-2">{a.name}</span>
                      <span className="font-mono text-xs font-medium text-ok">{a.rating}</span>
                    </li>
                  ))}
                </ul>
              </div>
            )}
            {data.bottomAssets.length > 0 && (
              <div className="bg-surface px-3.5 py-3">
                <Label size="sm" tone="faint">개선 필요 자산</Label>
                <ul className="mt-2 space-y-1.5">
                  {data.bottomAssets.map((a, i) => (
                    <li key={i} className="flex items-baseline justify-between gap-3 text-[13px]">
                      <span className="text-fg-2">{a.name}</span>
                      <span className="font-mono text-xs font-medium text-warn">{a.rating}</span>
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        )}

        {/* 자산별 ESG 테이블 */}
        <section className="mt-8">
          <SectionHeader label="자산별 ESG" />
          <div className="overflow-x-auto">
            <table className="w-full min-w-[640px] border-t-[1.5px] border-ink">
              <thead>
                <tr className="border-b border-line text-left">
                  <th className="py-2 pr-4 font-normal"><Label size="sm" tone="faint">자산명</Label></th>
                  <th className="py-2 pr-4 font-normal"><Label size="sm" tone="faint">유형</Label></th>
                  <th className="py-2 pr-4 text-right font-normal"><Label size="sm" tone="faint">비중</Label></th>
                  <th className="py-2 pr-4 text-right font-normal"><Label size="sm" tone="faint">E</Label></th>
                  <th className="py-2 pr-4 text-right font-normal"><Label size="sm" tone="faint">S</Label></th>
                  <th className="py-2 pr-4 text-right font-normal"><Label size="sm" tone="faint">G</Label></th>
                  <th className="py-2 pr-4 text-right font-normal"><Label size="sm" tone="faint">총점</Label></th>
                  <th className="py-2 text-right font-normal"><Label size="sm" tone="faint">등급</Label></th>
                </tr>
              </thead>
              <tbody>
                {data.assetBreakdown.map((row, i) => (
                  <AssetRow key={i} row={row} />
                ))}
              </tbody>
            </table>
          </div>
        </section>

        {/* 방법론 안내 */}
        <p className="mt-8 text-xs leading-relaxed text-fg-faint">
          ESG 점수는 자산 유형별 기본값(E×35% + S×30% + G×35%)을 현재 가치 기준으로 가중 평균한 휴리스틱 점수입니다.
          실제 ESG 등급과 다를 수 있습니다.
        </p>
      </div>
    </div>
  )
}
