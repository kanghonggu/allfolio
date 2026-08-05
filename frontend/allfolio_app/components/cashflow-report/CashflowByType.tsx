// components/cashflow-report/CashflowByType.tsx
'use client'

import { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import SectionHeader from '@/components/ui/SectionHeader'
import { dirTone } from '@/lib/format'
import type { CashflowByTypeRow } from '@/types/cashflow-report'
import { fmtKrw } from '@/lib/report-format'

export function CashflowByType({ rows }: { rows: CashflowByTypeRow[] }) {
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="유형별 현금흐름" />
      <div className="border-t-[1.5px] border-ink pt-3">
        {rows.length === 0 ? (
          <div className="flex h-[240px] items-center justify-center text-[12px] text-fg-faint">데이터 없음</div>
        ) : (
          <ResponsiveContainer width="100%" height={240}>
            <BarChart data={rows}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--c-line)" />
              <XAxis
                dataKey="type"
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
                formatter={(v: number) => [fmtKrw(v), '금액']}
                contentStyle={{ background: 'var(--c-surface)', border: '1px solid var(--c-line-card)', borderRadius: 0, color: 'var(--c-ink)' }}
              />
              <Bar dataKey="amount">
                {rows.map((r) => (
                  <Cell key={r.type} fill={r.direction === 'IN' ? 'var(--c-gain)' : 'var(--c-loss)'} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        )}
      </div>
      <div className="mt-4 overflow-x-auto">
        <table className="w-full min-w-[400px] border-t-[1.5px] border-ink text-[13px]">
          <thead>
            <tr className="border-b border-line text-left">
              <th className="py-2 pr-2 font-normal"><Label size="sm" tone="faint">유형</Label></th>
              <th className="px-2 py-2 text-right font-normal"><Label size="sm" tone="faint">금액</Label></th>
              <th className="py-2 pl-2 font-normal"><Label size="sm" tone="faint">방향</Label></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.type} className="border-b border-line-hair">
                <td className="py-2.5 pr-2 font-medium text-ink">{r.type}</td>
                <td className="px-2 py-2.5 text-right"><Num tone={dirTone(r.amount)} className="text-[12.5px]">{fmtKrw(r.amount)}</Num></td>
                <td className="py-2.5 pl-2 text-fg-3">{r.direction === 'IN' ? '유입' : '유출'}</td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={3} className="py-6 text-center text-[12px] text-fg-faint">데이터가 없습니다.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}
