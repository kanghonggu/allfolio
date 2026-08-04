import Label from './Label'
import Button from './Button'
import { cx } from '@/lib/cx'

/** 로딩 — 스피너 대신 모노 라벨. 문서 톤 유지 */
export function LoadingState({ label = '불러오는 중', className }: { label?: string; className?: string }) {
  return (
    <div className={cx('border-t border-ink py-14 text-center', className)} role="status">
      <span className="animate-pulse font-mono text-[10px] tracking-wideLabel text-fg-muted">
        {label} …
      </span>
    </div>
  )
}

/** 에러 — 메시지 + 재시도 */
export function ErrorState({
  message,
  onRetry,
  className,
}: {
  message: string
  onRetry?: () => void
  className?: string
}) {
  return (
    <div className={cx('border-t border-ink py-12 text-center', className)} role="alert">
      <Label size="sm" tone="faint">오류</Label>
      <p className="mx-auto mt-2 max-w-md text-[13px] leading-relaxed text-fg-2">{message}</p>
      {onRetry && (
        <Button variant="outline" size="sm" className="mt-4" onClick={onRetry}>
          다시 시도
        </Button>
      )}
    </div>
  )
}

/** 빈 상태 — 실데이터가 짧은 서비스 특성상 모든 화면에서 필요 */
export function EmptyState({
  title,
  description,
  action,
  className,
}: {
  title: string
  description?: string
  action?: React.ReactNode
  className?: string
}) {
  return (
    <div className={cx('border-t border-ink py-12 text-center', className)}>
      <p className="m-0 text-[13.5px] font-medium text-fg-2">{title}</p>
      {description && (
        <p className="mx-auto mt-1.5 max-w-md text-xs leading-relaxed text-fg-faint">{description}</p>
      )}
      {action && <div className="mt-4 flex justify-center">{action}</div>}
    </div>
  )
}
