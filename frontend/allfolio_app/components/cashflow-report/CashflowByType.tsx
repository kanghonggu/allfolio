// components/cashflow-report/CashflowByType.tsx
'use client'

import { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { CashflowByTypeRow } from '@/types/cashflow-report'
import { fmtKrw, pctColor } from '@/lib/report-format'

export function CashflowByType({ rows }: { rows: CashflowByTypeRow[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">유형별 현금흐름</h2>
      <div className="rounded-xl border border-gray-700 bg-gray-900 p-4">
        {rows.length === 0 ? (
          <div className="flex h-[240px] items-center justify-center text-sm text-gray-500">데이터 없음</div>
        ) : (
          <ResponsiveContainer width="100%" height={240}>
            <BarChart data={rows}>
              <CartesianGrid strokeDasharray="3 3" stroke="#374151" />
              <XAxis dataKey="type" tick={{ fill: '#9ca3af', fontSize: 11 }} />
              <YAxis tickFormatter={(v) => fmtKrw(v)} tick={{ fill: '#9ca3af', fontSize: 11 }} width={80} />
              <Tooltip
                formatter={(v: number) => [fmtKrw(v), '금액']}
                contentStyle={{ background: '#111827', border: '1px solid #374151' }}
              />
              <Bar dataKey="amount" radius={[4, 4, 0, 0]}>
                {rows.map((r) => (
                  <Cell key={r.type} fill={r.direction === 'IN' ? '#34d399' : '#f87171'} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        )}
      </div>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">유형</th><th className="p-3 text-right">금액</th><th className="p-3">방향</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.type} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3 font-medium text-gray-100">{r.type}</td>
                <td className={`p-3 text-right tabular-nums ${pctColor(r.amount)}`}>{fmtKrw(r.amount)}</td>
                <td className="p-3 text-gray-400">{r.direction === 'IN' ? '유입' : '유출'}</td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={3} className="p-4 text-center text-gray-500">데이터가 없습니다.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}
