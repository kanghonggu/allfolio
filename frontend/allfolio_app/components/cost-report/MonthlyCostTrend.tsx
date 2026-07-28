// components/cost-report/MonthlyCostTrend.tsx
'use client'

import {
  Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'
import type { CostMonthly } from '@/types/cost-report'
import { fmtKrw } from '@/lib/report-format'

export function MonthlyCostTrend({ rows }: { rows: CostMonthly[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">월별 비용 추이</h2>
      <div className="rounded-xl border border-gray-700 bg-gray-900 p-4">
        {rows.length === 0 ? (
          <div className="flex h-[240px] items-center justify-center text-sm text-gray-500">데이터 없음</div>
        ) : (
          <ResponsiveContainer width="100%" height={240}>
            <BarChart data={rows}>
              <CartesianGrid strokeDasharray="3 3" stroke="#374151" />
              <XAxis dataKey="month" tick={{ fill: '#9ca3af', fontSize: 12 }} />
              <YAxis tickFormatter={(v) => fmtKrw(v)} tick={{ fill: '#9ca3af', fontSize: 11 }} width={80} />
              <Tooltip
                formatter={(v: number, name: string) => [fmtKrw(v), name]}
                contentStyle={{ background: '#111827', border: '1px solid #374151' }}
              />
              <Legend formatter={(v) => <span className="text-xs text-gray-300">{v}</span>} />
              <Bar dataKey="brokerFee" stackId="c" fill="#3b82f6" name="매매수수료" />
              <Bar dataKey="tradingTax" stackId="c" fill="#f59e0b" name="거래세" radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        )}
      </div>
    </section>
  )
}
