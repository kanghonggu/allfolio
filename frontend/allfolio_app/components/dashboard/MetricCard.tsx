'use client'

import type { MetricGrade, MetricValue } from '@/types/dashboard'

const GRADE_STYLES: Record<MetricGrade, { badge: string; label: string }> = {
  EXCELLENT: { badge: 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/30', label: '우수' },
  GOOD:      { badge: 'bg-blue-500/20 text-blue-400 border border-blue-500/30',         label: '양호' },
  WARN:      { badge: 'bg-yellow-500/20 text-yellow-400 border border-yellow-500/30',   label: '주의' },
  BAD:       { badge: 'bg-red-500/20 text-red-400 border border-red-500/30',             label: '위험' },
}

const VALUE_COLORS: Record<MetricGrade, string> = {
  EXCELLENT: 'text-emerald-400',
  GOOD:      'text-blue-400',
  WARN:      'text-yellow-400',
  BAD:       'text-red-400',
}

interface MetricCardProps {
  label: string
  metric: MetricValue
  formatValue?: (v: number) => string
  benchmarkLabel?: string
  description?: string
}

function Stars({ count }: { count: number }) {
  return (
    <span className="text-sm">
      {Array.from({ length: 5 }).map((_, i) => (
        <span key={i} className={i < count ? 'text-yellow-400' : 'text-gray-700'}>★</span>
      ))}
    </span>
  )
}

export default function MetricCard({
  label, metric, formatValue, benchmarkLabel, description,
}: MetricCardProps) {
  const { badge, label: gradeLabel } = GRADE_STYLES[metric.grade]
  const valueColor = VALUE_COLORS[metric.grade]
  const displayValue = formatValue
    ? formatValue(metric.value)
    : `${metric.value >= 0 ? '+' : ''}${metric.value.toFixed(2)}%`

  return (
    <div className="rounded-xl border border-gray-700 bg-gray-900 p-5">
      <div className="flex items-start justify-between mb-3">
        <p className="text-xs font-medium uppercase tracking-wider text-gray-500">{label}</p>
        <span className={`rounded-full px-2.5 py-0.5 text-xs font-semibold ${badge}`}>
          {gradeLabel}
        </span>
      </div>

      <p className={`text-2xl font-bold tabular-nums ${valueColor}`}>{displayValue}</p>
      <Stars count={metric.stars} />

      {metric.benchmarkVsKospi != null && (
        <div className="mt-3 rounded-lg bg-gray-800 px-3 py-2">
          <p className="text-xs text-gray-500">{benchmarkLabel ?? '코스피 대비'}</p>
          <p className="text-sm font-medium">
            <span className={metric.benchmarkVsKospi >= 0 ? 'text-emerald-400' : 'text-red-400'}>
              {metric.benchmarkVsKospi >= 0 ? '+' : ''}{metric.benchmarkVsKospi.toFixed(2)}%p
            </span>
            <span className="ml-1 text-gray-500 text-xs">초과수익</span>
          </p>
        </div>
      )}

      {description && (
        <div className="mt-2 rounded-lg bg-gray-800 px-3 py-2">
          <p className="text-xs text-gray-400 leading-relaxed">{description}</p>
        </div>
      )}

      {metric.dataWarning && (
        <div className="mt-2 flex items-center gap-1.5 text-xs text-yellow-600">
          <span>⚠</span>
          <span>{metric.dataWarning}</span>
        </div>
      )}
    </div>
  )
}
