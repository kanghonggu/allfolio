// components/monthly-report/PerformanceSummary.tsx
import type { Performance } from '@/types/monthly-report'
import { fmtPct, fmtKrw, pctColor } from '@/lib/report-format'

const STANDARD_KEYS: Array<'3M' | 'YTD' | '1Y' | 'SI'> = ['3M', 'YTD', '1Y', 'SI']

export function PerformanceSummary({ perf }: { perf: Performance }) {
  const m = perf.month
  return (
    <section className="space-y-4 break-inside-avoid">
      <h2 className="text-lg font-semibold">성과 요약</h2>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Kpi label="TWR (시간가중)" value={fmtPct(m.twr)} color={pctColor(m.twr)} />
        <Kpi label="MWR (금액가중)" value={fmtPct(m.mwr)} color={pctColor(m.mwr)} />
        <Kpi label="기말 NAV" value={fmtKrw(m.endNav)} />
        <Kpi label="순증(입출금)" value={fmtKrw(m.netFlow)} />
      </div>

      {m.benchmark && (
        <div className="rounded-xl border border-gray-700 bg-gray-900 p-4 text-sm">
          <p className="text-gray-400">
            벤치마크 <span className="text-gray-200">{m.benchmark.label}</span> 대비
          </p>
          <div className="mt-2 flex gap-6">
            <span>기간수익률 <b className={pctColor(m.benchmark.periodReturn)}>{fmtPct(m.benchmark.periodReturn)}</b></span>
            <span>초과수익 <b className={pctColor(m.benchmark.excessReturn)}>{fmtPct(m.benchmark.excessReturn)}</b></span>
          </div>
        </div>
      )}

      <div className="grid gap-4 sm:grid-cols-2">
        <div className="rounded-xl border border-gray-700 bg-gray-900 p-4">
          <p className="mb-2 text-xs text-gray-500">표준기간 TWR</p>
          <table className="w-full text-sm">
            <tbody>
              {STANDARD_KEYS.map((k) => (
                <tr key={k} className="border-t border-gray-800 first:border-t-0">
                  <td className="py-1.5 text-gray-400">{k}</td>
                  <td className={`py-1.5 text-right tabular-nums ${pctColor(perf.standard[k]?.twr)}`}>
                    {fmtPct(perf.standard[k]?.twr)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <Kpi label="연환산 변동성" value={fmtPct(perf.volatility)} />
      </div>
    </section>
  )
}

function Kpi({ label, value, color = 'text-gray-100' }: { label: string; value: string; color?: string }) {
  return (
    <div className="rounded-xl border border-gray-700 bg-gray-900 p-5">
      <p className="text-xs text-gray-500">{label}</p>
      <p className={`mt-2 text-xl font-bold tabular-nums ${color}`}>{value}</p>
    </div>
  )
}
