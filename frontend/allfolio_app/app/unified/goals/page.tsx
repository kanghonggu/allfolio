'use client'

import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import Link from 'next/link'
import { useGoalApi } from '@/lib/useApi'
import PageHeader from '@/components/ui/PageHeader'
import SectionHeader from '@/components/ui/SectionHeader'
import Badge from '@/components/ui/Badge'
import Button from '@/components/ui/Button'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import Field, { Input, Select, Textarea } from '@/components/ui/Field'
import { EmptyState, ErrorState, LoadingState } from '@/components/ui/states'
import type { GoalCategory, GoalRequest, GoalResponse } from '@/types/goal'

function fmt(n: number) {
  return new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW', maximumFractionDigits: 0 }).format(n)
}
function digitsOnly(s: string) { return s.replace(/[^\d]/g, '') }
function fmtComma(n: number) { return n > 0 ? Math.round(n).toLocaleString('ko-KR') : '' }

const CATEGORY_META: Record<GoalCategory, { label: string }> = {
  RETIREMENT:  { label: '은퇴' },
  HOUSING:     { label: '내 집 마련' },
  EDUCATION:   { label: '교육' },
  TRAVEL:      { label: '여행' },
  EMERGENCY:   { label: '비상금' },
  OTHER:       { label: '기타' },
}

