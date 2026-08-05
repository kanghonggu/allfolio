// components/cashflow-report/InternalFlows.tsx
import Badge from '@/components/ui/Badge'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import SectionHeader from '@/components/ui/SectionHeader'
import { fmtKrw } from '@/lib/report-format'
import type { CashflowInternalFlow } from '@/types/cashflow-report'

export function InternalFlows({ rows }: { rows: CashflowInternalFlow[] }) {
  if (!rows || rows.length === 0) return null
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="환전·계좌간이체" note="내부이동은 외부 유입/유출에서 제외" />
      <div className="overflow-x-auto">
        <table className="w-full min-w-[640px] border-t-[1.5px] border-ink text-[13px]">
          <thead>
            <tr className="border-b border-line text-left">
              <th className="py-2 pr-2 font-normal"><Label size="sm" tone="faint">날짜</Label></th>
              <th className="px-2 py-2 font-normal"><Label size="sm" tone="faint">유형</Label></th>
              <th className="px-2 py-2 font-normal"><Label size="sm" tone="faint">내용</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">금액(KRW)</Label></th>
              <th className="py-2 pl-2 text-right font-normal"><Label size="sm" tone="faint">전환비용</Label></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={`${r.date}-${i}`} className="border-b border-line-hair">
                <td className="py-2.5 pr-2"><Num className="text-[12px] text-fg-3">{r.date}</Num></td>
                <td className="px-2 py-2.5"><Badge variant="ink">{r.kind}</Badge></td>
                <td className="px-2 py-2.5 text-fg-2">
                  {r.kind === '환전'
                    ? `${r.fromCurrency} ${r.fromAmount?.toLocaleString()} → ${r.toCurrency} ${r.toAmount?.toLocaleString()}`
                    : `${r.fromAccount} → ${r.toAccount}`}
                </td>
                <td className="px-2 py-2.5 text-right"><Num className="text-[12.5px]">{fmtKrw(r.amountKrw)}</Num></td>
                <td className="py-2.5 pl-2 text-right">
                  <Num className="text-[12.5px] text-fg-3">
                    {r.kind === '환전' && r.spreadKrw != null && r.spreadKrw !== 0 ? fmtKrw(r.spreadKrw) : '–'}
                  </Num>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}
