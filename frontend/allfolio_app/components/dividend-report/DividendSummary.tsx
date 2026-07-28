// components/dividend-report/DividendSummary.tsx
import type { DividendSummary as Summary } from '@/types/dividend-report'
import { fmtKrw, fmtPctScaled } from '@/lib/report-format'

export function DividendSummary({ summary }: { summary: Summary }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">요약</h2>
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Card label="세전 총액" value={fmtKrw(summary.grossTotal)} />
        <Card label={`원천징수 (실효 ${fmtPctScaled(summary.effectiveTaxRate)})`} value={fmtKrw(summary.withholdingTax)} />
        <Card label="세후 실수령" value={fmtKrw(summary.netTotal)} />
        <Card label="TTM 배당수익률" value={fmtPctScaled(summary.ttmYield)} />
      </div>
      <p className="text-xs text-gray-500">수취 {summary.receiptCount}건 · 금액은 KRW 환산 기준</p>
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
