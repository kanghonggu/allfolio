// components/dividend-report/DividendCalendar.tsx
import type { DividendCalendarEntry } from '@/types/dividend-report'
import { fmtKrw } from '@/lib/report-format'

const MONTHS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]

export function DividendCalendar({ rows }: { rows: DividendCalendarEntry[] }) {
  if (!rows || rows.length === 0) return null
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">배당 지급 캘린더</h2>
      <p className="text-xs text-gray-500">최근 12개월 지급 이력 기반 패턴이며, 향후 지급을 보장·예측하지 않습니다.</p>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">종목</th><th className="p-3">주기</th>
              <th className="p-3">지급 월</th><th className="p-3 text-right">TTM 횟수</th>
              <th className="p-3">최근 지급일</th><th className="p-3 text-right">TTM 순수취</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={`${r.symbol ?? r.stockName}-${i}`} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3">
                  <span className="font-medium text-gray-100">{r.stockName}</span>
                  {r.symbol && <span className="ml-2 text-xs text-gray-500">{r.symbol}</span>}
                </td>
                <td className="p-3"><span className="rounded bg-gray-800 px-2 py-0.5 text-xs text-gray-300">{r.cadence}</span></td>
                <td className="p-3">
                  <div className="flex gap-0.5">
                    {MONTHS.map((m) => (
                      <span key={m} className={`inline-block h-4 w-4 rounded-sm text-center text-[9px] leading-4 ${r.paidMonths.includes(m) ? 'bg-emerald-800 text-emerald-100' : 'bg-gray-800 text-gray-600'}`}>{m}</span>
                    ))}
                  </div>
                </td>
                <td className="p-3 text-right tabular-nums text-gray-300">{r.payCount}</td>
                <td className="p-3 tabular-nums text-gray-400">{r.lastPayDate}</td>
                <td className="p-3 text-right tabular-nums text-gray-100">{fmtKrw(r.ttmNet)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}
