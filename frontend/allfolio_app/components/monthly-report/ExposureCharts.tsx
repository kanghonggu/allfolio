// components/monthly-report/ExposureCharts.tsx
'use client'

import { Cell, Legend, Pie, PieChart, ResponsiveContainer, Tooltip } from 'recharts'
import Label from '@/components/ui/Label'
import SectionHeader from '@/components/ui/SectionHeader'
import type { Exposure } from '@/types/monthly-report'
import { fmtKrw } from '@/lib/report-format'

// 토큰 기반 그레이스케일 램프 — 비중 순서대로 진한 → 옅은 (순환)
const COLORS = ['var(--c-ink)', 'var(--c-fg-muted)', 'var(--c-fg-ghost)', 'var(--c-line)']

type Slice = { label: string; valueKrw: number; weight: number }

function collapse(rows: Slice[]): Slice[] {
  if (rows.length <= 8) return rows
  const sorted = [...rows].sort((a, b) => b.valueKrw - a.valueKrw)
  const head = sorted.slice(0, 7)
  const tail = sorted.slice(7)
  const rest = tail.reduce((a, r) => a + r.valueKrw, 0)
  const restWeight = tail.reduce((a, r) => a + r.weight, 0)
  return [...head, { label: '기타', valueKrw: rest, weight: restWeight }]
}

/**
 * 인쇄 전용 값 — **조각이 아니라 범례에 붙인다** (AF-206).
 *
 * 처음엔 조각 바깥에 2행 라벨을 그렸는데, 조각이 6개를 넘으면 작은 조각(3% 이하)끼리
 * 라벨이 물리고 12시 방향은 SVG 상단을 넘어 잘렸다(실측: 8조각·6조각에서 각 1쌍 겹침 +
 * 상단 4.7px 잘림). 도넛 컬럼이 인쇄 A4에서 307px뿐이라 지시선으로 밀어내면 이번엔
 * 가로로 잘린다 — **조각 수가 늘수록 깨지는 구조**였다.
 *
 * 범례는 세로로 쌓이므로 조각이 몇 개든 겹칠 수가 없고, 색 스와치가 조각과 값을 잇는다.
 * 기관 리서치 보고서의 표준 형태이기도 하다. 대가는 조각에서 값까지 눈이 가는 거리다.
 *
 * 화면에서는 `globals.css`의 `@media screen { .print-only-label { display: none } }`이
 * 가린다 — 범례는 SVG가 아니라 HTML이라 같은 클래스가 그대로 통한다.
 */
function printLegendValue(rows: Slice[]) {
  const total = rows.reduce((a, r) => a + r.valueKrw, 0)
  return function Formatter(value: unknown, _entry: unknown, index: number) {
    const row = rows[index]
    // weight(0~100 스케일)가 정본. weight가 없는 구 아카이브는 금액으로 직접 낸다 —
    // recharts의 percent에 의존하지 않으므로 조각 라벨 시절의 폴백이 여기서도 산다.
    const pct = row?.weight ?? (total > 0 ? (row.valueKrw / total) * 100 : 0)
    return (
      <span className="text-[11px] text-fg-3">
        {String(value)}
        {row && (
          <span className="print-only-label font-mono">
            {' '}{pct.toFixed(1)}% {fmtKrw(row.valueKrw)}
          </span>
        )}
      </span>
    )
  }
}

function Donut({ title, data }: { title: string; data: Slice[] }) {
  const rows = collapse(data)
  return (
    <div className="border-t-[1.5px] border-ink pt-3">
      <Label size="sm" tone="faint" className="mb-2 block">{title}</Label>
      {rows.length === 0 ? (
        <div className="flex h-[240px] items-center justify-center text-[12px] text-fg-faint">데이터 없음</div>
      ) : (
        <ResponsiveContainer width="100%" height={240}>
          <PieChart>
            <Pie
              data={rows}
              dataKey="valueKrw"
              nameKey="label"
              innerRadius={50}
              outerRadius={80}
              paddingAngle={2}
              stroke="var(--c-surface)"
            >
              {rows.map((_, i) => <Cell key={i} fill={COLORS[i % COLORS.length]} />)}
            </Pie>
            <Tooltip
              formatter={(v: number) => [fmtKrw(v), '평가액']}
              contentStyle={{ background: 'var(--c-surface)', border: '1px solid var(--c-line-card)', borderRadius: 0, color: 'var(--c-ink)' }}
            />
            <Legend formatter={printLegendValue(rows)} />
          </PieChart>
        </ResponsiveContainer>
      )}
    </div>
  )
}

export function ExposureCharts({ exposure }: { exposure: Exposure }) {
  const byType = exposure.byType.map((r) => ({ label: r.type, valueKrw: r.valueKrw, weight: r.weight }))
  const byCurrency = exposure.byCurrency.map((r) => ({ label: r.currency, valueKrw: r.valueKrw, weight: r.weight }))
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="익스포저" />
      <div className="grid gap-4 sm:grid-cols-2">
        <Donut title="자산유형별" data={byType} />
        <Donut title="통화별" data={byCurrency} />
      </div>
    </section>
  )
}
