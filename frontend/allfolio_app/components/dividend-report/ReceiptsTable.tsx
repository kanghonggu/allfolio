// components/dividend-report/ReceiptsTable.tsx
import type { DividendReceipt } from '@/types/dividend-report'
import { fmtKrw } from '@/lib/report-format'

export function ReceiptsTable({ receipts }: { receipts: DividendReceipt[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">수취 내역</h2>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">지급일</th><th className="p-3">종목</th><th className="p-3">계좌</th>
              <th className="p-3 text-right">세전</th><th className="p-3 text-right">원천징수</th><th className="p-3 text-right">세후</th>
            </tr>
          </thead>
          <tbody>
            {receipts.map((r, i) => (
              <tr key={`${r.payDate}-${r.symbol}-${i}`} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3 text-gray-300">{r.payDate}</td>
                <td className="p-3">
                  <span className="font-medium text-gray-100">{r.stockName}</span>
                  {r.symbol && <span className="ml-2 text-xs text-gray-500">{r.symbol}</span>}
                </td>
                <td className="p-3 text-gray-400">{r.account}</td>
                <td className="p-3 text-right tabular-nums">{fmtKrw(r.gross)}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{fmtKrw(r.tax)}</td>
                <td className="p-3 text-right tabular-nums">{fmtKrw(r.net)}</td>
              </tr>
            ))}
            {receipts.length === 0 && (
              <tr><td colSpan={6} className="p-4 text-center text-gray-500">수취 내역이 없습니다.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}
