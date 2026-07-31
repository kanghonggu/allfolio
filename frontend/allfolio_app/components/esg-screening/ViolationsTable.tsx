// components/esg-screening/ViolationsTable.tsx
import type { EsgViolation } from '@/types/esg-screening'
import { fmtKrw } from '@/lib/report-format'

export function ViolationsTable({ rows }: { rows: EsgViolation[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">배제 위반 내역</h2>
      {rows.length === 0 ? (
        <div className="rounded-xl border border-emerald-700 bg-emerald-950/40 p-6 text-center text-sm text-emerald-300">
          배제 위반 없음 ✓
        </div>
      ) : (
        <div className="overflow-x-auto rounded-xl border border-red-800 bg-gray-900">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
                <th className="p-3">종목</th><th className="p-3">배제 리스트</th><th className="p-3">사유</th>
                <th className="p-3">편입일</th>
                <th className="p-3 text-right">평가액</th><th className="p-3 text-right">비중</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={`${r.symbol}-${r.name}-${i}`} className="border-b border-gray-800 last:border-b-0">
                  <td className="p-3">
                    <span className="font-medium text-gray-100">{r.name}</span>
                    {r.symbol && <span className="ml-2 text-xs text-gray-500">{r.symbol}</span>}
                  </td>
                  <td className="p-3 text-gray-400">{r.listName}</td>
                  <td className="p-3"><span className="rounded bg-red-950 px-2 py-0.5 text-xs text-red-300">{r.reason}</span></td>
                  <td className="p-3 text-xs text-gray-400">
                    {r.firstBuyDate ?? '-'}
                    {r.sinceListed && <span className="ml-1 rounded bg-gray-800 px-1.5 py-0.5 text-[10px] text-gray-400">{r.sinceListed}</span>}
                  </td>
                  <td className="p-3 text-right tabular-nums text-red-300">{fmtKrw(r.valueKrw)}</td>
                  <td className="p-3 text-right tabular-nums text-gray-300">{r.weight.toFixed(2)}%</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
