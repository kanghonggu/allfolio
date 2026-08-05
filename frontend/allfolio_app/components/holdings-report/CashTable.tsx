// components/holdings-report/CashTable.tsx
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import SectionHeader from '@/components/ui/SectionHeader'
import type { HoldingCash } from '@/types/holdings-report'
import { fmtKrw } from '@/lib/report-format'

export function CashTable({ rows }: { rows: HoldingCash[] }) {
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="현금 잔고" />
      <div className="overflow-x-auto">
        <table className="w-full min-w-[400px] border-t-[1.5px] border-ink text-[13px]">
          <thead>
            <tr className="border-b border-line text-left">
              <th className="py-2 pr-2 font-normal"><Label size="sm" tone="faint">계좌</Label></th>
              <th className="px-2 py-2 font-normal"><Label size="sm" tone="faint">통화</Label></th>
              <th className="py-2 pl-2 text-right font-normal"><Label size="sm" tone="faint">잔액</Label></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={`${r.account}-${r.currency}-${i}`} className="border-b border-line-hair">
                <td className="py-2.5 pr-2 font-medium text-ink">{r.account}</td>
                <td className="px-2 py-2.5 text-fg-3">{r.currency}</td>
                <td className="py-2.5 pl-2 text-right"><Num className="text-[12.5px]">{fmtKrw(r.valueKrw)}</Num></td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={3} className="py-6 text-center text-[12px] text-fg-faint">현금성 자산 없음</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}
