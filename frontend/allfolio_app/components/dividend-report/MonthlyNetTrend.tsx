// components/dividend-report/MonthlyNetTrend.tsx
'use client'

import {
  Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'
import type { DividendMonthly } from '@/types/dividend-report'
import { fmtKrw } from '@/lib/report-format'

export function MonthlyNetTrend({ rows }: { rows: DividendMonthly[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">월별 세후 수취 추이</h2>
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
                formatter={(v: number) => [fmtKrw(v), '세후']}
                contentStyle={{ background: '#111827', border: '1px solid #374151' }}
              />
              <Bar dataKey="net" fill="#10b981" radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        )}
      </div>
    </section>
  )
}
