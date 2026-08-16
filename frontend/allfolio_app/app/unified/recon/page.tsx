'use client'

import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '@/contexts/AuthContext'
import { createReconApi } from '@/lib/recon-api'
import PageHeader from '@/components/ui/PageHeader'
import Badge, { type BadgeVariant } from '@/components/ui/Badge'
import Button from '@/components/ui/Button'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import Field, { Input, Select } from '@/components/ui/Field'
import { EmptyState } from '@/components/ui/states'
import type { ReconDiffDetail, ReconKd, ReconRun, ReconSummary, RegisterKdPayload } from '@/types/recon'
import { todayIso } from '@/lib/date'

const RUN_STATUS: Record<string, { label: string; variant: BadgeVariant }> = {
  RUNNING:   { label: '실행 중', variant: 'warn' },
  COMPLETED: { label: '완료',   variant: 'ok' },
  FAILED:    { label: '실패',   variant: 'danger' },
}
const SUMMARY_STATUS: Record<ReconSummary['status'], { label: string; variant: BadgeVariant }> = {
  PASSED:     { label: '통과',      variant: 'ok' },
  DIFF_FOUND: { label: '차이 발견', variant: 'warn' },
  FAILED:     { label: '실행 실패', variant: 'danger' },
}
const RULE_KO: Record<string, string> = {
  NEGATIVE_QUANTITY: '음수 수량',
  STALE_SYNC:        '장기 미동기화',
  DUPLICATE_TRADE:   '중복 거래 후보',
  SNAPSHOT_MISSING:  '스냅샷 부재',
  POSITION_RECON:    '포지션 대사',
}
const DIFF_TYPE_KO: Record<ReconDiffDetail['diffType'], string> = {
  VALUE_MISMATCH:   '수량 불일치',
  MISSING_INTERNAL: '내부 기록 없음',
  MISSING_EXTERNAL: '브로커 기록 없음',
  RULE_VIOLATION:   '룰 위반',
}

function fmt(ts: string | null | undefined) {
  return ts ? new Date(ts).toLocaleString('ko-KR') : '-'
}
// 로컬 달력 기준 — UTC 포맷이면 KST 09시 전에 어제 대사를 돌린다 (lib/date 참조)
function today() {
  return todayIso()
}

// ── 실행·결과 탭 ─────────────────────────────────────────────

const DETAIL_GRID = 'grid grid-cols-[0.9fr_0.7fr_0.7fr_0.8fr_0.8fr_0.7fr_1.2fr] gap-3'

function DiffDetails({ runId, ruleCode, api }: { runId: string; ruleCode?: string; api: ReturnType<typeof createReconApi> }) {
  const { data: details = [], isLoading } = useQuery({
    queryKey: ['recon', 'details', runId, ruleCode ?? 'all'],
    queryFn:  () => api.runs.details(runId, ruleCode),
  })
  if (isLoading) {
    return <div className="py-3 font-mono text-[10px] tracking-label text-fg-faint">상세 불러오는 중 …</div>
  }
  if (details.length === 0) {
    return <div className="py-3 text-xs text-fg-faint">차이 상세가 없습니다</div>
  }
  return (
    <div className="mb-2 mt-1 overflow-x-auto bg-surface-muted px-3 py-2">
      <div className="min-w-[720px]">
        <div className={`${DETAIL_GRID} border-b border-line py-1.5`}>
          <Label size="sm" tone="faint">유형</Label>
          <Label size="sm" tone="faint">심볼</Label>
          <Label size="sm" tone="faint">필드</Label>
          <Label size="sm" tone="faint" className="text-right">Allfolio 집계</Label>
          <Label size="sm" tone="faint" className="text-right">기관 원장</Label>
          <Label size="sm" tone="faint" className="text-right">차이</Label>
          <Label size="sm" tone="faint">비고</Label>
        </div>
        {details.map(d => (
          <div key={d.id} className={`${DETAIL_GRID} items-baseline border-b border-line-hair py-1.5 text-xs`}>
            <span>{DIFF_TYPE_KO[d.diffType]}</span>
            <Num className="text-[11px]">{d.symbol ?? '-'}</Num>
            <span className="text-fg-3">{d.fieldName ?? '-'}</span>
            <Num className="text-right text-[11.5px]">{d.internalValue ?? '-'}</Num>
            <Num className="text-right text-[11.5px] text-fg-3">{d.externalValue ?? '-'}</Num>
            <Num className="text-right text-[11.5px] text-warn">{d.diffValue ?? '-'}</Num>
            <span className="flex items-baseline gap-2">
              {d.kdId && <Badge variant="ink">알려진 차이</Badge>}
              {d.extras && <span className="text-fg-faint">{d.extras}</span>}
            </span>
          </div>
        ))}
      </div>
    </div>
  )
}

