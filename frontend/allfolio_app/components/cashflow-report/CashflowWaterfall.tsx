// components/cashflow-report/CashflowWaterfall.tsx
'use client'

import {
  Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'
import SectionHeader from '@/components/ui/SectionHeader'
import type { CashflowReconciliation } from '@/types/cashflow-report'
import { fmtKrw } from '@/lib/report-format'

/**
 * 현금 조정표 워터폴: 기초 현금 → 유형별 증감(부호) → 기말 현금(계산).
 * reconciliation의 기존 값만 사용(부호 포함 changes) — 추가 데이터 없음.
 */
export function CashflowWaterfall({ data }: { data: CashflowReconciliation }) {
  type Step = { name: string; base: number; value: number; color: string }
  const steps: Step[] = [
    { name: '기초 현금', base: 0, value: data.openingBalance, color: 'var(--c-fg-muted)' },
  ]

  let running = data.openingBalance
  for (const c of data.changes) {
    const amt = c.amount
    steps.push({
      name: c.type,
      base: amt >= 0 ? running : running + amt,
      value: Math.abs(amt),
      color: amt >= 0 ? 'var(--c-gain)' : 'var(--c-loss)',
    })
    running += amt
  }

  steps.push({ name: '기말 현금', base: 0, value: data.closingCalculated, color: 'var(--c-ink)' })

  return (
    <section className="break-inside-avoid">
      <SectionHeader label="현금 워터폴" note="기초 → 유형별 증감 → 기말(계산)" />
      <div className="border-t-[1.5px] border-ink pt-3">
        <ResponsiveContainer width="100%" height={300}>
          <BarChart data={steps} margin={{ top: 8, right: 8, bottom: 8, left: 8 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--c-line)" />
            <XAxis
              dataKey="name"
              stroke="var(--c-line)"
              tick={{ fill: 'var(--c-fg-faint)', fontSize: 10, fontFamily: 'var(--font-mono), monospace' }}
              interval={0}
              angle={-15}
              textAnchor="end"
              height={60}
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
              {steps.map((s, i) => <Cell key={`${s.name}-${i}`} fill={s.color} />)}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>
    </section>
  )
}
