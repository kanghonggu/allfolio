'use client'

import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { money } from '@/lib/format'
import Link from 'next/link'
import { useReportApi } from '@/lib/useApi'
import PageHeader from '@/components/ui/PageHeader'
import SectionHeader from '@/components/ui/SectionHeader'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import { EmptyState, ErrorState, LoadingState } from '@/components/ui/states'
import type { NetWorthBreakdown, NetWorthPoint } from '@/types/report'
import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid,
  Tooltip, ResponsiveContainer,
} from 'recharts'

const TYPE_KO: Record<string, string> = {
  STOCK: '주식', CRYPTO: '암호화폐', REAL_ESTATE: '부동산',
  VEHICLE: '자동차', GOLD: '금', CASH: '현금', ETC: '기타',
}
// 토큰 기반 그레이스케일 램프 — 유형 순서대로 진한 → 옅은
const TONES = ['var(--c-ink)', 'var(--c-fg-muted)', 'var(--c-fg-ghost)', 'var(--c-line)']

const TICK = { fontSize: 10, fill: 'var(--c-fg-faint)', fontFamily: 'var(--font-mono), monospace' } as const
const TOOLTIP_STYLE = {
  background: 'var(--c-surface)',
  border: '1px solid var(--c-line-card)',
  borderRadius: 0,
  color: 'var(--c-ink)',
} as const

// 통화 포맷은 `lib/format`의 money를 쓴다. 이 화면들은 오늘 KRW로만 부르지만 `currency`
// 파라미터가 열려 있어, 넘기는 순간 계좌 상세와 같은 방식으로 죽는다 (AF-158).
const fmt = money
function fmtShort(n: number) {
  if (Math.abs(n) >= 100_000_000) return `${(n / 100_000_000).toFixed(1)}억`
  if (Math.abs(n) >= 10_000) return `${(n / 10_000).toFixed(0)}만`
  return n.toLocaleString('ko-KR')
}

/**
 * 축 상한 후보 — 상위 10%를 뺀 최대값. **한 점짜리 튐은 잘리고, 지속되는 계단은 안 잘린다.**
 *
 * 이 구분이 이 함수의 존재 이유다. 3,800만 포트폴리오에 33억 아파트를 등록하면 100배
 * 계단이 생기는데, 그건 이 제품에서 **정상적으로 나오는 모양**이라 숨기면 안 된다. 등록
 * 이후로 계속 높은 값이면 그 점들이 상위 10%를 넘어 상한 안으로 들어온다. 반대로 하루만
 * 튄 값(검증용 자산을 넣었다 지운 2026-08-23 같은)은 잘린다.
 */
function robustMax(values: number[]): number {
  const sorted = [...values].sort((a, b) => a - b)
  return sorted[Math.min(Math.floor(sorted.length * 0.9), sorted.length - 1)]
}

/**
 * 이 배수를 넘어야 축을 제한한다. 어지간한 등락에는 손대지 않는다 —
 * 축을 건드리는 건 값을 감추는 일이라 값어치가 분명할 때만 한다.
 */
const CLIP_TRIGGER = 10

