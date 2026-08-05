import Badge from '@/components/ui/Badge'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import SectionHeader from '@/components/ui/SectionHeader'
import { dirTone } from '@/lib/format'
import { fmtKrw } from '@/lib/report-format'
import type { CashflowSpecialTransactions } from '@/types/cashflow-report'

export function SpecialTransactions({ data }: { data: CashflowSpecialTransactions }) {
  const empty = data.largeMovements.length === 0 && data.unclassified.length === 0
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="특이거래" />
      {empty ? (
        <div className="border-t-[1.5px] border-ink py-8 text-center text-[12px] text-fg-faint">특이거래 없음</div>
      ) : (
        <div className="space-y-5">
          {data.largeMovements.length > 0 && (
            <div className="border-t-[1.5px] border-ink pt-2">
              <div className="mb-1 flex items-baseline gap-2">
                <span className="font-mono text-[9px] uppercase tracking-label text-warn">대규모 이동</span>
                <Label size="sm" tone="ghost">자산 {Math.round(data.thresholdRatio * 100)}% 이상</Label>
              </div>
              <table className="w-full text-[13px]">
                <tbody>
                  {data.largeMovements.map((m, i) => (
                    <tr key={`${m.date}-${m.type}-${i}`} className="border-b border-line-hair">
                      <td className="py-2 pr-2"><Num className="text-[12px] text-fg-3">{m.date}</Num></td>
                      <td className="px-2 py-2 text-fg-2">{m.type}</td>
                      <td className="px-2 py-2 text-fg-muted">{m.account}</td>
                      <td className="px-2 py-2 text-fg-muted">{m.description}</td>
                      <td className="py-2 pl-2 text-right"><Num tone={dirTone(m.amountKrw)} className="text-[12.5px]">{fmtKrw(m.amountKrw)}</Num></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {data.unclassified.length > 0 && (
            <div className="border-t-[1.5px] border-ink pt-2">
              <div className="mb-1 flex items-baseline gap-2">
                <Label size="sm" tone="muted">미분류 흐름</Label>
                <Label size="sm" tone="ghost">유형 매핑 실패</Label>
              </div>
              <table className="w-full text-[13px]">
                <tbody>
                  {data.unclassified.map((u, i) => (
                    <tr key={`${u.date}-${u.tradeType}-${i}`} className="border-b border-line-hair">
                      <td className="py-2 pr-2"><Num className="text-[12px] text-fg-3">{u.date}</Num></td>
                      <td className="px-2 py-2"><Badge variant="ink">{u.tradeType}</Badge></td>
                      <td className="px-2 py-2 text-fg-muted">{u.account}</td>
                      <td className="py-2 pl-2 text-right"><Num className="text-[12.5px] text-fg-2">{fmtKrw(u.amountKrw)}</Num></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </section>
  )
}
