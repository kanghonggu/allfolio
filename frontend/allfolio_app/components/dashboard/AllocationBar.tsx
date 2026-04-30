'use client'

import type { AllocationItem, MetricGrade } from '@/types/dashboard'

const TYPE_COLORS: Record<string, string> = {
  CRYPTO: '#f59e0b', STOCK: '#3b82f6', GOLD: '#eab308', CASH: '#6b7280', ETC: '#ec4899',
}
const TYPE_KO: Record<string, string> = {
  CRYPTO: '암호화폐', STOCK: '주식', GOLD: '금', CASH: '현금', ETC: '기타',
}
const WARN_TEXT: Record<MetricGrade, string> = {
  EXCELLENT: '분산 양호',
  GOOD: '적정 수준',
  WARN: '집중도 주의',
  BAD: '집중도 위험',
}

interface AllocationBarProps {
  allocation: AllocationItem[]
}

export default function AllocationBar({ allocation }: AllocationBarProps) {
  const topItem = allocation[0]
  const topGrade = topItem?.grade as MetricGrade | undefined

  return (
    <div className="rounded-xl border border-gray-700 bg-gray-900 p-5">
      <div className="flex items-center justify-between mb-4">
        <h3 className="text-sm font-semibold text-gray-300 uppercase tracking-wider">
          자산 배분
        </h3>
        {topGrade && (
          <span className={`text-xs font-medium ${
            topGrade === 'EXCELLENT' ? 'text-emerald-400' :
            topGrade === 'GOOD'      ? 'text-blue-400' :
            topGrade === 'WARN'      ? 'text-yellow-400' : 'text-red-400'
          }`}>
            {WARN_TEXT[topGrade]}
          </span>
        )}
      </div>

      <div className="space-y-3">
        {allocation.map((item) => {
          const color = TYPE_COLORS[item.type] ?? '#9ca3af'
          const pct = (item.ratio * 100).toFixed(1)
          return (
            <div key={item.type} className="flex items-center gap-3">
              <span className="h-3 w-3 shrink-0 rounded-full" style={{ background: color }} />
              <span className="w-16 text-sm text-gray-300">{TYPE_KO[item.type] ?? item.type}</span>
              <div className="flex-1 h-2 rounded-full bg-gray-800 overflow-hidden">
                <div
                  className="h-full rounded-full transition-all"
                  style={{ width: `${pct}%`, background: color }}
                />
              </div>
              <span className="w-12 text-right text-sm tabular-nums text-gray-300">{pct}%</span>
            </div>
          )
        })}
      </div>

      {topItem && Number(topItem.ratio) > 0.5 && (
        <div className="mt-4 rounded-lg bg-yellow-900/20 border border-yellow-700/30 px-3 py-2">
          <p className="text-xs text-yellow-400">
            {TYPE_KO[topItem.type] ?? topItem.type} 집중도가 {(Number(topItem.ratio) * 100).toFixed(0)}%로 높아요.
            단일 자산이 50% 이상이면 변동성이 커집니다.
          </p>
        </div>
      )}
    </div>
  )
}
