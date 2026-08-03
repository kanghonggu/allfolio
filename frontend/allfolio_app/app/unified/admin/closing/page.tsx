'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '@/contexts/AuthContext'
import { useRequireAdmin } from '@/lib/useRequireAdmin'
import { closingSseUrl, createClosingAdminApi, type ClosingAdminApi } from '@/lib/closing-admin-api'
import type { ClosingStepEvent, WfDayView, WfRollup, WfStepDetail, WfSubStepView } from '@/types/closing'

const ROLLUP_STYLE: Record<WfRollup, string> = {
  STANDBY: 'bg-gray-800 text-gray-400 border-gray-700',
  FINISH:  'bg-emerald-900/40 text-emerald-400 border-emerald-800',
  ERROR:   'bg-red-900/40 text-red-400 border-red-800',
  RUNNING: 'bg-yellow-900/40 text-yellow-400 border-yellow-800',
  PAUSED:  'bg-orange-900/40 text-orange-400 border-orange-800',
}
const ROLLUP_DOT: Record<WfRollup, string> = {
  STANDBY: 'bg-gray-600', FINISH: 'bg-emerald-500', ERROR: 'bg-red-500',
  RUNNING: 'bg-yellow-400', PAUSED: 'bg-orange-400',
}
const ROLLUP_KO: Record<WfRollup, string> = {
  STANDBY: '대기', FINISH: '완료', ERROR: '오류', RUNNING: '실행중', PAUSED: '중단',
}
const JOB_STATUS_KO: Record<string, string> = {
  PENDING: '대기', RUNNING: '실행중', SUCCESS: '성공', ERROR: '오류', PAUSED: '중지',
}

function todayStr() { return new Date().toISOString().slice(0, 10) }
function thisMonth() { return todayStr().slice(0, 7) }
function fmt(ts: string | null | undefined) { return ts ? new Date(ts).toLocaleString('ko-KR') : '-' }

// ── 수동 처리 모달 ───────────────────────────────────────────

function ManualModal({ target, onClose, onSubmit }: {
  target: { stepCd: string; subStepCd: string }
  onClose: () => void
  onSubmit: (result: 'SUCCESS' | 'ERROR', remark: string) => void
}) {
  const [result, setResult] = useState<'SUCCESS' | 'ERROR'>('SUCCESS')
  const [remark, setRemark] = useState('')
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60" onClick={onClose}>
      <div className="w-full max-w-md rounded-xl border border-gray-700 bg-gray-900 p-5" onClick={e => e.stopPropagation()}>
        <h3 className="font-semibold">수동 처리 — {target.stepCd}/{target.subStepCd}</h3>
        <p className="mt-1 text-xs text-gray-500">처리자·사유가 감사 이력으로 남습니다 (사유 필수)</p>
        <div className="mt-4 flex gap-3">
          {(['SUCCESS', 'ERROR'] as const).map(r => (
            <label key={r} className="flex items-center gap-1.5 text-sm">
              <input type="radio" checked={result === r} onChange={() => setResult(r)} />
              {r === 'SUCCESS' ? '성공 처리' : '실패 처리'}
            </label>
          ))}
        </div>
        <textarea
          value={remark} onChange={e => setRemark(e.target.value)}
          placeholder="처리 사유 (필수)"
          className="mt-3 h-24 w-full rounded-lg border border-gray-600 bg-gray-800 p-3 text-sm"
        />
        <div className="mt-4 flex justify-end gap-2">
          <button onClick={onClose} className="rounded-lg border border-gray-600 px-4 py-2 text-sm">취소</button>
          <button
            onClick={() => onSubmit(result, remark)}
            disabled={!remark.trim()}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium hover:bg-blue-500 disabled:opacity-50"
          >
            처리
          </button>
        </div>
      </div>
    </div>
  )
}

// ── 일자 상세 (SCR-DASH-02) ──────────────────────────────────

