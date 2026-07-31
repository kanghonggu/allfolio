// components/cashflow-report/InternalFlows.tsx
import { fmtKrw } from '@/lib/report-format'
import type { CashflowInternalFlow } from '@/types/cashflow-report'

export function InternalFlows({ rows }: { rows: CashflowInternalFlow[] }) {
  if (!rows || rows.length === 0) return null
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">환전·계좌간이체</h2>
      <p className="text-xs text-gray-500">내부이동은 외부 유입/유출에서 제외되어 별도 표기됩니다.</p>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">날짜</th><th className="p-3">유형</th>
              <th className="p-3">내용</th><th className="p-3 text-right">금액(KRW)</th>
              <th className="p-3 text-right">전환비용</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={`${r.date}-${i}`} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3 tabular-nums text-gray-400">{r.date}</td>
                <td className="p-3"><span className="rounded bg-gray-800 px-2 py-0.5 text-xs text-gray-300">{r.kind}</span></td>
                <td className="p-3 text-gray-300">
                  {r.kind === '환전'
                    ? `${r.fromCurrency} ${r.fromAmount?.toLocaleString()} → ${r.toCurrency} ${r.toAmount?.toLocaleString()}`
                    : `${r.fromAccount} → ${r.toAccount}`}
                </td>
                <td className="p-3 text-right tabular-nums text-gray-100">{fmtKrw(r.amountKrw)}</td>
                <td className="p-3 text-right tabular-nums text-gray-400">
                  {r.kind === '환전' && r.spreadKrw != null && r.spreadKrw !== 0 ? fmtKrw(r.spreadKrw) : '–'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}
