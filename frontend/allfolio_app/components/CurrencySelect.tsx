'use client'

import { SUPPORTED_CURRENCIES } from '@/lib/currencies'

// 통화 입력 공통 컴포넌트 (QA P2) — 자유 텍스트 금지, 화면 간 UI 통일
interface CurrencySelectProps {
  value: string
  onChange: (currency: string) => void
  className?: string
  /** true면 첫 옵션으로 '선택' placeholder를 노출 */
  allowEmpty?: boolean
  /** 스크린리더 식별용 이름 (QA 후속 #6 접근성) */
  ariaLabel?: string
}

export default function CurrencySelect({ value, onChange, className, allowEmpty = false, ariaLabel }: CurrencySelectProps) {
  return (
    <select
      className={className ?? 'mt-1 w-full rounded-md border border-gray-700 bg-gray-950 px-3 py-2 text-gray-200'}
      value={value}
      aria-label={ariaLabel ?? '통화'}
      onChange={(e) => onChange(e.target.value)}
    >
      {allowEmpty && <option value="">선택</option>}
      {SUPPORTED_CURRENCIES.map((c) => (
        <option key={c} value={c}>{c}</option>
      ))}
    </select>
  )
}
