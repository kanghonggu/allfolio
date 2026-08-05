// components/monthly-report/TopHoldingsTable.tsx
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import SectionHeader from '@/components/ui/SectionHeader'
import { dirTone } from '@/lib/format'
import type { Holding } from '@/types/monthly-report'
import { fmtKrw, fmtPctScaled } from '@/lib/report-format'
// 주의: returnRate·weight는 백엔드에서 이미 0~100으로 스케일됨 → fmtPct(×100) 금지

export function TopHoldingsTable({ holdings }: { holdings: Holding[] }) {
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="상위 보유 종목 (Top 10)" />
      <div className="overflow-x-auto">
        <table className="w-full min-w-[640px] border-t-[1.5px] border-ink text-[13px]">
          <thead>
            <tr className="border-b border-line text-left">
              <th className="py-2 pr-2 font-normal"><Label size="sm" tone="faint">종목</Label></th>
              <th className="px-2 py-2 font-normal"><Label size="sm" tone="faint">유형</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">수량</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">평가액</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">비중</Label></th>
              <th className="py-2 pl-2 text-right font-normal"><Label size="sm" tone="faint">수익률</Label></th>
            </tr>
          </thead>
          <tbody>
            {holdings.map((h) => (
              <tr key={`${h.symbol}-${h.name}`} className="border-b border-line-hair">
                <td className="py-2.5 pr-2">
                  <span className="font-medium text-ink">{h.name}</span>
                  <span className="ml-2 font-mono text-[10px] text-fg-faint">{h.symbol}</span>
                </td>
                <td className="px-2 py-2.5 text-fg-3">{h.type}</td>
                <td className="px-2 py-2.5 text-right"><Num className="text-[12.5px] text-fg-3">{h.quantity.toLocaleString('ko-KR', { maximumFractionDigits: 8 })}</Num></td>
                <td className="px-2 py-2.5 text-right"><Num className="text-[12.5px]">{fmtKrw(h.valueKrw)}</Num></td>
                <td className="px-2 py-2.5 text-right"><Num className="text-[12.5px] text-fg-3">{h.weight.toFixed(2)}%</Num></td>
                <td className="py-2.5 pl-2 text-right"><Num tone={dirTone(h.returnRate)} className="text-[12.5px]">{fmtPctScaled(h.returnRate)}</Num></td>
              </tr>
            ))}
            {holdings.length === 0 && (
              <tr><td colSpan={6} className="py-6 text-center text-[12px] text-fg-faint">보유 종목이 없습니다.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}
