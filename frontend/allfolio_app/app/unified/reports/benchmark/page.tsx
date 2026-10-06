'use client'

import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useReportApi } from '@/lib/useApi'
import type { BenchmarkItem, BenchmarkSeries } from '@/types/report'
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip,
  ResponsiveContainer, ReferenceLine, Legend,
  BarChart, Bar, Cell,
} from 'recharts'
import PageHeader from '@/components/ui/PageHeader'
import SectionHeader from '@/components/ui/SectionHeader'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import InfoTip from '@/components/ui/InfoTip'
import { METRIC_HELP } from '@/lib/metric-help'
import { LoadingState, ErrorState, EmptyState } from '@/components/ui/states'
import { dirTone, toneText } from '@/lib/format'

const PERIODS = ['1W', '1M', '3M', 'YTD', '1Y'] as const
type Period = typeof PERIODS[number]

const PERIOD_KO: Record<string, string> = {
  '1W': '1주', '1M': '1개월', '3M': '3개월', 'YTD': '연초 이후', '1Y': '1년',
}

const TOOLTIP_STYLE = {
  background: 'var(--c-surface)',
  border: '1px solid var(--c-line-card)',
  borderRadius: 0,
  color: 'var(--c-ink)',
} as const
const TICK_STYLE = { fontSize: 10, fill: 'var(--c-fg-faint)', fontFamily: 'monospace' } as const

/**
 * 지수 이름(서버 `BenchmarkItem.name` = `BenchmarkType.label`) → 시계열의 `dataKey`.
 *
 * **둘이 다르다** — `Bitcoin` 칩이 끄고 켜는 선의 키는 `btc`다. 칩 쪽에서 `'BTC'`를 찾으면
 * 아무것도 안 걸려 **칩이 조용히 무력화된다**(오류도 안 난다). 여기 한 곳에 묶어 둔다.
 */
const SERIES_KEY: Record<string, string> = {
  'S&P 500': 'S&P 500',
  'Bitcoin': 'BTC',
  'KOSPI': 'KOSPI',
}

function fmtPct(n: number) {
  return `${n >= 0 ? '+' : ''}${n.toFixed(2)}%`
}

