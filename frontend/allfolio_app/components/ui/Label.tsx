import { cx } from '@/lib/cx'

/** 대문자 모노 라벨 — 시안의 섹션·컬럼·메타 라벨 문법 */
export default function Label({
  children,
  size = 'md',
  tone = 'muted',
  className,
}: {
  children: React.ReactNode
  /** md=10px(섹션), sm=9px(테이블 컬럼) */
  size?: 'sm' | 'md'
  tone?: 'muted' | 'faint' | 'ghost' | 'ink'
  className?: string
}) {
  return (
    <span
      className={cx(
        'font-mono uppercase',
        size === 'sm' ? 'text-[9px]' : 'text-[10px]',
        size === 'sm' ? 'tracking-label' : 'tracking-wideLabel',
        tone === 'muted' && 'text-fg-muted',
        tone === 'faint' && 'text-fg-faint',
        tone === 'ghost' && 'text-fg-ghost',
        tone === 'ink' && 'text-ink',
        className,
      )}
    >
      {children}
    </span>
  )
}
