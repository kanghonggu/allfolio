'use client'

import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '@/contexts/AuthContext'
import { createReconApi } from '@/lib/recon-api'
import type { ReconDiffDetail, ReconKd, ReconRun, ReconSummary, RegisterKdPayload } from '@/types/recon'

const RUN_STATUS_STYLE: Record<string, string> = {
  RUNNING:   'bg-yellow-900/40 text-yellow-400 border-yellow-800',
  COMPLETED: 'bg-emerald-900/40 text-emerald-400 border-emerald-800',
  FAILED:    'bg-red-900/40 text-red-400 border-red-800',
}
const SUMMARY_STATUS: Record<ReconSummary['status'], { label: string; cls: string }> = {
  PASSED:     { label: '통과',     cls: 'bg-emerald-900/40 text-emerald-400 border-emerald-800' },
  DIFF_FOUND: { label: '차이 발견', cls: 'bg-orange-900/40 text-orange-400 border-orange-800' },
  FAILED:     { label: '실행 실패', cls: 'bg-red-900/40 text-red-400 border-red-800' },
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
function today() {
  return new Date().toISOString().slice(0, 10)
}

// ── 실행·결과 탭 ─────────────────────────────────────────────

function DiffDetails({ runId, ruleCode, api }: { runId: string; ruleCode?: string; api: ReturnType<typeof createReconApi> }) {
  const { data: details = [], isLoading } = useQuery({
    queryKey: ['recon', 'details', runId, ruleCode ?? 'all'],
    queryFn:  () => api.runs.details(runId, ruleCode),
  })
  if (isLoading) return <div className="py-3 text-xs text-gray-500">상세 불러오는 중…</div>
  if (details.length === 0) return <div className="py-3 text-xs text-gray-500">차이 상세가 없습니다</div>
  return (
    <table className="mt-2 w-full text-xs">
      <thead>
        <tr className="text-left text-gray-500">
          <th className="py-1 pr-3 font-medium">유형</th>
          <th className="py-1 pr-3 font-medium">심볼</th>
          <th className="py-1 pr-3 font-medium">필드</th>
          <th className="py-1 pr-3 font-medium text-right">내부</th>
          <th className="py-1 pr-3 font-medium text-right">외부(브로커)</th>
          <th className="py-1 pr-3 font-medium text-right">차이</th>
          <th className="py-1 font-medium">비고</th>
        </tr>
      </thead>
      <tbody>
        {details.map(d => (
          <tr key={d.id} className="border-t border-gray-800">
            <td className="py-1.5 pr-3">{DIFF_TYPE_KO[d.diffType]}</td>
            <td className="py-1.5 pr-3">{d.symbol ?? '-'}</td>
            <td className="py-1.5 pr-3 text-gray-400">{d.fieldName ?? '-'}</td>
            <td className="py-1.5 pr-3 text-right">{d.internalValue ?? '-'}</td>
            <td className="py-1.5 pr-3 text-right">{d.externalValue ?? '-'}</td>
            <td className="py-1.5 pr-3 text-right text-orange-400">{d.diffValue ?? '-'}</td>
            <td className="py-1.5">
              {d.kdId && (
                <span className="rounded-full border border-blue-800 bg-blue-900/40 px-2 py-0.5 text-blue-400">
                  알려진 차이
                </span>
              )}
              {d.extras && <span className="ml-2 text-gray-500">{d.extras}</span>}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function RunResult({ runId, api }: { runId: string; api: ReturnType<typeof createReconApi> }) {
  const [expandedRule, setExpandedRule] = useState<string | null>(null)
  const { data, isLoading } = useQuery({
    queryKey: ['recon', 'run', runId],
    queryFn:  () => api.runs.get(runId),
  })
  if (isLoading) return <div className="py-4 text-sm text-gray-500">결과 불러오는 중…</div>
  if (!data) return null
  const { run, summaries } = data
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-gray-500">
        <span>기준일: {run.runDate}</span>
        <span>내부 시점: {run.internalAsOf ?? '-'}</span>
        <span>외부 시점(마지막 동기화): {fmt(run.externalAsOf)}</span>
        <span>실행: {fmt(run.startedAt)}</span>
      </div>
      <table className="w-full text-sm">
        <thead>
          <tr className="text-left text-xs text-gray-500">
            <th className="py-2 pr-4 font-medium">룰</th>
            <th className="py-2 pr-4 font-medium">결과</th>
            <th className="py-2 pr-4 font-medium text-right">검사</th>
            <th className="py-2 pr-4 font-medium text-right">차이</th>
            <th className="py-2 pr-4 font-medium text-right">알려진 차이</th>
            <th className="py-2 font-medium">오류</th>
          </tr>
        </thead>
        <tbody>
          {summaries.map(s => (
            <RuleRow key={s.id} summary={s} runId={runId} api={api}
              expanded={expandedRule === s.ruleCode}
              onToggle={() => setExpandedRule(prev => (prev === s.ruleCode ? null : s.ruleCode))} />
          ))}
        </tbody>
      </table>
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
      <tr
        onClick={() => { if (s.diffCnt > 0) onToggle() }}
        className={`border-t border-gray-800 ${s.diffCnt > 0 ? 'cursor-pointer hover:bg-gray-800/50' : ''}`}
      >
        <td className="py-2 pr-4">
          {RULE_KO[s.ruleCode] ?? s.ruleCode}
          {s.diffCnt > 0 && <span className="ml-1 text-xs text-gray-500">{expanded ? '▾' : '▸'}</span>}
        </td>
        <td className="py-2 pr-4">
          <span className={`rounded-full border px-2 py-0.5 text-xs font-medium ${st.cls}`}>{st.label}</span>
        </td>
        <td className="py-2 pr-4 text-right text-gray-400">{s.checkedCnt}</td>
        <td className={`py-2 pr-4 text-right ${s.diffCnt > 0 ? 'text-orange-400 font-medium' : 'text-gray-400'}`}>{s.diffCnt}</td>
        <td className="py-2 pr-4 text-right text-blue-400">{s.kdAbsorbedCnt}</td>
        <td className="py-2 text-xs text-red-400/80 max-w-xs truncate">{s.errorMsg ?? '-'}</td>
      </tr>
      {expanded && (
        <tr className="border-t border-gray-800/50">
          <td colSpan={6}><DiffDetails runId={runId} ruleCode={s.ruleCode} api={api} /></td>
        </tr>
      )}
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
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-sm text-gray-400">
          브로커 동기화 데이터와 내부 계산 포지션을 비교하고 데이터 품질 룰을 검사합니다
        </p>
        <button
          onClick={() => executeMutation.mutate()}
          disabled={executeMutation.isPending}
          className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium hover:bg-blue-500 disabled:opacity-50 transition-colors"
        >
          {executeMutation.isPending ? '실행 중…' : '▶ 대사 실행'}
        </button>
      </div>

      {error && (
        <div className="rounded-xl border border-red-800 bg-red-900/20 px-4 py-3 text-sm text-red-400">⚠ {error}</div>
      )}

      {runs.length === 0 ? (
        <div className="rounded-xl border border-dashed border-gray-700 p-12 text-center text-gray-400">
          실행 이력이 없습니다 — 대사 실행 버튼으로 시작하세요
        </div>
      ) : (
        <div className="grid gap-4 lg:grid-cols-[260px_1fr]">
          <div className="space-y-2">
            {runs.map((r: ReconRun) => (
              <button
                key={r.id}
                onClick={() => setSelectedRunId(r.id)}
                className={`w-full rounded-lg border px-3 py-2 text-left text-xs transition-colors ${
                  selected === r.id ? 'border-blue-600 bg-blue-950/30' : 'border-gray-700 bg-gray-900 hover:border-gray-500'
                }`}
              >
                <div className="flex items-center justify-between">
                  <span className="font-medium">{r.runDate}</span>
                  <span className={`rounded-full border px-1.5 py-0.5 ${RUN_STATUS_STYLE[r.status]}`}>{r.status}</span>
                </div>
                <div className="mt-1 text-gray-500">{fmt(r.startedAt)} · {r.runType}</div>
              </button>
            ))}
          </div>
          <div className="rounded-xl border border-gray-700 bg-gray-900 p-4">
            {selected ? <RunResult runId={selected} api={api} /> : null}
          </div>
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

  const input = 'rounded-lg border border-gray-600 bg-gray-800 px-3 py-1.5 text-sm'

  return (
    <div className="space-y-4">
      <p className="text-sm text-gray-400">
        허용 가능한 차이를 등록하면 대사 결과에서 &ldquo;알려진 차이&rdquo;로 구분 표시됩니다 (숨기지 않음).
        같은 코드로 재등록하면 기존 항목은 마감되고 새 버전이 열립니다.
      </p>

      <form onSubmit={submit} className="flex flex-wrap items-end gap-3 rounded-xl border border-gray-700 bg-gray-900 p-4">
        <label className="text-xs text-gray-500">코드
          <input required value={form.kdCode} onChange={e => setForm({ ...form, kdCode: e.target.value })}
            placeholder="KD-005930-QTY" className={`mt-1 block ${input}`} />
        </label>
        <label className="text-xs text-gray-500">심볼 (비우면 전체)
          <input value={form.targetSymbol} onChange={e => setForm({ ...form, targetSymbol: e.target.value })}
            placeholder="005930" className={`mt-1 block ${input}`} />
        </label>
        <label className="text-xs text-gray-500">필드
          <input value={form.targetField} onChange={e => setForm({ ...form, targetField: e.target.value })}
            className={`mt-1 block w-28 ${input}`} />
        </label>
        <label className="text-xs text-gray-500">유형
          <select value={form.valueType} onChange={e => setForm({ ...form, valueType: e.target.value as 'ABS' | 'RATIO' })}
            className={`mt-1 block ${input}`}>
            <option value="ABS">절대값</option>
            <option value="RATIO">비율</option>
          </select>
        </label>
        <label className="text-xs text-gray-500">허용값
          <input required type="number" step="any" min="0" value={form.allowValue}
            onChange={e => setForm({ ...form, allowValue: e.target.value })} className={`mt-1 block w-24 ${input}`} />
        </label>
        <label className="text-xs text-gray-500">적용 시작일
          <input required type="date" value={form.apldStrtDt}
            onChange={e => setForm({ ...form, apldStrtDt: e.target.value })} className={`mt-1 block ${input}`} />
        </label>
        <label className="flex-1 min-w-[200px] text-xs text-gray-500">사유
          <input required value={form.reason} onChange={e => setForm({ ...form, reason: e.target.value })}
            placeholder="예: 브로커 소수점 단수차" className={`mt-1 block w-full ${input}`} />
        </label>
        <button type="submit" disabled={registerMutation.isPending}
          className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium hover:bg-blue-500 disabled:opacity-50 transition-colors">
          등록
        </button>
      </form>

      {error && <div className="rounded-xl border border-red-800 bg-red-900/20 px-4 py-3 text-sm text-red-400">⚠ {error}</div>}

      {kds.length === 0 ? (
        <div className="rounded-xl border border-dashed border-gray-700 p-10 text-center text-gray-400">등록된 KD가 없습니다</div>
      ) : (
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-xs text-gray-500">
              <th className="py-2 pr-4 font-medium">코드</th>
              <th className="py-2 pr-4 font-medium">대상</th>
              <th className="py-2 pr-4 font-medium">허용</th>
              <th className="py-2 pr-4 font-medium">기간</th>
              <th className="py-2 pr-4 font-medium">사유</th>
              <th className="py-2 pr-4 font-medium">상태</th>
              <th className="py-2 font-medium" />
            </tr>
          </thead>
          <tbody>
            {kds.map((kd: ReconKd) => {
              const open = kd.apldEndDt.startsWith('9999')
              return (
                <tr key={kd.id} className={`border-t border-gray-800 ${!kd.useYn ? 'opacity-50' : ''}`}>
                  <td className="py-2 pr-4 font-medium">{kd.kdCode}</td>
                  <td className="py-2 pr-4 text-gray-400">{kd.targetSymbol ?? '전체'} · {kd.targetField ?? '전체'}</td>
                  <td className="py-2 pr-4">{kd.valueType === 'ABS' ? '±' : '비율 '}{kd.allowValue}</td>
                  <td className="py-2 pr-4 text-xs text-gray-400">{kd.apldStrtDt} ~ {open ? '현행' : kd.apldEndDt}</td>
                  <td className="py-2 pr-4 text-xs text-gray-400 max-w-xs truncate">{kd.reason}</td>
                  <td className="py-2 pr-4 text-xs">{kd.useYn ? (open ? <span className="text-emerald-400">활성</span> : <span className="text-gray-500">마감</span>) : <span className="text-gray-500">비활성</span>}</td>
                  <td className="py-2 text-right">
                    {kd.useYn && (
                      <button
                        onClick={() => { if (confirm(`"${kd.kdCode}" KD를 비활성화하시겠습니까?`)) deactivateMutation.mutate(kd.id) }}
                        className="rounded-lg border border-gray-700 px-2 py-1 text-xs text-gray-500 hover:border-red-700 hover:text-red-400 transition-colors"
                      >
                        비활성화
                      </button>
                    )}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
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
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">대사·검증</h1>
        <p className="mt-1 text-sm text-gray-400">브로커 데이터와 내부 기록의 정합성을 검사합니다</p>
      </div>

      <div className="flex gap-1 border-b border-gray-800">
        {([['runs', '실행·결과'], ['kds', '알려진 차이 (KD)']] as const).map(([key, label]) => (
          <button
            key={key}
            onClick={() => setTab(key)}
            className={`px-4 py-2 text-sm transition-colors ${
              tab === key ? 'border-b-2 border-blue-500 font-medium text-white' : 'text-gray-500 hover:text-gray-300'
            }`}
          >
            {label}
          </button>
        ))}
      </div>

      {tab === 'runs' ? <RunsTab api={api} /> : <KdTab api={api} />}
    </div>
  )
}
