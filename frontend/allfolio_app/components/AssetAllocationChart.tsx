'use client'

import {
  Cell,
  Legend,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
} from 'recharts'
import type { Position } from '@/types/portfolio'

// 자산별 색상 팔레트 (최대 10개 자산)
const COLORS = [
  '#6366f1', '#22d3ee', '#f59e0b', '#10b981', '#ef4444',
  '#a855f7', '#3b82f6', '#f97316', '#14b8a6', '#ec4899',
]

type Props = {
  positions: Position[]
}

type ChartEntry = {
  name: string
  value: number
  pct: string
}

function formatKrw(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}백만`
  if (n >= 10_000)    return `${(n / 10_000).toFixed(1)}만`
  return n.toLocaleString()
}

export default function AssetAllocationChart({ positions }: Props) {
  const totalKrw = positions.reduce((sum, p) => sum + p.krwValue, 0)
  if (totalKrw <= 0) return null

  const data: ChartEntry[] = positions
    .filter((p) => p.krwValue > 0)
    .sort((a, b) => b.krwValue - a.krwValue)
    .map((p) => ({
      name:  p.assetId.slice(0, 8),
      value: p.krwValue,
      pct:   ((p.krwValue / totalKrw) * 100).toFixed(1),
    }))

  return (
    <div className="rounded-lg border border-gray-700 bg-gray-900 p-4">
      <h3 className="mb-4 text-sm font-semibold text-gray-300">
        자산 비중 (KRW 기준, 총{' '}
        <span className="text-white">{formatKrw(totalKrw)}원</span>)
      </h3>
      <ResponsiveContainer width="100%" height={260}>
        <PieChart>
          <Pie
            data={data}
            dataKey="value"
            nameKey="name"
            cx="50%"
            cy="50%"
            outerRadius={90}
            innerRadius={50}
            paddingAngle={2}
          >
            {data.map((_, i) => (
              <Cell key={i} fill={COLORS[i % COLORS.length]} />
            ))}
          </Pie>
          <Tooltip
            formatter={(value: number, name: string) => [
              `${formatKrw(value)}원`,
              name,
            ]}
            contentStyle={{
              backgroundColor: '#1f2937',
              border: '1px solid #374151',
              borderRadius: '6px',
              color: '#f9fafb',
              fontSize: '12px',
            }}
          />
          <Legend
            formatter={(value, entry: any) => (
              <span className="text-xs text-gray-300">
                {value} {entry.payload?.pct}%
              </span>
            )}
            wrapperStyle={{ fontSize: '12px' }}
          />
        </PieChart>
      </ResponsiveContainer>
    </div>
  )
}
