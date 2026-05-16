'use client'

import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useReportApi } from '@/lib/useApi'
import type { AssetEsgRow, EsgReport } from '@/types/report'

const RATING_COLORS: Record<string, string> = {
  'A+': 'text-emerald-400 border-emerald-600',
  'A':  'text-green-400 border-green-600',
  'B+': 'text-blue-400 border-blue-600',
  'B':  'text-blue-300 border-blue-700',
  'C+': 'text-amber-400 border-amber-600',
  'C':  'text-red-400 border-red-600',
}

const TYPE_KO: Record<string, string> = {
  CRYPTO: '암호화폐', STOCK: '주식', REAL_ESTATE: '부동산',
  JEONSE: '전세', VEHICLE: '차량', GOLD: '금', CASH: '현금', ETC: '기타',
}

function ScoreBar({ label, score, icon }: { label: string; score: number; icon: string }) {
  const pct = Math.min(100, Math.max(0, score))
  const color = score >= 75 ? 'bg-emerald-500' : score >= 55 ? 'bg-blue-500' : 'bg-amber-500'
  return (
    <div className="space-y-1">
      <div className="flex justify-between text-sm">
        <span className="text-gray-400">{icon} {label}</span>
        <span className="tabular-nums font-semibold">{score.toFixed(1)}</span>
      </div>
      <div className="h-2 rounded-full bg-gray-700">
        <div className={`h-2 rounded-full ${color} transition-all`} style={{ width: `${pct}%` }} />
      </div>
    </div>
  )
}

function RatingBadge({ rating }: { rating: string }) {
  const cls = RATING_COLORS[rating] ?? 'text-gray-400 border-gray-600'
  return (
    <span className={`inline-flex items-center rounded-full border px-4 py-1 text-2xl font-bold ${cls}`}>
      {rating}
    </span>
  )
}

function AssetRow({ row }: { row: AssetEsgRow }) {
  const pct = (row.weight * 100).toFixed(1)
  const ratingCls = (RATING_COLORS[row.rating] ?? 'text-gray-400').split(' ')[0]
  return (
    <tr className="border-t border-gray-800">
      <td className="py-3 pr-4 text-sm text-gray-200">{row.name}</td>
      <td className="py-3 pr-4 text-xs text-gray-500">{TYPE_KO[row.type] ?? row.type}</td>
      <td className="py-3 pr-4 text-right text-xs text-gray-500 tabular-nums">{pct}%</td>
      <td className="py-3 pr-4 text-right text-sm tabular-nums">{Number(row.environmental).toFixed(0)}</td>
      <td className="py-3 pr-4 text-right text-sm tabular-nums">{Number(row.social).toFixed(0)}</td>
      <td className="py-3 pr-4 text-right text-sm tabular-nums">{Number(row.governance).toFixed(0)}</td>
      <td className="py-3 pr-4 text-right font-semibold tabular-nums">{Number(row.total).toFixed(1)}</td>
      <td className={`py-3 text-right text-sm font-bold ${ratingCls}`}>{row.rating}</td>
    </tr>
  )
}

function Skeleton() {
  return <div className="h-96 animate-pulse rounded-xl bg-gray-800" />
}

function ErrorBox() {
  return (
    <div className="rounded-xl border border-red-800 bg-red-950 p-6 text-sm text-red-400">
      ESG 보고서를 불러올 수 없습니다. 자산을 먼저 등록해주세요.
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
    <div className="space-y-8">
      {/* 헤더 */}
      <div className="flex items-center gap-3">
        <Link href="/unified/reports" className="text-sm text-gray-500 hover:text-gray-300">
          ← 보고서
        </Link>
        <h1 className="text-2xl font-bold">ESG 점수</h1>
      </div>
      <p className="text-xs text-gray-500">
        생성: {new Date(data.generatedAt).toLocaleString('ko-KR')}
      </p>

      {/* 등급 + 총점 */}
      <div className="flex flex-col gap-6 sm:flex-row sm:items-center">
        <div className="flex flex-col items-center gap-2">
          <RatingBadge rating={data.rating} />
          <p className="text-xs text-gray-500">포트폴리오 등급</p>
        </div>
        <div className="flex-1 space-y-3">
          <ScoreBar label="환경 (E)" score={Number(data.environmentalScore)} icon="🌿" />
          <ScoreBar label="사회 (S)" score={Number(data.socialScore)} icon="🤝" />
          <ScoreBar label="지배구조 (G)" score={Number(data.governanceScore)} icon="🏛" />
        </div>
        <div className="text-center">
          <p className="text-4xl font-bold tabular-nums">{Number(data.totalScore).toFixed(1)}</p>
          <p className="text-xs text-gray-500 mt-1">ESG 총점</p>
        </div>
      </div>

      {/* 우수 / 개선 */}
      {(data.topAssets.length > 0 || data.bottomAssets.length > 0) && (
        <div className="grid gap-4 sm:grid-cols-2">
          {data.topAssets.length > 0 && (
            <div className="rounded-xl border border-emerald-800 bg-emerald-950/30 p-4">
              <p className="mb-3 text-sm font-semibold text-emerald-400">ESG 우수 자산</p>
              <ul className="space-y-1">
                {data.topAssets.map((a, i) => (
                  <li key={i} className="flex justify-between text-sm">
                    <span className="text-gray-300">{a.name}</span>
                    <span className="text-emerald-400 font-semibold">{a.rating}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
          {data.bottomAssets.length > 0 && (
            <div className="rounded-xl border border-amber-800 bg-amber-950/30 p-4">
              <p className="mb-3 text-sm font-semibold text-amber-400">개선 필요 자산</p>
              <ul className="space-y-1">
                {data.bottomAssets.map((a, i) => (
                  <li key={i} className="flex justify-between text-sm">
                    <span className="text-gray-300">{a.name}</span>
                    <span className="text-amber-400 font-semibold">{a.rating}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}

      {/* 자산별 ESG 테이블 */}
      <div className="rounded-xl border border-gray-800 bg-gray-900 overflow-x-auto">
        <div className="px-6 py-4 border-b border-gray-800">
          <h2 className="text-sm font-semibold text-gray-300">자산별 ESG</h2>
        </div>
        <table className="w-full px-6">
          <thead>
            <tr className="text-xs text-gray-500">
              <th className="px-6 py-3 text-left">자산명</th>
              <th className="px-6 py-3 text-left">유형</th>
              <th className="px-6 py-3 text-right">비중</th>
              <th className="px-6 py-3 text-right">E</th>
              <th className="px-6 py-3 text-right">S</th>
              <th className="px-6 py-3 text-right">G</th>
              <th className="px-6 py-3 text-right">총점</th>
              <th className="px-6 py-3 text-right">등급</th>
            </tr>
          </thead>
          <tbody className="px-6">
            {data.assetBreakdown.map((row, i) => (
              <AssetRow key={i} row={row} />
            ))}
          </tbody>
        </table>
      </div>

      {/* 방법론 안내 */}
      <p className="text-xs text-gray-600">
        ESG 점수는 자산 유형별 기본값(E×35% + S×30% + G×35%)을 현재 가치 기준으로 가중 평균한 휴리스틱 점수입니다.
        실제 ESG 등급과 다를 수 있습니다.
      </p>
    </div>
  )
}
