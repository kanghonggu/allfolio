// components/dividend-report/BySymbolTable.tsx
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import SectionHeader from '@/components/ui/SectionHeader'
import type { DividendBySymbol } from '@/types/dividend-report'
import { fmtKrw } from '@/lib/report-format'

export function BySymbolTable({ rows }: { rows: DividendBySymbol[] }) {
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="종목별 집계" />
      <div className="overflow-x-auto">
        <table className="w-full min-w-[560px] border-t-[1.5px] border-ink text-[13px]">
          <thead>
            <tr className="border-b border-line text-left">
              <th className="py-2 pr-2 font-normal"><Label size="sm" tone="faint">종목</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">세전</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">원천징수</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">세후</Label></th>
              <th className="py-2 pl-2 text-right font-normal"><Label size="sm" tone="faint">비중</Label></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={`${r.symbol}-${r.stockName}-${i}`} className="border-b border-line-hair">
                <td className="py-2.5 pr-2">
                  <span className="font-medium text-ink">{r.stockName}</span>
                  {r.symbol && <span className="ml-2 font-mono text-[10px] text-fg-faint">{r.symbol}</span>}
                </td>
                <td className="px-2 py-2.5 text-right"><Num className="text-[12.5px]">{fmtKrw(r.gross)}</Num></td>
                <td className="px-2 py-2.5 text-right"><Num className="text-[12.5px] text-fg-3">{fmtKrw(r.tax)}</Num></td>
                <td className="px-2 py-2.5 text-right"><Num className="text-[12.5px]">{fmtKrw(r.net)}</Num></td>
                <td className="py-2.5 pl-2 text-right"><Num className="text-[12.5px] text-fg-3">{r.weight.toFixed(2)}%</Num></td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={5} className="py-6 text-center text-[12px] text-fg-faint">종목이 없습니다.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}
