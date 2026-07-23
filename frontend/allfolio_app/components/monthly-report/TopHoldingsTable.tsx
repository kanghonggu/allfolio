// components/monthly-report/TopHoldingsTable.tsx
import type { Holding } from '@/types/monthly-report'
import { fmtKrw, fmtPctScaled, pctColor } from '@/lib/report-format'
// 주의: returnRate·weight는 백엔드에서 이미 0~100으로 스케일됨 → fmtPct(×100) 금지

export function TopHoldingsTable({ holdings }: { holdings: Holding[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">상위 보유 종목 (Top 10)</h2>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">종목</th><th className="p-3">유형</th>
              <th className="p-3 text-right">수량</th><th className="p-3 text-right">평가액</th>
              <th className="p-3 text-right">비중</th><th className="p-3 text-right">수익률</th>
            </tr>
          </thead>
          <tbody>
            {holdings.map((h) => (
              <tr key={`${h.symbol}-${h.name}`} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3">
                  <span className="font-medium text-gray-100">{h.name}</span>
                  <span className="ml-2 text-xs text-gray-500">{h.symbol}</span>
                </td>
                <td className="p-3 text-gray-400">{h.type}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{h.quantity.toLocaleString()}</td>
                <td className="p-3 text-right tabular-nums">{fmtKrw(h.valueKrw)}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{h.weight.toFixed(2)}%</td>
                <td className={`p-3 text-right tabular-nums ${pctColor(h.returnRate)}`}>{fmtPctScaled(h.returnRate)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}
