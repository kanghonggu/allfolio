// components/cashflow-report/MonthlyCashflowChart.tsx
'use client'

import {
  Bar, CartesianGrid, ComposedChart, Legend, Line, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'
import SectionHeader from '@/components/ui/SectionHeader'
import type { CashflowMonthly } from '@/types/cashflow-report'
import { fmtKrw } from '@/lib/report-format'

export function MonthlyCashflowChart({ rows }: { rows: CashflowMonthly[] }) {
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="월별 추이" />
      <div className="border-t-[1.5px] border-ink pt-3">
        {rows.length === 0 ? (
          <div className="flex h-[260px] items-center justify-center text-[12px] text-fg-faint">데이터 없음</div>
        ) : (
          <ResponsiveContainer width="100%" height={260}>
            <ComposedChart data={rows}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--c-line)" />
              <XAxis
                dataKey="month"
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
                formatter={(v: number, name: string) => [fmtKrw(v), name]}
                contentStyle={{ background: 'var(--c-surface)', border: '1px solid var(--c-line-card)', borderRadius: 0, color: 'var(--c-ink)' }}
              />
              <Legend formatter={(v) => <span className="text-[11px] text-fg-3">{v}</span>} />
              <Bar dataKey="inflow" fill="var(--c-gain)" name="유입" />
              <Bar dataKey="outflow" fill="var(--c-loss)" name="유출" />
              <Line dataKey="net" stroke="var(--c-ink)" name="순흐름" strokeWidth={2} dot={{ r: 3 }} />
            </ComposedChart>
          </ResponsiveContainer>
        )}
      </div>
    </section>
  )
}
