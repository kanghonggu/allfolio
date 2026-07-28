// components/cost-report/CostSummary.tsx
import type { CostSummary as Summary } from '@/types/cost-report'
import { fmtKrw, fmtPctScaled, pctColor } from '@/lib/report-format'

export function CostSummary({ summary }: { summary: Summary }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">요약</h2>
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Card label="총비용" value={fmtKrw(summary.totalCost)} />
        <Card label="비용률" value={fmtPctScaled(summary.costRatio)} />
        <Card label="연환산 TER" value={fmtPctScaled(summary.annualizedTer)} />
        <Card label="수익 대비 비용" value={fmtPctScaled(summary.costVsProfit)} />
      </div>
      <p className="text-xs text-gray-500">
        매매수수료 {fmtKrw(summary.brokerFee)} · 거래세 {fmtKrw(summary.tradingTax)} · 거래 {summary.tradeCount}건 ·
        기간 손익 <span className={pctColor(summary.investmentPnl)}>{fmtKrw(summary.investmentPnl)}</span>
      </p>
    </section>
  )
}

function Card({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-xl border border-gray-700 bg-gray-900 p-5">
      <p className="text-xs text-gray-500">{label}</p>
      <p className="mt-2 text-xl font-bold tabular-nums text-gray-100">{value}</p>
    </div>
  )
}
