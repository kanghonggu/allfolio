'use client'

import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import Link from 'next/link'
import { useGoalApi } from '@/lib/useApi'
import type { GoalCategory, GoalRequest, GoalResponse } from '@/types/goal'

function fmt(n: number) {
  return new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW', maximumFractionDigits: 0 }).format(n)
}
function digitsOnly(s: string) { return s.replace(/[^\d]/g, '') }
function fmtComma(n: number) { return n > 0 ? Math.round(n).toLocaleString('ko-KR') : '' }

const CATEGORY_META: Record<GoalCategory, { label: string; color: string; bg: string }> = {
  RETIREMENT:  { label: '은퇴', color: 'text-purple-400', bg: 'bg-purple-900/40 border-purple-700' },
  HOUSING:     { label: '내 집 마련', color: 'text-blue-400', bg: 'bg-blue-900/40 border-blue-700' },
  EDUCATION:   { label: '교육', color: 'text-emerald-400', bg: 'bg-emerald-900/40 border-emerald-700' },
  TRAVEL:      { label: '여행', color: 'text-amber-400', bg: 'bg-amber-900/40 border-amber-700' },
  EMERGENCY:   { label: '비상금', color: 'text-red-400', bg: 'bg-red-900/40 border-red-700' },
  OTHER:       { label: '기타', color: 'text-gray-400', bg: 'bg-gray-800 border-gray-600' },
}

const PROGRESS_COLOR = (pct: number) => {
  if (pct >= 100) return 'bg-emerald-500'
  if (pct >= 75)  return 'bg-blue-500'
  if (pct >= 50)  return 'bg-amber-500'
  return 'bg-gray-500'
}

interface GoalFormProps {
  initial?: GoalResponse
  onSubmit: (req: GoalRequest) => void
  onCancel: () => void
  loading: boolean
}

function GoalForm({ initial, onSubmit, onCancel, loading }: GoalFormProps) {
  const [name, setName] = useState(initial?.name ?? '')
  const [description, setDescription] = useState(initial?.description ?? '')
  const [targetAmount, setTargetAmount] = useState(initial ? Number(initial.targetAmount) : 0)
  const [targetDate, setTargetDate] = useState(initial?.targetDate ?? '')
  const [category, setCategory] = useState<GoalCategory>(initial?.category ?? 'OTHER')

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    onSubmit({
      name: name.trim(),
      description: description.trim() || undefined,
      targetAmount,
      targetDate: targetDate || null,
      category,
    })
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <div>
        <label className="mb-1 block text-xs text-gray-400">목표 이름 *</label>
        <input
          value={name}
          onChange={e => setName(e.target.value)}
          required
          placeholder="예: 내 집 마련 1억 모으기"
          className="w-full rounded-lg border border-gray-700 bg-gray-800 px-3 py-2 text-sm text-white placeholder-gray-600 focus:border-blue-500 focus:outline-none"
        />
      </div>

      <div>
        <label className="mb-1 block text-xs text-gray-400">목표 금액 (원) *</label>
        <input
          type="text"
          inputMode="numeric"
          placeholder="0"
          value={fmtComma(targetAmount)}
          onChange={e => { const d = digitsOnly(e.target.value); setTargetAmount(d ? parseInt(d) : 0) }}
          required
          className="w-full rounded-lg border border-gray-700 bg-gray-800 px-3 py-2 text-sm text-white placeholder-gray-600 focus:border-blue-500 focus:outline-none"
        />
      </div>

      <div className="grid gap-4 sm:grid-cols-2">
        <div>
          <label className="mb-1 block text-xs text-gray-400">카테고리</label>
          <select
            value={category}
            onChange={e => setCategory(e.target.value as GoalCategory)}
            className="w-full rounded-lg border border-gray-700 bg-gray-800 px-3 py-2 text-sm text-white focus:border-blue-500 focus:outline-none"
          >
            {(Object.keys(CATEGORY_META) as GoalCategory[]).map(k => (
              <option key={k} value={k}>{CATEGORY_META[k].label}</option>
            ))}
          </select>
        </div>
        <div>
          <label className="mb-1 block text-xs text-gray-400">목표 날짜 (선택)</label>
          <input
            type="date"
            value={targetDate}
            onChange={e => setTargetDate(e.target.value)}
            className="w-full rounded-lg border border-gray-700 bg-gray-800 px-3 py-2 text-sm text-white focus:border-blue-500 focus:outline-none"
          />
        </div>
      </div>

      <div>
        <label className="mb-1 block text-xs text-gray-400">메모 (선택)</label>
        <textarea
          value={description}
          onChange={e => setDescription(e.target.value)}
          rows={2}
          placeholder="목표에 대한 간단한 메모"
          className="w-full rounded-lg border border-gray-700 bg-gray-800 px-3 py-2 text-sm text-white placeholder-gray-600 focus:border-blue-500 focus:outline-none resize-none"
        />
      </div>

      <div className="flex gap-2 justify-end pt-1">
        <button type="button" onClick={onCancel}
          className="rounded-lg px-4 py-2 text-sm text-gray-400 hover:text-gray-200 transition-colors">
          취소
        </button>
        <button type="submit" disabled={loading || !name.trim() || targetAmount <= 0}
          className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-500 disabled:opacity-40 transition-colors">
          {loading ? '저장 중…' : initial ? '수정' : '목표 추가'}
        </button>
      </div>
    </form>
  )
}

