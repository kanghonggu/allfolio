'use client'

import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import { won, signWon, signPct, wonPlain, dirTone } from '@/lib/format'

interface NetWorthBarProps {
  total: number
  liquid: number
  illiquid: number
  debt: number
  // null = 30일 전 비교 기준 없음
  change30d: number | null
  changeRate30d: number | null
  /** 기간 내 순 외부 입출금 — 0이 아니면 숫자의 근거를 밝힌다 (AF-95) */
  netFlow30d?: number | null
  currency?: string
}

export default function NetWorthBar({
  total, liquid, illiquid, debt, change30d, changeRate30d, netFlow30d,
}: NetWorthBarProps) {
  // QA: 비교 기준 스냅샷이 없으면 0이 아니라 '비교 데이터 없음'으로 표기
  const hasBaseline = change30d !== null && changeRate30d !== null
  // AF-95: 이 값은 순자산 총변화가 아니라 입출금을 뺀 투자손익이다. 입출금이 있었다면
  // 화면 숫자와 실제 잔고 증감이 다르게 보이므로, 왜 다른지를 같이 적는다.
  const hadFlows = netFlow30d != null && netFlow30d !== 0

  return (
    <div className="border-b border-line px-5 py-5 sm:px-7">
      <Label size="sm">총 순자산 (Net Worth) · KRW</Label>
      <Num className="mt-1.5 block text-[32px] font-medium leading-[1.1] sm:text-[42px]">
        {won(total)}
      </Num>
      {hasBaseline ? (
        <div className="mt-2 flex flex-wrap items-baseline gap-x-3 gap-y-1">
          <Num tone={dirTone(change30d)} className="text-[13px]">
            {signWon(change30d)}
          </Num>
          <Num tone={dirTone(change30d)} className="text-[13px]">
            {signPct(changeRate30d)}
          </Num>
          <Label size="sm" tone="faint">30일 투자손익</Label>
          {hadFlows && (
            <span title="입출금은 수익이 아니므로 손익에서 제외합니다">
              <Label size="sm" tone="faint">입출금 {signWon(netFlow30d!)} 제외</Label>
            </span>
          )}
        </div>
      ) : (
        <p className="mt-2 text-[13px] text-fg-faint">30일 전 비교 데이터 없음</p>
      )}

      <div className="mt-4 flex flex-wrap gap-x-8 gap-y-3 border-t border-line-hair pt-3.5">
        <div>
          <Label size="sm" tone="faint">투자자산</Label>
          <Num className="mt-0.5 block text-sm">{wonPlain(liquid)}</Num>
        </div>
        <div>
          <Label size="sm" tone="faint">실물·고정자산</Label>
          <Num className="mt-0.5 block text-sm">{wonPlain(illiquid)}</Num>
        </div>
        <div>
          <Label size="sm" tone="faint">부채</Label>
          <Num tone={debt > 0 ? 'loss' : 'flat'} className="mt-0.5 block text-sm">
            {debt > 0 ? `−${wonPlain(debt)}` : '0'}
          </Num>
        </div>
      </div>
    </div>
  )
}
