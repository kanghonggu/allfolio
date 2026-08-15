// components/market/RatePanel.tsx
'use client'

import type { RateView } from '@/types/market'
import { rateLabel, rateCountry } from '@/lib/market-labels'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import { EmptyState } from '@/components/ui/states'
import { dirTone } from '@/lib/format'
import { fixed } from '@/lib/market-format'

/**
 * 금리 값은 소수 4자리다 — 백엔드가 `2.7500`으로 실어 보내는데 `JSON.parse`가 뒤 0을 버려
 * `2.75 / 2.769 / 2.94 / 3.796`처럼 열이 어긋난 채 나갔다. 4자리로 되돌린다.
 * 변화폭(bp)은 백엔드가 스케일 2로 고정한다(MarketQueryService.rateViews) — 여기도 2다.
 */
const RATE_DIGITS = 4
const BP_DIGITS = 2

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
                <td className="py-1.5 text-right">
                  <Num className="text-[13px]">{fixed(r.value, RATE_DIGITS)}</Num>
                </td>
                <td className="py-1.5 text-right">
                  {/* **bp다(%p 아님).** 1%p = 100bp — `-0.01bp` 같은 값이 보이면 단위가 100배 틀린 것이다.
                      null은 비교할 직전 값이 없다는 뜻이라 0이 아니라 대시로 그린다.
                      **`r.changeBp ?`로 가르면 안 된다** — number라 0이 falsy여서
                      "안 움직였다"(0.00)가 "직전 값 없음"(대시)으로 둔갑한다 */}
                  {r.changeBp != null ? (
                    <Num tone={dirTone(r.changeBp)}>{fixed(r.changeBp, BP_DIGITS)}bp</Num>
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
  const gap = krBase && usBase ? krBase.value - usBase.value : null

  return (
    <div className="space-y-6">
      {gap != null && krBase && usBase && (
        <div className="border border-line-card p-4">
          <Label size="sm" tone="faint">한·미 기준금리차</Label>
          <div className="mt-1 flex flex-wrap items-baseline gap-2">
            <Num tone={dirTone(gap)} className="text-[18px]">{fixed(gap, 2)}%p</Num>
            {/* 여기 두 값도 표와 같은 4자리여야 한다 — 같은 화면에서 `2.75`와 `2.7500`이
                동시에 보이면 둘이 다른 값처럼 읽힌다 */}
            <span className="text-[11px] text-fg-2">
              한국 {fixed(krBase.value, RATE_DIGITS)} · 미국 {fixed(usBase.value, RATE_DIGITS)}
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
