// components/holdings-report/CashTable.tsx
import type { HoldingCash } from '@/types/holdings-report'
import { fmtKrw } from '@/lib/report-format'

export function CashTable({ rows }: { rows: HoldingCash[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">현금 잔고</h2>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">계좌</th><th className="p-3">통화</th><th className="p-3 text-right">잔액</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={`${r.account}-${r.currency}-${i}`} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3 font-medium text-gray-100">{r.account}</td>
                <td className="p-3 text-gray-400">{r.currency}</td>
                <td className="p-3 text-right tabular-nums">{fmtKrw(r.valueKrw)}</td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={3} className="p-4 text-center text-gray-500">현금성 자산 없음</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}
