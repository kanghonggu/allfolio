// components/esg-screening/ViolationsTable.tsx
import Badge from '@/components/ui/Badge'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import SectionHeader from '@/components/ui/SectionHeader'
import type { EsgViolation } from '@/types/esg-screening'
import { fmtKrw } from '@/lib/report-format'

export function ViolationsTable({ rows }: { rows: EsgViolation[] }) {
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="배제 위반 내역" />
      {rows.length === 0 ? (
        <div className="border-t-[1.5px] border-ink py-8 text-center text-[12.5px] text-ok">
          배제 위반 없음
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[680px] border-t-[1.5px] border-ink text-[13px]">
            <thead>
              <tr className="border-b border-line text-left">
                <th className="py-2 pr-2 font-normal"><Label size="sm" tone="faint">종목</Label></th>
                <th className="px-2 py-2 font-normal"><Label size="sm" tone="faint">배제 리스트</Label></th>
                <th className="px-2 py-2 font-normal"><Label size="sm" tone="faint">사유</Label></th>
                <th className="px-2 py-2 font-normal"><Label size="sm" tone="faint">편입일</Label></th>
                <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">평가액</Label></th>
                <th className="py-2 pl-2 text-right font-normal"><Label size="sm" tone="faint">비중</Label></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={`${r.symbol}-${r.name}-${i}`} className="border-b border-line-hair">
                  <td className="py-2.5 pr-2">
                    <span className="font-medium text-ink">{r.name}</span>
                    {r.symbol && <span className="ml-2 font-mono text-[10px] text-fg-faint">{r.symbol}</span>}
                  </td>
                  <td className="px-2 py-2.5 text-fg-3">{r.listName}</td>
                  <td className="px-2 py-2.5"><Badge variant="danger">{r.reason}</Badge></td>
                  <td className="px-2 py-2.5">
                    <Num className="text-[12px] text-fg-3">{r.firstBuyDate ?? '-'}</Num>
                    {r.sinceListed && <Badge variant="muted" className="ml-1.5">{r.sinceListed}</Badge>}
                  </td>
                  <td className="px-2 py-2.5 text-right"><Num className="text-[12.5px] text-danger">{fmtKrw(r.valueKrw)}</Num></td>
                  <td className="py-2.5 pl-2 text-right"><Num className="text-[12.5px] text-fg-3">{r.weight.toFixed(2)}%</Num></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
