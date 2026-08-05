// components/monthly-report/FlowWaterfall.tsx
'use client'

import {
  Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'
import SectionHeader from '@/components/ui/SectionHeader'
import type { FlowDecomposition } from '@/types/monthly-report'
import { fmtKrw } from '@/lib/report-format'

export function FlowWaterfall({ flow }: { flow: FlowDecomposition }) {
  let running = flow.startNav
  const steps: { name: string; base: number; value: number; color: string }[] = [
    { name: '기초 NAV', base: 0, value: flow.startNav, color: 'var(--c-fg-muted)' },
  ]
  steps.push({
    name: '순유입',
    base: flow.netFlow >= 0 ? running : running + flow.netFlow,
    value: Math.abs(flow.netFlow),
    color: flow.netFlow >= 0 ? 'var(--c-gain)' : 'var(--c-loss)',
  })
  running += flow.netFlow
  steps.push({
    name: '투자손익',
    base: flow.investmentPnl >= 0 ? running : running + flow.investmentPnl,
    value: Math.abs(flow.investmentPnl),
    color: flow.investmentPnl >= 0 ? 'var(--c-gain)' : 'var(--c-loss)',
  })
  steps.push({ name: '기말 NAV', base: 0, value: flow.endNav, color: 'var(--c-ink)' })

  return (
    <section className="break-inside-avoid">
      <SectionHeader label="입출금 효과 분해" />
      <div className="border-t-[1.5px] border-ink pt-3">
        <ResponsiveContainer width="100%" height={280}>
          <BarChart data={steps}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--c-line)" />
            <XAxis
              dataKey="name"
              stroke="var(--c-line)"
              tick={{ fill: 'var(--c-fg-faint)', fontSize: 10, fontFamily: 'var(--font-mono), monospace' }}
            />
            <YAxis
              tickFormatter={(v) => fmtKrw(v)}
              stroke="var(--c-line)"
              tick={{ fill: 'var(--c-fg-faint)', fontSize: 10, fontFamily: 'var(--font-mono), monospace' }}
              width={80}
            />
            <Tooltip
              formatter={(value: number, name: string) =>
                name === 'value' ? [fmtKrw(value), '금액'] : [null, null]
              }
              contentStyle={{ background: 'var(--c-surface)', border: '1px solid var(--c-line-card)', borderRadius: 0, color: 'var(--c-ink)' }}
            />
            <Bar dataKey="base" stackId="wf" fill="transparent" />
            <Bar dataKey="value" stackId="wf">
              {steps.map((s) => <Cell key={s.name} fill={s.color} />)}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>
    </section>
  )
}
