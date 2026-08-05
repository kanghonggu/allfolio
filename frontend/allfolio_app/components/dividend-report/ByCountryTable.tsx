// components/dividend-report/ByCountryTable.tsx
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import SectionHeader from '@/components/ui/SectionHeader'
import type { DividendByCountry } from '@/types/dividend-report'
import { fmtKrw } from '@/lib/report-format'

export function ByCountryTable({ rows }: { rows: DividendByCountry[] }) {
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="국가별 원천징수 요약" note="기대세율은 국내(KR) 기준율 대비 · 해외는 국가 판별 불가로 생략" />
      <div className="overflow-x-auto">
        <table className="w-full min-w-[680px] border-t-[1.5px] border-ink text-[13px]">
          <thead>
            <tr className="border-b border-line text-left">
              <th className="py-2 pr-2 font-normal"><Label size="sm" tone="faint">국가</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">세전</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">원천징수</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">세후</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">실효세율</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">기대세율</Label></th>
              <th className="py-2 pl-2 text-right font-normal"><Label size="sm" tone="faint">편차(%p)</Label></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.country} className="border-b border-line-hair">
                <td className="py-2.5 pr-2 font-medium text-ink">{r.country}</td>
                <td className="px-2 py-2.5 text-right"><Num className="text-[12.5px]">{fmtKrw(r.gross)}</Num></td>
                <td className="px-2 py-2.5 text-right"><Num className="text-[12.5px] text-fg-3">{fmtKrw(r.tax)}</Num></td>
                <td className="px-2 py-2.5 text-right"><Num className="text-[12.5px]">{fmtKrw(r.net)}</Num></td>
                <td className="px-2 py-2.5 text-right"><Num className="text-[12.5px] text-fg-3">{r.effectiveTaxRate.toFixed(2)}%</Num></td>
                <td className="px-2 py-2.5 text-right">
                  <Num className="text-[12.5px] text-fg-3">
                    {r.expectedTaxRate == null ? '–' : `${r.expectedTaxRate.toFixed(2)}%`}
                  </Num>
                </td>
                <td className="py-2.5 pl-2 text-right">
                  <Num className={`text-[12.5px] ${r.taxFlagged ? 'text-warn' : 'text-fg-3'}`}>
                    {r.expectedTaxRate == null || r.taxDeviationPp == null
                      ? '–'
                      : `${r.taxDeviationPp.toFixed(2)}%p`}
                  </Num>
                </td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={7} className="py-6 text-center text-[12px] text-fg-faint">데이터가 없습니다.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}
