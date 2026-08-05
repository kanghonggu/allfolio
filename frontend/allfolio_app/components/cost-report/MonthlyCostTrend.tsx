// components/cost-report/MonthlyCostTrend.tsx
'use client'

import {
  Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'
import SectionHeader from '@/components/ui/SectionHeader'
import type { CostMonthly } from '@/types/cost-report'
import { fmtKrw } from '@/lib/report-format'

export function MonthlyCostTrend({ rows }: { rows: CostMonthly[] }) {
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="월별 비용 추이" />
      <div className="border-t-[1.5px] border-ink pt-3">
        {rows.length === 0 ? (
          <div className="flex h-[240px] items-center justify-center text-[12px] text-fg-faint">데이터 없음</div>
        ) : (
          <ResponsiveContainer width="100%" height={240}>
            <BarChart data={rows}>
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
              <Bar dataKey="brokerFee" stackId="c" fill="var(--c-ink)" name="매매수수료" />
              <Bar dataKey="tradingTax" stackId="c" fill="var(--c-fg-ghost)" name="거래세" />
            </BarChart>
          </ResponsiveContainer>
        )}
      </div>
    </section>
  )
}
