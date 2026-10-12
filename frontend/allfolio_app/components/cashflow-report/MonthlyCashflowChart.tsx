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
 *
 * 인쇄물엔 호버가 없으므로 Tooltip에만 있던 값을 SVG `<text>`로 항상 렌더하고, 화면에서는
 * `globals.css`의 `@media screen { .print-only-label { display: none } }`으로 가린다.
 * 세로쓰기(`rotate(-90)`)는 개월 수가 늘어도 이웃 라벨과 부딪히지 않게 하기 위함이다 —
 * 12개월 36개 라벨에서 이웃 간 겹침이 0이었다.
 *
 * ## 🔴 위치 — 잘리는 자리는 상단에 붙인다
 *
 * 막대 위에만 그렸더니 **가장 높은 막대의 라벨이 차트 상단을 넘어 잘렸다**
 * (실측 최대 25.9px, 12개월 케이스 5개).
 *
 * 1차 수정은 "라벨을 품을 만큼 긴 막대는 안쪽"이었다. **그 규칙만으로는 부족했다** —
 * 브라우저 실측에서 3개가 여전히 잘렸다(최대 16.9px). 이유는 `net`이 음수가 되면
 * Y 도메인이 0 아래로 내려가고, **막대가 플롯 바닥이 아니라 0선에서 자라기** 때문이다.
 * 짧은 막대가 높은 자리에서 시작할 수 있어 "짧으면 안 잘린다"가 성립하지 않는다.
 *
 * 그래서 마지막에 **상단으로 clamp**한다. recharts가 넘기는 props에는 플롯 영역이 없고
 * (`offset·x·y·width·height·value·viewBox·index`가 전부이며 `viewBox`는 막대 자신이다)
 * 차트 margin top이 곧 플롯 상단이라 그 값을 상수로 둔다. clamp가 걸린 라벨은 막대
 * 윗부분에 걸치므로, **겹친 길이가 절반을 넘으면 흰 글자로 뺀다**.
 *
 * ## 여유(clearance) — 칼날은 도달 불가 구간에 있다 (AF-211)
 *
 * 막대 라벨과 선 라벨은 **같은 평면에 놓인다.** 회전 라벨의 수평 폭은 글꼴 높이에서 나오므로
 * **10px 고정**이고(금액 자릿수와 무관), 두 라벨 중심의 수평 거리는 컬럼 폭과 월 수만으로
 * 정해진다. 그래서 12개월·630px에서 그 거리가 정확히 10px — **여유가 0.000px**이다.
 * 겹침 0은 설계된 여유가 아니라 **우연한 경계값**이라서, 월이 늘거나 폭이 좁아지면 즉시 깨진다
 * (실측 겹침 쌍: 18개월 30 · 24개월 40 · 12개월@420px 20 — 630px에서는 전부 막대↔선,
 * 420px·18개월 이상에서는 막대↔막대도 35쌍 추가).
 *
 * **그런데 그 구간은 제품에서 도달할 수 없다.** 기간 선택 UI는 연·월 드롭다운 둘뿐이고
 * (`app/unified/reports/cashflow-report/page.tsx`), 생성 API는
 * `GenerateRequest(type, year, month)` → `ReportPeriod.monthly(year, month)`로 **항상 한 달**이다
 * (`ReportArchiveController.kt`. 월말 마감 `ClosingActions.kt`도 같은 `monthly()`를 쓴다).
 * flows·trades는 그 기간으로 잘려 조회되므로(`findByUserIdAndFlowDateBetween…`)
 * `monthly` 행은 **최대 1개**다. 실측 도달 케이스는 라벨 3개 · 겹침 0 ·
 * **수평 여유 99px(630px) / 57px(420px)** · 상단 잘림 0 · 범례 교차 0 — 두 라벨이 수직으로도
 * 안 겹쳐서 x가 어떻든 부딪히지 않는다.
 *
 * 그래서 좌표를 손대지 않았다. **다중 월이 도달 가능해지는 순간**(기간을 범위로 바꾸거나
 * `ReportPeriod`를 월 밖으로 넓히면) 이 라벨은 바로 겹친다 — 그때 `offset` 분리나 `barGap`
 * 조정으로 여유를 만들 것. 측정은 `getBoundingClientRect()` 전수 교차로 하고,
 * **범례 교차까지 함께** 잰다(AF-206 1라운드가 놓친 자리다).
 */