export default function BenchmarkPage() {
  const reportApi = useReportApi()
  const [period, setPeriod] = useState<Period>('YTD')
  /**
   * 켜 둘 지수. `null`이면 아직 사용자가 안 건드린 상태라 **보유 시장(`held`)을 기본으로** 쓴다
   * (AF-107: *"칩으로 토글. 항상 켜두지 않는다"* · *"국내주식만 가진 사용자에게 항셍은 소음이다"*).
   * 보유 판정은 통화 기준 근사라 틀릴 수 있어 **목록에서 지우지는 않는다** — 꺼진 칩으로 남는다.
   */
  const [shown, setShown] = useState<Set<string> | null>(null)

  const { data, isLoading, isError } = useQuery({
    queryKey: ['report', 'benchmark', period],
    queryFn: () => reportApi!.benchmark(period),
    enabled: !!reportApi,
  })

  if (isLoading) return <Skeleton />
  if (isError || !data) return <Err />

  const visible = shown ?? new Set(data.benchmarks.filter((b: BenchmarkItem) => b.held).map((b) => b.name))
  const picked = data.benchmarks.filter((b: BenchmarkItem) => visible.has(b.name))
  const shownKeys = new Set(Array.from(visible, (n) => SERIES_KEY[n] ?? n))
  const toggle = (name: string) => {
    const next = new Set(visible)
    if (next.has(name)) next.delete(name); else next.add(name)
    setShown(next)
  }

  // 🔴 `portfolioReturn`은 null일 수 있다 — 스냅샷이 선택 기간을 못 덮는 경우다.
  // **`Number(null)`은 0이라 그대로 두면 "+0.00%"를 지어낸다.** 타입스크립트가 안 잡아 준다.
  const pr = data.portfolioReturn
  const barData = [
    ...(pr !== null ? [{ name: '내 포트폴리오', value: Number(pr), color: 'var(--c-ink)' }] : []),
    ...picked.map((b: BenchmarkItem) => ({
      name: b.name,
      value: Number(b.benchmarkReturn),
      // 한국 관례: 상승 빨강(gain) / 하락 파랑(loss)
      color: Number(b.benchmarkReturn) >= 0 ? 'var(--c-gain)' : 'var(--c-loss)',
    })),
  ]

  const chartData = data.series.map((s: BenchmarkSeries) => ({
    date: s.date,
    portfolio: Number(s.portfolio),
    'S&P 500': s.sp500 !== null ? Number(s.sp500) : null,
    BTC: s.btc !== null ? Number(s.btc) : null,
    KOSPI: s.kospi !== null ? Number(s.kospi) : null,
  }))

  return (
    <div className="border border-line-card bg-surface">
      <div className="px-5 pt-4 sm:px-7">
        <Link
          href="/unified/reports"
          className="font-mono text-[10px] tracking-label text-fg-faint transition-colors hover:text-ink"
        >
          ← 보고서
        </Link>
      </div>
      <PageHeader
        className="px-5 pt-2 sm:px-7"
        title="벤치마크 비교"
        meta="B-06 · 스냅샷 기반 자동 산출"
      />

      <div className="px-5 py-5 pb-10 sm:px-7">
        {/* Period selector */}
        <div className="flex flex-wrap gap-2">
          {PERIODS.map((p) => (
            <button
              key={p}
              onClick={() => setPeriod(p)}
              className={`border px-3.5 py-1.5 font-mono text-[10px] tracking-label transition-colors ${
                period === p
                  ? 'border-ink bg-ink text-white'
                  : 'border-line bg-surface text-fg-3 hover:border-ink hover:text-ink'
              }`}
            >
              {PERIOD_KO[p]}
            </button>
          ))}
        </div>

        {/* Portfolio return */}
        <div className="mt-6 border border-line-soft bg-surface px-4 py-4">
          <Label size="sm" tone="faint">내 포트폴리오 수익률 ({PERIOD_KO[period]})</Label>
          {pr !== null ? (
            <Num tone={dirTone(Number(pr))} className="mt-1.5 block text-[26px]">
              {fmtPct(Number(pr))}
            </Num>
          ) : (
            <>
              <span className="mt-1.5 block font-mono text-[22px] text-fg-faint">—</span>
              <p className="mt-1.5 text-[11.5px] leading-relaxed text-fg-3">
                일별 스냅샷이 이 기간을 덮지 못합니다. 짧은 기간을 고르면 표시됩니다.
                <strong className="font-normal text-ink"> 취득가 기준 수익률로 대신 채우지 않습니다</strong>
                {' '}— 그 값은 기간 수익률이 아니라 지수와 나란히 둘 수 없습니다.
              </p>
            </>
          )}
        </div>

        {/* 지수 칩 — 보유 시장이 기본값 */}
        {data.benchmarks.length > 0 && (
          <div className="mt-3 flex flex-wrap items-center gap-2">
            <Label size="sm" tone="faint" className="mr-1">비교 지수</Label>
            {data.benchmarks.map((b: BenchmarkItem) => {
              const on = visible.has(b.name)
              return (
                <button
                  key={b.name}
                  onClick={() => toggle(b.name)}
                  aria-pressed={on}
                  className={`border px-3 py-1 font-mono text-[10px] tracking-label transition-colors ${
                    on
                      ? 'border-ink bg-ink text-white'
                      : 'border-line bg-surface text-fg-faint hover:border-ink hover:text-ink'
                  }`}
                >
                  {b.name}{!b.held && <span className="ml-1 opacity-60">·미보유</span>}
                </button>
              )
            })}
          </div>
        )}

        {/* Alpha Cards — 지수 데이터 없으면 명시적 빈 상태 (합성값 표시 금지) */}
        {data.benchmarks.length === 0 && (
          <p className="mt-3 border border-line-soft bg-surface-muted px-4 py-4 text-[12.5px] leading-relaxed text-fg-3">
            벤치마크 지수 데이터가 아직 수집되지 않았습니다. 지수 시세는 매일 새벽 자동
            동기화되며, 수집되는 대로 실제 지수 기준 비교가 표시됩니다.
          </p>
        )}
        {/* 칸 수가 켜진 지수를 따라간다 — 3칸 고정이면 하나만 켰을 때 빈 칸 둘이 회색으로 남는다 */}
        {picked.length > 0 && (
          <div
            className="mt-3 grid gap-px border border-line-soft bg-line-soft"
            style={{ gridTemplateColumns: `repeat(${Math.min(picked.length, 3)}, minmax(0, 1fr))` }}
          >
            {picked.map((b: BenchmarkItem) => {
              const benchClass = toneText[dirTone(Number(b.benchmarkReturn))]
              return (
                <div key={b.name} className="bg-surface px-3.5 py-3">
                  <InfoTip label={`${b.name} 수익률`} text={METRIC_HELP.benchmarkIndexReturn}>
                    <Label size="sm" tone="faint">{b.name}</Label>
                  </InfoTip>
                  <Num className={`mt-1 block text-[16px] ${benchClass}`}>
                    {fmtPct(Number(b.benchmarkReturn))}
                  </Num>
                  <div className="mt-3 border-t border-line-hair pt-3">
                    <InfoTip label="알파" text={METRIC_HELP.benchmarkAlpha}>
                      <Label size="sm" tone="faint">알파 (초과 수익)</Label>
                    </InfoTip>
                    {/* 기저가 없으면 알파도 없다 — 0으로 적으면 "시장과 똑같았다"로 읽힌다 */}
                    {b.alpha !== null ? (
                      <Num className={`mt-1 block text-[14px] ${toneText[dirTone(Number(b.alpha))]}`}>
                        {fmtPct(Number(b.alpha))}
                      </Num>
                    ) : (
                      <span className="mt-1 block font-mono text-[14px] text-fg-faint">—</span>
                    )}
                  </div>
                </div>
              )
            })}
          </div>
        )}

        {/* Bar Chart Comparison */}
        <section className="mt-8">
          <SectionHeader label={`수익률 비교 (${PERIOD_KO[period]})`} />
          <ResponsiveContainer width="100%" height={240}>
            <BarChart data={barData} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--c-line)" vertical={false} />
              <XAxis dataKey="name" tick={TICK_STYLE} axisLine={false} tickLine={false} />
              <YAxis
                tickFormatter={(v) => `${v.toFixed(0)}%`}
                tick={TICK_STYLE}
                axisLine={false}
                tickLine={false}
              />
              <Tooltip
                formatter={(v: number) => [`${v.toFixed(2)}%`, '수익률']}
                contentStyle={TOOLTIP_STYLE}
                labelStyle={{ color: 'var(--c-fg-muted)' }}
              />
              <ReferenceLine y={0} stroke="var(--c-line)" />
              <Bar dataKey="value">
                {barData.map((entry, index) => (
                  <Cell key={index} fill={entry.color} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </section>

        {/* Cumulative Return Chart */}
        <section className="mt-8">
          <SectionHeader label="누적 수익률 비교" />
          {chartData.length > 0 ? (
            <ResponsiveContainer width="100%" height={360}>
              <LineChart data={chartData} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="var(--c-line)" />
                <XAxis
                  dataKey="date"
                  tick={TICK_STYLE}
                  tickLine={false}
                  interval="preserveStartEnd"
                />
                <YAxis
                  tickFormatter={(v) => `${v.toFixed(0)}%`}
                  tick={TICK_STYLE}
                  axisLine={false}
                  tickLine={false}
                />
                <Tooltip
                  formatter={(v: number, name) => [`${v.toFixed(2)}%`, name]}
                  contentStyle={TOOLTIP_STYLE}
                  labelStyle={{ color: 'var(--c-fg-muted)' }}
                />
                <ReferenceLine y={0} stroke="var(--c-line)" strokeDasharray="4 4" />
                <Legend formatter={(v) => <span className="font-mono text-[10px] text-fg-3">{v}</span>} />
                <Line type="monotone" dataKey="portfolio" name="내 포트폴리오" stroke="var(--c-ink)" strokeWidth={2.5} dot={false} />
                {shownKeys.has('S&P 500') && <Line type="monotone" dataKey="S&P 500" stroke="var(--c-fg-muted)" strokeWidth={1.5} dot={false} strokeDasharray="5 5" connectNulls />}
                {shownKeys.has('BTC') && <Line type="monotone" dataKey="BTC" stroke="var(--c-fg-ghost)" strokeWidth={1.5} dot={false} strokeDasharray="5 5" connectNulls />}
                {shownKeys.has('KOSPI') && <Line type="monotone" dataKey="KOSPI" stroke="var(--c-line)" strokeWidth={1.5} dot={false} strokeDasharray="5 5" connectNulls />}
              </LineChart>
            </ResponsiveContainer>
          ) : (
            <EmptyState title="데이터 없음" />
          )}
        </section>

        {/*
          배당 처리 (AF-107의 ⚠️). **사용자 TWR에는 받은 배당이 들어가는데 KOSPI·S&P 500은
          가격지수라 배당이 빠져 있다.** 그대로 겹치면 사용자가 실제보다 시장을 이긴 것처럼 보인다.
          TR(총수익) 지수로 바꾸는 것이 정답이지만 두 소스 모두 가격지수만 주므로, 지금은
          **각주로 방향과 크기를 밝힌다** — 이 차이는 사용자에게 유리한 쪽으로만 치우친다.
          TR 시계열이 생기면 각주가 아니라 지수를 바꿀 것.
        */}
        <p className="mt-4 border-l-2 border-line pl-3 text-[11.5px] leading-relaxed text-fg-3">
          <strong className="font-normal text-ink">* 배당만큼 내 수익률이 유리하게 보입니다.</strong>{' '}
          내 수익률에는 받은 배당이 포함되지만 KOSPI·S&amp;P 500은 <strong className="font-normal text-ink">가격지수</strong>라
          배당이 빠져 있습니다. 알파에는 그 차이(국내 약 1.5~2%p/년, 미국 약 1.2~1.5%p/년 수준)가
          실력과 섞여 있습니다. 기간이 길수록 격차가 커집니다.
        </p>
        <p className="mt-2 text-[11.5px] leading-relaxed text-fg-faint">
          * S&amp;P 500, BTC, KOSPI는 일별 종가 기준 실제 지수 데이터입니다. 데이터가 없는 날짜는 표시되지 않습니다.
        </p>
        {/*
          두 개의 KOSPI (AF-107). 시장 화면은 KIS 시세를 하루 세 슬롯(collect-index.yml의
          KST 09:10 OPEN · 12:10 MID · 15:50 CLOSE)으로 담고, 여기는 공공데이터포털 D+1 확정 종가다.
          **"실시간"이라고 쓰지 말 것** — 스트림이 아니라 스냅샷 세 번이고, CLOSE 슬롯은 그날 종가다.
          차이의 주된 원인도 장중/종가가 아니라 기준일이다: 저녁에 두 화면을 비교하면 시장 화면엔
          오늘 종가가 있고 벤치마크는 아직 어제까지다. 사용자가 그 두 숫자를 나란히 봤을 때
          답이 화면에 있어야 하므로 지우지 말 것.
        */}
        <p className="mt-2 text-[11.5px] leading-relaxed text-fg-faint">
          * KOSPI는 공공데이터포털의 확정 종가 기준입니다. 시장 화면의 KOSPI는 KIS 시세를 하루
          세 번(개장 직후·장중·마감 직후) 담은 값이라 값·기준일이 다를 수 있습니다. 확정 종가는
          다음 날 들어오므로 벤치마크의 최신 날짜는 시장 화면보다 하루 늦습니다.
        </p>
      </div>
    </div>
  )
}

function Skeleton() {
  return (
    <div className="border border-line-card bg-surface px-5 sm:px-7">
      <LoadingState />
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
