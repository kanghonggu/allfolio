// components/dividend-report/ReceiptsTable.tsx
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import SectionHeader from '@/components/ui/SectionHeader'
import type { DividendReceipt } from '@/types/dividend-report'
import { fmtKrw } from '@/lib/report-format'

export function ReceiptsTable({ receipts }: { receipts: DividendReceipt[] }) {
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="수취 내역" />
      <div className="overflow-x-auto">
        <table className="w-full min-w-[640px] border-t-[1.5px] border-ink text-[13px]">
          <thead>
            <tr className="border-b border-line text-left">
              <th className="py-2 pr-2 font-normal"><Label size="sm" tone="faint">지급일</Label></th>
              <th className="px-2 py-2 font-normal"><Label size="sm" tone="faint">종목</Label></th>
              <th className="px-2 py-2 font-normal"><Label size="sm" tone="faint">계좌</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">세전</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">원천징수</Label></th>
              <th className="py-2 pl-2 text-right font-normal"><Label size="sm" tone="faint">세후</Label></th>
            </tr>
          </thead>
          <tbody>
            {receipts.map((r, i) => (
              <tr key={`${r.payDate}-${r.symbol}-${i}`} className="border-b border-line-hair">
                <td className="py-2.5 pr-2"><Num className="text-[12px] text-fg-3">{r.payDate}</Num></td>
                <td className="px-2 py-2.5">
                  <span className="font-medium text-ink">{r.stockName}</span>
                  {r.symbol && <span className="ml-2 font-mono text-[10px] text-fg-faint">{r.symbol}</span>}
                </td>
                <td className="px-2 py-2.5 text-fg-3">{r.account}</td>
                <td className="px-2 py-2.5 text-right"><Num className="text-[12.5px]">{fmtKrw(r.gross)}</Num></td>
                <td className="px-2 py-2.5 text-right"><Num className="text-[12.5px] text-fg-3">{fmtKrw(r.tax)}</Num></td>
                <td className="py-2.5 pl-2 text-right"><Num className="text-[12.5px]">{fmtKrw(r.net)}</Num></td>
              </tr>
            ))}
            {receipts.length === 0 && (
              <tr><td colSpan={6} className="py-6 text-center text-[12px] text-fg-faint">수취 내역이 없습니다.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}
