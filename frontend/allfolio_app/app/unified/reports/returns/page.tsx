'use client'

import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import Link from 'next/link'
import axios from 'axios'
import { useBenchmarkApi, useCashFlowApi, useReportApi } from '@/lib/useApi'
import { SUPPORTED_CURRENCIES } from '@/lib/currencies'
import { isoDate, todayIso } from '@/lib/date'
import PageHeader from '@/components/ui/PageHeader'
import SectionHeader from '@/components/ui/SectionHeader'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import Button from '@/components/ui/Button'
import Field, { Input, Select } from '@/components/ui/Field'
import { LoadingState } from '@/components/ui/states'
import type { BenchmarkType, CashFlowItem, FlowType, RecordCashFlowRequest } from '@/types/returns'
import {
  Bar, BarChart, CartesianGrid, Cell, Line, LineChart, ReferenceDot,
  ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'

const PRESETS = ['1M', '3M', '6M', 'YTD', '1Y', 'SI', '직접'] as const
type Preset = typeof PRESETS[number]

const TICK = { fontSize: 10, fill: 'var(--c-fg-faint)', fontFamily: 'var(--font-mono), monospace' } as const
const TOOLTIP_STYLE = {
  background: 'var(--c-surface)',
  border: '1px solid var(--c-line-card)',
  borderRadius: 0,
  color: 'var(--c-ink)',
} as const

// 날짜 포맷은 lib/date의 isoDate로 통일한다 — 여기 있던 toISOString().slice(0,10)은
// 로컬 new Date()로 만든 값을 UTC로 읽어 KST 09시 전에는 to가 어제였다. 이유는 그 파일 참조.
function presetRange(preset: Preset): { from: string; to: string } {
  const today = new Date()
  const to = isoDate(today)
  const d = new Date(today)
  switch (preset) {
    case '1M': d.setMonth(d.getMonth() - 1); break
    case '3M': d.setMonth(d.getMonth() - 3); break
    case '6M': d.setMonth(d.getMonth() - 6); break
    case 'YTD': return { from: `${today.getFullYear()}-01-01`, to }
    case '1Y': d.setFullYear(d.getFullYear() - 1); break
    case 'SI': return { from: '2000-01-01', to }   // BE가 첫 관측일로 클램프
    default: break
  }
  return { from: isoDate(d), to }
}

// BE가 percent(0~100) 단위로 내려보낸다 (QA 후속 #1 — dashboard와 단위 통일, ×100 금지)
function fmtPct(n: number | null | undefined): string {
  if (n === null || n === undefined) return '—'
  return `${n >= 0 ? '+' : ''}${n.toFixed(2)}%`
}

function fmtKrw(n: number | null | undefined): string {
  if (n === null || n === undefined) return '—'
  return `${n >= 0 ? '' : '-'}₩${Math.abs(Math.round(n)).toLocaleString()}`
}

// 손익 방향 색 — 한국 관례: 양수 빨강(gain) / 음수 파랑(loss)
function pctColor(n: number | null | undefined): string {
  if (n === null || n === undefined) return 'text-fg-3'
  return n >= 0 ? 'text-gain' : 'text-loss'
}

/** TWR vs MWR 규칙 기반 해석 문구 (SCR-RPT-04 ③) — 입력은 percent(0~100) 단위 */
function interpret(twr: number | null, mwr: number | null): string {
  if (twr === null || mwr === null) return '데이터가 부족해 두 수익률을 비교할 수 없습니다.'
  const diff = mwr - twr
  if (Math.abs(diff) < 0.5) return '입출금 타이밍이 수익률에 미친 영향이 크지 않았습니다.'
  if (diff < 0) return '상승 이후에 입금한 비중이 컸습니다 — 타이밍 비용으로 체감 수익률(MWR)이 운용 수익률(TWR)보다 낮습니다.'
  return '하락 구간에서 추가 매수한 효과가 있습니다 — 체감 수익률(MWR)이 운용 수익률(TWR)보다 높습니다.'
}

export default function ReturnsReportPage() {
  const reportApi = useReportApi()
  const cashFlowApi = useCashFlowApi()
  const benchmarkApi = useBenchmarkApi()
  const queryClient = useQueryClient()

  const [preset, setPreset] = useState<Preset>('YTD')
  const [customFrom, setCustomFrom] = useState('')
  const [customTo, setCustomTo] = useState('')
  const [showModal, setShowModal] = useState(false)

  const range = useMemo(() => {
    if (preset === '직접') {
      if (!customFrom || !customTo || customFrom > customTo) return null
      return { from: customFrom, to: customTo }
    }
    return presetRange(preset)
  }, [preset, customFrom, customTo])

  const analysisQuery = useQuery({
    queryKey: ['report', 'returns', range?.from, range?.to],
    queryFn: () => reportApi!.returns(range!.from, range!.to),
    enabled: !!reportApi && !!range,
    retry: false,
  })

  const flowsQuery = useQuery({
    queryKey: ['cashflows', range?.from, range?.to],
    queryFn: () => cashFlowApi!.list(range!.from, range!.to),
    enabled: !!cashFlowApi && !!range,
  })

  const benchmarkConfigQuery = useQuery({
    queryKey: ['benchmark-config'],
    queryFn: () => benchmarkApi!.get(),
    enabled: !!benchmarkApi,
  })

  const setBenchmark = useMutation({
    mutationFn: (indexType: BenchmarkType | null) => benchmarkApi!.set(indexType),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['benchmark-config'] })
      queryClient.invalidateQueries({ queryKey: ['report', 'returns'] })
    },
  })

  const removeFlow = useMutation({
    mutationFn: (id: string) => cashFlowApi!.remove(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['cashflows'] })
      queryClient.invalidateQueries({ queryKey: ['report', 'returns'] })
    },
  })

  const insufficientData =
    analysisQuery.isError &&
    axios.isAxiosError(analysisQuery.error) &&
    analysisQuery.error.response?.status === 400

  const analysis = analysisQuery.data
  const flows = flowsQuery.data ?? []

  const chartData = useMemo(() => {
    const bmByDate = new Map((analysis?.benchmark?.series ?? []).map((p) => [p.date, p.nav]))
    return (analysis?.navSeries ?? []).map((p) => ({
      date: p.date,
      nav: p.nav,
      bm: bmByDate.get(p.date) ?? null,
    }))
  }, [analysis])
  const flowByDate = useMemo(() => {
    const m = new Map<string, CashFlowItem[]>()
    flows.forEach((f) => {
      const list = m.get(f.flowDate) ?? []
      list.push(f)
      m.set(f.flowDate, list)
    })
    return m
  }, [flows])

  // 워터폴: 투명 베이스 스택 — [기초, +입금, −출금, ±투자손익, 기말]
  // 색 규약: 증가=gain, 감소=loss, 합계(기초·기말)=ink
  const waterfall = useMemo(() => {
    if (!analysis?.summary) return []
    const s = analysis.summary
    if (s.startNav === null || s.endNav === null || s.investmentPnl === null) return []
    const deposits = flows.filter((f) => f.flowType === 'DEPOSIT').reduce((a, f) => a + f.amountKrw, 0)
    const withdrawals = flows.filter((f) => f.flowType === 'WITHDRAWAL').reduce((a, f) => a + f.amountKrw, 0)
    let running = s.startNav
    const steps = [
      { name: '기초 자산', base: 0, value: s.startNav, color: 'var(--c-ink)' },
    ]
    steps.push({ name: '입금', base: running, value: deposits, color: 'var(--c-gain)' })
    running += deposits
    steps.push({ name: '출금', base: running - withdrawals, value: withdrawals, color: 'var(--c-loss)' })
    running -= withdrawals
    steps.push({
      name: '투자손익',
      base: s.investmentPnl >= 0 ? running : running + s.investmentPnl,
      value: Math.abs(s.investmentPnl),
      color: s.investmentPnl >= 0 ? 'var(--c-gain)' : 'var(--c-loss)',
    })
    steps.push({ name: '기말 자산', base: 0, value: s.endNav, color: 'var(--c-ink)' })
    return steps
  }, [analysis, flows])

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
        title="수익률 보고서 (TWR·MWR)"
        meta={<span>R-02{analysis ? ` · 기준일 ${analysis.asOfDate}` : ''}</span>}
      />

      <div className="px-5 py-5 pb-10 sm:px-7">
        {/* ① 기간 선택바 */}
        <div className="flex flex-wrap items-center gap-3">
          <div className="flex border border-line bg-surface">
            {PRESETS.map((p) => (
              <button
                key={p}
                onClick={() => setPreset(p)}
                className={`px-3 py-1.5 font-mono text-xs transition-colors ${
                  preset === p ? 'bg-ink text-white' : 'text-fg-3 hover:text-ink'
                }`}
              >
                {p}
              </button>
            ))}
          </div>
          {preset === '직접' && (
            <div className="flex items-center gap-2 text-sm">
              <input type="date" aria-label="조회 시작일" value={customFrom} onChange={(e) => setCustomFrom(e.target.value)}
                className="border border-line bg-surface px-2 py-1.5 text-sm text-fg-2 focus:border-ink focus:outline-none" />
              <span className="text-fg-faint">~</span>
              <input type="date" aria-label="조회 종료일" value={customTo} onChange={(e) => setCustomTo(e.target.value)}
                className="border border-line bg-surface px-2 py-1.5 text-sm text-fg-2 focus:border-ink focus:outline-none" />
            </div>
          )}
          <select
            value={benchmarkConfigQuery.data?.indexType ?? ''}
            onChange={(e) => setBenchmark.mutate((e.target.value || null) as BenchmarkType | null)}
            disabled={!benchmarkConfigQuery.data || setBenchmark.isPending}
            className="border border-line bg-surface px-2 py-2 text-sm text-fg-2 focus:border-ink focus:outline-none disabled:bg-surface-muted disabled:text-fg-faint"
            title="벤치마크 설정"
          >
            <option value="">BM 미설정</option>
            {(benchmarkConfigQuery.data?.available ?? []).map((o) => (
              <option key={o.type} value={o.type}>vs {o.label}</option>
            ))}
          </select>
          <Button variant="primary" className="ml-auto" onClick={() => setShowModal(true)}>
            + 입출금 기록
          </Button>
        </div>

        {insufficientData && (
          <div className="mt-6 border border-warn-line bg-warn-bg px-4 py-3.5 text-[13px] leading-relaxed text-warn">
            이 기간에는 일별 NAV 스냅샷이 2건 미만이라 수익률을 계산할 수 없습니다.
            계좌를 연동하면 매일 자정 스냅샷이 쌓입니다. 입출금 기록은 지금도 남길 수 있고, 스냅샷이 쌓이면 자동 반영됩니다.
          </div>
        )}

        {analysisQuery.isLoading && <LoadingState label="불러오는 중" className="mt-6" />}

        {analysis && (
          <>
            {/* ② 요약 카드 */}
            <div className="mt-8 grid grid-cols-1 gap-px border border-line-soft bg-line-soft sm:grid-cols-2 lg:grid-cols-4">
              <div className="bg-surface px-3.5 py-3">
                <Label size="sm" tone="faint">기간 수익률 (TWR)</Label>
                <Num className={`mt-1 block text-[20px] ${pctColor(analysis.summary.twr)}`}>
                  {fmtPct(analysis.summary.twr)}
                </Num>
              </div>
              <div className="bg-surface px-3.5 py-3">
                <Label size="sm" tone="faint">체감 수익률 (MWR·XIRR)</Label>
                <Num className={`mt-1 block text-[20px] ${pctColor(analysis.summary.mwr)}`}>
                  {fmtPct(analysis.summary.mwr)}
                </Num>
              </div>
              <div className="bg-surface px-3.5 py-3">
                <Label size="sm" tone="faint">기간 손익금액</Label>
                <Num className={`mt-1 block text-[20px] ${pctColor(analysis.summary.investmentPnl)}`}>
                  {fmtKrw(analysis.summary.investmentPnl)}
                </Num>
              </div>
              <div className="bg-surface px-3.5 py-3">
                <Label size="sm" tone="faint">순입출금</Label>
                <Num className="mt-1 block text-[20px]">{fmtKrw(analysis.summary.netFlow)}</Num>
              </div>
              {analysis.benchmark && (
                <>
                  <div className="bg-surface px-3.5 py-3 lg:col-span-2">
                    <Label size="sm" tone="faint">BM 수익률 ({analysis.benchmark.label})</Label>
                    <Num className={`mt-1 block text-[20px] ${pctColor(analysis.benchmark.periodReturn)}`}>
                      {fmtPct(analysis.benchmark.periodReturn)}
                    </Num>
                  </div>
                  <div className="bg-surface px-3.5 py-3 lg:col-span-2">
                    <Label size="sm" tone="faint">초과수익 (TWR − BM)</Label>
                    <Num className={`mt-1 block text-[20px] ${pctColor(analysis.benchmark.excessReturn)}`}>
                      {analysis.benchmark.excessReturn === null
                        ? '—'
                        : `${analysis.benchmark.excessReturn >= 0 ? '+' : ''}${analysis.benchmark.excessReturn.toFixed(2)}%p`}
                    </Num>
                  </div>
                </>
              )}
            </div>

            {/* ③ TWR vs MWR 해석 */}
            <section className="mt-8 border border-line-card bg-surface-muted p-5">
              <SectionHeader label="TWR vs MWR — 무엇이 다른가" />
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                <div>
                  <Label size="sm" tone="faint">TWR — &quot;투자 실력&quot;</Label>
                  <p className="mt-1 text-[13px] text-fg-3">입출금 타이밍의 영향을 제거한 순수 운용 성과</p>
                </div>
                <div>
                  <Label size="sm" tone="faint">MWR — &quot;내 돈 기준 체감&quot;</Label>
                  <p className="mt-1 text-[13px] text-fg-3">모든 입출금 현금흐름을 반영한 XIRR</p>
                </div>
              </div>
              <p className="mt-3 border-t border-line pt-3 text-[13px] font-medium text-ink">
                {interpret(analysis.summary.twr, analysis.summary.mwr)}
              </p>
            </section>

            {/* ④ NAV 곡선 + 입출금 마커 */}
            <section className="mt-8">
              <SectionHeader
                label="자산 추이 (NAV) — ▲입금 ▼출금"
                note={
                  analysis.benchmark
                    ? `회색 점선 = ${analysis.benchmark.label} (기초 자산 기준 정규화)`
                    : undefined
                }
              />
              <ResponsiveContainer width="100%" height={320}>
                <LineChart data={chartData}>
                  <CartesianGrid strokeDasharray="3 3" stroke="var(--c-line)" />
                  <XAxis dataKey="date" stroke="var(--c-line)" tick={TICK} tickLine={false} minTickGap={40} />
                  <YAxis stroke="var(--c-line)" tick={TICK} tickLine={false} tickFormatter={(v: number) => `${(v / 1_000_000).toFixed(0)}M`} width={48} />
                  <Tooltip
                    contentStyle={TOOLTIP_STYLE}
                    labelStyle={{ color: 'var(--c-fg-3)' }}
                    formatter={(value: number, name: string) => [fmtKrw(value), name === 'bm' ? 'BM' : 'NAV']}
                  />
                  <Line type="monotone" dataKey="nav" stroke="var(--c-ink)" dot={false} strokeWidth={1.5} />
                  {analysis.benchmark && (
                    <Line
                      type="monotone"
                      dataKey="bm"
                      stroke="var(--c-fg-muted)"
                      strokeDasharray="5 4"
                      dot={false}
                      strokeWidth={1.5}
                      connectNulls
                    />
                  )}
                  {chartData
                    .filter((p) => flowByDate.has(p.date))
                    .map((p) => {
                      const dayFlows = flowByDate.get(p.date)!
                      const isDeposit = dayFlows.some((f) => f.flowType === 'DEPOSIT')
                      return (
                        <ReferenceDot
                          key={p.date}
                          x={p.date}
                          y={p.nav}
                          r={5}
                          fill={isDeposit ? 'var(--c-gain)' : 'var(--c-loss)'}
                          stroke="var(--c-surface)"
                        />
                      )
                    })}
                </LineChart>
              </ResponsiveContainer>
            </section>

            {/* AF-106 자산/환율 기여도 분해. 워터폴 바로 앞 — 둘 다 분해이고, 이쪽이
                설명하는 TWR 숫자에 더 가까워야 한다.
                null이면 섹션 자체를 안 그린다 — 외화 자산이 없거나 관측이 2건 미만이거나
                정직하게 분해할 수 없다는 뜻이고, "수집 중입니다" 안내는 외화가 없는
                사용자에게 영원히 오지 않을 것을 기다리게 한다.
                불변식은 합이 아니라 곱이다: (1+자산)(1+환율)−1 == TWR. 두 다리를 더한
                값이 위 TWR과 조금 어긋나 보이는 건 정상이고(교차항), 곱이 어긋나면 버그다. */}
            {analysis.currencyAttribution && (
              <section className="mt-8 border border-line-card bg-surface-muted p-5">
                <SectionHeader label="수익 기여도 — 자산 vs 환율" />
                <dl className="space-y-2">
                  <div className="flex items-baseline justify-between border-b border-line pb-2">
                    <dt className="text-[13px] text-fg-2">기간 수익 (TWR)</dt>
                    <dd>
                      <Num className={`text-[15px] font-medium ${pctColor(analysis.summary.twr)}`}>
                        {fmtPct(analysis.summary.twr)}
                      </Num>
                    </dd>
                  </div>
                  <div className="flex items-baseline justify-between">
                    <dt className="text-[13px] text-fg-3">├ 자산</dt>
                    <dd>
                      <Num className={`text-[13px] ${pctColor(analysis.currencyAttribution.assetContribution)}`}>
                        {fmtPct(analysis.currencyAttribution.assetContribution)}
                      </Num>
                    </dd>
                  </div>
                  <div className="flex items-baseline justify-between">
                    <dt className="text-[13px] text-fg-3">└ 환율</dt>
                    <dd>
                      <Num className={`text-[13px] ${pctColor(analysis.currencyAttribution.fxContribution)}`}>
                        {fmtPct(analysis.currencyAttribution.fxContribution)}
                      </Num>
                    </dd>
                  </div>
                </dl>
                <p className="mt-3 border-t border-line pt-3 text-[12px] leading-relaxed text-fg-3">
                  보유 외화: {analysis.currencyAttribution.currencies.join(' · ')}. 두 기여를 곱하면 기간
                  수익이 됩니다 — 더하기가 아닙니다.
                </p>
              </section>
            )}

            {/* ⑥ 워터폴 */}
            {waterfall.length > 0 && (
              <section className="mt-8">
                <SectionHeader label="입출금 효과 분해" />
                <p className="mb-4 mt-[-6px] text-xs text-fg-faint">
                  자산 증가가 입금 때문인지 수익 때문인지 — 투자손익은 요약 카드와 동일 값
                </p>
                <ResponsiveContainer width="100%" height={280}>
                  <BarChart data={waterfall}>
                    <CartesianGrid strokeDasharray="3 3" stroke="var(--c-line)" />
                    <XAxis dataKey="name" stroke="var(--c-line)" tick={TICK} tickLine={false} />
                    <YAxis stroke="var(--c-line)" tick={TICK} tickLine={false} tickFormatter={(v: number) => `${(v / 1_000_000).toFixed(0)}M`} width={48} />
                    <Tooltip
                      contentStyle={TOOLTIP_STYLE}
                      formatter={(value: number, name: string) => (name === 'value' ? [fmtKrw(value), '금액'] : [null, null])}
                    />
                    <Bar dataKey="base" stackId="wf" fill="transparent" />
                    <Bar dataKey="value" stackId="wf">
                      {waterfall.map((s) => (
                        <Cell key={s.name} fill={s.color} />
                      ))}
                    </Bar>
                  </BarChart>
                </ResponsiveContainer>
              </section>
            )}
          </>
        )}

        {/* 입출금 내역 그리드 — 분석 실패(스냅샷 부족)여도 표시 */}
        <section className="mt-8">
          <SectionHeader label="입출금 내역" note={`${flows.length}건`} />
          {flows.length === 0 ? (
            <p className="text-[13px] leading-relaxed text-fg-faint">
              기간 내 입출금 기록이 없습니다. 입출금을 기록하면 TWR에서 입금이 수익으로 잡히는 왜곡이 제거됩니다.
            </p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[640px] border-t-[1.5px] border-ink text-sm">
                <thead>
                  <tr className="border-b border-line text-left">
                    <th className="py-2 font-normal"><Label size="sm" tone="faint">일자</Label></th>
                    <th className="py-2 font-normal"><Label size="sm" tone="faint">유형</Label></th>
                    <th className="py-2 text-right font-normal"><Label size="sm" tone="faint">금액</Label></th>
                    <th className="py-2 text-right font-normal"><Label size="sm" tone="faint">KRW 환산</Label></th>
                    <th className="py-2 font-normal"><Label size="sm" tone="faint">메모</Label></th>
                    <th className="py-2 font-normal" />
                  </tr>
                </thead>
                <tbody>
                  {flows.map((f) => (
                    <tr key={f.id} className="border-b border-line-hair hover:bg-surface-muted">
                      <td className="py-2.5"><Num className="text-[11.5px] text-fg-3">{f.flowDate}</Num></td>
                      <td className={`py-2.5 font-mono text-[10px] tracking-label ${f.flowType === 'DEPOSIT' ? 'text-gain' : 'text-loss'}`}>
                        {f.flowType === 'DEPOSIT' ? '입금' : '출금'}
                      </td>
                      <td className="py-2.5 text-right">
                        <Num className="text-[12.5px] text-fg-2">{f.amount.toLocaleString()} {f.currency}</Num>
                      </td>
                      <td className="py-2.5 text-right"><Num className="text-[12.5px]">{fmtKrw(f.amountKrw)}</Num></td>
                      <td className="py-2.5 text-xs text-fg-faint">{f.memo ?? ''}</td>
                      <td className="py-2.5 text-right">
                        <button
                          onClick={() => {
                            // QA P2: 금융 데이터 삭제는 확인 후 실행
                            if (confirm(`${f.flowDate} ${fmtKrw(f.amountKrw)} 기록을 삭제하시겠습니까?`))
                              removeFlow.mutate(f.id)
                          }}
                          className="font-mono text-[10px] tracking-label text-fg-ghost transition-colors hover:text-danger"
                        >
                          삭제
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>

        {showModal && cashFlowApi && (
          <RecordFlowModal
            onClose={() => setShowModal(false)}
            onSaved={() => {
              setShowModal(false)
              queryClient.invalidateQueries({ queryKey: ['cashflows'] })
              queryClient.invalidateQueries({ queryKey: ['report', 'returns'] })
            }}
            record={(req) => cashFlowApi.record(req)}
          />
        )}
      </div>
    </div>
  )
}

function RecordFlowModal({
  onClose,
  onSaved,
  record,
}: {
  onClose: () => void
  onSaved: () => void
  record: (req: RecordCashFlowRequest) => Promise<CashFlowItem>
}) {
  // 저장되는 flowDate의 기본값이다 — UTC 포맷이면 KST 09시 전 입력이 어제로 기록된다 (lib/date 참조)
  const [flowDate, setFlowDate] = useState(todayIso())
  const [flowType, setFlowType] = useState<FlowType>('DEPOSIT')
  const [amount, setAmount] = useState('')
  const [currency, setCurrency] = useState('KRW')
  const [memo, setMemo] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const submit = async () => {
    const n = Number(amount)
    if (!flowDate || !n || n <= 0) {
      setError('일자와 0보다 큰 금액을 입력하세요')
      return
    }
    setSaving(true)
    setError(null)
    try {
      await record({ flowDate, flowType, amount: n, currency, memo: memo || null })
      onSaved()
    } catch {
      setError('저장에 실패했습니다. 잠시 후 다시 시도하세요.')
      setSaving(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-ink/25 p-4" onClick={onClose}>
      <div
        className="w-full max-w-sm border border-ink bg-surface p-6"
        onClick={(e) => e.stopPropagation()}
      >
        <h3 className="m-0 text-[15px] font-semibold">입출금 기록</h3>
        <p className="mt-1 text-xs text-fg-faint">환율은 기록 시점 기준으로 고정 환산됩니다</p>

        <div className="mt-4 space-y-3.5">
          <div className="flex border border-line">
            {(['DEPOSIT', 'WITHDRAWAL'] as const).map((t) => (
              <button
                key={t}
                onClick={() => setFlowType(t)}
                className={`flex-1 py-1.5 font-mono text-xs transition-colors ${
                  flowType === t ? 'bg-ink text-white' : 'text-fg-3 hover:text-ink'
                }`}
              >
                {t === 'DEPOSIT' ? '입금' : '출금'}
              </button>
            ))}
          </div>
          <Field id="rf-date" label="일자">
            <Input type="date" aria-label="입출금 일자" value={flowDate} onChange={(e) => setFlowDate(e.target.value)} />
          </Field>
          <div className="flex gap-3">
            <Field id="rf-amount" label="금액" className="flex-1">
              <Input type="number" aria-label="금액" placeholder="금액" value={amount} onChange={(e) => setAmount(e.target.value)} />
            </Field>
            <Field id="rf-currency" label="통화" className="w-24">
              <Select aria-label="통화" value={currency} onChange={(e) => setCurrency(e.target.value)}>
                {SUPPORTED_CURRENCIES.map((c) => <option key={c}>{c}</option>)}
              </Select>
            </Field>
          </div>
          <Field id="rf-memo" label="메모">
            <Input aria-label="메모" placeholder="메모 (선택)" value={memo} onChange={(e) => setMemo(e.target.value)} />
          </Field>
          {error && <p role="alert" className="m-0 text-xs text-danger">{error}</p>}
        </div>

        <div className="mt-5 flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>취소</Button>
          <Button variant="primary" onClick={submit} disabled={saving}>
            {saving ? '저장 중…' : '저장'}
          </Button>
        </div>
      </div>
    </div>
  )
}
