// components/dividend-report/DividendCalendar.tsx
import Badge from '@/components/ui/Badge'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import SectionHeader from '@/components/ui/SectionHeader'
import type { DividendCalendarEntry } from '@/types/dividend-report'
import { fmtKrw } from '@/lib/report-format'

const MONTHS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]

export function DividendCalendar({ rows }: { rows: DividendCalendarEntry[] }) {
  if (!rows || rows.length === 0) return null
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="배당 지급 캘린더" note="최근 12개월 지급 이력 기반 패턴 · 향후 지급을 보장·예측하지 않음" />
      <div className="overflow-x-auto">
        <table className="w-full min-w-[720px] border-t-[1.5px] border-ink text-[13px]">
          <thead>
            <tr className="border-b border-line text-left">
              <th className="py-2 pr-2 font-normal"><Label size="sm" tone="faint">종목</Label></th>
              <th className="px-2 py-2 font-normal"><Label size="sm" tone="faint">주기</Label></th>
              <th className="px-2 py-2 font-normal"><Label size="sm" tone="faint">지급 월</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">TTM 횟수</Label></th>
              <th className="px-2 py-2 font-normal"><Label size="sm" tone="faint">최근 지급일</Label></th>
              <th className="py-2 pl-2 text-right font-normal"><Label size="sm" tone="faint">TTM 순수취</Label></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={`${r.symbol ?? r.stockName}-${i}`} className="border-b border-line-hair">
                <td className="py-2.5 pr-2">
                  <span className="font-medium text-ink">{r.stockName}</span>
                  {r.symbol && <span className="ml-2 font-mono text-[10px] text-fg-faint">{r.symbol}</span>}
                </td>
                <td className="px-2 py-2.5"><Badge variant="ink">{r.cadence}</Badge></td>
                <td className="px-2 py-2.5">
                  <div className="flex gap-px">
                    {MONTHS.map((m) => (
                      <span
                        key={m}
                        className={`inline-block h-4 w-4 text-center font-mono text-[9px] leading-4 ${
                          r.paidMonths.includes(m)
                            ? 'bg-ink text-surface'
                            : 'bg-surface-muted text-fg-ghost'
                        }`}
                      >
                        {m}
                      </span>
                    ))}
                  </div>
                </td>
                <td className="px-2 py-2.5 text-right"><Num className="text-[12.5px] text-fg-3">{r.payCount}</Num></td>
                <td className="px-2 py-2.5"><Num className="text-[12px] text-fg-3">{r.lastPayDate}</Num></td>
                <td className="py-2.5 pl-2 text-right"><Num className="text-[12.5px]">{fmtKrw(r.ttmNet)}</Num></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}
