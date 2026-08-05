// components/cost-report/CostSummary.tsx
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import SectionHeader from '@/components/ui/SectionHeader'
import { dirTone } from '@/lib/format'
import type { CostSummary as Summary } from '@/types/cost-report'
import { fmtKrw, fmtPctScaled } from '@/lib/report-format'

export function CostSummary({ summary }: { summary: Summary }) {
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="요약" />
      <div className="grid gap-px border border-line-soft bg-line-soft sm:grid-cols-2 lg:grid-cols-4">
        <Card label="총비용" value={fmtKrw(summary.totalCost)} />
        <Card label="비용률" value={fmtPctScaled(summary.costRatio)} />
        <Card label="연환산 TER" value={fmtPctScaled(summary.annualizedTer)} />
        <Card label="수익 대비 비용" value={fmtPctScaled(summary.costVsProfit)} />
      </div>
      <p className="mt-3 text-[11.5px] leading-relaxed text-fg-faint">
        매매수수료 {fmtKrw(summary.brokerFee)} · 거래세 {fmtKrw(summary.tradingTax)} · 거래 {summary.tradeCount}건 ·
        기간 손익 <Num tone={dirTone(summary.investmentPnl)} className="text-[11px]">{fmtKrw(summary.investmentPnl)}</Num>
      </p>
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