const RULE_GRID = 'grid grid-cols-[1.3fr_0.8fr_0.6fr_0.6fr_0.8fr_1.2fr] gap-3'

function RunResult({ runId, api }: { runId: string; api: ReturnType<typeof createReconApi> }) {
  const [expandedRule, setExpandedRule] = useState<string | null>(null)
  const { data, isLoading } = useQuery({
    queryKey: ['recon', 'run', runId],
    queryFn:  () => api.runs.get(runId),
  })
  if (isLoading) {
    return <div className="py-4 font-mono text-[10px] tracking-label text-fg-faint">결과 불러오는 중 …</div>
  }
  if (!data) return null
  const { run, summaries } = data
  return (
    <div>
      <div className="flex flex-wrap gap-x-4 gap-y-1 pb-3 font-mono text-[10px] tracking-label text-fg-muted">
        <span>기준일 {run.runDate}</span>
        <span>내부 시점 {run.internalAsOf ?? '-'}</span>
        <span>외부 시점(마지막 동기화) {fmt(run.externalAsOf)}</span>
        <span>실행 {fmt(run.startedAt)}</span>
      </div>
      <div className="overflow-x-auto">
        <div className="min-w-[680px] border-t-[1.5px] border-ink">
          <div className={`${RULE_GRID} border-b border-line py-2`}>
            <Label size="sm" tone="faint">검증 룰</Label>
            <Label size="sm" tone="faint">판정</Label>
            <Label size="sm" tone="faint" className="text-right">검사</Label>
            <Label size="sm" tone="faint" className="text-right">차이</Label>
            <Label size="sm" tone="faint" className="text-right">알려진 차이</Label>
            <Label size="sm" tone="faint">오류</Label>
          </div>
          {summaries.map(s => (
            <RuleRow key={s.id} summary={s} runId={runId} api={api}
              expanded={expandedRule === s.ruleCode}
              onToggle={() => setExpandedRule(prev => (prev === s.ruleCode ? null : s.ruleCode))} />
          ))}
        </div>
      </div>
    </div>
  )
}

function RuleRow({ summary: s, runId, api, expanded, onToggle }: {
  summary: ReconSummary
  runId: string
  api: ReturnType<typeof createReconApi>
  expanded: boolean
  onToggle: () => void
}) {
  const st = SUMMARY_STATUS[s.status]
  return (
    <>
      <div
        onClick={() => { if (s.diffCnt > 0) onToggle() }}
        className={`${RULE_GRID} items-baseline border-b border-line-hair py-2.5 ${
          s.diffCnt > 0 ? 'cursor-pointer hover:bg-surface-muted' : ''
        }`}
      >
        <span className="text-[13px]">
          {RULE_KO[s.ruleCode] ?? s.ruleCode}
          {s.diffCnt > 0 && (
            <span className="ml-1.5 font-mono text-[10px] text-fg-faint">{expanded ? '−' : '+'}</span>
          )}
        </span>
        <Badge variant={st.variant}>{st.label}</Badge>
        <Num className="text-right text-[12.5px] text-fg-3">{s.checkedCnt}</Num>
        <Num className={`text-right text-[12.5px] ${s.diffCnt > 0 ? 'text-warn' : 'text-fg-3'}`}>{s.diffCnt}</Num>
        <Num className="text-right text-[12.5px] text-fg-muted">{s.kdAbsorbedCnt}</Num>
        <span className="max-w-xs truncate text-xs text-danger">{s.errorMsg ?? '-'}</span>
      </div>
      {expanded && <DiffDetails runId={runId} ruleCode={s.ruleCode} api={api} />}
    </>
  )
}

