'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { useAuth } from '@/contexts/AuthContext'
import { useRequireAdmin } from '@/lib/useRequireAdmin'
import { createTaxRateAdminApi } from '@/lib/tax-rate-admin-api'
import type { IncomeType, TaxRate } from '@/types/tax-rate'

const COUNTRIES = ['US', 'KR', 'JP'] as const
const INCOME_TYPES: IncomeType[] = ['DIVIDEND', 'INTEREST', 'DISTRIBUTION']

export default function TaxRateMasterPage() {
  const { ready } = useRequireAdmin()
  const { accessToken } = useAuth()
  const [rates, setRates] = useState<TaxRate[]>([])
  const [error, setError] = useState<string | null>(null)
  const [form, setForm] = useState({ country: 'US', incomeType: 'DIVIDEND' as IncomeType, rate: '15', effectiveStart: '' })

  const api = useMemo(() => (accessToken ? createTaxRateAdminApi(accessToken) : null), [accessToken])

  const refetch = useCallback(async () => {
    if (!api) return
    try { setRates(await api.list()) } catch { setError('세율 목록을 불러오지 못했습니다.') }
  }, [api])

  useEffect(() => { if (ready) refetch() }, [ready, refetch])

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!api) return
    setError(null)
    try {
      await api.register({
        country: form.country,
        incomeType: form.incomeType,
        rate: Number(form.rate),
        effectiveStart: form.effectiveStart,
      })
      await refetch()
    } catch { setError('등록에 실패했습니다. 입력값(세율 0~50, 시작일)을 확인하세요.') }
  }

  // 국가×유형 그룹핑 이력 타임라인
  const history = useMemo(() => {
    const groups = new Map<string, TaxRate[]>()
    for (const r of rates) {
      const key = `${r.country}·${r.incomeType}`
      groups.set(key, [...(groups.get(key) ?? []), r])
    }
    return Array.from(groups.entries()).map(([key, rs]) => ({
      key, rows: [...rs].sort((a, b) => b.effectiveStart.localeCompare(a.effectiveStart)),
    }))
  }, [rates])

  if (!ready) return <div className="p-6 text-gray-400">권한 확인 중…</div>

  return (
    <div className="mx-auto max-w-5xl space-y-8 p-6">
      <h1 className="text-2xl font-bold">원천징수 세율 마스터 <span className="text-sm text-gray-400">(ADMIN)</span></h1>
      {error && <div className="rounded bg-red-900/40 px-3 py-2 text-sm text-red-300">{error}</div>}

      {/* 등록 폼 */}
      <form onSubmit={submit} className="flex flex-wrap items-end gap-3 rounded-lg border border-gray-800 p-4">
        <label className="text-sm">국가
          <select className="ml-2 rounded bg-gray-800 px-2 py-1" value={form.country}
            onChange={e => setForm(f => ({ ...f, country: e.target.value }))}>
            {COUNTRIES.map(c => <option key={c} value={c}>{c}</option>)}
          </select>
        </label>
        <label className="text-sm">유형
          <select className="ml-2 rounded bg-gray-800 px-2 py-1" value={form.incomeType}
            onChange={e => setForm(f => ({ ...f, incomeType: e.target.value as IncomeType }))}>
            {INCOME_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
          </select>
        </label>
        <label className="text-sm">세율(%)
          <input type="number" step="0.001" min="0" max="50" required className="ml-2 w-24 rounded bg-gray-800 px-2 py-1"
            value={form.rate} onChange={e => setForm(f => ({ ...f, rate: e.target.value }))} />
        </label>
        <label className="text-sm">적용 시작일
          <input type="date" required className="ml-2 rounded bg-gray-800 px-2 py-1"
            value={form.effectiveStart} onChange={e => setForm(f => ({ ...f, effectiveStart: e.target.value }))} />
        </label>
        <button type="submit" className="rounded bg-blue-600 px-4 py-1.5 text-sm font-medium hover:bg-blue-500">저장</button>
      </form>

      {/* 세율 목록 */}
      <section>
        <h2 className="mb-2 text-lg font-semibold">현행 + 이력</h2>
        <table className="w-full text-sm">
          <thead className="text-gray-400">
            <tr className="border-b border-gray-800 text-left">
              <th className="py-2">국가</th><th>유형</th><th>세율</th><th>적용 시작</th><th>적용 종료</th><th>수정일</th>
            </tr>
          </thead>
          <tbody>
            {rates.map(r => (
              <tr key={r.id} className={`border-b border-gray-900 ${r.effectiveEnd === null ? 'font-semibold text-white' : 'text-gray-400'}`}>
                <td className="py-1.5">{r.country}</td><td>{r.incomeType}</td>
                <td>{r.rate.toFixed(3)}%</td><td>{r.effectiveStart}</td>
                <td>{r.effectiveEnd ?? '현행'}</td><td>{r.updatedAt?.slice(0, 10) ?? '-'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      {/* 국가×유형 변경 이력 */}
      <section>
        <h2 className="mb-2 text-lg font-semibold">변경 이력</h2>
        <div className="space-y-3">
          {history.map(g => (
            <div key={g.key} className="rounded border border-gray-800 p-3">
              <div className="mb-1 text-sm font-medium">{g.key}</div>
              <div className="flex flex-wrap gap-2 text-xs text-gray-400">
                {g.rows.map(r => (
                  <span key={r.id} className="rounded bg-gray-800 px-2 py-1">
                    {r.effectiveStart} ~ {r.effectiveEnd ?? '현행'} : {r.rate.toFixed(3)}%
                  </span>
                ))}
              </div>
            </div>
          ))}
        </div>
      </section>
    </div>
  )
}
