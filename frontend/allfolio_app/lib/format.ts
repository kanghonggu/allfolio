// 리디자인 공통 숫자 포맷 — 시안 문법: 모노스페이스 · tabular-nums · 명시적 부호 · U+2212 마이너스
// 기존 lib/report-format.ts(fmtPct=×100, fmtPctScaled=사전스케일)와 스케일 규약이 다르므로 혼용 주의:
// 여기의 pct 계열은 전부 "이미 0~100으로 스케일된 값"을 받는다.

export type PnlTone = 'gain' | 'loss' | 'flat'

const MINUS = '−'

export function won(n: number | null | undefined): string {
  if (n === null || n === undefined) return '—'
  const sign = n < 0 ? MINUS : ''
  return `${sign}₩${Math.abs(Math.round(n)).toLocaleString('en-US')}`
}

export function wonPlain(n: number | null | undefined): string {
  if (n === null || n === undefined) return '—'
  const sign = n < 0 ? MINUS : ''
  return `${sign}${Math.abs(Math.round(n)).toLocaleString('en-US')}`
}

export function signWon(n: number | null | undefined): string {
  if (n === null || n === undefined) return '—'
  return `${n >= 0 ? '+' : MINUS}₩${Math.abs(Math.round(n)).toLocaleString('en-US')}`
}

/** 이미 0~100 스케일된 퍼센트 (returnRate, unrealizedPnlPct 등) */
export function signPct(n: number | null | undefined, digits = 2): string {
  if (n === null || n === undefined) return '—'
  return `${n >= 0 ? '+' : MINUS}${Math.abs(n).toFixed(digits)}%`
}

export function pct(n: number | null | undefined, digits = 1): string {
  if (n === null || n === undefined) return '—'
  return `${n.toFixed(digits)}%`
}

/** 손익 방향 → 톤. 한국 관례: 상승 빨강(gain) / 하락 파랑(loss) */
export function dirTone(n: number | null | undefined): PnlTone {
  if (n === null || n === undefined || n === 0) return 'flat'
  return n > 0 ? 'gain' : 'loss'
}

export const toneText: Record<PnlTone, string> = {
  gain: 'text-gain',
  loss: 'text-loss',
  flat: 'text-fg-3',
}
