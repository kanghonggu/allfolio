'use client'

import { useState } from 'react'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import { signPct, dirTone } from '@/lib/format'
import { EmptyState } from '@/components/ui/states'
import type { Position } from '@/types/dashboard'

// QA P1 #11: 평가액 1 미만(코인 잔여 단위 등) 먼지 포지션은 접어서 표 노이즈를 줄인다
const DUST_THRESHOLD = 1

const TYPE_KO: Record<string, string> = {
  CRYPTO: '코인', STOCK: '주식', GOLD: '금', CASH: '현금', ETC: '기타',
}

const GRID = 'grid grid-cols-[2fr_0.7fr_1.1fr_1fr_0.7fr] gap-3'

interface PositionTableProps {
  positions: Position[]
}

export default function PositionTable({ positions }: PositionTableProps) {
  const [showDust, setShowDust] = useState(false)

  if (positions.length === 0) {
    return <EmptyState title="투자 포지션 없음" description="계좌를 연결하고 sync하면 보유 포지션이 표시됩니다" />
  }

  const mainPositions = positions.filter(p => p.currentValue >= DUST_THRESHOLD)
  const dustPositions = positions.filter(p => p.currentValue < DUST_THRESHOLD)
  const visible = showDust ? [...mainPositions, ...dustPositions] : mainPositions

  return (
    <div>
      <div className="mb-3 flex items-baseline justify-between">
        <h2 className="m-0 font-mono text-[10px] font-medium uppercase tracking-wideLabel text-fg-muted">
          포지션 ({positions.length})
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
                {p.symbol && (
                  <span className="font-mono text-[9.5px] tracking-[0.08em] text-fg-ghost">{p.symbol}</span>
                )}
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
                  : `먼지 포지션 ${dustPositions.length}건 표시 (평가액 1 미만)`}
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
