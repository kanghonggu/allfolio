// components/cashflow-report/CashflowDetails.tsx
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import SectionHeader from '@/components/ui/SectionHeader'
import { dirTone } from '@/lib/format'
import type { CashflowDetail } from '@/types/cashflow-report'
import { fmtKrw } from '@/lib/report-format'

export function CashflowDetails({ rows }: { rows: CashflowDetail[] }) {
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="상세 내역" />
      <div className="overflow-x-auto">
        <table className="w-full min-w-[600px] border-t-[1.5px] border-ink text-[13px]">
          <thead>
            <tr className="border-b border-line text-left">
              <th className="py-2 pr-2 font-normal"><Label size="sm" tone="faint">일자</Label></th>
              <th className="px-2 py-2 font-normal"><Label size="sm" tone="faint">계좌</Label></th>
              <th className="px-2 py-2 font-normal"><Label size="sm" tone="faint">유형</Label></th>
              <th className="px-2 py-2 font-normal"><Label size="sm" tone="faint">설명</Label></th>
              <th className="py-2 pl-2 text-right font-normal"><Label size="sm" tone="faint">금액</Label></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={`${r.date}-${r.description}-${i}`} className="border-b border-line-hair">
                <td className="py-2.5 pr-2"><Num className="text-[12px] text-fg-3">{r.date}</Num></td>
                <td className="px-2 py-2.5 text-fg-3">{r.account}</td>
                <td className="px-2 py-2.5 text-fg-3">{r.type}</td>
                <td className="px-2 py-2.5 font-medium text-ink">{r.description}</td>
                <td className="py-2.5 pl-2 text-right"><Num tone={dirTone(r.amount)} className="text-[12.5px]">{fmtKrw(r.amount)}</Num></td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={5} className="py-6 text-center text-[12px] text-fg-faint">내역이 없습니다.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}
