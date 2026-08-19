// components/disclosure/DisclosureFeedPanel.tsx
'use client'

import { useState } from 'react'
import type { DisclosureItem } from '@/types/disclosure'
import Label from '@/components/ui/Label'
import { EmptyState } from '@/components/ui/states'

/**
 * Tier 5는 정기보고서다 — 실측 상장사 5,394건 중 2,846건(53%)이다.
 * 반기보고서 마감 시즌엔 보유 10종목이면 T5만 10건이고 T1~T4는 0~2건일 수 있어,
 * 평평한 목록이면 화면이 반기보고서 더미로 보인다. **버리지 않고 접는다** —
 * 백엔드가 is_material=true로 저장하고 정렬로만 뒤로 민 것과 같은 판단이다.
 */
const TIER_PERIODIC = 5

/**
 * **Tier에 분류명을 붙이지 않는다.** 백엔드 스펙 8절이 기록한 기지 오분류 때문이다 —
 * `매매거래정지`(T3)가 부정형까지 잡아 27건 중 `주권매매거래정지해제`(= 정지가 풀린 것)가
 * 12건이다. `상장폐지`도 `절차 미진행` 안내를 잡는다. 백엔드에선 정렬 순위 문제였지만
 * 화면에 "위험" 배지를 붙이는 순간 주장이 된다. `report_nm` 원문이 이미 사실을 말하므로
 * 분류명을 덧붙일 값이 없다. 숫자와 색만 쓴다. S13에서 키워드를 고친 뒤 재검토할 것.
 */
const TIER_TONE: Record<number, string> = {
  1: 'text-danger',
  2: 'text-ink',
  3: 'text-warn',
  4: 'text-fg-2',
  5: 'text-fg-faint',
}

function shortDate(iso: string): string {
  // `2026-08-18` → `08-18`. 30일 창이라 연도가 바뀌는 경계는 드물고, 바뀌어도
  // 정렬이 최신순이라 순서로 읽힌다
  return iso.slice(5)
}

function Row({ item }: { item: DisclosureItem }) {
  return (
    <li className="border-b border-line-card/50 px-4 py-2.5 last:border-b-0">
      <div className="flex items-baseline gap-2">
        <span
          className={`shrink-0 font-mono text-[11px] ${TIER_TONE[item.materialTier ?? 5] ?? 'text-fg-faint'}`}
          aria-label={`분류 ${item.materialTier ?? '-'}`}
        >
          [{item.materialTier ?? '-'}]
        </span>
        {/* 색은 fg 기본(text-ink 상속)을 쓴다 — 'text-fg-1'은 이 레포에 정의되지 않은
            토큰이라 조용히 무색으로 렌더된다(tailwind.config.ts에 fg.DEFAULT만 있고 fg.1은 없다) */}
        <a
          href={item.sourceUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="min-w-0 flex-1 text-[13px] leading-snug underline-offset-2 hover:underline"
        >
          {item.reportNm}
        </a>
        {item.supersededCount > 0 && (
          <span className="shrink-0 text-[11px] text-fg-faint">정정 {item.supersededCount}회</span>
        )}
        <span className="shrink-0 font-mono text-[11px] text-fg-faint">{shortDate(item.rceptDt)}</span>
      </div>
      <div className="mt-0.5 pl-6 text-[11.5px] text-fg-faint">
        {item.corpName}
        {item.stockCode && <span className="ml-1.5 font-mono">{item.stockCode}</span>}
        {/* 제출인은 회사 자신일 때 반복이라 안 보여준다. Tier 4에서만 임원 이름이 온다 */}
        {item.flrNm && item.flrNm !== item.corpName && <span className="ml-1.5">· {item.flrNm}</span>}
      </div>
    </li>
  )
}

export default function DisclosureFeedPanel({ items }: { items: DisclosureItem[] }) {
  const [periodicOpen, setPeriodicOpen] = useState(false)

  if (items.length === 0) {
    return <EmptyState title="최근 30일간 공시가 없습니다" description="보유 종목에 접수된 주요 공시가 없습니다." />
  }

  // 백엔드가 Tier 오름차순 → 접수일 내림차순으로 이미 정렬해 보내므로 순서를 다시 잡지 않는다
  const main = items.filter((i) => i.materialTier !== TIER_PERIODIC)
  const periodic = items.filter((i) => i.materialTier === TIER_PERIODIC)

  return (
    <div>
      <ul className="m-0 list-none p-0">
        {main.map((i) => <Row key={i.rceptNo} item={i} />)}
      </ul>

      {periodic.length > 0 && (
        <div className="border-t border-line-card">
          <button
            type="button"
            onClick={() => setPeriodicOpen((v) => !v)}
            aria-expanded={periodicOpen}
            className="flex w-full items-center justify-between px-4 py-2.5 text-left"
          >
            <Label size="sm" tone="faint">정기보고서 {periodic.length}건</Label>
            <span className="text-[11px] text-fg-faint">{periodicOpen ? '▴' : '▾'}</span>
          </button>
          {periodicOpen && (
            <ul className="m-0 list-none border-t border-line-card/50 p-0">
              {periodic.map((i) => <Row key={i.rceptNo} item={i} />)}
            </ul>
          )}
        </div>
      )}
    </div>
  )
}
