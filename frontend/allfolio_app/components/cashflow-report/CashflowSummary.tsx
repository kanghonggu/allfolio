// components/cashflow-report/CashflowSummary.tsx
import type { CashflowSummary as Summary } from '@/types/cashflow-report'
import { fmtKrw, pctColor } from '@/lib/report-format'

export function CashflowSummary({ summary }: { summary: Summary }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">요약</h2>
      <div className="grid gap-4 sm:grid-cols-3">
        <Card label="총유입" value={fmtKrw(summary.totalInflow)} color="text-emerald-400" />
        <Card label="총유출" value={fmtKrw(summary.totalOutflow)} color="text-red-400" />
        <Card label="순현금흐름" value={fmtKrw(summary.netFlow)} color={pctColor(summary.netFlow)} />
      </div>
    </section>
  )
}

function Card({ label, value, color }: { label: string; value: string; color: string }) {
  return (
    <div className="rounded-xl border border-gray-700 bg-gray-900 p-5">
      <p className="text-xs text-gray-500">{label}</p>
      <p className={`mt-2 text-xl font-bold tabular-nums ${color}`}>{value}</p>
    </div>
  )
}
