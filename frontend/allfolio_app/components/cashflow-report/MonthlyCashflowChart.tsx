// components/cashflow-report/MonthlyCashflowChart.tsx
'use client'

import {
  Bar, CartesianGrid, ComposedChart, Legend, Line, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'
import type { CashflowMonthly } from '@/types/cashflow-report'
import { fmtKrw } from '@/lib/report-format'

export function MonthlyCashflowChart({ rows }: { rows: CashflowMonthly[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">월별 추이</h2>
      <div className="rounded-xl border border-gray-700 bg-gray-900 p-4">
        {rows.length === 0 ? (
          <div className="flex h-[260px] items-center justify-center text-sm text-gray-500">데이터 없음</div>
        ) : (
          <ResponsiveContainer width="100%" height={260}>
            <ComposedChart data={rows}>
              <CartesianGrid strokeDasharray="3 3" stroke="#374151" />
              <XAxis dataKey="month" tick={{ fill: '#9ca3af', fontSize: 12 }} />
              <YAxis tickFormatter={(v) => fmtKrw(v)} tick={{ fill: '#9ca3af', fontSize: 11 }} width={80} />
              <Tooltip
                formatter={(v: number, name: string) => [fmtKrw(v), name]}
                contentStyle={{ background: '#111827', border: '1px solid #374151' }}
              />
              <Legend formatter={(v) => <span className="text-xs text-gray-300">{v}</span>} />
              <Bar dataKey="inflow" fill="#34d399" name="유입" radius={[4, 4, 0, 0]} />
              <Bar dataKey="outflow" fill="#f87171" name="유출" radius={[4, 4, 0, 0]} />
              <Line dataKey="net" stroke="#60a5fa" name="순흐름" strokeWidth={2} dot={{ r: 3 }} />
            </ComposedChart>
          </ResponsiveContainer>
        )}
      </div>
    </section>
  )
}
