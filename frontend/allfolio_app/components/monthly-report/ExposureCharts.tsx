// components/monthly-report/ExposureCharts.tsx
'use client'

import { Cell, Legend, Pie, PieChart, ResponsiveContainer, Tooltip } from 'recharts'
import Label from '@/components/ui/Label'
import SectionHeader from '@/components/ui/SectionHeader'
import type { Exposure } from '@/types/monthly-report'
import { fmtKrw } from '@/lib/report-format'

// 토큰 기반 그레이스케일 램프 — 비중 순서대로 진한 → 옅은 (순환)
const COLORS = ['var(--c-ink)', 'var(--c-fg-muted)', 'var(--c-fg-ghost)', 'var(--c-line)']

type Slice = { label: string; valueKrw: number; weight: number }

function collapse(rows: Slice[]): Slice[] {
  if (rows.length <= 8) return rows
  const sorted = [...rows].sort((a, b) => b.valueKrw - a.valueKrw)
  const head = sorted.slice(0, 7)
  const tail = sorted.slice(7)
  const rest = tail.reduce((a, r) => a + r.valueKrw, 0)
  const restWeight = tail.reduce((a, r) => a + r.weight, 0)
  return [...head, { label: '기타', valueKrw: rest, weight: restWeight }]
}

/**
 * 인쇄 전용 조각 라벨 (AF-206).
 * 인쇄물엔 호버가 없으므로 Tooltip에만 있던 값을 SVG <text>로 항상 렌더하고,
 * 화면에서는 globals.css의 `@media screen { .print-only-label { display: none } }`으로 가린다.
 * (recharts는 SVG라 미디어 쿼리로 prop을 바꿀 수 없다 — 렌더 경로를 하나로 유지한다)
 */
function PrintSliceLabel(props: {
  cx: number
  cy: number
  midAngle: number
  outerRadius: number
  percent: number
  value: number
  payload?: Slice
}) {
  const { cx, cy, midAngle, outerRadius, percent, value, payload } = props
  const rad = -midAngle * (Math.PI / 180)
  const r = outerRadius + 12
  const x = cx + r * Math.cos(rad)
  const y = cy + r * Math.sin(rad)
  // weight(0~100 스케일)가 정본. 없으면 recharts가 계산한 비율로 폴백.
  const pct = payload?.weight ?? percent * 100
  return (
    <text
      className="print-only-label"
      x={x}
      y={y}
      textAnchor={x >= cx ? 'start' : 'end'}
      fill="var(--c-ink)"
      fontSize={8}
      fontFamily="var(--font-mono), monospace"
    >
      <tspan x={x} dy="-0.1em">{pct.toFixed(1)}%</tspan>
      <tspan x={x} dy="1.1em">{fmtKrw(payload?.valueKrw ?? value)}</tspan>
    </text>
  )
}

function Donut({ title, data }: { title: string; data: Slice[] }) {
  const rows = collapse(data)
  return (
    <div className="border-t-[1.5px] border-ink pt-3">
      <Label size="sm" tone="faint" className="mb-2 block">{title}</Label>
      {rows.length === 0 ? (
        <div className="flex h-[240px] items-center justify-center text-[12px] text-fg-faint">데이터 없음</div>
      ) : (
        <ResponsiveContainer width="100%" height={240}>
          <PieChart>
            <Pie
              data={rows}
              dataKey="valueKrw"
              nameKey="label"
              innerRadius={50}
              outerRadius={80}
              paddingAngle={2}
              stroke="var(--c-surface)"
              isAnimationActive={false}
              label={PrintSliceLabel}
              labelLine={false}
            >
              {rows.map((_, i) => <Cell key={i} fill={COLORS[i % COLORS.length]} />)}
            </Pie>
            <Tooltip
              formatter={(v: number) => [fmtKrw(v), '평가액']}
              contentStyle={{ background: 'var(--c-surface)', border: '1px solid var(--c-line-card)', borderRadius: 0, color: 'var(--c-ink)' }}
            />
            <Legend formatter={(v) => <span className="text-[11px] text-fg-3">{v}</span>} />
          </PieChart>
        </ResponsiveContainer>
      )}
    </div>
  )
}

export function ExposureCharts({ exposure }: { exposure: Exposure }) {
  const byType = exposure.byType.map((r) => ({ label: r.type, valueKrw: r.valueKrw, weight: r.weight }))
  const byCurrency = exposure.byCurrency.map((r) => ({ label: r.currency, valueKrw: r.valueKrw, weight: r.weight }))
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="익스포저" />
      <div className="grid gap-4 sm:grid-cols-2">
        <Donut title="자산유형별" data={byType} />
        <Donut title="통화별" data={byCurrency} />
      </div>
    </section>
  )
}
