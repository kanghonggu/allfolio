import { cx } from '@/lib/cx'

export interface Column<T> {
  key: string
  header: React.ReactNode
  /** CSS grid 트랙 (예: '1.4fr', '96px') */
  width: string
  align?: 'left' | 'right'
  cell: (row: T, index: number) => React.ReactNode
}

/**
 * 시안의 grid 테이블 문법:
 * 상단 1.5px 잉크 보더 → 모노 컬럼 라벨 행 → 헤어라인 행 → (선택) 1.5px 잉크 보더 소계 행.
 * 모바일에서는 가로 스크롤 컨테이너로 감싼다.
 */
export default function DataTable<T>({
  columns,
  rows,
  rowKey,
  footer,
  minWidth = 640,
  gap = 12,
  className,
}: {
  columns: Column<T>[]
  rows: T[]
  rowKey: (row: T, index: number) => string | number
  /** 합계·소계 행 셀 (columns와 같은 길이, 빈 셀은 null) */
  footer?: React.ReactNode[]
  /** 가로 스크롤이 생기기 전 테이블 최소 폭(px) */
  minWidth?: number
  gap?: number
  className?: string
}) {
  const template = columns.map((c) => c.width).join(' ')
  const grid = { display: 'grid', gridTemplateColumns: template, columnGap: gap } as const

  return (
    <div className={cx('overflow-x-auto', className)}>
      <div style={{ minWidth }} className="border-t-[1.5px] border-ink">
        <div style={grid} className="border-b border-line py-2">
          {columns.map((c) => (
            <span
              key={c.key}
              className={cx(
                'font-mono text-[9px] uppercase tracking-label text-fg-faint',
                c.align === 'right' && 'text-right',
              )}
            >
              {c.header}
            </span>
          ))}
        </div>
        {rows.map((row, i) => (
          <div key={rowKey(row, i)} style={grid} className="items-baseline border-b border-line-hair py-2.5 hover:bg-surface-muted">
            {columns.map((c) => (
              <span key={c.key} className={cx('min-w-0', c.align === 'right' && 'text-right')}>
                {c.cell(row, i)}
              </span>
            ))}
          </div>
        ))}
        {footer && (
          <div style={grid} className="border-t-[1.5px] border-ink py-3">
            {columns.map((c, i) => (
              <span key={c.key} className={cx('min-w-0', c.align === 'right' && 'text-right')}>
                {footer[i]}
              </span>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
