import { fmtKrw, pctColor } from '@/lib/report-format'
import type { CashflowSpecialTransactions } from '@/types/cashflow-report'

export function SpecialTransactions({ data }: { data: CashflowSpecialTransactions }) {
  const empty = data.largeMovements.length === 0 && data.unclassified.length === 0
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">특이거래</h2>
      {empty ? (
        <div className="rounded-xl border border-gray-700 bg-gray-900 p-6 text-center text-sm text-gray-500">특이거래 없음</div>
      ) : (
        <div className="space-y-4">
          {data.largeMovements.length > 0 && (
            <div className="rounded-xl border border-gray-800 bg-gray-900 p-4">
              <h3 className="mb-2 text-sm font-medium text-amber-300">
                대규모 이동 <span className="text-xs text-gray-500">(자산 {Math.round(data.thresholdRatio * 100)}% 이상)</span>
              </h3>
              <table className="w-full text-sm">
                <tbody>
                  {data.largeMovements.map((m, i) => (
                    <tr key={`${m.date}-${m.type}-${i}`} className="border-b border-gray-800 last:border-b-0">
                      <td className="p-2 tabular-nums text-gray-400">{m.date}</td>
                      <td className="p-2 text-gray-300">{m.type}</td>
                      <td className="p-2 text-gray-500">{m.description}</td>
                      <td className={`p-2 text-right tabular-nums ${pctColor(m.amountKrw)}`}>{fmtKrw(m.amountKrw)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {data.unclassified.length > 0 && (
            <div className="rounded-xl border border-gray-800 bg-gray-900 p-4">
              <h3 className="mb-2 text-sm font-medium text-gray-300">미분류 흐름 <span className="text-xs text-gray-500">(유형 매핑 실패)</span></h3>
              <table className="w-full text-sm">
                <tbody>
                  {data.unclassified.map((u, i) => (
                    <tr key={`${u.date}-${u.tradeType}-${i}`} className="border-b border-gray-800 last:border-b-0">
                      <td className="p-2 tabular-nums text-gray-400">{u.date}</td>
                      <td className="p-2"><span className="rounded bg-gray-800 px-2 py-0.5 text-xs text-gray-300">{u.tradeType}</span></td>
                      <td className="p-2 text-gray-500">{u.account}</td>
                      <td className="p-2 text-right tabular-nums text-gray-300">{fmtKrw(u.amountKrw)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </section>
  )
}
