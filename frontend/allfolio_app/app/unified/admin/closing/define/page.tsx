'use client'

import { useMemo, useState } from 'react'
import axios from 'axios'
import Link from 'next/link'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '@/contexts/AuthContext'
import { useRequireAdmin } from '@/lib/useRequireAdmin'
import PageHeader from '@/components/ui/PageHeader'
import Badge from '@/components/ui/Badge'
import Button from '@/components/ui/Button'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import Field, { Input, Select } from '@/components/ui/Field'

const BASE = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'}/api/admin/closing/definitions`

interface SubStepDef {
  stepCd: string; subStepCd: string; subStepSeq: number; subStepName: string
  autoManual: string; closingCheckYn: boolean; dateTerm: number | null; dateGb: string | null
  actionType: 'CHAIN' | 'POLL' | 'MANUAL'; actionRef: string | null
  timeoutSec: number; pollIntervalSec: number; useYn: boolean
}
interface StepDef {
  stepCd: string; stepSeq: number; stepName: string; stepGroup: string | null
  termGb: 'D' | 'M' | 'Q'; cutoffStart: string | null; cutoffEnd: string | null
  essentialStepCd: string | null; url: string | null; holidayExceptYn: boolean; useYn: boolean
  subSteps: SubStepDef[]
}
interface DefHist {
  id: string; entityType: string; entityKey: string; crud: string
  snapshot: string | null; changedBy: string | null; changedAt: string
}

const EMPTY_STEP = {
  stepCd: '', stepSeq: 10, stepName: '', stepGroup: '', termGb: 'D' as 'D' | 'M' | 'Q',
  cutoffStart: '', cutoffEnd: '', essentialStepCd: '', url: '', holidayExceptYn: false, useYn: true,
}
const EMPTY_SUB = {
  stepCd: '', subStepCd: '', subStepSeq: 1, subStepName: '', autoManual: 'A',
  closingCheckYn: true, dateTerm: '', dateGb: '', actionType: 'CHAIN' as SubStepDef['actionType'],
  actionRef: '', timeoutSec: 300, pollIntervalSec: 10, useYn: true,
}

const HIST_GRID = 'grid grid-cols-[1.1fr_1.2fr_0.5fr_2.2fr] gap-3'

