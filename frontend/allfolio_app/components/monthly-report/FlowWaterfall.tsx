// components/monthly-report/FlowWaterfall.tsx
'use client'

import {
  Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'
import type { FlowDecomposition } from '@/types/monthly-report'
import { fmtKrw } from '@/lib/report-format'

export function FlowWaterfall({ flow }: { flow: FlowDecomposition }) {
  let running = flow.startNav
  const steps: { name: string; base: number; value: number; color: string }[] = [
    { name: '기초 NAV', base: 0, value: flow.startNav, color: '#6b7280' },
  ]
  steps.push({
    name: '순유입',
    base: flow.netFlow >= 0 ? running : running + flow.netFlow,
    value: Math.abs(flow.netFlow),
    color: flow.netFlow >= 0 ? '#10b981' : '#ef4444',
  })
  running += flow.netFlow
  steps.push({
    name: '투자손익',
    base: flow.investmentPnl >= 0 ? running : running + flow.investmentPnl,
    value: Math.abs(flow.investmentPnl),
    color: flow.investmentPnl >= 0 ? '#34d399' : '#f87171',
  })
  steps.push({ name: '기말 NAV', base: 0, value: flow.endNav, color: '#3b82f6' })

  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">입출금 효과 분해</h2>
      <div className="rounded-xl border border-gray-700 bg-gray-900 p-4">
        <ResponsiveContainer width="100%" height={280}>
          <BarChart data={steps}>
            <CartesianGrid strokeDasharray="3 3" stroke="#374151" />
            <XAxis dataKey="name" tick={{ fill: '#9ca3af', fontSize: 12 }} />
            <YAxis tickFormatter={(v) => fmtKrw(v)} tick={{ fill: '#9ca3af', fontSize: 11 }} width={80} />
            <Tooltip
              formatter={(v: number) => fmtKrw(v)}
              contentStyle={{ background: '#111827', border: '1px solid #374151' }}
            />
            <Bar dataKey="base" stackId="wf" fill="transparent" />
            <Bar dataKey="value" stackId="wf" radius={[4, 4, 0, 0]}>
              {steps.map((s) => <Cell key={s.name} fill={s.color} />)}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>
    </section>
  )
}
