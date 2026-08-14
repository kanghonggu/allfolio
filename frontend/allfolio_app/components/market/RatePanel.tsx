// components/market/RatePanel.tsx
'use client'

import type { RateView } from '@/types/market'
import { rateLabel, rateCountry } from '@/lib/market-labels'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import { EmptyState } from '@/components/ui/states'
import { dirTone } from '@/lib/format'

function Section({ title, rows }: { title: string; rows: RateView[] }) {
  // 빈 단은 아예 안 그린다 — 코드가 다 매핑돼 있으면 '기타'가 사라지는 게 정상이다
  if (rows.length === 0) return null
  return (
    <div>
      <Label size="sm" tone="faint">{title}</Label>
      <div className="mt-2 overflow-x-auto">
        <table className="w-full text-[12px]">
          <tbody>
            {rows.map((r) => (
              <tr key={r.code} className="border-b border-line-card/50">
                <td className="py-1.5">{rateLabel(r.code)}</td>
                <td className="py-1.5 text-right"><Num className="text-[13px]">{r.value}</Num></td>
                <td className="py-1.5 text-right">
                  {/* **bp다(%p 아님).** 1%p = 100bp — `-0.01bp` 같은 값이 보이면 단위가 100배 틀린 것이다.
                      null은 비교할 직전 값이 없다는 뜻이라 0이 아니라 대시로 그린다 */}
                  {r.changeBp ? (
                    <Num tone={dirTone(Number(r.changeBp))}>{r.changeBp}bp</Num>
                  ) : (
                    <span className="text-fg-faint">-</span>
                  )}
                </td>
                {/* **기준일을 항목마다 단다.** 기준금리 공표가 시장금리보다 이틀 늦은 게
                    실측으로 확인됐다 — 공통 헤더에 시각 하나를 두면 화면이 거짓말을 한다 */}
                <td className="py-1.5 text-right font-mono text-[10px] text-fg-faint">{r.quoteDate}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

export default function RatePanel({ rates }: { rates: RateView[] }) {
  // rates는 null이 아니라 빈 배열로 온다 — 계약이 그렇다(MarketSnapshot KDoc)
  if (rates.length === 0) return <EmptyState title="금리 데이터가 아직 없습니다" />

  const kr = rates.filter((r) => rateCountry(r.code) === 'KR')
  const us = rates.filter((r) => rateCountry(r.code) === 'US')
  const etc = rates.filter((r) => rateCountry(r.code) === 'ETC')

  // 한·미 기준금리차 — 저장하지 않고 여기서 만든다. 원본이 정정되면 파생값은
  // 같이 안 고쳐져 화석이 되기 때문이다(AF-102 판단)
  const krBase = kr.find((r) => r.code === 'BASE_RATE')
  const usBase = us.find((r) => r.code === 'US_FFR')
  const gap = krBase && usBase ? (Number(krBase.value) - Number(usBase.value)).toFixed(2) : null

  return (
    <div className="space-y-6">
      {gap && krBase && usBase && (
        <div className="border border-line-card p-4">
          <Label size="sm" tone="faint">한·미 기준금리차</Label>
          <div className="mt-1 flex flex-wrap items-baseline gap-2">
            <Num tone={dirTone(Number(gap))} className="text-[18px]">{gap}%p</Num>
            <span className="text-[11px] text-fg-2">
              한국 {krBase.value} · 미국 {usBase.value}
            </span>
          </div>
          {/* 두 기준일이 다르다 — 하나만 적으면 다른 쪽 값이 그 날짜 것으로 읽힌다 */}
          <p className="mt-1 text-[10px] text-fg-faint">
            기준일이 다를 수 있습니다 — 한국 {krBase.quoteDate} · 미국 {usBase.quoteDate}
          </p>
        </div>
      )}
      <Section title="한국 (한국은행)" rows={kr} />
      <Section title="미국 (FRED)" rows={us} />
      <Section title="기타" rows={etc} />
    </div>
  )
}
