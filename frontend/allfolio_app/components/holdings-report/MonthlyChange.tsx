import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import SectionHeader from '@/components/ui/SectionHeader'
import { dirTone } from '@/lib/format'
import { fmtKrw } from '@/lib/report-format'
import type { HoldingMonthlyChange } from '@/types/holdings-report'

export function MonthlyChange({ data }: { data: HoldingMonthlyChange }) {
  const empty = data.newEntries.length === 0 && data.soldOut.length === 0 && data.qtyChanges.length === 0
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="월간 변동" />
      {empty ? (
        <div className="border-t-[1.5px] border-ink py-8 text-center text-[12px] text-fg-faint">당월 변동 없음</div>
      ) : (
        <div className="grid gap-4 lg:grid-cols-3">
          <div className="border-t-[1.5px] border-ink pt-2">
            <Label size="sm" tone="muted" className="mb-1 block">신규 편입</Label>
            {data.newEntries.length === 0 ? <p className="text-[11px] text-fg-ghost">-</p> : data.newEntries.map((e) => (
              <div key={e.symbol} className="flex justify-between border-b border-line-hair py-1.5 text-xs">
                <span className="text-fg-2">{e.name} <span className="font-mono text-[10px] text-fg-faint">{e.symbol}</span></span>
                <Num className="text-[11.5px] text-fg-3">{e.firstBuyDate}</Num>
              </div>
            ))}
          </div>
          <div className="border-t-[1.5px] border-ink pt-2">
            <Label size="sm" tone="muted" className="mb-1 block">전량 매도</Label>
            {data.soldOut.length === 0 ? <p className="text-[11px] text-fg-ghost">-</p> : data.soldOut.map((e) => (
              <div key={e.symbol} className="flex justify-between border-b border-line-hair py-1.5 text-xs">
                <span className="text-fg-2">{e.name} <span className="font-mono text-[10px] text-fg-faint">{e.symbol}</span></span>
                <Num tone={dirTone(e.realizedPnl)} className="text-[11.5px]">{fmtKrw(e.realizedPnl)}</Num>
              </div>
            ))}
          </div>
          <div className="border-t-[1.5px] border-ink pt-2">
            <Label size="sm" tone="muted" className="mb-1 block">수량 변동</Label>
            {data.qtyChanges.length === 0 ? <p className="text-[11px] text-fg-ghost">-</p> : data.qtyChanges.map((e) => (
              <div key={e.symbol} className="flex justify-between border-b border-line-hair py-1.5 text-xs">
                <span className="text-fg-2">{e.name} <span className="font-mono text-[10px] text-fg-faint">{e.symbol}</span></span>
                <Num tone={dirTone(e.netBuyAmount)} className="text-[11.5px]">{e.netQty > 0 ? '+' : ''}{e.netQty} · {fmtKrw(e.netBuyAmount)}</Num>
              </div>
            ))}
          </div>
        </div>
      )}
    </section>
  )
}