const LABEL_CHAR_W = 4.9  // 8px 모노스페이스 한 글자 폭(실측)
const GAP = 12            // 막대 밖 라벨과 윗변 사이
/** 플롯 상단 = ComposedChart의 margin top. **margin을 바꾸면 여기도 바꿀 것** */
const PLOT_TOP = 5

function PrintValueLabel(props: {
  x?: number | string
  y?: number | string
  width?: number | string
  height?: number | string
  value?: number | string
}) {
  const { value } = props
  if (value == null || value === '') return null
  const x = Number(props.x ?? 0)
  const y = Number(props.y ?? 0)
  const width = Number(props.width ?? 0)
  const height = Math.abs(Number(props.height ?? 0))

  const text = fmtKrw(Number(value))
  const extent = text.length * LABEL_CHAR_W
  // 품을 만큼 긴 막대는 안쪽, 아니면 윗변 위. 그래도 상단을 넘으면 상단에 붙인다 —
  // 이 clamp가 "안 잘린다"를 보장하는 유일한 장치다.
  const fits = Number.isFinite(height) && height >= extent + GAP
  const cx = x + width / 2
  // 바깥쪽은 **라벨의 아랫변**이 윗변에서 GAP만큼 떨어지게 둔다. 중심을 `y - GAP`에 두면
  // 세로쓰기라 아래 절반(≈extent/2)이 자기 막대 안으로 들어가 진한 바탕에 검은 글자가 된다 —
  // 실측에서 36개 중 19개가 그랬다.
  const cy = Math.max(
    PLOT_TOP + extent / 2,
    fits ? y + GAP / 2 + extent / 2 : y - GAP - extent / 2,
  )
  // 막대와 세로로 겹친 길이가 절반을 넘으면 진한 막대 위라 흰 글자여야 한다
  const overlap = Math.min(y + height, cy + extent / 2) - Math.max(y, cy - extent / 2)
  const onBar = Number.isFinite(height) && height > 0 && overlap > extent / 2

  return (
    <text
      // 안쪽 라벨은 진한 막대(#b4232c·#14509b) 위에 얹힌다. 인쇄 CSS가 모든 svg text를
      // #111로 강제하므로, 더 구체적인 선택자로 흰 글자로 되돌린다(globals.css 참조).
      className={onBar ? 'print-only-label print-label-inside' : 'print-only-label'}
      x={cx}
      y={cy}
      transform={`rotate(-90 ${cx} ${cy})`}
      textAnchor="middle"
      dominantBaseline="central"
      fill={onBar ? 'var(--c-surface)' : 'var(--c-ink)'}
      // **테두리로 바탕에서 떼어 낸다.** 좌표만으로는 못 막는다 — 이웃 막대나 선 위에
      // 걸치는 라벨이 남고(실측 7건), 그건 자기 막대가 아니라서 렌더러가 알 수 없다.
      // 반대 색 halo를 두르면 바탕이 무엇이든 읽힌다. 인쇄 CSS는 fill만 강제하므로
      // stroke는 그대로 산다.
      stroke={onBar ? 'var(--c-ink)' : 'var(--c-surface)'}
      strokeWidth={2.5}
      paintOrder="stroke"
      strokeLinejoin="round"
      fontSize={8}
      fontFamily="var(--font-mono), monospace"
    >
      {text}
    </text>
  )
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
                <LabelList dataKey="inflow" content={PrintValueLabel} />
              </Bar>
              <Bar dataKey="outflow" fill="var(--c-loss)" name="유출" isAnimationActive={false}>
                <LabelList dataKey="outflow" content={PrintValueLabel} />
              </Bar>
              <Line dataKey="net" stroke="var(--c-ink)" name="순흐름" strokeWidth={2} dot={{ r: 3 }} isAnimationActive={false}>
                <LabelList dataKey="net" content={PrintValueLabel} />
              </Line>
            </ComposedChart>
          </ResponsiveContainer>
        )}
      </div>
    </section>
  )
}
