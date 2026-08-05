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

// 자산별 색상 램프 — 잉크 → 라인 순의 단색 문서 톤 (순환)
const COLORS = [
  'var(--c-ink)',
  'var(--c-fg-muted)',
  'var(--c-fg-ghost)',
  'var(--c-line)',
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
    <div className="border-t-[1.5px] border-ink pt-4">
      <p className="m-0 mb-4 font-mono text-[10px] tracking-label text-fg-muted">
        KRW 기준 · 총 <span className="text-ink tnum">{formatKrw(totalKrw)}원</span>
      </p>
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
              <Cell key={i} fill={COLORS[i % COLORS.length]} stroke="var(--c-surface)" />
            ))}
          </Pie>
          <Tooltip
            formatter={(value: number, name: string) => [
              `${formatKrw(value)}원`,
              name,
            ]}
            contentStyle={{
              background: 'var(--c-surface)',
              border: '1px solid var(--c-line-card)',
              borderRadius: 0,
              color: 'var(--c-ink)',
              fontSize: '12px',
            }}
          />
          <Legend
            formatter={(value, entry: any) => (
              <span className="font-mono text-[10px] tracking-label text-fg-3">
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
