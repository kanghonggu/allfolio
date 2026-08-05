// components/esg-screening/EsgSummary.tsx
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import SectionHeader from '@/components/ui/SectionHeader'
import type { EsgScores, EsgScreeningSummary } from '@/types/esg-screening'
import { fmtKrw } from '@/lib/report-format'

function ratingColor(rating: string): string {
  if (rating.startsWith('A')) return 'text-ok'
  if (rating.startsWith('B')) return 'text-warn'
  if (rating.startsWith('C')) return 'text-danger'
  return 'text-ink'
}

export function EsgSummary({ esg, screening }: { esg: EsgScores; screening: EsgScreeningSummary }) {
  const clean = screening.violationCount === 0
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="요약" />
      <div className="grid gap-px border border-line-soft bg-line-soft sm:grid-cols-2 lg:grid-cols-4">
        <Card label="ESG 등급" value={esg.rating} color={ratingColor(esg.rating)} />
        <Card label="종합점수" value={`${esg.totalScore.toFixed(1)}점`} color="text-ink" />
        <Card
          label="배제 위반"
          value={clean ? '없음' : `${screening.violationCount}종목`}
          color={clean ? 'text-ok' : 'text-danger'}
        />
        <Card
          label="위반 비중"
          value={`${screening.violationWeight.toFixed(2)}%`}
          color={clean ? 'text-ink' : 'text-danger'}
          sub={fmtKrw(screening.violationValueKrw)}
        />
      </div>
    </section>
  )
}

function Card({ label, value, color, sub }: { label: string; value: string; color: string; sub?: string }) {
  return (
    <div className="bg-surface px-3.5 py-3">
      <Label size="sm" tone="faint">{label}</Label>
      <Num className={`mt-1 block text-[16px] ${color}`}>{value}</Num>
      {sub && <Num className="mt-0.5 block text-[11px] text-fg-faint">{sub}</Num>}
    </div>
  )
}
