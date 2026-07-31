'use client'

import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useRequireAdmin } from '@/lib/useRequireAdmin'
import { useExclusionPresetAdminApi } from '@/lib/useApi'
import type { ExclusionPreset, UpsertPresetRequest } from '@/types/exclusion-preset'

const EMPTY_FORM: UpsertPresetRequest = { symbol: '', listName: '', reason: '' }

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

  if (!ready) return <div className="p-6 text-gray-400">권한 확인 중…</div>

  const presets = data ?? []

  return (
    <div className="mx-auto max-w-5xl space-y-8 p-6">
      <h1 className="text-2xl font-bold">배제 프리셋 <span className="text-sm text-gray-400">(ADMIN)</span></h1>
      {error && <div className="rounded bg-red-900/40 px-3 py-2 text-sm text-red-300">{error}</div>}

      {/* 추가/수정 폼 */}
      <form onSubmit={submit} className="flex flex-wrap items-end gap-3 rounded-lg border border-gray-800 p-4">
        <label className="text-sm">심볼
          <input required className="ml-2 w-32 rounded bg-gray-800 px-2 py-1"
            value={form.symbol} onChange={e => setForm(f => ({ ...f, symbol: e.target.value }))} />
        </label>
        <label className="text-sm">리스트명
          <input required className="ml-2 w-40 rounded bg-gray-800 px-2 py-1"
            value={form.listName} onChange={e => setForm(f => ({ ...f, listName: e.target.value }))} />
        </label>
        <label className="text-sm">사유
          <input required className="ml-2 w-48 rounded bg-gray-800 px-2 py-1"
            value={form.reason} onChange={e => setForm(f => ({ ...f, reason: e.target.value }))} />
        </label>
        <button type="submit" disabled={upsertMut.isPending}
          className="rounded bg-blue-600 px-4 py-1.5 text-sm font-medium hover:bg-blue-500 disabled:opacity-40">
          {upsertMut.isPending ? '저장 중…' : '저장'}
        </button>
        {(form.symbol || form.listName || form.reason) && (
          <button type="button" onClick={() => setForm(EMPTY_FORM)}
            className="rounded px-3 py-1.5 text-sm text-gray-400 hover:text-gray-200">
            취소
          </button>
        )}
      </form>

      {/* 프리셋 목록 */}
      <section>
        <h2 className="mb-2 text-lg font-semibold">프리셋 목록</h2>
        {isLoading && <div className="h-40 animate-pulse rounded bg-gray-800" />}
        {isError && (
          <div className="rounded bg-red-900/40 px-3 py-2 text-sm text-red-300">
            프리셋 목록을 불러오지 못했습니다.
          </div>
        )}
        {!isLoading && !isError && (
          <table className="w-full text-sm">
            <thead className="text-gray-400">
              <tr className="border-b border-gray-800 text-left">
                <th className="py-2">심볼</th><th>리스트명</th><th>사유</th><th>수정일</th><th></th>
              </tr>
            </thead>
            <tbody>
              {presets.map(p => (
                <tr key={p.id} className="border-b border-gray-900 text-gray-200">
                  <td className="py-1.5">{p.symbol}</td>
                  <td>{p.listName}</td>
                  <td>{p.reason}</td>
                  <td>{p.updatedAt?.slice(0, 10) ?? '-'}</td>
                  <td className="space-x-2 py-1.5 text-right">
                    <button onClick={() => edit(p)}
                      className="rounded px-2 py-1 text-xs text-gray-400 hover:text-gray-200">
                      수정
                    </button>
                    <button onClick={() => deleteMut.mutate(p.id)} disabled={deleteMut.isPending}
                      className="rounded px-2 py-1 text-xs text-red-400 hover:text-red-300 disabled:opacity-40">
                      삭제
                    </button>
                  </td>
                </tr>
              ))}
              {presets.length === 0 && (
                <tr><td colSpan={5} className="py-4 text-center text-gray-500">등록된 프리셋이 없습니다.</td></tr>
              )}
            </tbody>
          </table>
        )}
      </section>
    </div>
  )
}
