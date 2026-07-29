// components/cashflow-report/CashflowDetails.tsx
import type { CashflowDetail } from '@/types/cashflow-report'
import { fmtKrw, pctColor } from '@/lib/report-format'

export function CashflowDetails({ rows }: { rows: CashflowDetail[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">상세 내역</h2>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">일자</th><th className="p-3">계좌</th><th className="p-3">유형</th>
              <th className="p-3">설명</th><th className="p-3 text-right">금액</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={`${r.date}-${r.description}-${i}`} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3 text-gray-300">{r.date}</td>
                <td className="p-3 text-gray-400">{r.account}</td>
                <td className="p-3 text-gray-400">{r.type}</td>
                <td className="p-3 font-medium text-gray-100">{r.description}</td>
                <td className={`p-3 text-right tabular-nums ${pctColor(r.amount)}`}>{fmtKrw(r.amount)}</td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={5} className="p-4 text-center text-gray-500">내역이 없습니다.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}
