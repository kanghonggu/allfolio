'use client'

import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import Link from 'next/link'
import axios from 'axios'
import { useBenchmarkApi, useCashFlowApi, useReportApi } from '@/lib/useApi'
import { SUPPORTED_CURRENCIES } from '@/lib/currencies'
import type { BenchmarkType, CashFlowItem, FlowType, RecordCashFlowRequest } from '@/types/returns'
import {
  Bar, BarChart, CartesianGrid, Cell, Line, LineChart, ReferenceDot,
  ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'

const PRESETS = ['1M', '3M', '6M', 'YTD', '1Y', 'SI', '직접'] as const
type Preset = typeof PRESETS[number]

function iso(d: Date): string {
  return d.toISOString().slice(0, 10)
}

function presetRange(preset: Preset): { from: string; to: string } {
  const today = new Date()
  const to = iso(today)
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
  return { from: iso(d), to }
}

function fmtPct(n: number | null | undefined): string {
  if (n === null || n === undefined) return '—'
  const pct = n * 100
  return `${pct >= 0 ? '+' : ''}${pct.toFixed(2)}%`
}

function fmtKrw(n: number | null | undefined): string {
  if (n === null || n === undefined) return '—'
  return `${n >= 0 ? '' : '-'}₩${Math.abs(Math.round(n)).toLocaleString()}`
}

function pctColor(n: number | null | undefined): string {
  if (n === null || n === undefined) return 'text-gray-400'
  return n >= 0 ? 'text-emerald-400' : 'text-red-400'
}

/** TWR vs MWR 규칙 기반 해석 문구 (SCR-RPT-04 ③) */
function interpret(twr: number | null, mwr: number | null): string {
  if (twr === null || mwr === null) return '데이터가 부족해 두 수익률을 비교할 수 없습니다.'
  const diff = mwr - twr
  if (Math.abs(diff) < 0.005) return '입출금 타이밍이 수익률에 미친 영향이 크지 않았습니다.'
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
  const waterfall = useMemo(() => {
    if (!analysis?.summary) return []
    const s = analysis.summary
    if (s.startNav === null || s.endNav === null || s.investmentPnl === null) return []
    const deposits = flows.filter((f) => f.flowType === 'DEPOSIT').reduce((a, f) => a + f.amountKrw, 0)
    const withdrawals = flows.filter((f) => f.flowType === 'WITHDRAWAL').reduce((a, f) => a + f.amountKrw, 0)
    let running = s.startNav
    const steps = [
      { name: '기초 자산', base: 0, value: s.startNav, color: '#6b7280' },
    ]
    steps.push({ name: '입금', base: running, value: deposits, color: '#10b981' })
    running += deposits
    steps.push({ name: '출금', base: running - withdrawals, value: withdrawals, color: '#ef4444' })
    running -= withdrawals
    steps.push({
      name: '투자손익',
      base: s.investmentPnl >= 0 ? running : running + s.investmentPnl,
      value: Math.abs(s.investmentPnl),
      color: s.investmentPnl >= 0 ? '#34d399' : '#f87171',
    })
    steps.push({ name: '기말 자산', base: 0, value: s.endNav, color: '#3b82f6' })
    return steps
  }, [analysis, flows])

  return (
    <div className="space-y-8">
      <div className="flex items-center gap-3">
        <Link href="/unified/reports" className="text-sm text-gray-500 hover:text-gray-300">← 보고서</Link>
        <h1 className="text-2xl font-bold">수익률 보고서 (TWR·MWR)</h1>
        {analysis && (
          <span className="rounded bg-gray-800 px-2 py-0.5 text-xs text-gray-400">
            기준일 {analysis.asOfDate}
          </span>
        )}
      </div>

      {/* ① 기간 선택바 */}
      <div className="flex flex-wrap items-center gap-3">
        <div className="flex rounded-lg border border-gray-700 bg-gray-900 p-1">
          {PRESETS.map((p) => (
            <button
              key={p}
              onClick={() => setPreset(p)}
              className={`rounded-md px-3 py-1.5 text-sm transition-colors ${
                preset === p ? 'bg-gray-700 text-white' : 'text-gray-400 hover:text-gray-200'
              }`}
            >
              {p}
            </button>
          ))}
        </div>
        {preset === '직접' && (
          <div className="flex items-center gap-2 text-sm">
            <input type="date" value={customFrom} onChange={(e) => setCustomFrom(e.target.value)}
              className="rounded-md border border-gray-700 bg-gray-900 px-2 py-1.5 text-gray-200" />
            <span className="text-gray-500">~</span>
            <input type="date" value={customTo} onChange={(e) => setCustomTo(e.target.value)}
              className="rounded-md border border-gray-700 bg-gray-900 px-2 py-1.5 text-gray-200" />
          </div>
        )}
        <select
          value={benchmarkConfigQuery.data?.indexType ?? ''}
          onChange={(e) => setBenchmark.mutate((e.target.value || null) as BenchmarkType | null)}
          disabled={!benchmarkConfigQuery.data || setBenchmark.isPending}
          className="rounded-lg border border-gray-700 bg-gray-900 px-2 py-2 text-sm text-gray-300"
          title="벤치마크 설정"
        >
          <option value="">BM 미설정</option>
          {(benchmarkConfigQuery.data?.available ?? []).map((o) => (
            <option key={o.type} value={o.type}>vs {o.label}</option>
          ))}
        </select>
        <button
          onClick={() => setShowModal(true)}
          className="ml-auto rounded-lg bg-emerald-700 px-4 py-2 text-sm font-medium text-white hover:bg-emerald-600"
        >
          + 입출금 기록
        </button>
      </div>

      {insufficientData && (
        <div className="rounded-xl border border-amber-800 bg-amber-950/40 p-5 text-sm text-amber-300">
          이 기간에는 일별 NAV 스냅샷이 2건 미만이라 수익률을 계산할 수 없습니다.
          계좌를 연동하면 매일 자정 스냅샷이 쌓입니다. 입출금 기록은 지금도 남길 수 있고, 스냅샷이 쌓이면 자동 반영됩니다.
        </div>
      )}

      {analysisQuery.isLoading && <div className="text-sm text-gray-500">불러오는 중…</div>}

      {analysis && (
        <>
          {/* ② 요약 카드 */}
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <div className="rounded-xl border border-gray-800 bg-gray-900 p-5">
              <p className="text-xs text-gray-500">기간 수익률 (TWR)</p>
              <p className={`mt-1 text-2xl font-bold ${pctColor(analysis.summary.twr)}`}>
                {fmtPct(analysis.summary.twr)}
              </p>
            </div>
            <div className="rounded-xl border border-gray-800 bg-gray-900 p-5">
              <p className="text-xs text-gray-500">체감 수익률 (MWR·XIRR)</p>
              <p className={`mt-1 text-2xl font-bold ${pctColor(analysis.summary.mwr)}`}>
                {fmtPct(analysis.summary.mwr)}
              </p>
            </div>
            <div className="rounded-xl border border-gray-800 bg-gray-900 p-5">
              <p className="text-xs text-gray-500">기간 손익금액</p>
              <p className={`mt-1 text-2xl font-bold ${pctColor(analysis.summary.investmentPnl)}`}>
                {fmtKrw(analysis.summary.investmentPnl)}
              </p>
            </div>
            <div className="rounded-xl border border-gray-800 bg-gray-900 p-5">
              <p className="text-xs text-gray-500">순입출금</p>
              <p className="mt-1 text-2xl font-bold text-gray-200">{fmtKrw(analysis.summary.netFlow)}</p>
            </div>
            {analysis.benchmark && (
              <>
                <div className="rounded-xl border border-gray-800 bg-gray-900 p-5">
                  <p className="text-xs text-gray-500">BM 수익률 ({analysis.benchmark.label})</p>
                  <p className={`mt-1 text-2xl font-bold ${pctColor(analysis.benchmark.periodReturn)}`}>
                    {fmtPct(analysis.benchmark.periodReturn)}
                  </p>
                </div>
                <div className="rounded-xl border border-gray-800 bg-gray-900 p-5">
                  <p className="text-xs text-gray-500">초과수익 (TWR − BM)</p>
                  <p className={`mt-1 text-2xl font-bold ${pctColor(analysis.benchmark.excessReturn)}`}>
                    {analysis.benchmark.excessReturn === null
                      ? '—'
                      : `${analysis.benchmark.excessReturn >= 0 ? '+' : ''}${(analysis.benchmark.excessReturn * 100).toFixed(2)}%p`}
                  </p>
                </div>
              </>
            )}
          </div>

          {/* ③ TWR vs MWR 해석 */}
          <div className="rounded-xl border border-gray-800 bg-gray-900 p-5">
            <h2 className="text-sm font-semibold text-gray-300">TWR vs MWR — 무엇이 다른가</h2>
            <div className="mt-3 grid gap-4 sm:grid-cols-2">
              <div>
                <p className="text-xs text-gray-500">TWR — &quot;투자 실력&quot;</p>
                <p className="text-sm text-gray-400">입출금 타이밍의 영향을 제거한 순수 운용 성과</p>
              </div>
              <div>
                <p className="text-xs text-gray-500">MWR — &quot;내 돈 기준 체감&quot;</p>
                <p className="text-sm text-gray-400">모든 입출금 현금흐름을 반영한 XIRR</p>
              </div>
            </div>
            <p className="mt-3 border-t border-gray-800 pt-3 text-sm text-cyan-300">
              {interpret(analysis.summary.twr, analysis.summary.mwr)}
            </p>
          </div>

          {/* ④ NAV 곡선 + 입출금 마커 */}
          <div className="rounded-xl border border-gray-800 bg-gray-900 p-5">
            <h2 className="mb-4 text-sm font-semibold text-gray-300">
              자산 추이 (NAV) — ▲입금 ▼출금
              {analysis.benchmark && (
                <span className="ml-2 text-xs font-normal text-gray-500">
                  회색 점선 = {analysis.benchmark.label} (기초 자산 기준 정규화)
                </span>
              )}
            </h2>
            <ResponsiveContainer width="100%" height={320}>
              <LineChart data={chartData}>
                <CartesianGrid strokeDasharray="3 3" stroke="#1f2937" />
                <XAxis dataKey="date" stroke="#6b7280" fontSize={11} minTickGap={40} />
                <YAxis stroke="#6b7280" fontSize={11} tickFormatter={(v: number) => `${(v / 1_000_000).toFixed(0)}M`} width={48} />
                <Tooltip
                  contentStyle={{ background: '#111827', border: '1px solid #374151', borderRadius: 8 }}
                  labelStyle={{ color: '#9ca3af' }}
                  formatter={(value: number, name: string) => [fmtKrw(value), name === 'bm' ? 'BM' : 'NAV']}
                />
                <Line type="monotone" dataKey="nav" stroke="#34d399" dot={false} strokeWidth={2} />
                {analysis.benchmark && (
                  <Line
                    type="monotone"
                    dataKey="bm"
                    stroke="#9ca3af"
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
                        fill={isDeposit ? '#10b981' : '#ef4444'}
                        stroke="#0f172a"
                      />
                    )
                  })}
              </LineChart>
            </ResponsiveContainer>
          </div>

          {/* ⑥ 워터폴 */}
          {waterfall.length > 0 && (
            <div className="rounded-xl border border-gray-800 bg-gray-900 p-5">
              <h2 className="mb-1 text-sm font-semibold text-gray-300">입출금 효과 분해</h2>
              <p className="mb-4 text-xs text-gray-500">
                자산 증가가 입금 때문인지 수익 때문인지 — 투자손익은 요약 카드와 동일 값
              </p>
              <ResponsiveContainer width="100%" height={280}>
                <BarChart data={waterfall}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#1f2937" />
                  <XAxis dataKey="name" stroke="#6b7280" fontSize={12} />
                  <YAxis stroke="#6b7280" fontSize={11} tickFormatter={(v: number) => `${(v / 1_000_000).toFixed(0)}M`} width={48} />
                  <Tooltip
                    contentStyle={{ background: '#111827', border: '1px solid #374151', borderRadius: 8 }}
                    formatter={(value: number, name: string) => (name === 'value' ? [fmtKrw(value), '금액'] : [null, null])}
                  />
                  <Bar dataKey="base" stackId="wf" fill="transparent" />
                  <Bar dataKey="value" stackId="wf" radius={[4, 4, 0, 0]}>
                    {waterfall.map((s) => (
                      <Cell key={s.name} fill={s.color} />
                    ))}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            </div>
          )}
        </>
      )}

      {/* 입출금 내역 그리드 — 분석 실패(스냅샷 부족)여도 표시 */}
      <div className="rounded-xl border border-gray-800 bg-gray-900 p-5">
        <h2 className="mb-4 text-sm font-semibold text-gray-300">입출금 내역 ({flows.length}건)</h2>
        {flows.length === 0 ? (
          <p className="text-sm text-gray-500">
            기간 내 입출금 기록이 없습니다. 입출금을 기록하면 TWR에서 입금이 수익으로 잡히는 왜곡이 제거됩니다.
          </p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
                <th className="pb-2">일자</th>
                <th className="pb-2">유형</th>
                <th className="pb-2 text-right">금액</th>
                <th className="pb-2 text-right">KRW 환산</th>
                <th className="pb-2">메모</th>
                <th className="pb-2" />
              </tr>
            </thead>
            <tbody>
              {flows.map((f) => (
                <tr key={f.id} className="border-b border-gray-800/50 text-gray-300">
                  <td className="py-2">{f.flowDate}</td>
                  <td className={`py-2 ${f.flowType === 'DEPOSIT' ? 'text-emerald-400' : 'text-red-400'}`}>
                    {f.flowType === 'DEPOSIT' ? '입금' : '출금'}
                  </td>
                  <td className="py-2 text-right">
                    {f.amount.toLocaleString()} {f.currency}
                  </td>
                  <td className="py-2 text-right">{fmtKrw(f.amountKrw)}</td>
                  <td className="py-2 text-gray-500">{f.memo ?? ''}</td>
                  <td className="py-2 text-right">
                    <button
                      onClick={() => {
                        // QA P2: 금융 데이터 삭제는 확인 후 실행
                        if (confirm(`${f.flowDate} ${fmtKrw(f.amountKrw)} 기록을 삭제하시겠습니까?`))
                          removeFlow.mutate(f.id)
                      }}
                      className="text-xs text-gray-600 hover:text-red-400"
                    >
                      삭제
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

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
  const [flowDate, setFlowDate] = useState(iso(new Date()))
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
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60" onClick={onClose}>
      <div
        className="w-full max-w-sm rounded-xl border border-gray-700 bg-gray-900 p-6"
        onClick={(e) => e.stopPropagation()}
      >
        <h3 className="text-base font-semibold text-gray-100">입출금 기록</h3>
        <p className="mt-1 text-xs text-gray-500">환율은 기록 시점 기준으로 고정 환산됩니다</p>

        <div className="mt-4 space-y-3 text-sm">
          <div className="flex rounded-lg border border-gray-700 p-1">
            {(['DEPOSIT', 'WITHDRAWAL'] as const).map((t) => (
              <button
                key={t}
                onClick={() => setFlowType(t)}
                className={`flex-1 rounded-md py-1.5 transition-colors ${
                  flowType === t
                    ? t === 'DEPOSIT' ? 'bg-emerald-800 text-white' : 'bg-red-900 text-white'
                    : 'text-gray-400'
                }`}
              >
                {t === 'DEPOSIT' ? '입금' : '출금'}
              </button>
            ))}
          </div>
          <input type="date" value={flowDate} onChange={(e) => setFlowDate(e.target.value)}
            className="w-full rounded-md border border-gray-700 bg-gray-950 px-3 py-2 text-gray-200" />
          <div className="flex gap-2">
            <input type="number" placeholder="금액" value={amount} onChange={(e) => setAmount(e.target.value)}
              className="flex-1 rounded-md border border-gray-700 bg-gray-950 px-3 py-2 text-gray-200" />
            <select value={currency} onChange={(e) => setCurrency(e.target.value)}
              aria-label="통화"
              className="rounded-md border border-gray-700 bg-gray-950 px-2 py-2 text-gray-200">
              {SUPPORTED_CURRENCIES.map((c) => <option key={c}>{c}</option>)}
            </select>
          </div>
          <input placeholder="메모 (선택)" value={memo} onChange={(e) => setMemo(e.target.value)}
            className="w-full rounded-md border border-gray-700 bg-gray-950 px-3 py-2 text-gray-200" />
          {error && <p className="text-xs text-red-400">{error}</p>}
        </div>

        <div className="mt-5 flex justify-end gap-2 text-sm">
          <button onClick={onClose} className="rounded-lg px-4 py-2 text-gray-400 hover:text-gray-200">취소</button>
          <button
            onClick={submit}
            disabled={saving}
            className="rounded-lg bg-emerald-700 px-4 py-2 font-medium text-white hover:bg-emerald-600 disabled:opacity-50"
          >
            {saving ? '저장 중…' : '저장'}
          </button>
        </div>
      </div>
    </div>
  )
}
