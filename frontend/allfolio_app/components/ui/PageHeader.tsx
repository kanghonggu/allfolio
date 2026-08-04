import { cx } from '@/lib/cx'

/** 앱 화면 공통 머리: 좌측 제목 + 모노 메타라인, 우측 액션 버튼 */
export default function PageHeader({
  title,
  meta,
  actions,
  className,
}: {
  title: React.ReactNode
  /** 기준시각·건수 등 모노 메타라인 */
  meta?: React.ReactNode
  actions?: React.ReactNode
  className?: string
}) {
  return (
    <div className={cx('flex flex-wrap items-end justify-between gap-3 border-b border-line pb-3', className)}>
      <div>
        <h1 className="m-0 text-[19px] font-semibold tracking-[-0.01em]">{title}</h1>
        {meta && (
          <div className="mt-1.5 font-mono text-[10px] tracking-label text-fg-muted">{meta}</div>
        )}
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  )
}