const PROGRESS_COLOR = (pct: number) => {
  if (pct >= 100) return 'bg-ok'
  if (pct >= 75)  return 'bg-ink'
  if (pct >= 50)  return 'bg-fg-3'
  return 'bg-fg-muted'
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
      <Field id="goal-name" label="목표 이름 *">
        <Input
          value={name}
          onChange={e => setName(e.target.value)}
          required
          placeholder="예: 내 집 마련 1억 모으기"
        />
      </Field>

      <Field id="goal-target-amount" label="목표 금액 (원) *">
        <Input
          type="text"
          inputMode="numeric"
          placeholder="0"
          value={fmtComma(targetAmount)}
          onChange={e => { const d = digitsOnly(e.target.value); setTargetAmount(d ? parseInt(d) : 0) }}
          required
        />
      </Field>

      <div className="grid gap-4 sm:grid-cols-2">
        <Field id="goal-category" label="카테고리">
          <Select
            value={category}
            onChange={e => setCategory(e.target.value as GoalCategory)}
          >
            {(Object.keys(CATEGORY_META) as GoalCategory[]).map(k => (
              <option key={k} value={k}>{CATEGORY_META[k].label}</option>
            ))}
          </Select>
        </Field>
        <Field id="goal-target-date" label="목표 날짜 (선택)">
          <Input
            type="date"
            value={targetDate}
            onChange={e => setTargetDate(e.target.value)}
          />
        </Field>
      </div>

      <Field id="goal-description" label="메모 (선택)">
        <Textarea
          value={description}
          onChange={e => setDescription(e.target.value)}
          rows={2}
          placeholder="목표에 대한 간단한 메모"
          className="resize-none"
        />
      </Field>

      <div className="flex justify-end gap-2.5 pt-1">
        <Button type="button" onClick={onCancel}>
          취소
        </Button>
        <Button type="submit" variant="primary" disabled={loading || !name.trim() || targetAmount <= 0}>
          {loading ? '저장 중…' : initial ? '수정' : '목표 추가'}
        </Button>
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
    <div className="space-y-4 border border-line-card bg-surface p-5">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0 flex-1">
          <div className="mb-1.5 flex flex-wrap items-center gap-2">
            <Label size="sm" tone="faint">{meta.label}</Label>
            {done && <Badge variant="ok">달성 완료</Badge>}
          </div>
          <h3 className="m-0 truncate text-[14px] font-semibold text-ink">{goal.name}</h3>
          {goal.description && (
            <p className="mt-0.5 line-clamp-1 text-xs text-fg-faint">{goal.description}</p>
          )}
        </div>
        <div className="flex shrink-0 gap-1">
          <button onClick={() => onEdit(goal)}
            className="px-2 py-1 text-xs text-fg-faint transition-colors hover:text-ink">
            수정
          </button>
          <button onClick={() => onDelete(goal.id)}
            className="px-2 py-1 text-xs text-fg-faint transition-colors hover:text-danger">
            삭제
          </button>
        </div>
      </div>

      {/* 진행률 바 */}
      <div>
        <div className="mb-1.5 flex items-baseline justify-between">
          <Label size="sm" tone="faint">진행률</Label>
          <Num className={`text-xs font-semibold ${done ? 'text-ok' : 'text-ink'}`}>
            {pct.toFixed(1)}%
          </Num>
        </div>
        <div className="h-1.5 w-full overflow-hidden bg-line-soft">
          <div
            className={`h-full transition-all ${PROGRESS_COLOR(pct)}`}
            style={{ width: `${pct}%` }}
          />
        </div>
      </div>

      {/* 금액 정보 */}
      <div className="grid grid-cols-2 gap-3 border-t border-line-hair pt-3">
        <div>
          <Label size="sm" tone="ghost">현재 자산</Label>
          <Num className="mt-0.5 block text-[13px] font-medium text-ink">{fmt(Number(goal.currentAmount))}</Num>
        </div>
        <div>
          <Label size="sm" tone="ghost">목표 금액</Label>
          <Num className="mt-0.5 block text-[13px] font-medium text-fg-2">{fmt(Number(goal.targetAmount))}</Num>
        </div>
        {!done && (
          <div>
            <Label size="sm" tone="ghost">남은 금액</Label>
            <Num className="mt-0.5 block text-[13px] font-medium text-warn">{fmt(Number(goal.remainingAmount))}</Num>
          </div>
        )}
        {goal.targetDate && (
          <div>
            <Label size="sm" tone="ghost">목표 날짜</Label>
            <Num className="mt-0.5 block text-[13px] text-fg-3">
              {goal.targetDate}
              {goal.daysRemaining != null && (
                <span className={`ml-1.5 text-xs ${goal.daysRemaining <= 30 ? 'text-danger' : 'text-fg-faint'}`}>
                  D-{goal.daysRemaining}
                </span>
              )}
            </Num>
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

  if (isLoading) return (
    <div className="border border-line-card bg-surface">
      <PageHeader className="px-5 pt-5 sm:px-7" title="목표 달성 트래커" />
      <div className="px-5 py-5 pb-10 sm:px-7">
        <LoadingState label="목표 불러오는 중" />
      </div>
    </div>
  )
  if (isError || !data) return (
    <div className="border border-line-card bg-surface">
      <PageHeader className="px-5 pt-5 sm:px-7" title="목표 달성 트래커" />
      <div className="px-5 py-5 pb-10 sm:px-7">
        <ErrorState message="목표 데이터를 불러올 수 없습니다." />
      </div>
    </div>
  )

  const goals = data.goals
  const achieved = goals.filter(g => Number(g.progressPct) >= 100).length
  const inProgress = goals.length - achieved

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
        title="목표 달성 트래커"
        meta={goals.length > 0 ? `목표 ${goals.length} · 달성 ${achieved} · 진행 ${inProgress}` : '재무 목표와 달성률을 관리합니다'}
        actions={
          !showForm && !editing ? (
            <Button variant="primary" onClick={() => setShowForm(true)}>
              목표 추가
            </Button>
          ) : undefined
        }
      />

      <div className="px-5 py-5 pb-10 sm:px-7">
        {/* 현재 총 자산 */}
        <section className="border border-line-card bg-surface-muted p-5">
          <Label size="sm" tone="faint">현재 총 자산 (NAV)</Label>
          <Num className="mt-2 block text-[26px] font-semibold text-ink">{fmt(Number(data.totalNav))}</Num>
          <p className="mt-1 text-xs text-fg-faint">
            이 금액을 기준으로 각 목표의 달성률이 계산됩니다
          </p>
        </section>

        {/* 요약 */}
        {goals.length > 0 && (
          <div className="mt-6 grid grid-cols-1 gap-px border border-line-soft bg-line-soft sm:grid-cols-3">
            <div className="bg-surface px-3.5 py-3">
              <Label size="sm" tone="faint">전체 목표</Label>
              <Num className="mt-1 block text-[15px] font-semibold text-ink">{goals.length}개</Num>
            </div>
            <div className="bg-surface px-3.5 py-3">
              <Label size="sm" tone="faint">달성 완료</Label>
              <Num className="mt-1 block text-[15px] font-semibold text-ok">{achieved}개</Num>
            </div>
            <div className="bg-surface px-3.5 py-3">
              <Label size="sm" tone="faint">진행 중</Label>
              <Num className="mt-1 block text-[15px] font-semibold text-ink">{inProgress}개</Num>
            </div>
          </div>
        )}

        {/* 목표 추가 폼 */}
        {showForm && (
          <div className="mt-6 border border-ink bg-surface-muted p-5 sm:p-6">
            <SectionHeader label="새 목표 추가" />
            <GoalForm
              onSubmit={req => createMut.mutate(req)}
              onCancel={() => setShowForm(false)}
              loading={createMut.isPending}
            />
          </div>
        )}

        {/* 목표 편집 폼 */}
        {editing && (
          <div className="mt-6 border border-ink bg-surface-muted p-5 sm:p-6">
            <SectionHeader label="목표 수정" />
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
          <div className="mt-6 border border-danger bg-surface p-5">
            <p className="m-0 text-sm font-medium text-danger">이 목표를 삭제할까요?</p>
            <p className="mt-1 text-xs text-fg-faint">삭제된 목표는 복구할 수 없습니다.</p>
            <div className="mt-3 flex gap-2.5">
              <button
                onClick={() => deleteMut.mutate(deletingId)}
                disabled={deleteMut.isPending}
                className="border border-danger bg-danger px-3.5 py-2 text-[12.5px] text-white transition-colors disabled:cursor-not-allowed disabled:opacity-50"
              >
                {deleteMut.isPending ? '삭제 중…' : '삭제'}
              </button>
              <Button onClick={() => setDeletingId(null)}>
                취소
              </Button>
            </div>
          </div>
        )}

        {/* 목표 목록 */}
        <div className="mt-8">
          <SectionHeader label="목표 목록" note={goals.length > 0 ? `${goals.length}건` : undefined} />
          {goals.length === 0 ? (
            <EmptyState
              title="아직 목표가 없습니다"
              description="재무 목표를 추가하고 달성률을 추적해보세요"
              action={
                <Button variant="primary" size="sm" onClick={() => setShowForm(true)}>
                  첫 목표 만들기
                </Button>
              }
            />
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
      </div>
    </div>
  )
}
