// components/monthly-report/ExposureCharts.tsx
'use client'

import { Cell, Legend, Pie, PieChart, ResponsiveContainer, Tooltip } from 'recharts'
import Label from '@/components/ui/Label'
import SectionHeader from '@/components/ui/SectionHeader'
import type { Exposure } from '@/types/monthly-report'
import { fmtKrw } from '@/lib/report-format'

// 토큰 기반 그레이스케일 램프 — 비중 순서대로 진한 → 옅은 (순환)
const COLORS = ['var(--c-ink)', 'var(--c-fg-muted)', 'var(--c-fg-ghost)', 'var(--c-line)']

function collapse(rows: { label: string; valueKrw: number }[]) {
  if (rows.length <= 8) return rows
  const sorted = [...rows].sort((a, b) => b.valueKrw - a.valueKrw)
  const head = sorted.slice(0, 7)
  const rest = sorted.slice(7).reduce((a, r) => a + r.valueKrw, 0)
  return [...head, { label: '기타', valueKrw: rest }]
}

function Donut({ title, data }: { title: string; data: { label: string; valueKrw: number }[] }) {
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
  const byType = exposure.byType.map((r) => ({ label: r.type, valueKrw: r.valueKrw }))
  const byCurrency = exposure.byCurrency.map((r) => ({ label: r.currency, valueKrw: r.valueKrw }))
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
