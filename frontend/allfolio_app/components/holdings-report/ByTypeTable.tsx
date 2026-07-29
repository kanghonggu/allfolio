// components/holdings-report/ByTypeTable.tsx
import type { HoldingByType } from '@/types/holdings-report'
import { fmtKrw } from '@/lib/report-format'

export function ByTypeTable({ rows }: { rows: HoldingByType[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">자산군별 소계</h2>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">자산군</th><th className="p-3 text-right">평가액</th>
              <th className="p-3 text-right">비중</th><th className="p-3 text-right">종목수</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.type} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3 font-medium text-gray-100">{r.type}</td>
                <td className="p-3 text-right tabular-nums">{fmtKrw(r.valueKrw)}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{r.weight.toFixed(2)}%</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{r.holdingCount}</td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={4} className="p-4 text-center text-gray-500">데이터가 없습니다.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}
