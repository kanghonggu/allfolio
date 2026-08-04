'use client'

import { SUPPORTED_CURRENCIES } from '@/lib/currencies'

// 통화 입력 공통 컴포넌트 (QA P2) — 자유 텍스트 금지, 화면 간 UI 통일
interface CurrencySelectProps extends Omit<React.SelectHTMLAttributes<HTMLSelectElement>, 'value' | 'onChange'> {
  value: string
  onChange: (currency: string) => void
  className?: string
  /** true면 첫 옵션으로 '선택' placeholder를 노출 */
  allowEmpty?: boolean
}

const DEFAULT_CLS =
  'w-full border border-line bg-surface px-3 py-2 text-sm text-ink ' +
  'focus:border-ink focus:outline-none disabled:bg-surface-muted disabled:text-fg-faint'

export default function CurrencySelect({ value, onChange, className, allowEmpty = false, ...rest }: CurrencySelectProps) {
  return (
    <select
      className={className ?? DEFAULT_CLS}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      {...rest}
    >
      {allowEmpty && <option value="">선택</option>}
      {SUPPORTED_CURRENCIES.map((c) => (
        <option key={c} value={c}>{c}</option>
      ))}
    </select>
  )
}
