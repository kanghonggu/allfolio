// components/holdings-report/HoldingsSummary.tsx
import type { HoldingsSummary as Summary } from '@/types/holdings-report'
import { fmtKrw, fmtPctScaled, pctColor } from '@/lib/report-format'

export function HoldingsSummary({ summary }: { summary: Summary }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">요약</h2>
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Card label="총평가액" value={fmtKrw(summary.totalValueKrw)} />
        <Card label="보유 종목 / 계좌" value={`${summary.holdingCount}종목 / ${summary.accountCount}계좌`} />
        <Card label="현금 비중" value={fmtPctScaled(summary.cashWeight)} />
        <Card label="평가손익 합계" value={fmtKrw(summary.unrealizedPnlKrw)} color={pctColor(summary.unrealizedPnlKrw)} />
      </div>
    </section>
  )
}

function Card({ label, value, color = 'text-gray-100' }: { label: string; value: string; color?: string }) {
  return (
    <div className="rounded-xl border border-gray-700 bg-gray-900 p-5">
      <p className="text-xs text-gray-500">{label}</p>
      <p className={`mt-2 text-xl font-bold tabular-nums ${color}`}>{value}</p>
    </div>
  )
}
