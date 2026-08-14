// components/market/IndexCards.tsx
'use client'

import type { IndexQuoteView } from '@/types/market'
import { indexLabel } from '@/lib/market-labels'
import Num from '@/components/ui/Num'
import Badge from '@/components/ui/Badge'
import { EmptyState } from '@/components/ui/states'
import { dirTone } from '@/lib/format'

/**
 * 지수 카드 목록 (국내·해외 공용).
 *
 * **호출부가 null을 걸러서 넘긴다.** `null`은 재배포 플래그 off라 탭 자체가 없다는 뜻이고,
 * `[]`는 켜져 있는데 데이터가 없다는 뜻이다. 여기서 `?? []`로 합치면 킬 스위치가 화면상 무력해진다.
 */
export default function IndexCards({ quotes }: { quotes: IndexQuoteView[] }) {
  if (quotes.length === 0) return <EmptyState title="지수 데이터가 아직 없습니다" />

  return (
    <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
      {quotes.map((q) => (
        <div key={q.code} className="border border-line-card p-4">
          <div className="flex items-baseline justify-between gap-2">
            {/* 라벨이 없으면 코드가 그대로 나온다 — 빈칸이면 종목이 사라진 것처럼 보인다 */}
            <span className="text-[13px]">{indexLabel(q.code)}</span>
            {/* 장 상태가 없으면 한국 낮에 미국 지수가 안 움직이는 걸 보고 고장으로 오해한다 */}
            <Badge>{q.marketStatus}</Badge>
          </div>
          <div className="mt-2">
            <Num className="text-[20px]">{q.price}</Num>
          </div>
          <div className="mt-1">
            <Num tone={dirTone(Number(q.change))} className="text-[12px]">
              {q.change} ({q.changeRate}%)
            </Num>
          </div>
          {/* 기준 시각 — 장마감이면 언제 종가인지까지 말한다 */}
          <p className="mt-2 font-mono text-[10px] text-fg-faint">
            {q.tradeDate} · {q.slot}
          </p>
        </div>
      ))}
    </div>
  )
}
