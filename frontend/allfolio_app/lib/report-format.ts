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

export function pctColor(n: number | null | undefined): string {
  if (n === null || n === undefined) return 'text-gray-400'
  return n >= 0 ? 'text-emerald-400' : 'text-red-400'
}
