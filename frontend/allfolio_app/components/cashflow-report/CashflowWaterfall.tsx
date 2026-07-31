// components/cashflow-report/CashflowWaterfall.tsx
'use client'

import {
  Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'
import type { CashflowReconciliation } from '@/types/cashflow-report'
import { fmtKrw } from '@/lib/report-format'

/**
 * 현금 조정표 워터폴: 기초 현금 → 유형별 증감(부호) → 기말 현금(계산).
 * reconciliation의 기존 값만 사용(부호 포함 changes) — 추가 데이터 없음.
 */
export function CashflowWaterfall({ data }: { data: CashflowReconciliation }) {
  type Step = { name: string; base: number; value: number; color: string }
  const steps: Step[] = [
    { name: '기초 현금', base: 0, value: data.openingBalance, color: '#6b7280' },
  ]

  let running = data.openingBalance
  for (const c of data.changes) {
    const amt = c.amount
    steps.push({
      name: c.type,
      base: amt >= 0 ? running : running + amt,
      value: Math.abs(amt),
      color: amt >= 0 ? '#10b981' : '#ef4444',
    })
    running += amt
  }

  steps.push({ name: '기말 현금', base: 0, value: data.closingCalculated, color: '#3b82f6' })

  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">현금 워터폴</h2>
      <p className="text-xs text-gray-500">기초 현금에서 유형별 증감을 누적해 기말 현금(계산)에 이르는 흐름입니다.</p>
      <div className="rounded-xl border border-gray-700 bg-gray-900 p-4">
        <ResponsiveContainer width="100%" height={300}>
          <BarChart data={steps} margin={{ top: 8, right: 8, bottom: 8, left: 8 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="#374151" />
            <XAxis dataKey="name" tick={{ fill: '#9ca3af', fontSize: 11 }} interval={0} angle={-15} textAnchor="end" height={60} />
            <YAxis tickFormatter={(v) => fmtKrw(v)} tick={{ fill: '#9ca3af', fontSize: 11 }} width={80} />
            <Tooltip
              formatter={(value: number, name: string) =>
                name === 'value' ? [fmtKrw(value), '금액'] : [null, null]
              }
              contentStyle={{ background: '#111827', border: '1px solid #374151' }}
            />
            <Bar dataKey="base" stackId="wf" fill="transparent" />
            <Bar dataKey="value" stackId="wf" radius={[4, 4, 0, 0]}>
              {steps.map((s, i) => <Cell key={`${s.name}-${i}`} fill={s.color} />)}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>
    </section>
  )
}
