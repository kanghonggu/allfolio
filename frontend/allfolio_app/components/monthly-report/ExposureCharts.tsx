// components/monthly-report/ExposureCharts.tsx
'use client'

import { Cell, Legend, Pie, PieChart, ResponsiveContainer, Tooltip } from 'recharts'
import type { Exposure } from '@/types/monthly-report'
import { fmtKrw } from '@/lib/report-format'

const COLORS = ['#3b82f6', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#06b6d4', '#ec4899', '#6b7280']

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
    <div className="rounded-xl border border-gray-700 bg-gray-900 p-4">
      <p className="mb-2 text-xs text-gray-500">{title}</p>
      <ResponsiveContainer width="100%" height={240}>
        <PieChart>
          <Pie data={rows} dataKey="valueKrw" nameKey="label" innerRadius={50} outerRadius={80} paddingAngle={2}>
            {rows.map((_, i) => <Cell key={i} fill={COLORS[i % COLORS.length]} />)}
          </Pie>
          <Tooltip formatter={(v: number) => fmtKrw(v)} contentStyle={{ background: '#111827', border: '1px solid #374151' }} />
          <Legend wrapperStyle={{ fontSize: 12 }} />
        </PieChart>
      </ResponsiveContainer>
    </div>
  )
}

export function ExposureCharts({ exposure }: { exposure: Exposure }) {
  const byType = exposure.byType.map((r) => ({ label: r.type, valueKrw: r.valueKrw }))
  const byCurrency = exposure.byCurrency.map((r) => ({ label: r.currency, valueKrw: r.valueKrw }))
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">익스포저</h2>
      <div className="grid gap-4 sm:grid-cols-2">
        <Donut title="자산유형별" data={byType} />
        <Donut title="통화별" data={byCurrency} />
      </div>
    </section>
  )
}
