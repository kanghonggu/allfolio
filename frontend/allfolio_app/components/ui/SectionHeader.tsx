import Label from './Label'
import { cx } from '@/lib/cx'

/** 섹션 머리: 좌측 모노 라벨(h2) + 우측 보조 노트 */
export default function SectionHeader({
  label,
  note,
  actions,
  className,
}: {
  label: React.ReactNode
  note?: React.ReactNode
  actions?: React.ReactNode
  className?: string
}) {
  return (
    <div className={cx('mb-3 flex flex-wrap items-baseline justify-between gap-2', className)}>
      <h2 className="m-0 font-mono text-[10px] font-medium uppercase tracking-wideLabel text-fg-muted">
        {label}
      </h2>
      {note && <Label size="sm" tone="faint">{note}</Label>}
      {actions}
    </div>
  )
}
