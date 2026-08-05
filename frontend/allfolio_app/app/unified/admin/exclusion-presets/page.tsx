'use client'

import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useRequireAdmin } from '@/lib/useRequireAdmin'
import { useExclusionPresetAdminApi } from '@/lib/useApi'
import PageHeader from '@/components/ui/PageHeader'
import SectionHeader from '@/components/ui/SectionHeader'
import Button from '@/components/ui/Button'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import Field, { Input } from '@/components/ui/Field'
import { EmptyState, ErrorState, LoadingState } from '@/components/ui/states'
import type { ExclusionPreset, UpsertPresetRequest } from '@/types/exclusion-preset'

const EMPTY_FORM: UpsertPresetRequest = { symbol: '', listName: '', reason: '' }

const PRESET_GRID = 'grid grid-cols-[0.8fr_1fr_1.6fr_0.8fr_0.9fr] gap-3'

export default function ExclusionPresetAdminPage() {
  const { ready } = useRequireAdmin()
  const api = useExclusionPresetAdminApi()
  const queryClient = useQueryClient()
  const [form, setForm] = useState<UpsertPresetRequest>(EMPTY_FORM)
  const [error, setError] = useState<string | null>(null)

  const { data, isLoading, isError } = useQuery({
    queryKey: ['exclusion-presets'],
    queryFn: () => api!.list(),
    enabled: !!api && ready,
  })

  const upsertMut = useMutation({
    mutationFn: (req: UpsertPresetRequest) => api!.upsert(req),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['exclusion-presets'] })
      setForm(EMPTY_FORM)
      setError(null)
    },
    onError: () => setError('저장에 실패했습니다. 입력값(심볼·리스트명·사유)을 확인하세요.'),
  })

  const deleteMut = useMutation({
    mutationFn: (id: string) => api!.remove(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['exclusion-presets'] }),
    onError: () => setError('삭제에 실패했습니다.'),
  })

  function submit(e: React.FormEvent) {
    e.preventDefault()
    if (!api) return
    upsertMut.mutate({
      symbol: form.symbol.trim(),
      listName: form.listName.trim(),
      reason: form.reason.trim(),
    })
  }

  function edit(preset: ExclusionPreset) {
    setForm({ symbol: preset.symbol, listName: preset.listName, reason: preset.reason })
  }

  if (!ready) return <LoadingState label="권한 확인 중" />

  const presets = data ?? []

  return (
    <div className="border border-line-card bg-surface">
      <PageHeader
        className="px-5 pt-5 sm:px-7"
        title="배제 프리셋"
        meta={
          <>
            <Label size="sm" className="text-warn">ADMIN</Label>
            <span className="ml-3">ESG 스크리닝 내장 배제 프리셋 큐레이션</span>
          </>
        }
      />

      <div className="px-5 py-5 pb-10 sm:px-7">
        {error && (
          <div role="alert" className="mb-4 flex items-center gap-3 border border-warn-line bg-warn-bg px-4 py-2.5">
            <Label size="sm" className="text-warn">주의</Label>
            <span className="text-[12.5px] text-fg-2">{error}</span>
          </div>
        )}

        {/* 추가/수정 폼 */}
        <form onSubmit={submit} className="mb-8 grid grid-cols-1 items-end gap-3 border border-ink bg-surface-muted p-4 sm:grid-cols-2 lg:grid-cols-4">
          <Field id="preset-symbol" label="심볼">
            <Input required value={form.symbol} onChange={e => setForm(f => ({ ...f, symbol: e.target.value }))} />
          </Field>
          <Field id="preset-list-name" label="리스트명">
            <Input required value={form.listName} onChange={e => setForm(f => ({ ...f, listName: e.target.value }))} />
          </Field>
          <Field id="preset-reason" label="사유">
            <Input required value={form.reason} onChange={e => setForm(f => ({ ...f, reason: e.target.value }))} />
          </Field>
          <div className="flex gap-2">
            <Button type="submit" variant="primary" disabled={upsertMut.isPending} className="flex-1">
              {upsertMut.isPending ? '저장 중…' : '저장'}
            </Button>
            {(form.symbol || form.listName || form.reason) && (
              <Button type="button" variant="ghost" onClick={() => setForm(EMPTY_FORM)}>
                취소
              </Button>
            )}
          </div>
        </form>

        {/* 프리셋 목록 */}
        <section>
          <SectionHeader label="프리셋 목록" note={presets.length > 0 ? `${presets.length}건` : undefined} />
          {isLoading && <LoadingState label="프리셋 불러오는 중" />}
          {isError && <ErrorState message="프리셋 목록을 불러오지 못했습니다." />}
          {!isLoading && !isError && (
            presets.length === 0 ? (
              <EmptyState title="등록된 프리셋이 없습니다" description="위 폼에서 배제 프리셋을 등록하세요" />
            ) : (
              <div className="overflow-x-auto">
                <div className="min-w-[640px] border-t-[1.5px] border-ink">
                  <div className={`${PRESET_GRID} border-b border-line py-2`}>
                    <Label size="sm" tone="faint">심볼</Label>
                    <Label size="sm" tone="faint">리스트명</Label>
                    <Label size="sm" tone="faint">사유</Label>
                    <Label size="sm" tone="faint">수정일</Label>
                    <span />
                  </div>
                  {presets.map(p => (
                    <div key={p.id} className={`${PRESET_GRID} items-baseline border-b border-line-hair py-2.5 hover:bg-surface-muted`}>
                      <Num className="text-[12px] font-medium">{p.symbol}</Num>
                      <span className="text-[12.5px]">{p.listName}</span>
                      <span className="truncate text-[12.5px] text-fg-3">{p.reason}</span>
                      <Num className="text-[12px] text-fg-3">{p.updatedAt?.slice(0, 10) ?? '-'}</Num>
                      <span className="flex justify-end gap-2">
                        <button
                          onClick={() => edit(p)}
                          className="border border-line px-2 py-1 text-xs text-fg-2 transition-colors hover:border-ink hover:text-ink"
                        >
                          수정
                        </button>
                        <button
                          onClick={() => deleteMut.mutate(p.id)}
                          disabled={deleteMut.isPending}
                          className="border border-line px-2 py-1 text-xs text-fg-faint transition-colors hover:border-danger hover:text-danger disabled:opacity-40"
                        >
                          삭제
                        </button>
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            )
          )}
        </section>
      </div>
    </div>
  )
}