export default function NetWorthPage() {
  const reportApi = useReportApi()
  // **조기 반환보다 위에 있어야 한다** — 아래 isLoading/isError 분기 뒤에 두면 렌더마다
  // 훅 개수가 달라져 React가 순서를 잃는다.
  const [fullRange, setFullRange] = useState(false)
  const { data, isLoading, isError } = useQuery({
    queryKey: ['report', 'networth'],
    queryFn: () => reportApi!.networth(),
    enabled: !!reportApi,
  })

  if (isLoading) return <Skeleton />
  if (isError || !data) return <Err />

  const loanRatio = data.totalAssets > 0
    ? ((data.totalLoan / data.totalAssets) * 100).toFixed(1)
    : '0.0'

  const chartData = data.trend.map((p: NetWorthPoint) => ({
    date: p.date,
    nav: Number(p.nav),
  }))

  const navs = chartData.map((d) => d.nav)
  const cap = navs.length >= 2 ? Math.ceil(robustMax(navs) * 1.1) : 0
  // 상한을 넘는 점들. 비어 있으면 축을 건드릴 이유가 없다.
  const outliers = cap > 0 ? chartData.filter((d) => d.nav > cap * CLIP_TRIGGER) : []
  const clipping = outliers.length > 0 && !fullRange

  /**
   * 🔴 **축 밖 값을 상한에 붙여서 그린다. `allowDataOverflow`로는 안 된다** — 그건 축
   * 눈금만 좁힐 뿐 **데이터를 자르지 않는다.** 운영 실측(2026-09-02): 33.5억을 상한
   * 4,670만 스케일로 그리자 경로의 bounding box가 `y=-16566 · height=17202`가 됐고,
   * 화면 위로 16,000px 넘게 뻗은 경로를 브라우저가 아예 페인트하지 않아 **눈금과 좌표는
   * 다 맞는데 선만 안 보이는** 상태가 됐다. 처음 버그보다 나빴다 — 그때는 안 보이기라도
   * 했지 이건 전환 중에 잘못된 위치의 스파이크가 보였다.
   *
   * 실제 값은 [Tooltip]이 `nav`로 그대로 보여 주고, 잘린 점은 위 안내가 날짜·금액까지 적는다.
   */
  const series = chartData.map((d) => ({
    ...d,
    plotted: clipping ? Math.min(d.nav, cap) : d.nav,
  }))

  const toneByType = new Map(data.byType.map((b: NetWorthBreakdown, i: number) => [b.type, TONES[i % TONES.length]]))

  return (
    <div className="border border-line-card bg-surface">
      <div className="px-5 pt-5 sm:px-7">
        <Link
          href="/unified/reports"
          className="font-mono text-[10px] uppercase tracking-label text-fg-muted transition-colors hover:text-ink"
        >
          ← 보고서
        </Link>
      </div>
      <PageHeader
        className="px-5 pt-2 sm:px-7"
        title="순자산 추이"
        meta={<span>B-07 · 생성 {new Date(data.generatedAt).toLocaleString('ko-KR')}</span>}
      />

      <div className="px-5 py-5 pb-10 sm:px-7">
        {/* 핵심 KPI */}
        <div className="grid grid-cols-1 gap-px border border-line-soft bg-line-soft sm:grid-cols-3">
          <div className="bg-surface px-3.5 py-3">
            <Label size="sm" tone="faint">총 자산</Label>
            <Num className="mt-1 block text-[20px]">{fmt(data.totalAssets)}</Num>
          </div>
          <div className="bg-surface px-3.5 py-3">
            <Label size="sm" tone="faint">총 부채</Label>
            <Num tone={data.totalLoan > 0 ? 'loss' : 'flat'} className="mt-1 block text-[20px]">
              {data.totalLoan > 0 ? `-${fmt(data.totalLoan)}` : '—'}
            </Num>
            {data.totalLoan > 0 && (
              <p className="mt-0.5 text-[11px] text-fg-faint">자산 대비 {loanRatio}%</p>
            )}
          </div>
          <div className="bg-surface px-3.5 py-3">
            <Label size="sm" tone="faint">순자산 (NAV - 부채)</Label>
            <Num className={`mt-1 block text-[20px] ${data.netWorth >= 0 ? '' : 'text-loss'}`}>
              {fmt(data.netWorth)}
            </Num>
          </div>
        </div>

        {/* 순자산 추이 차트 */}
        <section className="mt-8">
          <SectionHeader label="총 자산(NAV) 추이" />

          {/* 극단값이 있을 때만 나온다 — 평소에는 축 선택지를 보일 이유가 없다 */}
          {outliers.length > 0 && (
            <div className="mb-3 flex flex-wrap items-center gap-x-3 gap-y-2">
              <div className="flex gap-2">
                {([['축 제한', false], ['전체 범위', true]] as const).map(([label, on]) => (
                  <button
                    key={label}
                    type="button"
                    onClick={() => setFullRange(on)}
                    className={`border px-3 py-1 font-mono text-[10px] tracking-label transition-colors ${
                      fullRange === on
                        ? 'border-ink bg-ink text-white'
                        : 'border-line bg-surface text-fg-3 hover:border-ink hover:text-ink'
                    }`}
                  >
                    {label}
                  </button>
                ))}
              </div>
              {/* 잘린 값을 숨기지 않는다 — 날짜와 금액을 그대로 적는다 */}
              <p className="text-[11px] text-fg-faint">
                {clipping
                  ? `축 상한 ₩${fmtShort(cap)} 밖 ${outliers.length}건: `
                    + outliers.map((o) => `${o.date} ₩${fmtShort(o.nav)}`).join(' · ')
                  : `극단값 ${outliers.length}건이 눈금을 차지해 나머지가 바닥선에 눌립니다.`}
              </p>
            </div>
          )}

          {chartData.length >= 2 ? (
            <ResponsiveContainer width="100%" height={280}>
              <AreaChart data={series} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="var(--c-line)" />
                <XAxis dataKey="date" tick={TICK} tickLine={false} axisLine={{ stroke: 'var(--c-line)' }} />
                <YAxis
                  // 눈금을 상한에 고정한다. 데이터는 위 `series`에서 이미 상한으로
                  // 잘라 넘기므로 `allowDataOverflow`는 필요 없다 — 오히려 그걸 쓰면
                  // 데이터가 안 잘려 경로가 화면 밖 수천 px까지 뻗는다(`series` 주석 참고).
                  domain={clipping ? [0, cap] : undefined}
                  tickFormatter={(v) => `₩${fmtShort(v)}`}
                  tick={TICK}
                  tickLine={false}
                  axisLine={false}
                  width={70}
                />
                <Tooltip
                  // 잘린 점은 상한 값으로 그려지므로, 툴팁은 원래 값(nav)을 보여 준다
                  formatter={(v: number, _n, item: { payload?: { nav?: number } }) =>
                    [fmt(item?.payload?.nav ?? v), '총 자산']}
                  contentStyle={TOOLTIP_STYLE}
                  labelStyle={{ color: 'var(--c-fg-3)' }}
                />
                <Area
                  type="monotone"
                  dataKey="plotted"
                  name="총 자산"
                  stroke="var(--c-ink)"
                  strokeWidth={1.5}
                  fill="var(--c-ink)"
                  fillOpacity={0.06}
                  dot={false}
                />
              </AreaChart>
            </ResponsiveContainer>
          ) : (
            <EmptyState
              title="자산 이력이 부족합니다"
              description="매일 Sync를 실행하면 추이 그래프가 채워집니다."
            />
          )}
        </section>

        {/* 유형별 순자산 */}
        <section className="mt-8">
          <SectionHeader label="유형별 순자산" />
          <div className="overflow-x-auto">
            <div className="min-w-[560px] border-t-[1.5px] border-ink">
              <div className="grid grid-cols-[14px_1.2fr_1fr_1fr_1.2fr_0.7fr] items-baseline gap-2.5 border-b border-line py-2">
                <span />
                <Label size="sm" tone="faint">유형</Label>
                <Label size="sm" tone="faint" className="text-right">자산</Label>
                <Label size="sm" tone="faint" className="text-right">부채</Label>
                <Label size="sm" tone="faint" className="text-right">순자산</Label>
                <Label size="sm" tone="faint" className="text-right">비중</Label>
              </div>
              {data.byType.map((b: NetWorthBreakdown) => {
                const nw = Number(b.netWorth)
                const isPos = nw >= 0
                return (
                  <div
                    key={b.type}
                    className="grid grid-cols-[14px_1.2fr_1fr_1fr_1.2fr_0.7fr] items-center gap-2.5 border-b border-line-hair py-2.5 hover:bg-surface-muted"
                    aria-label={TYPE_KO[b.type] ?? b.type}
                  >
                    <span
                      className="block h-[7px] w-[7px] shrink-0"
                      aria-hidden="true"
                      style={{ background: toneByType.get(b.type) }}
                    />
                    <span className="text-[13px]">{TYPE_KO[b.type] ?? b.type}</span>
                    <Num className="text-right text-xs text-fg-3">{fmt(Number(b.assets))}</Num>
                    <span className="text-right">
                      {Number(b.loan) > 0 ? (
                        <Num tone="loss" className="text-xs">-{fmt(Number(b.loan))}</Num>
                      ) : (
                        <span className="text-xs text-fg-ghost">—</span>
                      )}
                    </span>
                    <Num className={`text-right text-[12.5px] font-medium ${isPos ? '' : 'text-loss'}`}>
                      {fmt(nw)}
                    </Num>
                    <Num className="text-right text-xs text-fg-faint">{Number(b.pct).toFixed(1)}%</Num>
                  </div>
                )
              })}
            </div>
          </div>

          {/* 비중 바 */}
          <div className="mt-4 flex h-2 bg-surface-muted">
            {data.byType.filter(b => Number(b.netWorth) > 0).map((b: NetWorthBreakdown) => (
              <div
                key={b.type}
                style={{ width: `${Number(b.pct)}%`, background: toneByType.get(b.type) }}
                title={`${TYPE_KO[b.type] ?? b.type}: ${Number(b.pct).toFixed(1)}%`}
              />
            ))}
          </div>
        </section>

        {/* 부채 안내 */}
        {data.totalLoan > 0 && (
          <div className="mt-8 border border-warn-line bg-warn-bg px-3.5 py-2.5 text-xs leading-relaxed text-warn">
            부채 비율 {loanRatio}% — 일반적으로 자산 대비 부채 비율 40% 이하를 권장합니다.
          </div>
        )}
      </div>
    </div>
  )
}

function Skeleton() {
  return (
    <div className="border border-line-card bg-surface px-5 sm:px-7">
      <LoadingState label="보고서 불러오는 중" />
    </div>
  )
}
function Err() {
  return (
    <div className="border border-line-card bg-surface px-5 sm:px-7">
      <ErrorState message="보고서를 불러올 수 없습니다." />
    </div>
  )
}
