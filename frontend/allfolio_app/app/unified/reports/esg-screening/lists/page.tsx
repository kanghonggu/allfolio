'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import Link from 'next/link'
import { useAuth } from '@/contexts/AuthContext'
import { createExclusionListApi } from '@/lib/exclusion-list-api'
import type { ExclusionList, Preset } from '@/types/exclusion-list'

export default function ExclusionListsPage() {
  const { accessToken, initialized, authenticated } = useAuth()
  const [lists, setLists] = useState<ExclusionList[]>([])
  const [presets, setPresets] = useState<Preset[]>([])
  const [error, setError] = useState<string | null>(null)
  const [form, setForm] = useState({ name: '', category: '사용자지정', description: '' })
  const [itemInput, setItemInput] = useState<Record<string, string>>({})

  const api = useMemo(() => (accessToken ? createExclusionListApi(accessToken) : null), [accessToken])

  const refetch = useCallback(async () => {
    if (!api) return
    try {
      const [ls, ps] = await Promise.all([api.list(), api.presets()])
      setLists(ls); setPresets(ps)
    } catch { setError('배제리스트를 불러오지 못했습니다.') }
  }, [api])

  useEffect(() => { if (initialized && authenticated) refetch() }, [initialized, authenticated, refetch])

  const wrap = async (fn: () => Promise<unknown>, msg: string) => {
    if (!api) return
    setError(null)
    try { await fn(); await refetch() } catch { setError(msg) }
  }

  const createList = (e: React.FormEvent) => {
    e.preventDefault()
    if (!form.name.trim()) return
    void wrap(() => api!.create({ name: form.name, category: form.category, description: form.description || null }), '생성 실패')
    setForm({ name: '', category: '사용자지정', description: '' })
  }

  const onCsv = (id: string) => (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]; if (!file) return
    const reader = new FileReader()
    reader.onload = () => void wrap(() => api!.importCsv(id, String(reader.result ?? '')), 'CSV 가져오기 실패')
    reader.readAsText(file)
    e.target.value = ''
  }

  if (!initialized) return <div className="p-6 text-gray-400">로딩 중…</div>
  if (!authenticated) return <div className="p-6 text-gray-400">로그인이 필요합니다.</div>

  return (
    <div className="mx-auto max-w-4xl space-y-8 p-6">
      <div className="flex items-center gap-3">
        <Link href="/unified/reports/esg-screening" className="text-sm text-gray-400 hover:text-white">← ESG 스크리닝</Link>
        <h1 className="text-2xl font-bold">배제리스트 관리</h1>
      </div>
      {error && <div className="rounded bg-red-900/40 px-3 py-2 text-sm text-red-300">{error}</div>}

      {/* 내장 프리셋 */}
      <section>
        <h2 className="mb-2 text-lg font-semibold">내장 프리셋 <span className="text-xs text-gray-400">(읽기전용 · 복제 후 편집)</span></h2>
        <div className="flex flex-wrap gap-2">
          {presets.map(p => (
            <button key={p.name} onClick={() => void wrap(() => api!.clonePreset(p.name), '복제 실패')}
              className="rounded border border-amber-700 bg-amber-900/20 px-3 py-1.5 text-sm text-amber-300 hover:bg-amber-900/40">
              {p.name} ({p.symbols.length}) · 복제
            </button>
          ))}
        </div>
      </section>

      {/* 생성 폼 */}
      <form onSubmit={createList} className="flex flex-wrap items-end gap-3 rounded-lg border border-gray-800 p-4">
        <label className="text-sm">이름
          <input className="ml-2 rounded bg-gray-800 px-2 py-1" value={form.name} required
            onChange={e => setForm(f => ({ ...f, name: e.target.value }))} />
        </label>
        <label className="text-sm">카테고리
          <input className="ml-2 rounded bg-gray-800 px-2 py-1" value={form.category}
            onChange={e => setForm(f => ({ ...f, category: e.target.value }))} />
        </label>
        <label className="text-sm">설명
          <input className="ml-2 rounded bg-gray-800 px-2 py-1" value={form.description}
            onChange={e => setForm(f => ({ ...f, description: e.target.value }))} />
        </label>
        <button type="submit" className="rounded bg-blue-600 px-4 py-1.5 text-sm font-medium hover:bg-blue-500">리스트 생성</button>
      </form>

      {/* 리스트들 */}
      <section className="space-y-4">
        {lists.map(l => (
          <div key={l.id} className="rounded-lg border border-gray-800 p-4">
            <div className="mb-2 flex items-center gap-3">
              <span className="font-semibold">{l.name}</span>
              <span className="text-xs text-gray-400">{l.category} · {l.itemCount}종목</span>
              <label className="ml-auto flex items-center gap-1 text-xs text-gray-400">
                <input type="checkbox" checked={l.active}
                  onChange={() => void wrap(() => api!.update(l.id, { name: l.name, category: l.category, description: l.description, active: !l.active }), '토글 실패')} />
                활성
              </label>
              <label className="cursor-pointer text-xs text-blue-400 hover:text-blue-300">
                CSV 가져오기<input type="file" accept=".csv,text/csv,text/plain" className="hidden" onChange={onCsv(l.id)} />
              </label>
              <button onClick={() => void wrap(() => api!.remove(l.id), '삭제 실패')} className="text-xs text-red-400 hover:text-red-300">리스트 삭제</button>
            </div>
            {/* 종목 추가 */}
            <div className="mb-2 flex gap-2">
              <input placeholder="심볼 추가 (예: 005930)" className="rounded bg-gray-800 px-2 py-1 text-sm"
                value={itemInput[l.id] ?? ''} onChange={e => setItemInput(m => ({ ...m, [l.id]: e.target.value }))} />
              <button onClick={() => { const s = itemInput[l.id]?.trim(); if (s) { void wrap(() => api!.addItem(l.id, s), '추가 실패'); setItemInput(m => ({ ...m, [l.id]: '' })) } }}
                className="rounded bg-gray-700 px-3 py-1 text-sm hover:bg-gray-600">추가</button>
            </div>
            <div className="flex flex-wrap gap-2">
              {l.items.map(it => (
                <span key={it.id} className="flex items-center gap-1 rounded bg-gray-800 px-2 py-1 text-xs">
                  {it.symbol}
                  <button onClick={() => void wrap(() => api!.removeItem(l.id, it.id), '삭제 실패')} className="text-red-400 hover:text-red-300">×</button>
                </span>
              ))}
              {l.items.length === 0 && <span className="text-xs text-gray-500">종목 없음</span>}
            </div>
          </div>
        ))}
        {lists.length === 0 && <div className="text-sm text-gray-500">리스트가 없습니다. 위에서 생성하거나 프리셋을 복제하세요.</div>}
      </section>
    </div>
  )
}
