// components/dividend-report/MonthlyNetTrend.tsx
'use client'

import {
  Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'
import SectionHeader from '@/components/ui/SectionHeader'
import type { DividendMonthly } from '@/types/dividend-report'
import { fmtKrw } from '@/lib/report-format'

export function MonthlyNetTrend({ rows }: { rows: DividendMonthly[] }) {
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="월별 세후 수취 추이" />
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
                formatter={(v: number) => [fmtKrw(v), '세후']}
                contentStyle={{ background: 'var(--c-surface)', border: '1px solid var(--c-line-card)', borderRadius: 0, color: 'var(--c-ink)' }}
              />
              <Bar dataKey="net" fill="var(--c-ink)" />
            </BarChart>
          </ResponsiveContainer>
        )}
      </div>
    </section>
  )
}
