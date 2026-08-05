// components/cost-report/ByBrokerMatrix.tsx
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import SectionHeader from '@/components/ui/SectionHeader'
import type { CostByBroker } from '@/types/cost-report'
import { fmtKrw } from '@/lib/report-format'

export function ByBrokerMatrix({ rows }: { rows: CostByBroker[] }) {
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="브로커×유형 매트릭스" />
      <div className="overflow-x-auto">
        <table className="w-full min-w-[560px] border-t-[1.5px] border-ink text-[13px]">
          <thead>
            <tr className="border-b border-line text-left">
              <th className="py-2 pr-2 font-normal"><Label size="sm" tone="faint">브로커</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">매매수수료</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">거래세</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">합계</Label></th>
              <th className="py-2 pl-2 text-right font-normal"><Label size="sm" tone="faint">비중</Label></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.broker} className="border-b border-line-hair">
                <td className="py-2.5 pr-2 font-medium text-ink">{r.broker}</td>
                <td className="px-2 py-2.5 text-right"><Num className="text-[12.5px]">{fmtKrw(r.fee)}</Num></td>
                <td className="px-2 py-2.5 text-right"><Num className="text-[12.5px] text-fg-3">{fmtKrw(r.tax)}</Num></td>
                <td className="px-2 py-2.5 text-right"><Num className="text-[12.5px]">{fmtKrw(r.total)}</Num></td>
                <td className="py-2.5 pl-2 text-right"><Num className="text-[12.5px] text-fg-3">{r.weight.toFixed(2)}%</Num></td>
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