function RunsTab({ api }: { api: ReturnType<typeof createReconApi> }) {
  const qc = useQueryClient()
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const { data: runs = [] } = useQuery({
    queryKey: ['recon', 'runs'],
    queryFn:  () => api.runs.list(),
  })

  const executeMutation = useMutation({
    mutationFn: () => api.runs.execute(today(), 'ALL'),
    onSuccess: run => {
      setError(null)
      setSelectedRunId(run.id)
      qc.invalidateQueries({ queryKey: ['recon'] })
    },
    onError: (e: unknown) => {
      const err = e as { response?: { status?: number; data?: { error?: string } } }
      setError(err.response?.data?.error
        ?? (err.response?.status === 409 ? '동기화가 진행 중입니다. 잠시 후 다시 시도해주세요.' : '대사 실행에 실패했습니다.'))
    },
  })

  const selected = selectedRunId ?? runs[0]?.id ?? null

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <p className="m-0 text-[12.5px] text-fg-3">
          브로커 동기화 데이터와 내부 계산 포지션을 비교하고 데이터 품질 룰을 검사합니다
        </p>
        <Button variant="primary" onClick={() => executeMutation.mutate()} disabled={executeMutation.isPending}>
          {executeMutation.isPending ? '실행 중…' : '대사 실행'}
        </Button>
      </div>

      {error && (
        <div role="alert" className="mb-4 flex items-center gap-3 border border-warn-line bg-warn-bg px-4 py-2.5">
          <Label size="sm" className="text-warn">주의</Label>
          <span className="text-[12.5px] text-fg-2">{error}</span>
        </div>
      )}

      {runs.length === 0 ? (
        <EmptyState
          title="실행 이력이 없습니다"
          description="대사 실행 버튼으로 첫 검증을 시작하세요"
        />
      ) : (
        <div className="grid gap-5 lg:grid-cols-[240px_minmax(0,1fr)]">
          <div className="min-w-0 border-t-[1.5px] border-ink">
            {runs.map((r: ReconRun) => {
              const st = RUN_STATUS[r.status]
              return (
                <button
                  key={r.id}
                  onClick={() => setSelectedRunId(r.id)}
                  aria-pressed={selected === r.id}
                  className={`block w-full border-b border-line-hair px-2.5 py-2.5 text-left transition-colors ${
                    selected === r.id ? 'bg-surface-muted' : 'hover:bg-surface-muted'
                  }`}
                >
                  <span className="flex items-baseline justify-between">
                    <Num className={`text-[12px] ${selected === r.id ? 'font-medium' : 'text-fg-2'}`}>{r.runDate}</Num>
                    {st && <Badge variant={st.variant}>{st.label}</Badge>}
                  </span>
                  <span className="mt-1 block font-mono text-[9.5px] tracking-[0.04em] text-fg-faint">
                    {fmt(r.startedAt)} · {r.runType}
                  </span>
                </button>
              )
            })}
          </div>
          <div className="min-w-0">{selected ? <RunResult runId={selected} api={api} /> : null}</div>
        </div>
      )}
    </div>
  )
}

// ── KD 탭 ────────────────────────────────────────────────────

const EMPTY_KD_FORM = {
  kdCode: '', targetSymbol: '', targetField: 'quantity',
  valueType: 'ABS' as 'ABS' | 'RATIO', allowValue: '', reason: '', apldStrtDt: today(),
}

