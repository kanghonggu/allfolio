import Badge, { type BadgeVariant } from '@/components/ui/Badge'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import SectionHeader from '@/components/ui/SectionHeader'
import type { EsgViolationEvent } from '@/types/esg-screening'

const EVENT_VARIANT: Record<string, BadgeVariant> = {
  편입: 'danger',
  청산: 'muted',
  리스트등록: 'warn',
}

export function ViolationHistory({ events }: { events: EsgViolationEvent[] }) {
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="위반 이력" />
      {events.length === 0 ? (
        <div className="border-t-[1.5px] border-ink py-8 text-center text-[12px] text-fg-faint">
          위반 이력 없음
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[560px] border-t-[1.5px] border-ink text-[13px]">
            <thead>
              <tr className="border-b border-line text-left">
                <th className="py-2 pr-2 font-normal"><Label size="sm" tone="faint">일자</Label></th>
                <th className="px-2 py-2 font-normal"><Label size="sm" tone="faint">종목</Label></th>
                <th className="px-2 py-2 font-normal"><Label size="sm" tone="faint">이벤트</Label></th>
                <th className="py-2 pl-2 font-normal"><Label size="sm" tone="faint">비고</Label></th>
              </tr>
            </thead>
            <tbody>
              {events.map((e, i) => (
                <tr key={`${e.symbol}-${e.date}-${e.event}-${i}`} className="border-b border-line-hair">
                  <td className="py-2.5 pr-2"><Num className="text-[12px] text-fg-3">{e.date}</Num></td>
                  <td className="px-2 py-2.5">
                    <span className="text-ink">{e.name}</span>
                    <span className="ml-2 font-mono text-[10px] text-fg-faint">{e.symbol}</span>
                  </td>
                  <td className="px-2 py-2.5">
                    <Badge variant={EVENT_VARIANT[e.event] ?? 'muted'}>{e.event}</Badge>
                  </td>
                  <td className="py-2.5 pl-2 text-fg-muted">{e.note}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
