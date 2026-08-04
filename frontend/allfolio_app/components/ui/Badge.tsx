import { cx } from '@/lib/cx'

export type BadgeVariant = 'ok' | 'warn' | 'danger' | 'muted' | 'ink'

const variantText: Record<BadgeVariant, string> = {
  ok: 'text-ok',
  warn: 'text-warn',
  danger: 'text-danger',
  muted: 'text-fg-faint',
  ink: 'text-ink',
}

/**
 * 판정 배지 — 배경·라운드 없이 모노 소문자 + 색만으로 상태 표기.
 * (통과=ok, 차이·대기·잠정=warn, 실패·오류=danger)
 */
export default function Badge({
  children,
  variant = 'muted',
  className,
}: {
  children: React.ReactNode
  variant?: BadgeVariant
  className?: string
}) {
  return (
    <span className={cx('whitespace-nowrap font-mono text-[9.5px] tracking-label', variantText[variant], className)}>
      {children}
    </span>
  )
}