const KD_GRID = 'grid grid-cols-[1fr_1.1fr_0.7fr_1.1fr_1.4fr_0.6fr_0.7fr] gap-3'

function KdTab({ api }: { api: ReturnType<typeof createReconApi> }) {
  const qc = useQueryClient()
  const [form, setForm] = useState(EMPTY_KD_FORM)
  const [error, setError] = useState<string | null>(null)

  const { data: kds = [] } = useQuery({ queryKey: ['recon', 'kds'], queryFn: () => api.kds.list() })

  const registerMutation = useMutation({
    mutationFn: (payload: RegisterKdPayload) => api.kds.register(payload),
    onSuccess: () => {
      setError(null); setForm(EMPTY_KD_FORM)
      qc.invalidateQueries({ queryKey: ['recon', 'kds'] })
    },
    onError: (e: unknown) => {
      const err = e as { response?: { data?: { error?: string } } }
      setError(err.response?.data?.error ?? 'KD 등록에 실패했습니다.')
    },
  })

  const deactivateMutation = useMutation({
    mutationFn: (id: string) => api.kds.deactivate(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['recon', 'kds'] }),
  })

  const submit = (e: React.FormEvent) => {
    e.preventDefault()
    registerMutation.mutate({
      kdCode: form.kdCode,
      targetSymbol: form.targetSymbol || undefined,
      targetField: form.targetField || undefined,
      valueType: form.valueType,
      allowValue: Number(form.allowValue),
      reason: form.reason,
      apldStrtDt: form.apldStrtDt,
    })
  }

  return (
    <div>
      <p className="m-0 mb-4 max-w-[88ch] text-[12.5px] leading-relaxed text-fg-3">
        허용 가능한 차이를 등록하면 대사 결과에서 &ldquo;알려진 차이&rdquo;로 구분 표시됩니다 (숨기지 않음).
        같은 코드로 재등록하면 기존 항목은 마감되고 새 버전이 열립니다.
      </p>

      <form onSubmit={submit} className="mb-4 grid grid-cols-1 items-end gap-3 border border-ink bg-surface-muted p-4 sm:grid-cols-2 lg:grid-cols-4">
        <Field id="kd-code" label="코드">
          <Input required value={form.kdCode} onChange={e => setForm({ ...form, kdCode: e.target.value })}
            placeholder="KD-005930-QTY" />
        </Field>
        <Field id="kd-symbol" label="심볼 (비우면 전체)">
          <Input value={form.targetSymbol} onChange={e => setForm({ ...form, targetSymbol: e.target.value })}
            placeholder="005930" />
        </Field>
        <Field id="kd-field" label="필드">
          <Input value={form.targetField} onChange={e => setForm({ ...form, targetField: e.target.value })} />
        </Field>
        <Field id="kd-type" label="유형">
          <Select value={form.valueType} onChange={e => setForm({ ...form, valueType: e.target.value as 'ABS' | 'RATIO' })}>
            <option value="ABS">절대값</option>
            <option value="RATIO">비율</option>
          </Select>
        </Field>
        <Field id="kd-allow" label="허용값">
          <Input required type="number" step="any" min="0" value={form.allowValue}
            onChange={e => setForm({ ...form, allowValue: e.target.value })} />
        </Field>
        <Field id="kd-start" label="적용 시작일">
          <Input required type="date" value={form.apldStrtDt}
            onChange={e => setForm({ ...form, apldStrtDt: e.target.value })} />
        </Field>
        <Field id="kd-reason" label="사유">
          <Input required value={form.reason} onChange={e => setForm({ ...form, reason: e.target.value })}
            placeholder="예: 브로커 소수점 단수차" />
        </Field>
        <div>
          <Button type="submit" variant="primary" disabled={registerMutation.isPending} className="w-full">
            등록
          </Button>
        </div>
      </form>

      {error && (
        <div role="alert" className="mb-4 flex items-center gap-3 border border-warn-line bg-warn-bg px-4 py-2.5">
          <Label size="sm" className="text-warn">주의</Label>
          <span className="text-[12.5px] text-fg-2">{error}</span>
        </div>
      )}

      {kds.length === 0 ? (
        <EmptyState title="등록된 KD가 없습니다" description="위 폼에서 허용 가능한 차이를 등록하세요" />
      ) : (
        <div className="overflow-x-auto">
          <div className="min-w-[820px] border-t-[1.5px] border-ink">
            <div className={`${KD_GRID} border-b border-line py-2`}>
              <Label size="sm" tone="faint">코드</Label>
              <Label size="sm" tone="faint">대상</Label>
              <Label size="sm" tone="faint" className="text-right">허용</Label>
              <Label size="sm" tone="faint">기간</Label>
              <Label size="sm" tone="faint">사유</Label>
              <Label size="sm" tone="faint">상태</Label>
              <span />
            </div>
            {kds.map((kd: ReconKd) => {
              const open = kd.apldEndDt.startsWith('9999')
              return (
                <div key={kd.id} className={`${KD_GRID} items-baseline border-b border-line-hair py-2.5 ${!kd.useYn ? 'opacity-50' : ''}`}>
                  <Num className="text-[12px] font-medium">{kd.kdCode}</Num>
                  <span className="text-xs text-fg-3">{kd.targetSymbol ?? '전체'} · {kd.targetField ?? '전체'}</span>
                  <Num className="text-right text-[12px]">{kd.valueType === 'ABS' ? '±' : '비율 '}{kd.allowValue}</Num>
                  <Num className="text-[11px] text-fg-3">{kd.apldStrtDt} ~ {open ? '현행' : kd.apldEndDt}</Num>
                  <span className="max-w-xs truncate text-xs text-fg-3">{kd.reason}</span>
                  <span>
                    {kd.useYn
                      ? (open ? <Badge variant="ok">활성</Badge> : <Badge variant="muted">마감</Badge>)
                      : <Badge variant="muted">비활성</Badge>}
                  </span>
                  <span className="text-right">
                    {kd.useYn && (
                      <button
                        onClick={() => { if (confirm(`"${kd.kdCode}" KD를 비활성화하시겠습니까?`)) deactivateMutation.mutate(kd.id) }}
                        className="border border-line px-2 py-1 text-xs text-fg-faint transition-colors hover:border-danger hover:text-danger"
                      >
                        비활성화
                      </button>
                    )}
                  </span>
                </div>
              )
            })}
          </div>
        </div>
      )}
    </div>
  )
}

