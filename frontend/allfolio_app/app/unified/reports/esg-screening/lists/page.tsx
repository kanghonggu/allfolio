'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import Link from 'next/link'
import { useAuth } from '@/contexts/AuthContext'
import { createExclusionListApi } from '@/lib/exclusion-list-api'
import type { ExclusionList, Preset } from '@/types/exclusion-list'
import PageHeader from '@/components/ui/PageHeader'
import SectionHeader from '@/components/ui/SectionHeader'
import Button from '@/components/ui/Button'
import Label from '@/components/ui/Label'
import Field, { Input } from '@/components/ui/Field'
import { EmptyState, LoadingState } from '@/components/ui/states'

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

  if (!initialized) {
    return (
      <div className="border border-line-card bg-surface px-5 py-5 sm:px-7">
        <LoadingState label="로딩 중" />
      </div>
    )
  }
  if (!authenticated) {
    return (
      <div className="border border-line-card bg-surface px-5 py-5 pb-10 sm:px-7">
        <EmptyState title="로그인이 필요합니다" />
      </div>
    )
  }

  return (
    <div className="border border-line-card bg-surface">
      <div className="px-5 pt-4 sm:px-7">
        <Link
          href="/unified/reports/esg-screening"
          className="font-mono text-[10px] tracking-label text-fg-faint transition-colors hover:text-ink"
        >
          ← ESG 스크리닝
        </Link>
      </div>
      <PageHeader
        className="px-5 pt-3 sm:px-7"
        title="배제리스트 관리"
        meta="R-07 · ESG 스크리닝 배제 기준"
      />

      <div className="px-5 py-5 pb-10 sm:px-7">
        {error && (
          <div role="alert" className="mb-6 flex items-center gap-3 border border-warn-line bg-warn-bg px-4 py-2.5">
            <Label size="sm" className="text-warn">주의</Label>
            <span className="text-[12.5px] text-fg-2">{error}</span>
          </div>
        )}

        {/* 내장 프리셋 */}
        <section className="mb-8">
          <SectionHeader label="내장 프리셋" note="읽기전용 · 복제 후 편집" />
          <div className="flex flex-wrap gap-2">
            {presets.map(p => (
              <button
                key={p.name}
                onClick={() => void wrap(() => api!.clonePreset(p.name), '복제 실패')}
                className="border border-warn-line bg-warn-bg px-3 py-1.5 text-xs text-fg-2 transition-colors hover:border-warn hover:text-ink"
              >
                {p.name} <span className="font-mono text-[10px] text-fg-faint">({p.symbols.length})</span> · 복제
              </button>
            ))}
          </div>
        </section>

        {/* 생성 폼 */}
        <section className="mb-8">
          <SectionHeader label="리스트 생성" />
          <form onSubmit={createList} className="grid grid-cols-1 items-end gap-3 border border-ink bg-surface-muted p-4 sm:grid-cols-2 lg:grid-cols-4">
            <Field id="xl-name" label="이름">
              <Input required value={form.name}
                onChange={e => setForm(f => ({ ...f, name: e.target.value }))} />
            </Field>
            <Field id="xl-category" label="카테고리">
              <Input value={form.category}
                onChange={e => setForm(f => ({ ...f, category: e.target.value }))} />
            </Field>
            <Field id="xl-desc" label="설명">
              <Input value={form.description}
                onChange={e => setForm(f => ({ ...f, description: e.target.value }))} />
            </Field>
            <div>
              <Button type="submit" variant="primary" className="w-full">리스트 생성</Button>
            </div>
          </form>
        </section>

        {/* 리스트들 */}
        <section>
          <SectionHeader label="배제리스트" note={lists.length > 0 ? `${lists.length}개` : undefined} />
          {lists.length === 0 ? (
            <EmptyState
              title="리스트가 없습니다"
              description="위에서 생성하거나 프리셋을 복제하세요"
            />
          ) : (
            <div className="space-y-4">
              {lists.map(l => (
                <div key={l.id} className="border border-line-card p-4">
                  <div className="mb-3 flex flex-wrap items-baseline gap-3">
                    <span className="text-[13.5px] font-medium text-ink">{l.name}</span>
                    <span className="font-mono text-[10px] tracking-label text-fg-faint">
                      {l.category} · {l.itemCount}종목
                    </span>
                    <label className="ml-auto flex items-center gap-1.5 text-xs text-fg-3">
                      <input type="checkbox" checked={l.active}
                        onChange={() => void wrap(() => api!.update(l.id, { name: l.name, category: l.category, description: l.description, active: !l.active }), '토글 실패')} />
                      활성
                    </label>
                    <label className="cursor-pointer text-xs text-link transition-colors hover:text-link-hover">
                      CSV 가져오기<input type="file" accept=".csv,text/csv,text/plain" className="hidden" onChange={onCsv(l.id)} />
                    </label>
                    <button
                      onClick={() => void wrap(() => api!.remove(l.id), '삭제 실패')}
                      className="text-xs text-danger transition-colors hover:underline"
                    >
                      리스트 삭제
                    </button>
                  </div>
                  {/* 종목 추가 */}
                  <div className="mb-3 flex gap-2">
                    <div className="w-56">
                      <Input placeholder="심볼 추가 (예: 005930)" className="py-1.5 text-sm"
                        value={itemInput[l.id] ?? ''} onChange={e => setItemInput(m => ({ ...m, [l.id]: e.target.value }))} />
                    </div>
                    <Button size="sm"
                      onClick={() => { const s = itemInput[l.id]?.trim(); if (s) { void wrap(() => api!.addItem(l.id, s), '추가 실패'); setItemInput(m => ({ ...m, [l.id]: '' })) } }}>
                      추가
                    </Button>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    {l.items.map(it => (
                      <span key={it.id} className="flex items-center gap-1.5 border border-line bg-surface-muted px-2 py-1 font-mono text-[11px] text-fg-2">
                        {it.symbol}
                        <button
                          onClick={() => void wrap(() => api!.removeItem(l.id, it.id), '삭제 실패')}
                          className="text-danger transition-colors hover:text-ink"
                        >
                          ×
                        </button>
                      </span>
                    ))}
                    {l.items.length === 0 && <span className="text-xs text-fg-faint">종목 없음</span>}
                  </div>
                </div>
              ))}
            </div>
          )}
        </section>
      </div>
    </div>
  )
}