interface GoalCardProps {
  goal: GoalResponse
  onEdit: (g: GoalResponse) => void
  onDelete: (id: string) => void
}

function GoalCard({ goal, onEdit, onDelete }: GoalCardProps) {
  const meta = CATEGORY_META[goal.category]
  const pct = Math.min(Number(goal.progressPct), 100)
  const done = pct >= 100

  return (
    <div className="rounded-xl border border-gray-700 bg-gray-900 p-5 space-y-4">
      <div className="flex items-start justify-between gap-2">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 mb-1 flex-wrap">
            <span className={`rounded-full border px-2 py-0.5 text-xs font-medium ${meta.bg} ${meta.color}`}>
              {meta.label}
            </span>
            {done && (
              <span className="rounded-full bg-emerald-900/50 border border-emerald-700 px-2 py-0.5 text-xs text-emerald-400">
                달성 완료
              </span>
            )}
          </div>
          <h3 className="font-semibold text-gray-100 truncate">{goal.name}</h3>
          {goal.description && (
            <p className="mt-0.5 text-xs text-gray-500 line-clamp-1">{goal.description}</p>
          )}
        </div>
        <div className="flex shrink-0 gap-1">
          <button onClick={() => onEdit(goal)}
            className="rounded px-2 py-1 text-xs text-gray-500 hover:text-gray-300 hover:bg-gray-800 transition-colors">
            수정
          </button>
          <button onClick={() => onDelete(goal.id)}
            className="rounded px-2 py-1 text-xs text-gray-500 hover:text-red-400 hover:bg-gray-800 transition-colors">
            삭제
          </button>
        </div>
      </div>

      {/* 진행률 바 */}
      <div>
        <div className="flex justify-between text-xs text-gray-500 mb-1.5">
          <span>진행률</span>
          <span className={`font-semibold tabular-nums ${done ? 'text-emerald-400' : 'text-white'}`}>
            {pct.toFixed(1)}%
          </span>
        </div>
        <div className="h-2.5 w-full overflow-hidden rounded-full bg-gray-800">
          <div
            className={`h-full rounded-full transition-all ${PROGRESS_COLOR(pct)}`}
            style={{ width: `${pct}%` }}
          />
        </div>
      </div>

      {/* 금액 정보 */}
      <div className="grid grid-cols-2 gap-3">
        <div>
          <p className="text-xs text-gray-600">현재 자산</p>
          <p className="text-sm font-semibold tabular-nums text-blue-400">{fmt(Number(goal.currentAmount))}</p>
        </div>
        <div>
          <p className="text-xs text-gray-600">목표 금액</p>
          <p className="text-sm font-semibold tabular-nums text-gray-200">{fmt(Number(goal.targetAmount))}</p>
        </div>
        {!done && (
          <div>
            <p className="text-xs text-gray-600">남은 금액</p>
            <p className="text-sm font-semibold tabular-nums text-amber-400">{fmt(Number(goal.remainingAmount))}</p>
          </div>
        )}
        {goal.targetDate && (
          <div>
            <p className="text-xs text-gray-600">목표 날짜</p>
            <p className="text-sm tabular-nums text-gray-300">
              {goal.targetDate}
              {goal.daysRemaining != null && (
                <span className={`ml-1.5 text-xs ${goal.daysRemaining <= 30 ? 'text-red-400' : 'text-gray-500'}`}>
                  D-{goal.daysRemaining}
                </span>
              )}
            </p>
          </div>
        )}
      </div>
    </div>
  )
}

