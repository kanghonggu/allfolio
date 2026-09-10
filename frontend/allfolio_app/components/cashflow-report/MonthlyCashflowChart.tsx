// components/cashflow-report/MonthlyCashflowChart.tsx
'use client'

import {
  Bar, CartesianGrid, ComposedChart, LabelList, Legend, Line, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'
import SectionHeader from '@/components/ui/SectionHeader'
import type { CashflowMonthly } from '@/types/cashflow-report'
import { fmtKrw } from '@/lib/report-format'

/**
 * 인쇄 전용 값 라벨 (AF-206).
 * 인쇄물엔 호버가 없으므로 Tooltip에만 있던 값을 SVG <text>로 항상 렌더하고,
 * 화면에서는 globals.css의 `@media screen { .print-only-label { display: none } }`으로 가린다.
 * 세로쓰기(angle -90)는 개월 수가 늘어도 이웃 라벨과 부딪히지 않게 하기 위함.
 */
const printLabel = {
  className: 'print-only-label',
  position: 'top' as const,
  angle: -90,
  offset: 12,
  fill: 'var(--c-ink)',
  fontSize: 8,
  fontFamily: 'var(--font-mono), monospace',
  formatter: (v: number) => fmtKrw(v),
}

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
              <Bar dataKey="inflow" fill="var(--c-gain)" name="유입" isAnimationActive={false}>
                <LabelList dataKey="inflow" {...printLabel} />
              </Bar>
              <Bar dataKey="outflow" fill="var(--c-loss)" name="유출" isAnimationActive={false}>
                <LabelList dataKey="outflow" {...printLabel} />
              </Bar>
              <Line dataKey="net" stroke="var(--c-ink)" name="순흐름" strokeWidth={2} dot={{ r: 3 }} isAnimationActive={false}>
                <LabelList dataKey="net" {...printLabel} />
              </Line>
            </ComposedChart>
          </ResponsiveContainer>
        )}
      </div>
    </section>
  )
}
