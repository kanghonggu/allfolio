// components/holdings-report/RegionExposure.tsx
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import SectionHeader from '@/components/ui/SectionHeader'
import type { HoldingsByRegion } from '@/types/holdings-report'
import { fmtKrw } from '@/lib/report-format'

export function RegionExposure({ rows }: { rows: HoldingsByRegion[] }) {
  if (!rows || rows.length === 0) return null
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="지역 노출" note="통화 기준 추정 지역 (자산 국가·거래소 데이터 부재)" />
      <div className="overflow-x-auto">
        <table className="w-full min-w-[480px] border-t-[1.5px] border-ink text-[13px]">
          <thead>
            <tr className="border-b border-line text-left">
              <th className="py-2 pr-2 font-normal"><Label size="sm" tone="faint">지역</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">평가액</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">비중</Label></th>
              <th className="py-2 pl-2 text-right font-normal"><Label size="sm" tone="faint">종목수</Label></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.region} className="border-b border-line-hair">
                <td className="py-2.5 pr-2 font-medium text-ink">{r.region}</td>
                <td className="px-2 py-2.5 text-right"><Num className="text-[12.5px]">{fmtKrw(r.valueKrw)}</Num></td>
                <td className="px-2 py-2.5 text-right"><Num className="text-[12.5px] text-fg-3">{r.weight.toFixed(2)}%</Num></td>
                <td className="py-2.5 pl-2 text-right"><Num className="text-[12.5px] text-fg-3">{r.holdingCount}</Num></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}
