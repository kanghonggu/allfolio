// components/cost-report/ByTypeTable.tsx
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import SectionHeader from '@/components/ui/SectionHeader'
import type { CostByType } from '@/types/cost-report'
import { fmtKrw } from '@/lib/report-format'

export function ByTypeTable({ rows }: { rows: CostByType[] }) {
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="유형별 비용" />
      <div className="overflow-x-auto">
        <table className="w-full min-w-[400px] border-t-[1.5px] border-ink text-[13px]">
          <thead>
            <tr className="border-b border-line text-left">
              <th className="py-2 pr-2 font-normal"><Label size="sm" tone="faint">유형</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">금액</Label></th>
              <th className="py-2 pl-2 text-right font-normal"><Label size="sm" tone="faint">비중</Label></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.type} className="border-b border-line-hair">
                <td className="py-2.5 pr-2 font-medium text-ink">{r.type}</td>
                <td className="px-2 py-2.5 text-right"><Num className="text-[12.5px]">{fmtKrw(r.amount)}</Num></td>
                <td className="py-2.5 pl-2 text-right"><Num className="text-[12.5px] text-fg-3">{r.weight.toFixed(2)}%</Num></td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={3} className="py-6 text-center text-[12px] text-fg-faint">데이터가 없습니다.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}
