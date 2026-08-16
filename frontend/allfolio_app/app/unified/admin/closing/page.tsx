'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '@/contexts/AuthContext'
import { useRequireAdmin } from '@/lib/useRequireAdmin'
import { closingSseUrl, createClosingAdminApi, type ClosingAdminApi } from '@/lib/closing-admin-api'
import PageHeader from '@/components/ui/PageHeader'
import Badge, { type BadgeVariant } from '@/components/ui/Badge'
import Button from '@/components/ui/Button'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import Field, { Input, Textarea } from '@/components/ui/Field'
import { EmptyState, ErrorState, LoadingState } from '@/components/ui/states'
import type { ClosingStepEvent, WfDayView, WfRollup, WfStepDetail, WfSubStepView } from '@/types/closing'
import { todayIso } from '@/lib/date'

const ROLLUP_BADGE: Record<WfRollup, BadgeVariant> = {
  STANDBY: 'muted', FINISH: 'ok', ERROR: 'danger', RUNNING: 'warn', PAUSED: 'warn',
}
const ROLLUP_DOT: Record<WfRollup, string> = {
  STANDBY: 'bg-line', FINISH: 'bg-ok', ERROR: 'bg-danger',
  RUNNING: 'bg-warn', PAUSED: 'bg-fg-muted',
}
const ROLLUP_KO: Record<WfRollup, string> = {
  STANDBY: '대기', FINISH: '완료', ERROR: '오류', RUNNING: '실행중', PAUSED: '중단',
}
const JOB_STATUS_KO: Record<string, string> = {
  PENDING: '대기', RUNNING: '실행중', SUCCESS: '성공', ERROR: '오류', PAUSED: '중지',
}