function DayDetail({ ymd, api }: { ymd: string; api: ClosingAdminApi }) {
  const qc = useQueryClient()
  const [manualTarget, setManualTarget] = useState<{ stepCd: string; subStepCd: string } | null>(null)
  const [msg, setMsg] = useState<string | null>(null)

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['closing', 'day', ymd],
    queryFn:  () => api.dayDetail(ymd),
  })

  const invalidate = () => qc.invalidateQueries({ queryKey: ['closing'] })

  const runDay = useMutation({
    mutationFn: () => api.runDay(ymd),
    onSuccess: s => { setMsg(`실행: ${s.executedSteps.join(', ') || '없음'} · 게이트 대기: ${s.gateSkippedSteps.join(', ') || '없음'}`); invalidate() },
    onError: (e: unknown) => {
      const err = e as { response?: { data?: { error?: string } } }
      setMsg(err.response?.data?.error ?? '실행 실패')
    },
  })

  const runSub = useMutation({
    mutationFn: (t: { stepCd: string; subStepCd: string }) => api.runSubStep(ymd, t.stepCd, t.subStepCd),
    onSuccess: r => { setMsg(`재실행 결과: ${JOB_STATUS_KO[r.status] ?? r.status}`); invalidate() },
    onError: (e: unknown) => {
      const err = e as { response?: { data?: { error?: string } } }
      setMsg(err.response?.data?.error ?? '재실행 실패')
    },
  })

  const manual = useMutation({
    mutationFn: (p: { stepCd: string; subStepCd: string; result: 'SUCCESS' | 'ERROR'; remark: string }) =>
      api.manualComplete(ymd, p.stepCd, p.subStepCd, p.result, p.remark),
    onSuccess: () => { setManualTarget(null); setMsg('수동 처리 완료'); invalidate() },
    onError: (e: unknown) => {
      const err = e as { response?: { data?: { error?: string } } }
      setMsg(err.response?.data?.error ?? '수동 처리 실패')
    },
  })

  // 무한 '불러오는 중' 금지 — 로딩·실패·데이터 없음 구분 (QA P2)
  if (isLoading) return <div className="py-6 text-sm text-gray-500">불러오는 중…</div>
  if (isError) {
    const status = (error as { response?: { status?: number } })?.response?.status
    return (
      <div className="space-y-2 rounded-xl border border-red-800 bg-red-950/30 p-4 text-sm">
        <p className="text-red-400">
          {status === 404 ? `${ymd} 마감 데이터가 없습니다. 마감이 아직 실행되지 않았을 수 있습니다.`
            : '마감 현황 조회에 실패했습니다.'}
        </p>
        <button onClick={() => refetch()} className="rounded border border-gray-600 px-3 py-1 text-xs text-gray-300 hover:border-gray-400">
          다시 시도
        </button>
      </div>
    )
  }
  if (!data) return <div className="py-6 text-sm text-gray-500">해당 일자 마감 데이터가 없습니다.</div>

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <h2 className="font-semibold">
          {ymd} 마감 현황 {data.isHoliday && <span className="ml-2 rounded-full border border-gray-700 px-2 py-0.5 text-xs text-gray-500">휴일</span>}
        </h2>
        <button
          onClick={() => runDay.mutate()}
          disabled={runDay.isPending}
          className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium hover:bg-blue-500 disabled:opacity-50"
        >
          {runDay.isPending ? '실행 중…' : '▶ 워크플로우 실행'}
        </button>
      </div>
      {msg && <div className="rounded-lg border border-gray-600 bg-gray-800 px-4 py-2 text-sm">{msg}</div>}

      <div className="space-y-2">
        {data.steps.map((step: WfStepDetail) => (
          <StepCard key={step.stepCd} step={step}
            onRunSub={(sub: string) => runSub.mutate({ stepCd: step.stepCd, subStepCd: sub })}
            onManual={(sub: string) => setManualTarget({ stepCd: step.stepCd, subStepCd: sub })}
          />
        ))}
      </div>

      {manualTarget && (
        <ManualModal
          target={manualTarget}
          onClose={() => setManualTarget(null)}
          onSubmit={(result, remark) => manual.mutate({ ...manualTarget, result, remark })}
        />
      )}
    </div>
  )
}

