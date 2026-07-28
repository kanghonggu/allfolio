// components/cost-report/ByBrokerMatrix.tsx
import type { CostByBroker } from '@/types/cost-report'
import { fmtKrw } from '@/lib/report-format'

export function ByBrokerMatrix({ rows }: { rows: CostByBroker[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">브로커×유형 매트릭스</h2>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">브로커</th><th className="p-3 text-right">매매수수료</th>
              <th className="p-3 text-right">거래세</th><th className="p-3 text-right">합계</th><th className="p-3 text-right">비중</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.broker} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3 font-medium text-gray-100">{r.broker}</td>
                <td className="p-3 text-right tabular-nums">{fmtKrw(r.fee)}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{fmtKrw(r.tax)}</td>
                <td className="p-3 text-right tabular-nums">{fmtKrw(r.total)}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{r.weight.toFixed(2)}%</td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={5} className="p-4 text-center text-gray-500">데이터가 없습니다.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}
