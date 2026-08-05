// components/dividend-report/DividendSummary.tsx
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import SectionHeader from '@/components/ui/SectionHeader'
import type { DividendSummary as Summary } from '@/types/dividend-report'
import { fmtKrw, fmtPctScaled } from '@/lib/report-format'

export function DividendSummary({ summary }: { summary: Summary }) {
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="요약" note={`수취 ${summary.receiptCount}건 · KRW 환산 기준`} />
      <div className="grid gap-px border border-line-soft bg-line-soft sm:grid-cols-2 lg:grid-cols-4">
        <Card label="세전 총액" value={fmtKrw(summary.grossTotal)} />
        <Card label={`원천징수 (실효 ${fmtPctScaled(summary.effectiveTaxRate)})`} value={fmtKrw(summary.withholdingTax)} />
        <Card label="세후 실수령" value={fmtKrw(summary.netTotal)} />
        <Card label="TTM 배당수익률" value={fmtPctScaled(summary.ttmYield)} />
      </div>
    </section>
  )
}

function Card({ label, value }: { label: string; value: string }) {
  return (
    <div className="bg-surface px-3.5 py-3">
      <Label size="sm" tone="faint">{label}</Label>
      <Num className="mt-1 block text-[16px]">{value}</Num>
    </div>
  )
}
