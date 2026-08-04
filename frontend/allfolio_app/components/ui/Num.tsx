import { cx } from '@/lib/cx'
import type { PnlTone } from '@/lib/format'
import { toneText } from '@/lib/format'

/**
 * 숫자 셀 — 모노스페이스 + tabular-nums + nowrap.
 * 우측 정렬은 부모 셀(text-right)에서 담당한다.
 */
export default function Num({
  children,
  tone,
  className,
}: {
  children: React.ReactNode
  /** 손익 방향 색 (gain=빨강/loss=파랑/flat=중립). 미지정 시 상속 */
  tone?: PnlTone
  className?: string
}) {
  return (
    <span className={cx('tnum whitespace-nowrap font-mono', tone && toneText[tone], className)}>
      {children}
    </span>
  )
}
