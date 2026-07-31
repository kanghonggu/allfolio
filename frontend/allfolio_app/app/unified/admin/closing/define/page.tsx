'use client'

import { useMemo, useState } from 'react'
import axios from 'axios'
import Link from 'next/link'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '@/contexts/AuthContext'
import { useRequireAdmin } from '@/lib/useRequireAdmin'

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

  const input = 'rounded-lg border border-gray-600 bg-gray-800 px-2.5 py-1.5 text-sm'
  const label = 'text-xs text-gray-500'

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">워크플로우 정의 관리</h1>
          <p className="mt-1 text-sm text-gray-400">
            단계·하위단계 정의는 데이터 — 실행 액션(actionRef)은 코드 빈이며, 모든 변경은 이력으로 남습니다
          </p>
        </div>
        <Link href="/unified/admin/closing"
          className="rounded-lg border border-gray-600 px-4 py-2 text-sm hover:border-gray-400 transition-colors">
          ← 마감 대시보드
        </Link>
      </div>

      <div className="flex gap-1 border-b border-gray-800">
        {([['defs', '정의'], ['hist', '변경 이력']] as const).map(([key, l]) => (
          <button key={key} onClick={() => setTab(key)}
            className={`px-4 py-2 text-sm ${tab === key ? 'border-b-2 border-blue-500 font-medium text-white' : 'text-gray-500 hover:text-gray-300'}`}>
            {l}
          </button>
        ))}
      </div>

      {error && <div className="rounded-xl border border-red-800 bg-red-900/20 px-4 py-3 text-sm text-red-400">⚠ {error}</div>}

      {tab === 'defs' ? (
        <>
          {/* 단계 목록 */}
          <div className="space-y-2">
            {steps.map(step => (
              <div key={step.stepCd} className={`rounded-xl border border-gray-700 bg-gray-900 p-4 ${!step.useYn ? 'opacity-50' : ''}`}>
                <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                  <span className="font-semibold">{step.stepCd}</span>
                  <span>{step.stepName}</span>
                  <span className="text-xs text-gray-500">seq {step.stepSeq} · {step.termGb}주기{step.stepGroup ? ` · ${step.stepGroup}` : ''}</span>
                  {step.essentialStepCd && <span className="text-xs text-gray-500">선행 {step.essentialStepCd}</span>}
                  {step.cutoffEnd && <span className="text-xs text-gray-500">컷오프 ~{step.cutoffEnd}</span>}
                  {!step.useYn && <span className="text-xs text-red-400">비활성</span>}
                  <span className="ml-auto flex gap-2">
                    <button onClick={() => setStepForm({
                      stepCd: step.stepCd, stepSeq: step.stepSeq, stepName: step.stepName,
                      stepGroup: step.stepGroup ?? '', termGb: step.termGb,
                      cutoffStart: step.cutoffStart ?? '', cutoffEnd: step.cutoffEnd ?? '',
                      essentialStepCd: step.essentialStepCd ?? '', url: step.url ?? '',
                      holidayExceptYn: step.holidayExceptYn, useYn: step.useYn,
                    })} className="rounded-lg border border-gray-600 px-2.5 py-1 text-xs hover:border-blue-500 hover:text-blue-400">
                      수정
                    </button>
                    {step.useYn && (
                      <button onClick={() => { if (confirm(`${step.stepCd} 단계를 비활성화하시겠습니까?`)) deleteStep.mutate(step.stepCd) }}
                        className="rounded-lg border border-gray-700 px-2.5 py-1 text-xs text-gray-500 hover:border-red-700 hover:text-red-400">
                        비활성화
                      </button>
                    )}
                  </span>
                </div>
                <div className="mt-2 space-y-1">
                  {step.subSteps.map(sub => (
                    <div key={sub.subStepCd} className={`flex flex-wrap items-center gap-x-3 gap-y-1 rounded-lg bg-gray-950/60 px-3 py-1.5 text-xs ${!sub.useYn ? 'opacity-50' : ''}`}>
                      <span className="text-gray-300">{sub.subStepCd} {sub.subStepName}</span>
                      <span className="text-gray-600">{sub.actionType}{sub.actionRef ? ` → ${sub.actionRef}` : ''}</span>
                      {sub.dateTerm != null && <span className="text-gray-600">{sub.dateTerm}{sub.dateGb === 'B' ? '영업일' : '일'}</span>}
                      {!sub.closingCheckYn && <span className="text-gray-600">롤업 제외</span>}
                      {!sub.useYn && <span className="text-red-400">비활성</span>}
                      <span className="ml-auto flex gap-2">
                        <button onClick={() => setSubForm({
                          stepCd: sub.stepCd, subStepCd: sub.subStepCd, subStepSeq: sub.subStepSeq,
                          subStepName: sub.subStepName, autoManual: sub.autoManual,
                          closingCheckYn: sub.closingCheckYn,
                          dateTerm: sub.dateTerm == null ? '' : String(sub.dateTerm), dateGb: sub.dateGb ?? '',
                          actionType: sub.actionType, actionRef: sub.actionRef ?? '',
                          timeoutSec: sub.timeoutSec, pollIntervalSec: sub.pollIntervalSec, useYn: sub.useYn,
                        })} className="text-gray-500 hover:text-blue-400">수정</button>
                        {sub.useYn && (
                          <button onClick={() => { if (confirm(`${sub.subStepCd}를 비활성화하시겠습니까?`)) deleteSub.mutate({ stepCd: sub.stepCd, subStepCd: sub.subStepCd }) }}
                            className="text-gray-600 hover:text-red-400">비활성화</button>
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
            className="flex flex-wrap items-end gap-3 rounded-xl border border-gray-700 bg-gray-900 p-4">
            <span className="w-full text-sm font-semibold">단계 등록/수정 <span className="text-xs font-normal text-gray-500">(같은 코드면 수정)</span></span>
            <label className={label}>코드<input required value={stepForm.stepCd} onChange={e => setStepForm({ ...stepForm, stepCd: e.target.value })} placeholder="S070" className={`mt-1 block w-24 ${input}`} /></label>
            <label className={label}>순서<input required type="number" value={stepForm.stepSeq} onChange={e => setStepForm({ ...stepForm, stepSeq: Number(e.target.value) })} className={`mt-1 block w-20 ${input}`} /></label>
            <label className={label}>이름<input required value={stepForm.stepName} onChange={e => setStepForm({ ...stepForm, stepName: e.target.value })} className={`mt-1 block ${input}`} /></label>
            <label className={label}>주기
              <select value={stepForm.termGb} onChange={e => setStepForm({ ...stepForm, termGb: e.target.value as 'D' | 'M' | 'Q' })} className={`mt-1 block ${input}`}>
                <option value="D">일</option><option value="M">월</option><option value="Q">분기</option>
              </select>
            </label>
            <label className={label}>선행 단계<input value={stepForm.essentialStepCd} onChange={e => setStepForm({ ...stepForm, essentialStepCd: e.target.value })} placeholder="S060" className={`mt-1 block w-24 ${input}`} /></label>
            <label className={label}>컷오프 종료<input value={stepForm.cutoffEnd} onChange={e => setStepForm({ ...stepForm, cutoffEnd: e.target.value })} placeholder="23:59" className={`mt-1 block w-24 ${input}`} /></label>
            <label className="flex items-center gap-1.5 text-xs text-gray-500">
              <input type="checkbox" checked={stepForm.holidayExceptYn} onChange={e => setStepForm({ ...stepForm, holidayExceptYn: e.target.checked })} /> 휴일 제외
            </label>
            <button type="submit" className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium hover:bg-blue-500">저장</button>
          </form>

          {/* 하위단계 폼 */}
          <form onSubmit={e => { e.preventDefault(); saveSub.mutate() }}
            className="flex flex-wrap items-end gap-3 rounded-xl border border-gray-700 bg-gray-900 p-4">
            <span className="w-full text-sm font-semibold">하위단계 등록/수정</span>
            <label className={label}>단계 코드<input required value={subForm.stepCd} onChange={e => setSubForm({ ...subForm, stepCd: e.target.value })} placeholder="S070" className={`mt-1 block w-24 ${input}`} /></label>
            <label className={label}>코드<input required value={subForm.subStepCd} onChange={e => setSubForm({ ...subForm, subStepCd: e.target.value })} placeholder="S070-1" className={`mt-1 block w-24 ${input}`} /></label>
            <label className={label}>순서<input required type="number" value={subForm.subStepSeq} onChange={e => setSubForm({ ...subForm, subStepSeq: Number(e.target.value) })} className={`mt-1 block w-16 ${input}`} /></label>
            <label className={label}>이름<input required value={subForm.subStepName} onChange={e => setSubForm({ ...subForm, subStepName: e.target.value })} className={`mt-1 block ${input}`} /></label>
            <label className={label}>유형
              <select value={subForm.actionType} onChange={e => {
                const t = e.target.value as SubStepDef['actionType']
                setSubForm({ ...subForm, actionType: t, autoManual: t === 'MANUAL' ? 'M' : 'A' })
              }} className={`mt-1 block ${input}`}>
                <option value="CHAIN">CHAIN</option><option value="POLL">POLL</option><option value="MANUAL">MANUAL</option>
              </select>
            </label>
            <label className={label}>액션 ref<input value={subForm.actionRef} onChange={e => setSubForm({ ...subForm, actionRef: e.target.value })} placeholder="SYNC_ALL_ACCOUNTS" disabled={subForm.actionType === 'MANUAL'} className={`mt-1 block w-44 ${input} disabled:opacity-40`} /></label>
            <label className={label}>실행일(M/Q)<input value={subForm.dateTerm} onChange={e => setSubForm({ ...subForm, dateTerm: e.target.value })} placeholder="-1" className={`mt-1 block w-16 ${input}`} /></label>
            <label className={label}>기준
              <select value={subForm.dateGb} onChange={e => setSubForm({ ...subForm, dateGb: e.target.value })} className={`mt-1 block ${input}`}>
                <option value="">-</option><option value="B">영업일</option><option value="S">달력일</option>
              </select>
            </label>
            <label className="flex items-center gap-1.5 text-xs text-gray-500">
              <input type="checkbox" checked={subForm.closingCheckYn} onChange={e => setSubForm({ ...subForm, closingCheckYn: e.target.checked })} /> 마감판정 포함
            </label>
            <button type="submit" className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium hover:bg-blue-500">저장</button>
          </form>
        </>
      ) : (
        <table className="w-full text-xs">
          <thead>
            <tr className="text-left text-gray-500">
              <th className="py-2 pr-4 font-medium">시각</th>
              <th className="py-2 pr-4 font-medium">대상</th>
              <th className="py-2 pr-4 font-medium">변경</th>
              <th className="py-2 font-medium">스냅샷</th>
            </tr>
          </thead>
          <tbody>
            {hist.map(h => (
              <tr key={h.id} className="border-t border-gray-800 align-top">
                <td className="py-1.5 pr-4 text-gray-400 whitespace-nowrap">{new Date(h.changedAt).toLocaleString('ko-KR')}</td>
                <td className="py-1.5 pr-4">{h.entityType} {h.entityKey}</td>
                <td className={`py-1.5 pr-4 ${h.crud === 'D' ? 'text-red-400' : h.crud === 'C' ? 'text-emerald-400' : 'text-blue-400'}`}>
                  {h.crud === 'C' ? '생성' : h.crud === 'U' ? '수정' : '삭제'}
                </td>
                <td className="py-1.5 text-gray-500 break-all">{h.snapshot}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
