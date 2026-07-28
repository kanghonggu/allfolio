// components/cost-report/CostDetailsTable.tsx
import type { CostDetail } from '@/types/cost-report'
import { fmtKrw } from '@/lib/report-format'

export function CostDetailsTable({ rows }: { rows: CostDetail[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">상세 내역</h2>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">일자</th><th className="p-3">계좌</th><th className="p-3">유형</th>
              <th className="p-3">종목</th><th className="p-3 text-right">매매수수료</th><th className="p-3 text-right">거래세</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={`${r.date}-${r.stockName}-${i}`} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3 text-gray-300">{r.date}</td>
                <td className="p-3 text-gray-400">{r.account}</td>
                <td className="p-3 text-gray-400">{r.tradeType}</td>
                <td className="p-3 font-medium text-gray-100">{r.stockName}</td>
                <td className="p-3 text-right tabular-nums">{fmtKrw(r.fee)}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{fmtKrw(r.tax)}</td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={6} className="p-4 text-center text-gray-500">내역이 없습니다.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}
