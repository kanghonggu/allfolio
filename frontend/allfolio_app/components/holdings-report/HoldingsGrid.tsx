// components/holdings-report/HoldingsGrid.tsx
import type { Holding } from '@/types/holdings-report'
import { fmtKrw, fmtPctScaled, pctColor } from '@/lib/report-format'
// 주의: weight·returnRate는 백엔드에서 이미 0~100 스케일 → fmtPct(×100) 금지

function num(n: number) {
  return n.toLocaleString('ko-KR', { maximumFractionDigits: 8 })
}

export function HoldingsGrid({ holdings }: { holdings: Holding[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">보유 명세</h2>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">종목</th><th className="p-3">자산군</th><th className="p-3">계좌</th>
              <th className="p-3 text-right">수량</th><th className="p-3 text-right">평단</th>
              <th className="p-3 text-right">평가액</th><th className="p-3 text-right">비중</th>
              <th className="p-3 text-right">평가손익</th><th className="p-3 text-right">수익률</th>
              <th className="p-3 text-right">당월 실현손익</th>
            </tr>
          </thead>
          <tbody>
            {holdings.map((h, i) => (
              <tr key={`${h.symbol}-${h.name}-${i}`} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3">
                  <span className="font-medium text-gray-100">{h.name}</span>
                  {h.symbol && <span className="ml-2 text-xs text-gray-500">{h.symbol}</span>}
                </td>
                <td className="p-3 text-gray-400">{h.type}</td>
                <td className="p-3 text-gray-400">{h.account}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{num(h.quantity)}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{num(h.avgPrice)}</td>
                <td className="p-3 text-right tabular-nums">
                  {fmtKrw(h.valueKrw)}
                  <span className="ml-1 text-xs text-gray-500">({num(h.currentValue)})</span>
                </td>
                <td className="p-3 text-right tabular-nums text-gray-300">{h.weight.toFixed(2)}%</td>
                <td className={`p-3 text-right tabular-nums ${pctColor(h.unrealizedPnl)}`}>{fmtKrw(h.unrealizedPnl)}</td>
                <td className={`p-3 text-right tabular-nums ${pctColor(h.returnRate)}`}>{fmtPctScaled(h.returnRate)}</td>
                <td className={`p-3 text-right tabular-nums ${pctColor(h.realizedPnl)}`}>{fmtKrw(h.realizedPnl)}</td>
              </tr>
            ))}
            {holdings.length === 0 && (
              <tr><td colSpan={10} className="p-4 text-center text-gray-500">보유 종목이 없습니다.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}
