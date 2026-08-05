'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { useAuth } from '@/contexts/AuthContext'
import { useRequireAdmin } from '@/lib/useRequireAdmin'
import { createTaxRateAdminApi } from '@/lib/tax-rate-admin-api'
import PageHeader from '@/components/ui/PageHeader'
import SectionHeader from '@/components/ui/SectionHeader'
import Button from '@/components/ui/Button'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import Field, { Input, Select } from '@/components/ui/Field'
import { LoadingState } from '@/components/ui/states'
import type { IncomeType, TaxRate } from '@/types/tax-rate'

const COUNTRIES = ['US', 'KR', 'JP'] as const
const INCOME_TYPES: IncomeType[] = ['DIVIDEND', 'INTEREST', 'DISTRIBUTION']

const RATE_GRID = 'grid grid-cols-[0.6fr_1fr_0.8fr_1fr_1fr_1fr] gap-3'

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

  if (!ready) return <LoadingState label="권한 확인 중" />

  return (
    <div className="border border-line-card bg-surface">
      <PageHeader
        className="px-5 pt-5 sm:px-7"
        title="원천징수 세율 마스터"
        meta={
          <>
            <Label size="sm" className="text-warn">ADMIN</Label>
            <span className="ml-3">국가×소득유형 원천징수 세율 · 유효기간 버저닝</span>
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

        {/* 등록 폼 */}
        <form onSubmit={submit} className="mb-8 grid grid-cols-1 items-end gap-3 border border-ink bg-surface-muted p-4 sm:grid-cols-2 lg:grid-cols-5">
          <Field id="tax-country" label="국가">
            <Select value={form.country} onChange={e => setForm(f => ({ ...f, country: e.target.value }))}>
              {COUNTRIES.map(c => <option key={c} value={c}>{c}</option>)}
            </Select>
          </Field>
          <Field id="tax-type" label="유형">
            <Select value={form.incomeType} onChange={e => setForm(f => ({ ...f, incomeType: e.target.value as IncomeType }))}>
              {INCOME_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
            </Select>
          </Field>
          <Field id="tax-rate" label="세율(%)">
            <Input type="number" step="0.001" min="0" max="50" required
              value={form.rate} onChange={e => setForm(f => ({ ...f, rate: e.target.value }))} />
          </Field>
          <Field id="tax-start" label="적용 시작일">
            <Input type="date" required
              value={form.effectiveStart} onChange={e => setForm(f => ({ ...f, effectiveStart: e.target.value }))} />
          </Field>
          <div>
            <Button type="submit" variant="primary" className="w-full">저장</Button>
          </div>
        </form>

        {/* 세율 목록 */}
        <section>
          <SectionHeader label="현행 + 이력" note="현행 행은 굵게 표시" />
          <div className="overflow-x-auto">
            <div className="min-w-[640px] border-t-[1.5px] border-ink">
              <div className={`${RATE_GRID} border-b border-line py-2`}>
                <Label size="sm" tone="faint">국가</Label>
                <Label size="sm" tone="faint">유형</Label>
                <Label size="sm" tone="faint" className="text-right">세율</Label>
                <Label size="sm" tone="faint">적용 시작</Label>
                <Label size="sm" tone="faint">적용 종료</Label>
                <Label size="sm" tone="faint">수정일</Label>
              </div>
              {rates.map(r => {
                const current = r.effectiveEnd === null
                return (
                  <div
                    key={r.id}
                    className={`${RATE_GRID} items-baseline border-b border-line-hair py-2.5 hover:bg-surface-muted ${
                      current ? 'font-medium' : 'text-fg-3'
                    }`}
                  >
                    <Num className="text-[12px]">{r.country}</Num>
                    <span className="text-[12.5px]">{r.incomeType}</span>
                    <Num className="text-right text-[12.5px]">{r.rate.toFixed(3)}%</Num>
                    <Num className="text-[12px]">{r.effectiveStart}</Num>
                    <Num className="text-[12px]">{r.effectiveEnd ?? '현행'}</Num>
                    <Num className="text-[12px]">{r.updatedAt?.slice(0, 10) ?? '-'}</Num>
                  </div>
                )
              })}
            </div>
          </div>
        </section>

        {/* 국가×유형 변경 이력 */}
        <section className="mt-8">
          <SectionHeader label="변경 이력" />
          <div className="space-y-3">
            {history.map(g => (
              <div key={g.key} className="border border-line p-3">
                <div className="mb-1.5 text-[13px] font-medium">{g.key}</div>
                <div className="flex flex-wrap gap-2">
                  {g.rows.map(r => (
                    <span key={r.id} className="bg-surface-muted px-2 py-1 font-mono text-[10.5px] text-fg-3 tnum">
                      {r.effectiveStart} ~ {r.effectiveEnd ?? '현행'} : {r.rate.toFixed(3)}%
                    </span>
                  ))}
                </div>
              </div>
            ))}
          </div>
        </section>
      </div>
    </div>
  )
}