export default function ClosingDefinePage() {
  const { ready } = useRequireAdmin()
  const { accessToken } = useAuth()
  const qc = useQueryClient()
  const api = useMemo(() => {
    if (!accessToken) return null
    return axios.create({ baseURL: BASE, timeout: 30_000, headers: { Authorization: `Bearer ${accessToken}` } })
  }, [accessToken])

  const [tab, setTab] = useState<'defs' | 'hist'>('defs')
  const [stepForm, setStepForm] = useState(EMPTY_STEP)
  const [subForm, setSubForm] = useState(EMPTY_SUB)
  const [error, setError] = useState<string | null>(null)

  const { data: steps = [] } = useQuery({
    queryKey: ['closing-def', 'list'],
    queryFn:  async () => (await api!.get<StepDef[]>('')).data,
    enabled:  !!api && ready,
  })
  const { data: hist = [] } = useQuery({
    queryKey: ['closing-def', 'hist'],
    queryFn:  async () => (await api!.get<DefHist[]>('/history')).data,
    enabled:  !!api && ready && tab === 'hist',
  })

  const onError = (e: unknown) => {
    const err = e as { response?: { data?: { error?: string } } }
    setError(err.response?.data?.error ?? '요청이 실패했습니다')
  }
  const invalidate = () => { setError(null); qc.invalidateQueries({ queryKey: ['closing-def'] }) }

  const saveStep = useMutation({
    mutationFn: async () => api!.post('/steps', {
      ...stepForm,
      stepGroup: stepForm.stepGroup || null,
      cutoffStart: stepForm.cutoffStart || null,
      cutoffEnd: stepForm.cutoffEnd || null,
      essentialStepCd: stepForm.essentialStepCd || null,
      url: stepForm.url || null,
    }),
    onSuccess: () => { invalidate(); setStepForm(EMPTY_STEP) },
    onError,
  })
  const deleteStep = useMutation({
    mutationFn: async (stepCd: string) => api!.delete(`/steps/${stepCd}`),
    onSuccess: invalidate, onError,
  })
  const saveSub = useMutation({
    mutationFn: async () => api!.post('/substeps', {
      ...subForm,
      dateTerm: subForm.dateTerm === '' ? null : Number(subForm.dateTerm),
      dateGb: subForm.dateGb || null,
      actionRef: subForm.actionRef || null,
    }),
    onSuccess: () => { invalidate(); setSubForm(EMPTY_SUB) },
    onError,
  })
  const deleteSub = useMutation({
    mutationFn: async (t: { stepCd: string; subStepCd: string }) => api!.delete(`/substeps/${t.stepCd}/${t.subStepCd}`),
    onSuccess: invalidate, onError,
  })

  if (!ready || !api) return null

  return (
    <div className="border border-line-card bg-surface">
      <PageHeader
        className="px-5 pt-5 sm:px-7"
        title="워크플로우 정의 관리"
        meta={
          <>
            <Label size="sm" className="text-warn">ADMIN</Label>
            <span className="ml-3">단계·하위단계 정의는 데이터 — 실행 액션(actionRef)은 코드 빈, 모든 변경은 이력으로 기록</span>
          </>
        }
        actions={
          <Link href="/unified/admin/closing"
            className="border border-line bg-surface px-3.5 py-2 text-[12.5px] text-fg-2 transition-colors hover:border-ink hover:text-ink">
            ← 마감 대시보드
          </Link>
        }
      />

      <div className="flex gap-1 border-b border-line px-5 sm:px-7">
        {([['defs', '정의'], ['hist', '변경 이력']] as const).map(([key, l]) => (
          <button key={key} onClick={() => setTab(key)}
            aria-pressed={tab === key}
            className={`-mb-px border-b-2 px-4 py-2.5 text-[13px] transition-colors ${
              tab === key ? 'border-ink font-medium text-ink' : 'border-transparent text-fg-faint hover:text-ink'
            }`}>
            {l}
          </button>
        ))}
      </div>

      <div className="space-y-6 px-5 py-5 pb-10 sm:px-7">
        {error && (
          <div role="alert" className="flex items-center gap-3 border border-warn-line bg-warn-bg px-4 py-2.5">
            <Label size="sm" className="text-warn">주의</Label>
            <span className="text-[12.5px] text-fg-2">{error}</span>
          </div>
        )}

        {tab === 'defs' ? (
          <>
            {/* 단계 목록 */}
            <div className="space-y-2">
              {steps.map(step => (
                <div key={step.stepCd} className={`border border-line bg-surface p-4 ${!step.useYn ? 'opacity-50' : ''}`}>
                  <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                    <Num className="text-[12.5px] font-semibold">{step.stepCd}</Num>
                    <span className="text-[13.5px]">{step.stepName}</span>
                    <span className="font-mono text-[10px] text-fg-faint">seq {step.stepSeq} · {step.termGb}주기{step.stepGroup ? ` · ${step.stepGroup}` : ''}</span>
                    {step.essentialStepCd && <span className="font-mono text-[10px] text-fg-faint">선행 {step.essentialStepCd}</span>}
                    {step.cutoffEnd && <span className="font-mono text-[10px] text-fg-faint">컷오프 ~{step.cutoffEnd}</span>}
                    {!step.useYn && <Badge variant="danger">비활성</Badge>}
                    <span className="ml-auto flex gap-2">
                      <button onClick={() => setStepForm({
                        stepCd: step.stepCd, stepSeq: step.stepSeq, stepName: step.stepName,
                        stepGroup: step.stepGroup ?? '', termGb: step.termGb,
                        cutoffStart: step.cutoffStart ?? '', cutoffEnd: step.cutoffEnd ?? '',
                        essentialStepCd: step.essentialStepCd ?? '', url: step.url ?? '',
                        holidayExceptYn: step.holidayExceptYn, useYn: step.useYn,
                      })} className="border border-line px-2.5 py-1 text-xs text-fg-2 transition-colors hover:border-ink hover:text-ink">
                        수정
                      </button>
                      {step.useYn && (
                        <button onClick={() => { if (confirm(`${step.stepCd} 단계를 비활성화하시겠습니까?`)) deleteStep.mutate(step.stepCd) }}
                          className="border border-line px-2.5 py-1 text-xs text-fg-faint transition-colors hover:border-danger hover:text-danger">
                          비활성화
                        </button>
                      )}
                    </span>
                  </div>
                  <div className="mt-2 space-y-1">
                    {step.subSteps.map(sub => (
                      <div key={sub.subStepCd} className={`flex flex-wrap items-center gap-x-3 gap-y-1 bg-surface-muted px-3 py-1.5 text-xs ${!sub.useYn ? 'opacity-50' : ''}`}>
                        <span className="text-fg-2"><Num className="text-[11px]">{sub.subStepCd}</Num> {sub.subStepName}</span>
                        <span className="font-mono text-[10px] text-fg-faint">{sub.actionType}{sub.actionRef ? ` → ${sub.actionRef}` : ''}</span>
                        {sub.dateTerm != null && <span className="font-mono text-[10px] text-fg-faint">{sub.dateTerm}{sub.dateGb === 'B' ? '영업일' : '일'}</span>}
                        {!sub.closingCheckYn && <span className="font-mono text-[10px] text-fg-faint">롤업 제외</span>}
                        {!sub.useYn && <Badge variant="danger">비활성</Badge>}
                        <span className="ml-auto flex gap-2">
                          <button onClick={() => setSubForm({
                            stepCd: sub.stepCd, subStepCd: sub.subStepCd, subStepSeq: sub.subStepSeq,
                            subStepName: sub.subStepName, autoManual: sub.autoManual,
                            closingCheckYn: sub.closingCheckYn,
                            dateTerm: sub.dateTerm == null ? '' : String(sub.dateTerm), dateGb: sub.dateGb ?? '',
                            actionType: sub.actionType, actionRef: sub.actionRef ?? '',
                            timeoutSec: sub.timeoutSec, pollIntervalSec: sub.pollIntervalSec, useYn: sub.useYn,
                          })} className="text-fg-faint transition-colors hover:text-ink">수정</button>
                          {sub.useYn && (
                            <button onClick={() => { if (confirm(`${sub.subStepCd}를 비활성화하시겠습니까?`)) deleteSub.mutate({ stepCd: sub.stepCd, subStepCd: sub.subStepCd }) }}
                              className="text-fg-faint transition-colors hover:text-danger">비활성화</button>
                          )}
                        </span>
                      </div>
                    ))}
                  </div>
                </div>
              ))}
            </div>

            {/* 단계 폼 */}
            <form onSubmit={e => { e.preventDefault(); saveStep.mutate() }}
              className="flex flex-wrap items-end gap-3 border border-ink bg-surface-muted p-4">
              <span className="w-full">
                <Label>단계 등록/수정</Label>{' '}
                <Label size="sm" tone="faint">(같은 코드면 수정)</Label>
              </span>
              <Field id="def-step-cd" label="코드" className="w-24">
                <Input required value={stepForm.stepCd} onChange={e => setStepForm({ ...stepForm, stepCd: e.target.value })} placeholder="S070" />
              </Field>
              <Field id="def-step-seq" label="순서" className="w-20">
                <Input required type="number" value={stepForm.stepSeq} onChange={e => setStepForm({ ...stepForm, stepSeq: Number(e.target.value) })} />
              </Field>
              <Field id="def-step-name" label="이름" className="w-44">
                <Input required value={stepForm.stepName} onChange={e => setStepForm({ ...stepForm, stepName: e.target.value })} />
              </Field>
              <Field id="def-step-term" label="주기" className="w-24">
                <Select value={stepForm.termGb} onChange={e => setStepForm({ ...stepForm, termGb: e.target.value as 'D' | 'M' | 'Q' })}>
                  <option value="D">일</option><option value="M">월</option><option value="Q">분기</option>
                </Select>
              </Field>
              <Field id="def-step-essential" label="선행 단계" className="w-24">
                <Input value={stepForm.essentialStepCd} onChange={e => setStepForm({ ...stepForm, essentialStepCd: e.target.value })} placeholder="S060" />
              </Field>
              <Field id="def-step-cutoff-end" label="컷오프 종료" className="w-24">
                <Input value={stepForm.cutoffEnd} onChange={e => setStepForm({ ...stepForm, cutoffEnd: e.target.value })} placeholder="23:59" />
              </Field>
              <label className="flex items-center gap-1.5 pb-2 text-xs text-fg-3">
                <input type="checkbox" checked={stepForm.holidayExceptYn} onChange={e => setStepForm({ ...stepForm, holidayExceptYn: e.target.checked })} /> 휴일 제외
              </label>
              <Button type="submit" variant="primary">저장</Button>
            </form>

            {/* 하위단계 폼 */}
            <form onSubmit={e => { e.preventDefault(); saveSub.mutate() }}
              className="flex flex-wrap items-end gap-3 border border-ink bg-surface-muted p-4">
              <span className="w-full"><Label>하위단계 등록/수정</Label></span>
              <Field id="def-sub-step-cd" label="단계 코드" className="w-24">
                <Input required value={subForm.stepCd} onChange={e => setSubForm({ ...subForm, stepCd: e.target.value })} placeholder="S070" />
              </Field>
              <Field id="def-sub-cd" label="코드" className="w-24">
                <Input required value={subForm.subStepCd} onChange={e => setSubForm({ ...subForm, subStepCd: e.target.value })} placeholder="S070-1" />
              </Field>
              <Field id="def-sub-seq" label="순서" className="w-16">
                <Input required type="number" value={subForm.subStepSeq} onChange={e => setSubForm({ ...subForm, subStepSeq: Number(e.target.value) })} />
              </Field>
              <Field id="def-sub-name" label="이름" className="w-44">
                <Input required value={subForm.subStepName} onChange={e => setSubForm({ ...subForm, subStepName: e.target.value })} />
              </Field>
              <Field id="def-sub-action-type" label="유형" className="w-28">
                <Select value={subForm.actionType} onChange={e => {
                  const t = e.target.value as SubStepDef['actionType']
                  setSubForm({ ...subForm, actionType: t, autoManual: t === 'MANUAL' ? 'M' : 'A' })
                }}>
                  <option value="CHAIN">CHAIN</option><option value="POLL">POLL</option><option value="MANUAL">MANUAL</option>
                </Select>
              </Field>
              <Field id="def-sub-action-ref" label="액션 ref" className="w-44">
                <Input value={subForm.actionRef} onChange={e => setSubForm({ ...subForm, actionRef: e.target.value })} placeholder="SYNC_ALL_ACCOUNTS" disabled={subForm.actionType === 'MANUAL'} />
              </Field>
              <Field id="def-sub-date-term" label="실행일(M/Q)" className="w-20">
                <Input value={subForm.dateTerm} onChange={e => setSubForm({ ...subForm, dateTerm: e.target.value })} placeholder="-1" />
              </Field>
              <Field id="def-sub-date-gb" label="기준" className="w-24">
                <Select value={subForm.dateGb} onChange={e => setSubForm({ ...subForm, dateGb: e.target.value })}>
                  <option value="">-</option><option value="B">영업일</option><option value="S">달력일</option>
                </Select>
              </Field>
              <label className="flex items-center gap-1.5 pb-2 text-xs text-fg-3">
                <input type="checkbox" checked={subForm.closingCheckYn} onChange={e => setSubForm({ ...subForm, closingCheckYn: e.target.checked })} /> 마감판정 포함
              </label>
              <Button type="submit" variant="primary">저장</Button>
            </form>
          </>
        ) : (
          <div className="overflow-x-auto">
            <div className="min-w-[720px] border-t-[1.5px] border-ink">
              <div className={`${HIST_GRID} border-b border-line py-2`}>
                <Label size="sm" tone="faint">시각</Label>
                <Label size="sm" tone="faint">대상</Label>
                <Label size="sm" tone="faint">변경</Label>
                <Label size="sm" tone="faint">스냅샷</Label>
              </div>
              {hist.map(h => (
                <div key={h.id} className={`${HIST_GRID} border-b border-line-hair py-2.5 text-xs hover:bg-surface-muted`}>
                  <Num className="text-[11px] text-fg-3">{new Date(h.changedAt).toLocaleString('ko-KR')}</Num>
                  <span>{h.entityType} <Num className="text-[11px]">{h.entityKey}</Num></span>
                  <Badge variant={h.crud === 'D' ? 'danger' : h.crud === 'C' ? 'ok' : 'ink'}>
                    {h.crud === 'C' ? '생성' : h.crud === 'U' ? '수정' : '삭제'}
                  </Badge>
                  <span className="break-all font-mono text-[10.5px] text-fg-faint">{h.snapshot}</span>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
