import { fmtKrw, pctColor } from '@/lib/report-format'
import type { HoldingMonthlyChange } from '@/types/holdings-report'

export function MonthlyChange({ data }: { data: HoldingMonthlyChange }) {
  const empty = data.newEntries.length === 0 && data.soldOut.length === 0 && data.qtyChanges.length === 0
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">월간 변동</h2>
      {empty ? (
        <div className="rounded-xl border border-gray-700 bg-gray-900 p-6 text-center text-sm text-gray-500">당월 변동 없음</div>
      ) : (
        <div className="grid gap-4 lg:grid-cols-3">
          <div className="rounded-xl border border-gray-800 bg-gray-900 p-4">
            <h3 className="mb-2 text-sm font-medium text-emerald-300">신규 편입</h3>
            {data.newEntries.length === 0 ? <p className="text-xs text-gray-600">-</p> : data.newEntries.map((e) => (
              <div key={e.symbol} className="flex justify-between border-b border-gray-800 py-1 text-xs last:border-b-0">
                <span className="text-gray-200">{e.name} <span className="text-gray-500">{e.symbol}</span></span>
                <span className="tabular-nums text-gray-400">{e.firstBuyDate}</span>
              </div>
            ))}
          </div>
          <div className="rounded-xl border border-gray-800 bg-gray-900 p-4">
            <h3 className="mb-2 text-sm font-medium text-red-300">전량 매도</h3>
            {data.soldOut.length === 0 ? <p className="text-xs text-gray-600">-</p> : data.soldOut.map((e) => (
              <div key={e.symbol} className="flex justify-between border-b border-gray-800 py-1 text-xs last:border-b-0">
                <span className="text-gray-200">{e.name} <span className="text-gray-500">{e.symbol}</span></span>
                <span className={`tabular-nums ${pctColor(e.realizedPnl)}`}>{fmtKrw(e.realizedPnl)}</span>
              </div>
            ))}
          </div>
          <div className="rounded-xl border border-gray-800 bg-gray-900 p-4">
            <h3 className="mb-2 text-sm font-medium text-gray-300">수량 변동</h3>
            {data.qtyChanges.length === 0 ? <p className="text-xs text-gray-600">-</p> : data.qtyChanges.map((e) => (
              <div key={e.symbol} className="flex justify-between border-b border-gray-800 py-1 text-xs last:border-b-0">
                <span className="text-gray-200">{e.name} <span className="text-gray-500">{e.symbol}</span></span>
                <span className={`tabular-nums ${pctColor(e.netBuyAmount)}`}>{e.netQty > 0 ? '+' : ''}{e.netQty} · {fmtKrw(e.netBuyAmount)}</span>
              </div>
            ))}
          </div>
        </div>
      )}
    </section>
  )
}
