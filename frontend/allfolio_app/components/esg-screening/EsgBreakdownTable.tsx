// components/esg-screening/EsgBreakdownTable.tsx
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import SectionHeader from '@/components/ui/SectionHeader'
import type { EsgBreakdownRow } from '@/types/esg-screening'

function ratingColor(rating: string): string {
  if (rating.startsWith('A')) return 'text-ok'
  if (rating.startsWith('B')) return 'text-warn'
  if (rating.startsWith('C')) return 'text-danger'
  return 'text-ink'
}

export function EsgBreakdownTable({ rows }: { rows: EsgBreakdownRow[] }) {
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="종목별 ESG" />
      <div className="overflow-x-auto">
        <table className="w-full min-w-[640px] border-t-[1.5px] border-ink text-[13px]">
          <thead>
            <tr className="border-b border-line text-left">
              <th className="py-2 pr-2 font-normal"><Label size="sm" tone="faint">종목</Label></th>
              <th className="px-2 py-2 font-normal"><Label size="sm" tone="faint">유형</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">비중</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">E</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">S</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">G</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">종합</Label></th>
              <th className="py-2 pl-2 font-normal"><Label size="sm" tone="faint">등급</Label></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={`${r.name}-${i}`} className="border-b border-line-hair">
                <td className="py-2.5 pr-2 font-medium text-ink">{r.name}</td>
                <td className="px-2 py-2.5 text-fg-3">{r.type}</td>
                <td className="px-2 py-2.5 text-right"><Num className="text-[12.5px] text-fg-3">{r.weight.toFixed(2)}%</Num></td>
                <td className="px-2 py-2.5 text-right"><Num className="text-[12.5px] text-fg-3">{r.e}</Num></td>
                <td className="px-2 py-2.5 text-right"><Num className="text-[12.5px] text-fg-3">{r.s}</Num></td>
                <td className="px-2 py-2.5 text-right"><Num className="text-[12.5px] text-fg-3">{r.g}</Num></td>
                <td className="px-2 py-2.5 text-right"><Num className="text-[12.5px] font-medium text-ink">{r.total.toFixed(1)}</Num></td>
                <td className={`py-2.5 pl-2 font-mono text-[12px] font-medium ${ratingColor(r.rating)}`}>{r.rating}</td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={8} className="py-6 text-center text-[12px] text-fg-faint">데이터가 없습니다.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}
