import { cx } from '@/lib/cx'

/** 흰 표면 + 카드 보더. radius·그림자 없음 — 위계는 보더로만 표현한다. */
export default function Panel({
  children,
  muted = false,
  className,
}: {
  children: React.ReactNode
  /** true면 서브패널 톤(#f7f8fa) */
  muted?: boolean
  className?: string
}) {
  return (
    <div className={cx('border border-line-card', muted ? 'bg-surface-muted' : 'bg-surface', className)}>
      {children}
    </div>
  )
}
