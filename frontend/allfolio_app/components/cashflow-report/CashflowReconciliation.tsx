import { fmtKrw, pctColor } from '@/lib/report-format'
import type { CashflowReconciliation as Recon } from '@/types/cashflow-report'

export function CashflowReconciliation({ data }: { data: Recon }) {
  return (
    <section className="rounded-xl border border-gray-700 bg-gray-900 p-5">
      <h3 className="mb-3 text-sm font-semibold text-gray-300">현금 조정표</h3>
      <table className="w-full text-sm">
        <tbody>
          <tr className="border-b border-gray-800">
            <td className="p-2 text-gray-400">기초 현금</td>
            <td className="p-2 text-right tabular-nums">{fmtKrw(data.openingBalance)}</td>
          </tr>
          {data.changes.map((c) => (
            <tr key={c.type} className="border-b border-gray-800">
              <td className="p-2 pl-4 text-gray-500">{c.type}</td>
              <td className={`p-2 text-right tabular-nums ${pctColor(c.amount)}`}>{fmtKrw(c.amount)}</td>
            </tr>
          ))}
          <tr className="border-b border-gray-700 font-semibold">
            <td className="p-2">기말 현금 (계산)</td>
            <td className="p-2 text-right tabular-nums">{fmtKrw(data.closingCalculated)}</td>
          </tr>
        </tbody>
      </table>

      {/* 정합 검증 */}
      <div className="mt-3">
        {!data.reconcilable ? (
          <div className="rounded bg-gray-800 px-3 py-2 text-xs text-gray-400">
            과거 기간 — 실제 잔고 대조 생략 (기간 이후 현금활동 존재)
          </div>
        ) : data.reconciled ? (
          <div className="rounded bg-emerald-950/40 px-3 py-2 text-xs text-emerald-300">
            ✓ 정합 — 계산 기말 = 실제 현금 ({fmtKrw(data.actualCash)})
          </div>
        ) : (
          <div className="rounded bg-red-950/40 px-3 py-2 text-xs text-red-300">
            ⚠ 불일치 — 실제 현금 {fmtKrw(data.actualCash)} · 차액{' '}
            <span className={pctColor(data.difference)}>{fmtKrw(data.difference)}</span>{' '}
            (미포착 환전·이체·특이거래 추정)
          </div>
        )}
      </div>
    </section>
  )
}
