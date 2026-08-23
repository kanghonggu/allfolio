'use client'

import { useState } from 'react'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import { signPct, dirTone } from '@/lib/format'
import { EmptyState } from '@/components/ui/states'
import type { Position } from '@/types/dashboard'
import { priceAsOfLabel, showsPriceAsOf, staleThresholdOf, stalenessDays } from '@/lib/price-as-of'

// QA 후속 #4: 먼지 포지션 판정은 원통화가 아니라 KRW 환산 기준 —
// FDUSD 18원, TRX 13원 같은 잔여 단위가 실질 포지션처럼 노출되지 않게 접는다
const DUST_THRESHOLD_KRW = 1000

const TYPE_KO: Record<string, string> = {
  CRYPTO: '코인', STOCK: '주식', GOLD: '금', CASH: '현금', ETC: '기타',
}

const GRID = 'grid grid-cols-[2fr_0.7fr_1.1fr_1fr_0.7fr] gap-3'

interface PositionTableProps {
  positions: Position[]
  /** 포지션이 아예 없을 때의 안내 — 원인(계좌 없음/자산 없음)을 아는 화면이 넘겨준다 (AF-91) */
  empty?: React.ReactNode
}

export default function PositionTable({ positions, empty }: PositionTableProps) {
  const [showDust, setShowDust] = useState(false)

  if (positions.length === 0) {
    return <>{empty ?? <EmptyState title="집계된 포지션이 없습니다" />}</>
  }

  const mainPositions = positions.filter(p => (p.currentValueKrw ?? p.currentValue) >= DUST_THRESHOLD_KRW)
  const dustPositions = positions.filter(p => (p.currentValueKrw ?? p.currentValue) < DUST_THRESHOLD_KRW)
  const visible = showDust ? [...mainPositions, ...dustPositions] : mainPositions

  return (
    <div>
      <div className="mb-3 flex items-baseline justify-between">
        {/* 카운터는 실질 포지션 기준 — 먼지는 별도 표기 (QA 후속 #4) */}
        <h2 className="m-0 font-mono text-[10px] font-medium uppercase tracking-wideLabel text-fg-muted">
          포지션 ({mainPositions.length})
          {dustPositions.length > 0 && (
            <span className="ml-1.5 normal-case text-fg-ghost">+ 먼지 {dustPositions.length}건</span>
          )}
        </h2>
      </div>
      <div className="overflow-x-auto">
        <div className="min-w-[560px] border-t-[1.5px] border-ink">
          <div className={`${GRID} border-b border-line py-2`}>
            <Label size="sm" tone="faint">자산명</Label>
            <Label size="sm" tone="faint">유형</Label>
            <Label size="sm" tone="faint" className="text-right">평가액</Label>
            <Label size="sm" tone="faint" className="text-right">수익률</Label>
            <Label size="sm" tone="faint" className="text-right">비중</Label>
          </div>
          {visible.map((p) => (
            <div key={p.id} className={`${GRID} items-baseline border-b border-line-hair py-2.5 hover:bg-surface-muted`}>
              <span className="flex min-w-0 flex-col">
                <span className="truncate text-[13.5px]">{p.name}</span>
                <span className="flex flex-wrap items-baseline gap-x-1.5">
                  {p.symbol && (
                    <span className="font-mono text-[9.5px] tracking-[0.08em] text-fg-ghost">{p.symbol}</span>
                  )}
                  {/* **기준일은 자동 평가된 자산만 뜬다** (A1 · N2). 일요일에 보는 금값은
                      금요일 종가다 — 숨기면 사용자는 그게 지금 시세인 줄 안다.
                      주식·코인은 priceAsOf가 null이라 여기 아무것도 안 그려진다. */}
                  <PriceAsOf position={p} />
                </span>
              </span>
              <span className="font-mono text-[10px] tracking-label text-fg-3">
                {TYPE_KO[p.type] ?? p.type}
              </span>
              <Num className="text-right text-[12.5px]">
                {/* KRW는 소수점 없이 반올림 (QA P1 #11) */}
                ₩{(p.currency === 'KRW' ? Math.round(p.currentValue) : p.currentValue).toLocaleString('en-US')}
              </Num>
              <Num tone={dirTone(p.returnRate)} className="text-right text-[12.5px]">
                {signPct(p.returnRate)}
              </Num>
              <Num className="text-right text-xs text-fg-muted">
                {(p.weight * 100).toFixed(1)}%
              </Num>
            </div>
          ))}
          {dustPositions.length > 0 && (
            <div className="border-b border-line-hair py-2">
              <button
                onClick={() => setShowDust(v => !v)}
                className="font-mono text-[10px] tracking-label text-fg-faint transition-colors hover:text-ink"
              >
                {showDust
                  ? '먼지 포지션 접기'
                  : `먼지 포지션 ${dustPositions.length}건 표시 (평가액 ₩1,000 미만)`}
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

/**
 * 시세 기준일 + 묵음 경고.
 *
 * **임계치 5의 근거는 `lib/price-as-of.ts`에 있다** — 1~4는 정상 운영이라 여기 경고가 뜨면 안 된다.
 * 금은 D+1 공표라 `0`은 아예 나오지 않는 값이다.
 */
function PriceAsOf({ position }: { position: Position }) {
  if (!showsPriceAsOf(position)) return null

  const days = stalenessDays(position.priceAsOf)
  // 임계치는 자산 유형이 정한다 — 금의 5일을 부동산에 쓰면 늘 경고가 뜬다
  const stale = days !== null && days >= staleThresholdOf(position.type)

  return (
    <span
      className={`font-mono text-[9.5px] tracking-[0.08em] ${stale ? 'text-warn' : 'text-fg-ghost'}`}
      title={stale ? `시세가 ${days}일 지연됐습니다 — 소스 확인이 필요합니다` : undefined}
    >
      {priceAsOfLabel(position.priceAsOf, position.type)}
      {stale && ` · ${days}일 지연`}
    </span>
  )
}