function StepCard({ step, onRunSub, onManual }: {
  step: WfStepDetail
  onRunSub: (subStepCd: string) => void
  onManual: (subStepCd: string) => void
}) {
  const [expanded, setExpanded] = useState(step.rollup === 'ERROR' || step.rollup === 'PAUSED')
  return (
    <div className="rounded-xl border border-gray-700 bg-gray-900">
      <button onClick={() => setExpanded(v => !v)} className="flex w-full items-center gap-3 px-4 py-3 text-left">
        <span className={`rounded-full border px-2 py-0.5 text-xs font-medium ${ROLLUP_STYLE[step.rollup]}`}>
          {ROLLUP_KO[step.rollup]}
        </span>
        <span className="font-medium">{step.stepCd} {step.stepName}</span>
        {step.essentialStepCd && <span className="text-xs text-gray-600">선행: {step.essentialStepCd}</span>}
        {step.cutoffEnd && <span className="text-xs text-gray-600">컷오프 ~{step.cutoffEnd}</span>}
        <span className="ml-auto text-xs text-gray-500">{expanded ? '▾' : '▸'}</span>
      </button>
      {expanded && (
        <div className="border-t border-gray-800 px-4 py-3">
          {step.subSteps.map((sub: WfSubStepView) => (
            <div key={sub.subStepCd} className="flex flex-wrap items-center gap-x-3 gap-y-1 py-1.5 text-sm">
              <span className="text-gray-300">{sub.subStepCd} {sub.subStepName}</span>
              <span className="text-xs text-gray-600">{sub.actionType}{!sub.scheduledToday && ' · 오늘 비대상'}</span>
              {sub.latest ? (
                <span className={`text-xs ${sub.latest.status === 'ERROR' ? 'text-red-400' : sub.latest.status === 'SUCCESS' ? 'text-emerald-400' : 'text-gray-400'}`}>
                  {JOB_STATUS_KO[sub.latest.status]} · {sub.latest.execSeq}차 · {fmt(sub.latest.finishedAt ?? sub.latest.startedAt)}
                  {sub.latest.remark && <span className="ml-1 text-gray-500">— {sub.latest.remark.slice(0, 60)}</span>}
                </span>
              ) : (
                <span className="text-xs text-gray-600">이력 없음</span>
              )}
              <span className="ml-auto flex gap-2">
                {sub.actionType !== 'MANUAL' && (
                  <button onClick={() => onRunSub(sub.subStepCd)}
                    className="rounded-lg border border-gray-600 px-2.5 py-1 text-xs hover:border-blue-500 hover:text-blue-400">
                    재실행
                  </button>
                )}
                <button onClick={() => onManual(sub.subStepCd)}
                  className="rounded-lg border border-gray-600 px-2.5 py-1 text-xs hover:border-amber-500 hover:text-amber-400">
                  수동 처리
                </button>
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

// ── 월 달력 + 매트릭스 (SCR-DASH-01/04) ─────────────────────

function MonthCalendar({ api, month, onMonth, selected, onSelect }: {
  api: ClosingAdminApi
  month: string
  onMonth: (m: string) => void
  selected: string
  onSelect: (ymd: string) => void
}) {
  const { data } = useQuery({
    queryKey: ['closing', 'month', month],
    queryFn:  () => api.dashboard(month),
    refetchInterval: 60_000,
  })

  const days = data?.days ?? []
  const firstDow = days.length ? (new Date(days[0].ymd).getDay() + 6) % 7 : 0  // 월요일 시작

  const move = (delta: number) => {
    const [y, m] = month.split('-').map(Number)
    const d = new Date(y, m - 1 + delta, 1)
    onMonth(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`)
  }

  return (
    <div className="rounded-xl border border-gray-700 bg-gray-900 p-4">
      <div className="mb-3 flex items-center justify-between">
        <button onClick={() => move(-1)} className="rounded-lg border border-gray-600 px-2.5 py-1 text-sm">←</button>
        <span className="font-semibold">{month}</span>
        <button onClick={() => move(1)} className="rounded-lg border border-gray-600 px-2.5 py-1 text-sm">→</button>
      </div>
      <div className="grid grid-cols-7 gap-1 text-center text-xs text-gray-500">
        {['월', '화', '수', '목', '금', '토', '일'].map(d => <div key={d} className="py-1">{d}</div>)}
        {Array.from({ length: firstDow }).map((_, i) => <div key={`e${i}`} />)}
        {days.map((day: WfDayView) => {
          const worst: WfRollup | null =
            day.steps.some(s => s.rollup === 'ERROR') ? 'ERROR'
            : day.steps.some(s => s.rollup === 'RUNNING') ? 'RUNNING'
            : day.steps.some(s => s.rollup === 'PAUSED') ? 'PAUSED'
            : day.steps.length > 0 && day.steps.every(s => s.rollup === 'FINISH') ? 'FINISH'
            : day.steps.some(s => s.rollup !== 'STANDBY') ? 'PAUSED'
            : null
          return (
            <button
              key={day.ymd}
              onClick={() => onSelect(day.ymd)}
              className={`rounded-lg border p-1.5 text-left transition-colors ${
                selected === day.ymd ? 'border-blue-500 bg-blue-950/30' : 'border-gray-800 hover:border-gray-600'
              } ${day.isHoliday ? 'opacity-50' : ''}`}
            >
              <div className="flex items-center justify-between">
                <span className={day.ymd === todayStr() ? 'font-bold text-blue-400' : 'text-gray-400'}>
                  {Number(day.ymd.slice(8))}
                </span>
                {worst && <span className={`h-2 w-2 rounded-full ${ROLLUP_DOT[worst]}`} />}
              </div>
              {day.steps.some(s => s.errorCnt > 0) && (
                <div className="mt-0.5 text-[10px] text-red-400">오류 {day.steps.reduce((a, s) => a + s.errorCnt, 0)}</div>
              )}
            </button>
          )
        })}
      </div>

      {/* 일×단계 매트릭스 (SCR-DASH-04) */}
      {days.length > 0 && days.some(d => d.steps.length > 0) && (
        <div className="mt-4 overflow-x-auto">
          <table className="w-full text-[10px]">
            <thead>
              <tr className="text-gray-600">
                <th className="pr-1 text-left font-medium">단계</th>
                {days.map(d => <th key={d.ymd} className="font-normal">{Number(d.ymd.slice(8))}</th>)}
              </tr>
            </thead>
            <tbody>
              {(days.find(d => d.steps.length > 0)?.steps ?? []).map(ref => (
                <tr key={ref.stepCd}>
                  <td className="pr-1 text-gray-500">{ref.stepCd}</td>
                  {days.map(d => {
                    const s = d.steps.find(x => x.stepCd === ref.stepCd)
                    return (
                      <td key={d.ymd} className="p-0.5">
                        <div className={`mx-auto h-2.5 w-2.5 rounded-sm ${s ? ROLLUP_DOT[s.rollup] : 'bg-gray-900'}`}
                          title={s ? `${d.ymd} ${ref.stepCd} ${ROLLUP_KO[s.rollup]}` : ''} />
                      </td>
                    )
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

// ── 재작업 로그 탭 (SCR-DASH-05) ─────────────────────────────

function ReworkTab({ api }: { api: ClosingAdminApi }) {
  const [ymd, setYmd] = useState(todayStr())
  const { data: logs = [] } = useQuery({
    queryKey: ['closing', 'rework', ymd],
    queryFn:  () => api.reworkLogs(ymd),
  })
  return (
    <div className="space-y-3">
      <label className="text-xs text-gray-500">기준일
        <input type="date" value={ymd} onChange={e => setYmd(e.target.value)}
          className="ml-2 rounded-lg border border-gray-600 bg-gray-800 px-3 py-1.5 text-sm" />
      </label>
      {logs.length === 0 ? (
        <div className="rounded-xl border border-dashed border-gray-700 p-10 text-center text-sm text-gray-500">
          재작업(차수 2 이상) 이력이 없습니다
        </div>
      ) : (
        <table className="w-full text-xs">
          <thead>
            <tr className="text-left text-gray-500">
              <th className="py-2 pr-4 font-medium">단계</th>
              <th className="py-2 pr-4 font-medium">차수</th>
              <th className="py-2 pr-4 font-medium">결과</th>
              <th className="py-2 pr-4 font-medium">자동/수동</th>
              <th className="py-2 pr-4 font-medium">수행자</th>
              <th className="py-2 pr-4 font-medium">시각</th>
              <th className="py-2 font-medium">사유</th>
            </tr>
          </thead>
          <tbody>
            {logs.map(l => (
              <tr key={l.id} className="border-t border-gray-800">
                <td className="py-1.5 pr-4">{l.stepCd}/{l.subStepCd}</td>
                <td className="py-1.5 pr-4">{l.execSeq}차</td>
                <td className={`py-1.5 pr-4 ${l.status === 'ERROR' ? 'text-red-400' : l.status === 'SUCCESS' ? 'text-emerald-400' : 'text-gray-400'}`}>
                  {JOB_STATUS_KO[l.status]}
                </td>
                <td className="py-1.5 pr-4 text-gray-400">{l.autoManual === 'M' ? '수동' : '자동'}</td>
                <td className="py-1.5 pr-4 text-gray-400 max-w-[120px] truncate">{l.executor}</td>
                <td className="py-1.5 pr-4 text-gray-400">{fmt(l.finishedAt ?? l.startedAt)}</td>
                <td className="py-1.5 text-gray-400 max-w-xs truncate">{l.remark ?? '-'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

// ── 페이지 ───────────────────────────────────────────────────

export default function ClosingDashboardPage() {
  const { ready } = useRequireAdmin()
  const { accessToken } = useAuth()
  const qc = useQueryClient()
  const api = useMemo(() => (accessToken ? createClosingAdminApi(accessToken) : null), [accessToken])

  const [tab, setTab] = useState<'dashboard' | 'rework'>('dashboard')
  const [month, setMonth] = useState(thisMonth())
  const [selected, setSelected] = useState(todayStr())
  const [liveEvents, setLiveEvents] = useState<ClosingStepEvent[]>([])
  const [sseState, setSseState] = useState<'connecting' | 'open' | 'error'>('connecting')
  const esRef = useRef<EventSource | null>(null)

  // SSE 구독 — closing.step 수신 시 라이브 피드 + 쿼리 갱신 (#31)
  // 연결 상태를 노출해 '실시간'이라는 안내와 실제 상태가 어긋나지 않게 한다 (QA P2)
  useEffect(() => {
    if (!ready || !accessToken) return
    const es = new EventSource(closingSseUrl(accessToken))
    esRef.current = es
    es.onopen = () => setSseState('open')
    es.onerror = () => setSseState('error')   // EventSource가 자동 재연결 시도
    es.addEventListener('closing.step', e => {
      try {
        const ev = JSON.parse((e as MessageEvent).data) as ClosingStepEvent
        setLiveEvents(prev => [ev, ...prev].slice(0, 20))
        qc.invalidateQueries({ queryKey: ['closing'] })
      } catch { /* malformed event 무시 */ }
    })
    return () => { es.close(); esRef.current = null }
  }, [ready, accessToken, qc])

  if (!ready || !api) return null

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">마감 대시보드</h1>
          <p className="mt-1 text-sm text-gray-400">
            일일마감 워크플로우 현황·수동 개입{' '}
            {sseState === 'open' && <span className="text-emerald-500">● 실시간 연결됨</span>}
            {sseState === 'connecting' && <span className="text-gray-500">● 실시간 연결 중…</span>}
            {sseState === 'error' && <span className="text-amber-500">● 실시간 연결 끊김 — 자동 재연결 시도 중 (수동 새로고침 가능)</span>}
          </p>
        </div>
        <a href="/unified/admin/closing/define"
          className="rounded-lg border border-gray-600 px-4 py-2 text-sm hover:border-amber-500 hover:text-amber-400 transition-colors">
          워크플로우 정의 관리
        </a>
      </div>

      <div className="flex gap-1 border-b border-gray-800">
        {([['dashboard', '대시보드'], ['rework', '재작업 로그']] as const).map(([key, label]) => (
          <button key={key} onClick={() => setTab(key)}
            className={`px-4 py-2 text-sm transition-colors ${
              tab === key ? 'border-b-2 border-blue-500 font-medium text-white' : 'text-gray-500 hover:text-gray-300'
            }`}>
            {label}
          </button>
        ))}
      </div>

      {tab === 'dashboard' ? (
        <div className="grid gap-4 xl:grid-cols-[380px_1fr]">
          <div className="space-y-4">
            <MonthCalendar api={api} month={month} onMonth={setMonth} selected={selected} onSelect={setSelected} />
            {liveEvents.length > 0 && (
              <div className="rounded-xl border border-gray-700 bg-gray-900 p-4">
                <h3 className="text-xs font-semibold text-gray-400">실시간 이벤트</h3>
                <div className="mt-2 space-y-1 text-xs">
                  {liveEvents.slice(0, 8).map((ev, i) => (
                    <div key={i} className={ev.level === 'error' ? 'text-red-400' : 'text-gray-400'}>
                      {ev.stepCd}/{ev.subStepCd} {JOB_STATUS_KO[ev.status]}{ev.remark ? ` — ${ev.remark.slice(0, 40)}` : ''}
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
          <DayDetail ymd={selected} api={api} />
        </div>
      ) : (
        <ReworkTab api={api} />
      )}
    </div>
  )
}
