// components/holdings-report/HoldingsGrid.tsx
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import SectionHeader from '@/components/ui/SectionHeader'
import { dirTone } from '@/lib/format'
import type { Holding } from '@/types/holdings-report'
import { fmtKrw, fmtPctScaled } from '@/lib/report-format'
// 주의: weight·returnRate는 백엔드에서 이미 0~100 스케일 → fmtPct(×100) 금지

function num(n: number) {
  return n.toLocaleString('ko-KR', { maximumFractionDigits: 8 })
}

export function HoldingsGrid({ holdings }: { holdings: Holding[] }) {
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="보유 명세" />
      <div className="overflow-x-auto">
        <table className="w-full min-w-[960px] border-t-[1.5px] border-ink text-[13px]">
          <thead>
            <tr className="border-b border-line text-left">
              <th className="py-2 pr-2 font-normal"><Label size="sm" tone="faint">종목</Label></th>
              <th className="px-2 py-2 font-normal"><Label size="sm" tone="faint">자산군</Label></th>
              <th className="px-2 py-2 font-normal"><Label size="sm" tone="faint">계좌</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">수량</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">평단</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">평가액</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">비중</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">평가손익</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">수익률</Label></th>
              <th className="py-2 pl-2 text-right font-normal"><Label size="sm" tone="faint">당월 실현손익</Label></th>
            </tr>
          </thead>
          <tbody>
            {holdings.map((h, i) => (
              <tr key={`${h.symbol}-${h.name}-${i}`} className="border-b border-line-hair">
                <td className="py-2.5 pr-2">
                  <span className="font-medium text-ink">{h.name}</span>
                  {h.symbol && <span className="ml-2 font-mono text-[10px] text-fg-faint">{h.symbol}</span>}
                </td>
                <td className="px-2 py-2.5 text-fg-3">{h.type}</td>
                <td className="px-2 py-2.5 text-fg-3">{h.account}</td>
                <td className="px-2 py-2.5 text-right"><Num className="text-[12.5px] text-fg-3">{num(h.quantity)}</Num></td>
                <td className="px-2 py-2.5 text-right"><Num className="text-[12.5px] text-fg-3">{num(h.avgPrice)}</Num></td>
                <td className="px-2 py-2.5 text-right">
                  <Num className="text-[12.5px]">{fmtKrw(h.valueKrw)}</Num>
                  <Num className="ml-1 text-[10.5px] text-fg-faint">({num(h.currentValue)})</Num>
                </td>
                <td className="px-2 py-2.5 text-right"><Num className="text-[12.5px] text-fg-3">{h.weight.toFixed(2)}%</Num></td>
                <td className="px-2 py-2.5 text-right"><Num tone={dirTone(h.unrealizedPnl)} className="text-[12.5px]">{fmtKrw(h.unrealizedPnl)}</Num></td>
                <td className="px-2 py-2.5 text-right"><Num tone={dirTone(h.returnRate)} className="text-[12.5px]">{fmtPctScaled(h.returnRate)}</Num></td>
                <td className="py-2.5 pl-2 text-right"><Num tone={dirTone(h.realizedPnl)} className="text-[12.5px]">{fmtKrw(h.realizedPnl)}</Num></td>
              </tr>
            ))}
            {holdings.length === 0 && (
              <tr><td colSpan={10} className="py-6 text-center text-[12px] text-fg-faint">보유 종목이 없습니다.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}