export default function GoalsPage() {
  const goalApi = useGoalApi()
  const queryClient = useQueryClient()
  const [showForm, setShowForm] = useState(false)
  const [editing, setEditing] = useState<GoalResponse | null>(null)
  const [deletingId, setDeletingId] = useState<string | null>(null)

  const { data, isLoading, isError } = useQuery({
    queryKey: ['goals'],
    queryFn: () => goalApi!.list(),
    enabled: !!goalApi,
  })

  const createMut = useMutation({
    mutationFn: (req: GoalRequest) => goalApi!.create(req),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['goals'] }); setShowForm(false) },
  })

  const updateMut = useMutation({
    mutationFn: ({ id, req }: { id: string; req: GoalRequest }) => goalApi!.update(id, req),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['goals'] }); setEditing(null) },
  })

  const deleteMut = useMutation({
    mutationFn: (id: string) => goalApi!.delete(id),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['goals'] }); setDeletingId(null) },
  })

  if (isLoading) return <div className="h-96 animate-pulse rounded-xl bg-gray-800" />
  if (isError || !data) return (
    <div className="rounded-xl border border-red-800 bg-red-950 p-6 text-sm text-red-400">
      목표 데이터를 불러올 수 없습니다.
    </div>
  )

  const goals = data.goals
  const achieved = goals.filter(g => Number(g.progressPct) >= 100).length
  const inProgress = goals.length - achieved

  return (
    <div className="space-y-8">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <Link href="/unified/reports" className="text-sm text-gray-500 hover:text-gray-300">← 보고서</Link>
          <h1 className="text-2xl font-bold">목표 달성 트래커</h1>
        </div>
        {!showForm && !editing && (
          <button
            onClick={() => setShowForm(true)}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium hover:bg-blue-500 transition-colors"
          >
            + 목표 추가
          </button>
        )}
      </div>

      {/* 현재 총 자산 */}
      <div className="rounded-xl border border-blue-800 bg-blue-950/20 p-5">
        <p className="text-xs text-gray-500">현재 총 자산 (NAV)</p>
        <p className="mt-2 text-3xl font-bold tabular-nums text-blue-400">{fmt(Number(data.totalNav))}</p>
        <p className="mt-1 text-xs text-gray-600">
          이 금액을 기준으로 각 목표의 달성률이 계산됩니다
        </p>
      </div>

      {/* 요약 */}
      {goals.length > 0 && (
        <div className="grid gap-4 sm:grid-cols-3">
          <div className="rounded-xl border border-gray-700 bg-gray-900 p-5">
            <p className="text-xs text-gray-500">전체 목표</p>
            <p className="mt-2 text-xl font-bold">{goals.length}개</p>
          </div>
          <div className="rounded-xl border border-emerald-900 bg-gray-900 p-5">
            <p className="text-xs text-gray-500">달성 완료</p>
            <p className="mt-2 text-xl font-bold text-emerald-400">{achieved}개</p>
          </div>
          <div className="rounded-xl border border-blue-900 bg-gray-900 p-5">
            <p className="text-xs text-gray-500">진행 중</p>
            <p className="mt-2 text-xl font-bold text-blue-400">{inProgress}개</p>
          </div>
        </div>
      )}

      {/* 목표 추가 폼 */}
      {showForm && (
        <div className="rounded-xl border border-blue-700 bg-gray-900 p-6">
          <h2 className="mb-4 text-sm font-semibold text-gray-300">새 목표 추가</h2>
          <GoalForm
            onSubmit={req => createMut.mutate(req)}
            onCancel={() => setShowForm(false)}
            loading={createMut.isPending}
          />
        </div>
      )}

      {/* 목표 편집 폼 */}
      {editing && (
        <div className="rounded-xl border border-amber-700 bg-gray-900 p-6">
          <h2 className="mb-4 text-sm font-semibold text-gray-300">목표 수정</h2>
          <GoalForm
            initial={editing}
            onSubmit={req => updateMut.mutate({ id: editing.id, req })}
            onCancel={() => setEditing(null)}
            loading={updateMut.isPending}
          />
        </div>
      )}

      {/* 삭제 확인 */}
      {deletingId && (
        <div className="rounded-xl border border-red-800 bg-red-950/30 p-5">
          <p className="text-sm text-red-400 font-medium">이 목표를 삭제할까요?</p>
          <p className="mt-1 text-xs text-red-600">삭제된 목표는 복구할 수 없습니다.</p>
          <div className="mt-3 flex gap-2">
            <button
              onClick={() => deleteMut.mutate(deletingId)}
              disabled={deleteMut.isPending}
              className="rounded-lg bg-red-700 px-4 py-2 text-sm font-medium text-white hover:bg-red-600 disabled:opacity-40"
            >
              {deleteMut.isPending ? '삭제 중…' : '삭제'}
            </button>
            <button onClick={() => setDeletingId(null)}
              className="rounded-lg px-4 py-2 text-sm text-gray-400 hover:text-gray-200">
              취소
            </button>
          </div>
        </div>
      )}

      {/* 목표 목록 */}
      {goals.length === 0 ? (
        <div className="flex flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-gray-700 py-16 text-center">
          <p className="text-gray-400 font-medium">아직 목표가 없어요</p>
          <p className="text-sm text-gray-600">재무 목표를 추가하고 달성률을 추적해보세요</p>
          <button
            onClick={() => setShowForm(true)}
            className="mt-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium hover:bg-blue-500 transition-colors"
          >
            첫 목표 만들기
          </button>
        </div>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {goals.map(g => (
            <GoalCard
              key={g.id}
              goal={g}
              onEdit={g => { setEditing(g); setShowForm(false) }}
              onDelete={id => { setDeletingId(id); setEditing(null) }}
            />
          ))}
        </div>
      )}
    </div>
  )
}
