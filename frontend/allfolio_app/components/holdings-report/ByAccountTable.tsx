// components/holdings-report/ByAccountTable.tsx
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import SectionHeader from '@/components/ui/SectionHeader'
import type { HoldingByAccount } from '@/types/holdings-report'
import { fmtKrw } from '@/lib/report-format'

export function ByAccountTable({ rows }: { rows: HoldingByAccount[] }) {
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="계좌별 소계" />
      <div className="overflow-x-auto">
        <table className="w-full min-w-[560px] border-t-[1.5px] border-ink text-[13px]">
          <thead>
            <tr className="border-b border-line text-left">
              <th className="py-2 pr-2 font-normal"><Label size="sm" tone="faint">계좌</Label></th>
              <th className="px-2 py-2 font-normal"><Label size="sm" tone="faint">증권사</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">평가액</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">비중</Label></th>
              <th className="py-2 pl-2 text-right font-normal"><Label size="sm" tone="faint">종목수</Label></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={`${r.provider}-${r.account}`} className="border-b border-line-hair">
                <td className="py-2.5 pr-2 font-medium text-ink">{r.account}</td>
                <td className="px-2 py-2.5 text-fg-3">{r.provider}</td>
                <td className="px-2 py-2.5 text-right"><Num className="text-[12.5px]">{fmtKrw(r.valueKrw)}</Num></td>
                <td className="px-2 py-2.5 text-right"><Num className="text-[12.5px] text-fg-3">{r.weight.toFixed(2)}%</Num></td>
                <td className="py-2.5 pl-2 text-right"><Num className="text-[12.5px] text-fg-3">{r.holdingCount}</Num></td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={5} className="py-6 text-center text-[12px] text-fg-faint">데이터가 없습니다.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}
