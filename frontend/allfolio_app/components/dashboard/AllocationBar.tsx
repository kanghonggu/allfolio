'use client'

import Badge, { type BadgeVariant } from '@/components/ui/Badge'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import { wonPlain } from '@/lib/format'
import type { AllocationItem, MetricGrade } from '@/types/dashboard'

// 토큰 기반 그레이스케일 램프 — 비중 순서대로 진한 → 옅은
const TONES = [
  'var(--c-ink)',
  'var(--c-fg-muted)',
  'var(--c-fg-ghost)',
  'var(--c-line)',
  'var(--c-warn)',
]

const TYPE_KO: Record<string, string> = {
  CRYPTO: '암호화폐', STOCK: '주식', GOLD: '금', CASH: '현금', ETC: '기타',
}
const WARN_TEXT: Record<MetricGrade, { text: string; variant: BadgeVariant }> = {
  EXCELLENT: { text: '분산 양호',   variant: 'ok' },
  GOOD:      { text: '적정 수준',   variant: 'ink' },
  WARN:      { text: '집중도 주의', variant: 'warn' },
  BAD:       { text: '집중도 위험', variant: 'danger' },
}

interface AllocationBarProps {
  allocation: AllocationItem[]
}

export default function AllocationBar({ allocation }: AllocationBarProps) {
  const topItem = allocation[0]
  const topGrade = topItem?.grade as MetricGrade | undefined

  return (
    <div>
      {/* 세그먼트 바 */}
      <div className="mb-4 flex h-2" aria-hidden="true">
        {allocation.map((item, i) => (
          <span
            key={item.type}
            className="block"
            style={{ width: `${(item.ratio * 100).toFixed(2)}%`, background: TONES[i % TONES.length] }}
          />
        ))}
      </div>

      <div className="border-t-[1.5px] border-ink">
        <div className="grid grid-cols-[14px_1.2fr_0.7fr_1.1fr_0.9fr] items-baseline gap-2.5 border-b border-line py-2">
          <span />
          <Label size="sm" tone="faint">자산군</Label>
          <Label size="sm" tone="faint" className="text-right">비중</Label>
          <Label size="sm" tone="faint" className="text-right">평가액</Label>
          <Label size="sm" tone="faint" className="text-right">판정</Label>
        </div>
        <div role="list" aria-label="자산 배분">
          {allocation.map((item, i) => {
            const pct = (item.ratio * 100).toFixed(1)
            const grade = WARN_TEXT[item.grade as MetricGrade]
            return (
              // QA P2: 접근성 트리에서 유형 라벨이 확실히 잡히도록 row에 aria-label 부여
              <div
                key={item.type}
                role="listitem"
                aria-label={`${TYPE_KO[item.type] ?? item.type} ${pct}%`}
                className="grid grid-cols-[14px_1.2fr_0.7fr_1.1fr_0.9fr] items-center gap-2.5 border-b border-line-hair py-2.5"
              >
                <span
                  className="block h-[7px] w-[7px]"
                  aria-hidden="true"
                  style={{ background: TONES[i % TONES.length] }}
                />
                <span className="text-[13px]">{TYPE_KO[item.type] ?? item.type}</span>
                <Num className="text-right text-xs">{pct}%</Num>
                <Num className="text-right text-xs text-fg-3">{wonPlain(item.value)}</Num>
                <span className="text-right">
                  {grade && <Badge variant={grade.variant}>{grade.text}</Badge>}
                </span>
              </div>
            )
          })}
        </div>
      </div>

      {topItem && Number(topItem.ratio) > 0.5 && (
        <div className="mt-3.5 border border-warn-line bg-warn-bg px-3.5 py-2.5">
          <p className="m-0 text-xs leading-relaxed text-warn">
            {TYPE_KO[topItem.type] ?? topItem.type} 집중도가 {(Number(topItem.ratio) * 100).toFixed(0)}%로
            높습니다. 단일 자산군 비중이 50%를 초과하면 변동성이 커집니다.
          </p>
        </div>
      )}
    </div>
  )
}
