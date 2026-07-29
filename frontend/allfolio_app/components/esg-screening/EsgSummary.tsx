// components/esg-screening/EsgSummary.tsx
import type { EsgScores, EsgScreeningSummary } from '@/types/esg-screening'
import { fmtKrw } from '@/lib/report-format'

function ratingColor(rating: string): string {
  if (rating.startsWith('A')) return 'text-emerald-400'
  if (rating.startsWith('B')) return 'text-amber-400'
  if (rating.startsWith('C')) return 'text-red-400'
  return 'text-gray-100'
}

export function EsgSummary({ esg, screening }: { esg: EsgScores; screening: EsgScreeningSummary }) {
  const clean = screening.violationCount === 0
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">요약</h2>
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Card label="ESG 등급" value={esg.rating} color={ratingColor(esg.rating)} />
        <Card label="종합점수" value={`${esg.totalScore.toFixed(1)}점`} color="text-gray-100" />
        <Card
          label="배제 위반"
          value={clean ? '없음 ✓' : `${screening.violationCount}종목`}
          color={clean ? 'text-emerald-400' : 'text-red-400'}
        />
        <Card label="위반 비중" value={`${screening.violationWeight.toFixed(2)}%`} color={clean ? 'text-gray-100' : 'text-red-400'} sub={fmtKrw(screening.violationValueKrw)} />
      </div>
    </section>
  )
}

function Card({ label, value, color, sub }: { label: string; value: string; color: string; sub?: string }) {
  return (
    <div className="rounded-xl border border-gray-700 bg-gray-900 p-5">
      <p className="text-xs text-gray-500">{label}</p>
      <p className={`mt-2 text-xl font-bold tabular-nums ${color}`}>{value}</p>
      {sub && <p className="mt-1 text-xs text-gray-500 tabular-nums">{sub}</p>}
    </div>
  )
}
