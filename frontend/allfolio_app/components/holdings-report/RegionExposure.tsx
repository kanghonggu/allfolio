// components/holdings-report/RegionExposure.tsx
import type { HoldingsByRegion } from '@/types/holdings-report'
import { fmtKrw } from '@/lib/report-format'

export function RegionExposure({ rows }: { rows: HoldingsByRegion[] }) {
  if (!rows || rows.length === 0) return null
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">지역 노출</h2>
      <p className="text-xs text-gray-500">통화 기준 추정 지역입니다(자산 국가·거래소 데이터 부재).</p>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">지역</th><th className="p-3 text-right">평가액</th>
              <th className="p-3 text-right">비중</th><th className="p-3 text-right">종목수</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.region} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3 font-medium text-gray-100">{r.region}</td>
                <td className="p-3 text-right tabular-nums">{fmtKrw(r.valueKrw)}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{r.weight.toFixed(2)}%</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{r.holdingCount}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}
