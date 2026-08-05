// components/cashflow-report/CashflowSummary.tsx
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import SectionHeader from '@/components/ui/SectionHeader'
import { dirTone } from '@/lib/format'
import type { CashflowSummary as Summary } from '@/types/cashflow-report'
import { fmtKrw } from '@/lib/report-format'

export function CashflowSummary({ summary }: { summary: Summary }) {
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="요약" />
      <div className="grid gap-px border border-line-soft bg-line-soft sm:grid-cols-3">
        <Card label="총유입" value={fmtKrw(summary.totalInflow)} color="text-gain" />
        <Card label="총유출" value={fmtKrw(summary.totalOutflow)} color="text-loss" />
        <Card label="순현금흐름" value={fmtKrw(summary.netFlow)} tone={dirTone(summary.netFlow)} />
      </div>
    </section>
  )
}

function Card({
  label,
  value,
  color,
  tone,
}: {
  label: string
  value: string
  color?: string
  tone?: ReturnType<typeof dirTone>
}) {
  return (
    <div className="bg-surface px-3.5 py-3">
      <Label size="sm" tone="faint">{label}</Label>
      <Num tone={tone} className={`mt-1 block text-[16px] ${color ?? ''}`}>{value}</Num>
    </div>
  )
}
