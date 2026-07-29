// components/esg-screening/EsgBreakdownTable.tsx
import type { EsgBreakdownRow } from '@/types/esg-screening'

function ratingColor(rating: string): string {
  if (rating.startsWith('A')) return 'text-emerald-400'
  if (rating.startsWith('B')) return 'text-amber-400'
  if (rating.startsWith('C')) return 'text-red-400'
  return 'text-gray-100'
}

export function EsgBreakdownTable({ rows }: { rows: EsgBreakdownRow[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">종목별 ESG</h2>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">종목</th><th className="p-3">유형</th><th className="p-3 text-right">비중</th>
              <th className="p-3 text-right">E</th><th className="p-3 text-right">S</th><th className="p-3 text-right">G</th>
              <th className="p-3 text-right">종합</th><th className="p-3">등급</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={`${r.name}-${i}`} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3 font-medium text-gray-100">{r.name}</td>
                <td className="p-3 text-gray-400">{r.type}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{r.weight.toFixed(2)}%</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{r.e}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{r.s}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{r.g}</td>
                <td className="p-3 text-right tabular-nums font-medium text-gray-100">{r.total.toFixed(1)}</td>
                <td className={`p-3 font-medium ${ratingColor(r.rating)}`}>{r.rating}</td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={8} className="p-4 text-center text-gray-500">데이터가 없습니다.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}
