import { cloneElement, isValidElement } from 'react'
import { cx } from '@/lib/cx'

/**
 * 폼 필드: label(htmlFor) + 컨트롤(id·aria-invalid·aria-describedby 자동 연결) + 에러/힌트.
 * 컨트롤은 단일 엘리먼트여야 한다 (Input/Select 프리미티브 권장).
 */
export default function Field({
  id,
  label,
  error,
  hint,
  children,
  className,
}: {
  id: string
  label: string
  error?: string | null
  hint?: string
  children: React.ReactElement
  className?: string
}) {
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined
  const control = isValidElement(children)
    ? cloneElement(children as React.ReactElement<Record<string, unknown>>, {
        id,
        'aria-invalid': error ? true : undefined,
        'aria-describedby': describedBy,
      })
    : children

  return (
    <div className={className}>
      <label htmlFor={id} className="mb-1.5 block font-mono text-[10px] tracking-label text-fg-muted">
        {label}
      </label>
      {control}
      {hint && !error && (
        <p id={`${id}-hint`} className="mt-1 text-xs text-fg-faint">{hint}</p>
      )}
      {error && (
        <p id={`${id}-error`} role="alert" className="mt-1 text-xs text-danger">{error}</p>
      )}
    </div>
  )
}

const controlBase =
  'w-full border border-line bg-surface px-3 py-2 text-sm text-ink placeholder:text-fg-ghost ' +
  'focus:border-ink focus:outline-none disabled:bg-surface-muted disabled:text-fg-faint ' +
  'aria-[invalid=true]:border-danger'

export function Input({ className, ...rest }: React.InputHTMLAttributes<HTMLInputElement>) {
  return <input className={cx(controlBase, className)} {...rest} />
}

export function Select({ className, children, ...rest }: React.SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select className={cx(controlBase, className)} {...rest}>
      {children}
    </select>
  )
}

export function Textarea({ className, ...rest }: React.TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea className={cx(controlBase, className)} {...rest} />
}
