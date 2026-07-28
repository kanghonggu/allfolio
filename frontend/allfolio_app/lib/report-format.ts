// lib/report-format.ts
export function fmtPct(n: number | null | undefined): string {
  if (n === null || n === undefined) return '—'
  const pct = n * 100
  return `${pct >= 0 ? '+' : ''}${pct.toFixed(2)}%`
}

export function fmtKrw(n: number | null | undefined): string {
  if (n === null || n === undefined) return '—'
  return `${n >= 0 ? '' : '-'}₩${Math.abs(Math.round(n)).toLocaleString()}`
}

/** 이미 0~100으로 스케일된 퍼센트 값(returnRate 등) 포맷 — fmtPct와 달리 ×100 하지 않음 */
export function fmtPctScaled(n: number | null | undefined): string {
  if (n === null || n === undefined) return '—'
  return `${n >= 0 ? '+' : ''}${n.toFixed(2)}%`
}

export function pctColor(n: number | null | undefined): string {
  if (n === null || n === undefined) return 'text-gray-400'
  return n >= 0 ? 'text-emerald-400' : 'text-red-400'
}