// 로컬 달력 기준 — UTC 포맷을 쓰면 KST 09시 전에 어제 마감일을 보여준다 (lib/date 참조)
function todayStr() { return todayIso() }
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
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-ink/25 p-4" onClick={onClose}>
      <div className="w-full max-w-md border border-ink bg-surface p-5" onClick={e => e.stopPropagation()}>
        <h3 className="m-0 text-[14.5px] font-semibold">수동 처리 — {target.stepCd}/{target.subStepCd}</h3>
        <p className="mt-1 text-xs text-fg-faint">처리자·사유가 감사 이력으로 남습니다 (사유 필수)</p>
        <div className="mt-4 flex gap-4">
          {(['SUCCESS', 'ERROR'] as const).map(r => (
            <label key={r} className="flex items-center gap-1.5 text-[13px]">
              <input type="radio" checked={result === r} onChange={() => setResult(r)} />
              {r === 'SUCCESS' ? '성공 처리' : '실패 처리'}
            </label>
          ))}
        </div>
        <div className="mt-3">
          <Field id="manual-remark" label="처리 사유">
            <Textarea
              value={remark} onChange={e => setRemark(e.target.value)}
              placeholder="처리 사유 (필수)"
              className="h-24"
            />
          </Field>
        </div>
        <div className="mt-4 flex justify-end gap-2">
          <Button onClick={onClose}>취소</Button>
          <Button
            variant="primary"
            onClick={() => onSubmit(result, remark)}
            disabled={!remark.trim()}
          >
            처리
          </Button>
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
  if (isLoading) return <LoadingState label="불러오는 중" />
  if (isError) {
    const status = (error as { response?: { status?: number } })?.response?.status
    return (
      <ErrorState
        message={status === 404
          ? `${ymd} 마감 데이터가 없습니다. 마감이 아직 실행되지 않았을 수 있습니다.`
          : '마감 현황 조회에 실패했습니다.'}
        onRetry={() => refetch()}
      />
    )
  }
  if (!data) return <EmptyState title="해당 일자 마감 데이터가 없습니다" />

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="m-0 text-[15px] font-semibold">
          <Num>{ymd}</Num> 마감 현황 {data.isHoliday && <Badge variant="muted" className="ml-2">휴일</Badge>}
        </h2>
        <Button
          variant="primary"
          onClick={() => runDay.mutate()}
          disabled={runDay.isPending}
        >
          {runDay.isPending ? '실행 중…' : '워크플로우 실행'}
        </Button>
      </div>
      {msg && <div className="border border-line bg-surface-muted px-4 py-2 text-[12.5px]">{msg}</div>}

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
    <div className="border border-line bg-surface">
      <button onClick={() => setExpanded(v => !v)} className="flex w-full items-center gap-3 px-4 py-3 text-left transition-colors hover:bg-surface-muted">
        <Badge variant={ROLLUP_BADGE[step.rollup]}>{ROLLUP_KO[step.rollup]}</Badge>
        <span className="text-[13.5px] font-medium">
          <Num className="text-[12px]">{step.stepCd}</Num> {step.stepName}
        </span>
        {step.essentialStepCd && <span className="font-mono text-[10px] text-fg-ghost">선행: {step.essentialStepCd}</span>}
        {step.cutoffEnd && <span className="font-mono text-[10px] text-fg-ghost">컷오프 ~{step.cutoffEnd}</span>}
        <span className="ml-auto font-mono text-[10px] text-fg-faint">{expanded ? '−' : '+'}</span>
      </button>
      {expanded && (
        <div className="border-t border-line px-4 py-2">
          {step.subSteps.map((sub: WfSubStepView) => (
            <div key={sub.subStepCd} className="flex flex-wrap items-center gap-x-3 gap-y-1 border-b border-line-hair py-2 text-[12.5px] last:border-b-0">
              <span className="text-fg-2">
                <Num className="text-[11px]">{sub.subStepCd}</Num> {sub.subStepName}
              </span>
              <span className="font-mono text-[10px] text-fg-ghost">{sub.actionType}{!sub.scheduledToday && ' · 오늘 비대상'}</span>
              {sub.latest ? (
                <span className={`text-xs ${sub.latest.status === 'ERROR' ? 'text-danger' : sub.latest.status === 'SUCCESS' ? 'text-ok' : 'text-fg-3'}`}>
                  {JOB_STATUS_KO[sub.latest.status]} · {sub.latest.execSeq}차 · {fmt(sub.latest.finishedAt ?? sub.latest.startedAt)}
                  {sub.latest.remark && <span className="ml-1 text-fg-faint">— {sub.latest.remark.slice(0, 60)}</span>}
                </span>
              ) : (
                <span className="text-xs text-fg-ghost">이력 없음</span>
              )}
              <span className="ml-auto flex gap-2">
                {sub.actionType !== 'MANUAL' && (
                  <button onClick={() => onRunSub(sub.subStepCd)}
                    className="border border-line px-2.5 py-1 text-xs text-fg-2 transition-colors hover:border-ink hover:text-ink">
                    재실행
                  </button>
                )}
                <button onClick={() => onManual(sub.subStepCd)}
                  className="border border-line px-2.5 py-1 text-xs text-fg-2 transition-colors hover:border-warn hover:text-warn">
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
    <div className="border border-line bg-surface p-4">
      <div className="mb-3 flex items-center justify-between">
        <Button size="sm" onClick={() => move(-1)}>←</Button>
        <Num className="text-[13px] font-medium">{month}</Num>
        <Button size="sm" onClick={() => move(1)}>→</Button>
      </div>
      <div className="grid grid-cols-7 gap-1 text-center">
        {['월', '화', '수', '목', '금', '토', '일'].map(d => (
          <Label key={d} size="sm" tone="faint" className="py-1">{d}</Label>
        ))}
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
              className={`border p-1.5 text-left transition-colors ${
                selected === day.ymd ? 'border-ink bg-surface-muted' : 'border-line-hair hover:border-ink'
              } ${day.isHoliday ? 'opacity-50' : ''}`}
            >
              <div className="flex items-center justify-between">
                <Num className={`text-[11px] ${day.ymd === todayStr() ? 'font-semibold text-ink' : 'text-fg-3'}`}>
                  {Number(day.ymd.slice(8))}
                </Num>
                {worst && <span className={`h-2 w-2 ${ROLLUP_DOT[worst]}`} />}
              </div>
              {day.steps.some(s => s.errorCnt > 0) && (
                <div className="mt-0.5 font-mono text-[9px] text-danger">오류 {day.steps.reduce((a, s) => a + s.errorCnt, 0)}</div>
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
              <tr>
                <th className="pr-1 text-left font-normal"><Label size="sm" tone="faint">단계</Label></th>
                {days.map(d => (
                  <th key={d.ymd} className="font-mono text-[9px] font-normal text-fg-faint">{Number(d.ymd.slice(8))}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {(days.find(d => d.steps.length > 0)?.steps ?? []).map(ref => (
                <tr key={ref.stepCd}>
                  <td className="pr-1"><Num className="text-[9.5px] text-fg-faint">{ref.stepCd}</Num></td>
                  {days.map(d => {
                    const s = d.steps.find(x => x.stepCd === ref.stepCd)
                    return (
                      <td key={d.ymd} className="p-0.5">
                        <div className={`mx-auto h-2.5 w-2.5 ${s ? ROLLUP_DOT[s.rollup] : 'bg-surface-muted'}`}
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

const REWORK_GRID = 'grid grid-cols-[1fr_0.5fr_0.6fr_0.7fr_0.9fr_1.2fr_1.4fr] gap-3'

function ReworkTab({ api }: { api: ClosingAdminApi }) {
  const [ymd, setYmd] = useState(todayStr())
  const { data: logs = [] } = useQuery({
    queryKey: ['closing', 'rework', ymd],
    queryFn:  () => api.reworkLogs(ymd),
  })
  return (
    <div className="space-y-4">
      <Field id="rework-ymd" label="기준일" className="max-w-[180px]">
        <Input type="date" value={ymd} onChange={e => setYmd(e.target.value)} />
      </Field>
      {logs.length === 0 ? (
        <EmptyState title="재작업(차수 2 이상) 이력이 없습니다" />
      ) : (
        <div className="overflow-x-auto">
          <div className="min-w-[720px] border-t-[1.5px] border-ink">
            <div className={`${REWORK_GRID} border-b border-line py-2`}>
              <Label size="sm" tone="faint">단계</Label>
              <Label size="sm" tone="faint" className="text-right">차수</Label>
              <Label size="sm" tone="faint">결과</Label>
              <Label size="sm" tone="faint">자동/수동</Label>
              <Label size="sm" tone="faint">수행자</Label>
              <Label size="sm" tone="faint">시각</Label>
              <Label size="sm" tone="faint">사유</Label>
            </div>
            {logs.map(l => (
              <div key={l.id} className={`${REWORK_GRID} items-baseline border-b border-line-hair py-2.5 text-xs hover:bg-surface-muted`}>
                <Num className="text-[11.5px]">{l.stepCd}/{l.subStepCd}</Num>
                <Num className="text-right text-[11.5px]">{l.execSeq}차</Num>
                <span className={l.status === 'ERROR' ? 'text-danger' : l.status === 'SUCCESS' ? 'text-ok' : 'text-fg-3'}>
                  {JOB_STATUS_KO[l.status]}
                </span>
                <span className="text-fg-3">{l.autoManual === 'M' ? '수동' : '자동'}</span>
                <span className="max-w-[120px] truncate text-fg-3">{l.executor}</span>
                <Num className="text-[11px] text-fg-3">{fmt(l.finishedAt ?? l.startedAt)}</Num>
                <span className="max-w-xs truncate text-fg-3">{l.remark ?? '-'}</span>
              </div>
            ))}
          </div>
        </div>
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
    <div className="border border-line-card bg-surface">
      <PageHeader
        className="px-5 pt-5 sm:px-7"
        title="마감 대시보드"
        meta={
          <>
            <Label size="sm" className="text-warn">ADMIN</Label>
            <span className="ml-3">일일마감 워크플로우 현황·수동 개입</span>
            {sseState === 'open' && <span className="ml-3 text-ok">실시간 연결됨</span>}
            {sseState === 'connecting' && <span className="ml-3 text-fg-faint">실시간 연결 중…</span>}
            {sseState === 'error' && <span className="ml-3 text-warn">실시간 연결 끊김 — 자동 재연결 시도 중 (수동 새로고침 가능)</span>}
          </>
        }
        actions={
          <a href="/unified/admin/closing/define"
            className="border border-line bg-surface px-3.5 py-2 text-[12.5px] text-fg-2 transition-colors hover:border-ink hover:text-ink">
            워크플로우 정의 관리
          </a>
        }
      />

      <div className="flex gap-1 border-b border-line px-5 sm:px-7">
        {([['dashboard', '대시보드'], ['rework', '재작업 로그']] as const).map(([key, label]) => (
          <button key={key} onClick={() => setTab(key)}
            aria-pressed={tab === key}
            className={`-mb-px border-b-2 px-4 py-2.5 text-[13px] transition-colors ${
              tab === key ? 'border-ink font-medium text-ink' : 'border-transparent text-fg-faint hover:text-ink'
            }`}>
            {label}
          </button>
        ))}
      </div>

      <div className="px-5 py-5 pb-10 sm:px-7">
        {tab === 'dashboard' ? (
          <div className="grid gap-5 xl:grid-cols-[380px_minmax(0,1fr)]">
            <div className="space-y-4">
              <MonthCalendar api={api} month={month} onMonth={setMonth} selected={selected} onSelect={setSelected} />
              {liveEvents.length > 0 && (
                <div className="border border-line bg-surface p-4">
                  <h3 className="m-0 font-mono text-[10px] font-medium uppercase tracking-wideLabel text-fg-muted">실시간 이벤트</h3>
                  <div className="mt-2 space-y-1 text-xs">
                    {liveEvents.slice(0, 8).map((ev, i) => (
                      <div key={i} className={ev.level === 'error' ? 'text-danger' : 'text-fg-3'}>
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
    </div>
  )
}