// ── 페이지 ───────────────────────────────────────────────────

export default function ReconPage() {
  const { accessToken } = useAuth()
  const api = useMemo(() => (accessToken ? createReconApi(accessToken) : null), [accessToken])
  const [tab, setTab] = useState<'runs' | 'kds'>('runs')

  if (!api) return null

  return (
    <div className="border border-line-card bg-surface">
      <PageHeader
        className="px-5 pt-5 sm:px-7"
        title="대사 · 검증"
        meta="기관 원장 대비 내부 기록 정합성 검사"
      />

      <div className="flex gap-1 border-b border-line px-5 sm:px-7">
        {([['runs', '실행·결과'], ['kds', '알려진 차이 (KD)']] as const).map(([key, label]) => (
          <button
            key={key}
            onClick={() => setTab(key)}
            aria-pressed={tab === key}
            className={`-mb-px border-b-2 px-4 py-2.5 text-[13px] transition-colors ${
              tab === key ? 'border-ink font-medium text-ink' : 'border-transparent text-fg-faint hover:text-ink'
            }`}
          >
            {label}
          </button>
        ))}
      </div>

      <div className="px-5 py-5 pb-10 sm:px-7">
        {tab === 'runs' ? <RunsTab api={api} /> : <KdTab api={api} />}
      </div>
    </div>
  )
}
