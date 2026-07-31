// components/dividend-report/ByCountryTable.tsx
import type { DividendByCountry } from '@/types/dividend-report'
import { fmtKrw } from '@/lib/report-format'

export function ByCountryTable({ rows }: { rows: DividendByCountry[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">국가별 원천징수 요약</h2>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">국가</th><th className="p-3 text-right">세전</th>
              <th className="p-3 text-right">원천징수</th><th className="p-3 text-right">세후</th>
              <th className="p-3 text-right">실효세율</th>
              <th className="p-3 text-right">기대세율</th><th className="p-3 text-right">편차(%p)</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.country} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3 font-medium text-gray-100">{r.country}</td>
                <td className="p-3 text-right tabular-nums">{fmtKrw(r.gross)}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{fmtKrw(r.tax)}</td>
                <td className="p-3 text-right tabular-nums">{fmtKrw(r.net)}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{r.effectiveTaxRate.toFixed(2)}%</td>
                <td className="p-3 text-right tabular-nums text-gray-300">
                  {r.expectedTaxRate == null ? '–' : `${r.expectedTaxRate.toFixed(2)}%`}
                </td>
                <td className={`p-3 text-right tabular-nums ${r.taxFlagged ? 'text-amber-300' : 'text-gray-300'}`}>
                  {r.expectedTaxRate == null || r.taxDeviationPp == null
                    ? '–'
                    : `${r.taxFlagged ? '⚠ ' : ''}${r.taxDeviationPp.toFixed(2)}%p`}
                </td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={7} className="p-4 text-center text-gray-500">데이터가 없습니다.</td></tr>
            )}
          </tbody>
        </table>
      </div>
      <p className="text-xs text-gray-500">
        기대세율은 국내(KR) 기준율 대비이며, 해외는 국가 판별 불가로 생략.
      </p>
    </section>
  )
}
