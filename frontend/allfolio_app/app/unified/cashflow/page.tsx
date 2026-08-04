// app/unified/cashflow/page.tsx
'use client'

import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import Link from 'next/link'
import { useCashFlowApi, useUnifiedApi } from '@/lib/useApi'
import CurrencySelect from '@/components/CurrencySelect'
import { fmtKrw } from '@/lib/report-format'
import type { Account } from '@/types/unified'
import type { CashFlowItem, FxRequest, TransferRequest } from '@/types/returns'

const today = () => new Date().toISOString().slice(0, 10)

const FLOW_TYPE_KO: Record<string, string> = {
  TRANSFER_IN: '이체 입금',
  TRANSFER_OUT: '이체 출금',
  FX_IN: '환전 입금',
  FX_OUT: '환전 출금',
  DEPOSIT: '입금',
  WITHDRAWAL: '출금',
}

interface TransferFormState {
  fromAccountId: string
  toAccountId: string
  flowDate: string
  amount: string
  currency: string
  memo: string
}

interface FxFormState {
  accountId: string
  toAccountId: string
  flowDate: string
  fromAmount: string
  fromCurrency: string
  toAmount: string
  toCurrency: string
  memo: string
}

function transferError(f: { fromAccountId: string; toAccountId: string; amount: string; currency: string }): string | null {
  if (!f.fromAccountId || !f.toAccountId) return '계좌를 선택하세요'
  if (f.fromAccountId === f.toAccountId) return '출발·도착 계좌가 같을 수 없습니다'
  if (!(Number(f.amount) > 0)) return '금액은 0보다 커야 합니다'
  if (!f.currency) return '통화를 입력하세요'
  return null
}

function fxError(f: { accountId: string; fromAmount: string; fromCurrency: string; toAmount: string; toCurrency: string }): string | null {
  if (!f.accountId) return '계좌를 선택하세요'
  if (!(Number(f.fromAmount) > 0) || !(Number(f.toAmount) > 0)) return '금액은 0보다 커야 합니다'
  if (!f.fromCurrency || !f.toCurrency) return '통화를 입력하세요'
  if (f.fromCurrency.toUpperCase() === f.toCurrency.toUpperCase()) return '환전 통화가 같을 수 없습니다'
  return null
}

function serverErrorMessage(error: unknown, fallback: string): string {
  const msg = (error as { response?: { data?: { error?: string; message?: string } } })?.response?.data
  return msg?.error ?? msg?.message ?? fallback
}

const emptyTransferForm: TransferFormState = {
  fromAccountId: '', toAccountId: '', flowDate: today(), amount: '', currency: '', memo: '',
}

const emptyFxForm: FxFormState = {
  accountId: '', toAccountId: '', flowDate: today(), fromAmount: '', fromCurrency: '', toAmount: '', toCurrency: '', memo: '',
}

export default function CashflowPage() {
  const api = useCashFlowApi()
  const unified = useUnifiedApi()
  const qc = useQueryClient()

  const { data: accounts = [], isLoading: accountsLoading } = useQuery({
    queryKey: ['unified', 'accounts'],
    queryFn: () => unified!.accounts.list(),
    enabled: !!unified,
  })

  const { data: internalFlows = [], isLoading: flowsLoading } = useQuery({
    queryKey: ['cashflow', 'internal'],
    queryFn: () => api!.list(),
    enabled: !!api,
    select: (rows) =>
      rows.filter(
        (r) =>
          !!r.linkId ||
          r.flowType === 'TRANSFER_IN' ||
          r.flowType === 'TRANSFER_OUT' ||
          r.flowType === 'FX_IN' ||
          r.flowType === 'FX_OUT',
      ),
  })

  const accountById = useMemo(() => new Map(accounts.map((a) => [a.id, a])), [accounts])

  const [transferForm, setTransferForm] = useState<TransferFormState>(emptyTransferForm)
  const [transferValidationError, setTransferValidationError] = useState<string | null>(null)

  const [fxForm, setFxForm] = useState<FxFormState>(emptyFxForm)
  const [fxValidationError, setFxValidationError] = useState<string | null>(null)

  const transferMutation = useMutation({
    mutationFn: (req: TransferRequest) => api!.transfer(req),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['cashflow'] })
      setTransferForm(emptyTransferForm)
      setTransferValidationError(null)
    },
  })

  const fxMutation = useMutation({
    mutationFn: (req: FxRequest) => api!.fx(req),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['cashflow'] })
      setFxForm(emptyFxForm)
      setFxValidationError(null)
    },
  })

  const handleFromAccountChange = (accountId: string) => {
    const account = accountById.get(accountId)
    setTransferForm((f) => ({
      ...f,
      fromAccountId: accountId,
      currency: account?.currency ?? f.currency,
    }))
  }

  const submitTransfer = (e: React.FormEvent) => {
    e.preventDefault()
    const err = transferError(transferForm)
    setTransferValidationError(err)
    if (err) return
    transferMutation.mutate({
      fromAccountId: transferForm.fromAccountId,
      toAccountId: transferForm.toAccountId,
      flowDate: transferForm.flowDate,
      amount: Number(transferForm.amount),
      currency: transferForm.currency,
      memo: transferForm.memo || null,
    })
  }

  const submitFx = (e: React.FormEvent) => {
    e.preventDefault()
    const err = fxError(fxForm)
    setFxValidationError(err)
    if (err) return
    fxMutation.mutate({
      accountId: fxForm.accountId,
      toAccountId: fxForm.toAccountId || null,
      flowDate: fxForm.flowDate,
      fromAmount: Number(fxForm.fromAmount),
      fromCurrency: fxForm.fromCurrency,
      toAmount: Number(fxForm.toAmount),
      toCurrency: fxForm.toCurrency,
      memo: fxForm.memo || null,
    })
  }

  const accountLabel = (accountId: string | null): string => {
    if (!accountId) return '-'
    const account = accountById.get(accountId)
    return account ? `${account.accountName} (${account.provider})` : accountId
  }

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-2xl font-bold">현금흐름 기록</h1>
        <p className="mt-1 text-sm text-gray-400">계좌간이체·환전을 입력해 기록합니다</p>
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        {/* 계좌간이체 */}
        <section className="rounded-xl border border-gray-700 bg-gray-900 p-5">
          <h2 className="mb-4 text-lg font-semibold">계좌간이체</h2>
          <form onSubmit={submitTransfer} className="space-y-3">
            <label className="block text-sm text-gray-400">
              출발 계좌
              <select
                aria-label="이체 출발 계좌"
                className="mt-1 w-full rounded-md border border-gray-700 bg-gray-950 px-3 py-2 text-gray-200"
                value={transferForm.fromAccountId}
                onChange={(e) => handleFromAccountChange(e.target.value)}
              >
                <option value="">선택하세요</option>
                {accounts.map((a: Account) => (
                  <option key={a.id} value={a.id}>{a.accountName} ({a.provider})</option>
                ))}
              </select>
            </label>
            <label className="block text-sm text-gray-400">
              도착 계좌
              <select
                aria-label="이체 도착 계좌"
                className="mt-1 w-full rounded-md border border-gray-700 bg-gray-950 px-3 py-2 text-gray-200"
                value={transferForm.toAccountId}
                onChange={(e) => setTransferForm((f) => ({ ...f, toAccountId: e.target.value }))}
              >
                <option value="">선택하세요</option>
                {accounts.map((a: Account) => (
                  <option key={a.id} value={a.id}>{a.accountName} ({a.provider})</option>
                ))}
              </select>
            </label>
            <label className="block text-sm text-gray-400">
              날짜
              <input
                type="date"
                required
                aria-label="이체 날짜"
                className="mt-1 w-full rounded-md border border-gray-700 bg-gray-950 px-3 py-2 text-gray-200"
                value={transferForm.flowDate}
                onChange={(e) => setTransferForm((f) => ({ ...f, flowDate: e.target.value }))}
              />
            </label>
            <div className="flex gap-3">
              <label className="flex-1 text-sm text-gray-400">
                금액
                <input
                  type="number"
                  step="0.01"
                  min="0"
                  aria-label="이체 금액"
                  className="mt-1 w-full rounded-md border border-gray-700 bg-gray-950 px-3 py-2 text-gray-200"
                  value={transferForm.amount}
                  onChange={(e) => setTransferForm((f) => ({ ...f, amount: e.target.value }))}
                />
              </label>
              <label className="w-28 text-sm text-gray-400">
                통화
                <CurrencySelect
                  allowEmpty
                  ariaLabel="이체 통화"
                  value={transferForm.currency}
                  onChange={(currency) => setTransferForm((f) => ({ ...f, currency }))}
                />
              </label>
            </div>
            <label className="block text-sm text-gray-400">
              메모
              <input
                type="text"
                aria-label="이체 메모"
                className="mt-1 w-full rounded-md border border-gray-700 bg-gray-950 px-3 py-2 text-gray-200"
                value={transferForm.memo}
                onChange={(e) => setTransferForm((f) => ({ ...f, memo: e.target.value }))}
              />
            </label>

            {transferValidationError && (
              <p className="text-sm text-red-400">{transferValidationError}</p>
            )}
            {transferMutation.isError && (
              <p className="text-sm text-red-400">
                {serverErrorMessage(transferMutation.error, '이체 기록에 실패했습니다.')}
              </p>
            )}

            <button
              type="submit"
              disabled={transferMutation.isPending || !api}
              className="rounded-lg bg-emerald-700 px-4 py-2 text-sm font-medium text-white hover:bg-emerald-600 disabled:opacity-50"
            >
              {transferMutation.isPending ? '기록 중…' : '이체 기록'}
            </button>
          </form>
        </section>

        {/* 환전 */}
        <section className="rounded-xl border border-gray-700 bg-gray-900 p-5">
          <h2 className="mb-4 text-lg font-semibold">환전</h2>
          <form onSubmit={submitFx} className="space-y-3">
            <label className="block text-sm text-gray-400">
              출발 계좌
              <select
                aria-label="환전 출발 계좌"
                className="mt-1 w-full rounded-md border border-gray-700 bg-gray-950 px-3 py-2 text-gray-200"
                value={fxForm.accountId}
                onChange={(e) => setFxForm((f) => ({ ...f, accountId: e.target.value }))}
              >
                <option value="">선택하세요</option>
                {accounts.map((a: Account) => (
                  <option key={a.id} value={a.id}>{a.accountName} ({a.provider})</option>
                ))}
              </select>
            </label>
            <label className="block text-sm text-gray-400">
              도착 계좌 <span className="text-xs text-gray-500">(선택 — 미지정 시 동일 계좌, 지정 시 계좌간 환전)</span>
              <select
                aria-label="환전 도착 계좌"
                className="mt-1 w-full rounded-md border border-gray-700 bg-gray-950 px-3 py-2 text-gray-200"
                value={fxForm.toAccountId}
                onChange={(e) => setFxForm((f) => ({ ...f, toAccountId: e.target.value }))}
              >
                <option value="">동일 계좌</option>
                {accounts.map((a: Account) => (
                  <option key={a.id} value={a.id}>{a.accountName} ({a.provider})</option>
                ))}
              </select>
            </label>
            <label className="block text-sm text-gray-400">
              날짜
              <input
                type="date"
                required
                aria-label="환전 날짜"
                className="mt-1 w-full rounded-md border border-gray-700 bg-gray-950 px-3 py-2 text-gray-200"
                value={fxForm.flowDate}
                onChange={(e) => setFxForm((f) => ({ ...f, flowDate: e.target.value }))}
              />
            </label>
            <div className="flex gap-3">
              <label className="flex-1 text-sm text-gray-400">
                From 금액
                <input
                  type="number"
                  step="0.01"
                  min="0"
                  aria-label="환전 From 금액"
                  className="mt-1 w-full rounded-md border border-gray-700 bg-gray-950 px-3 py-2 text-gray-200"
                  value={fxForm.fromAmount}
                  onChange={(e) => setFxForm((f) => ({ ...f, fromAmount: e.target.value }))}
                />
              </label>
              <label className="w-28 text-sm text-gray-400">
                From 통화
                <CurrencySelect
                  allowEmpty
                  ariaLabel="환전 From 통화"
                  value={fxForm.fromCurrency}
                  onChange={(fromCurrency) => setFxForm((f) => ({ ...f, fromCurrency }))}
                />
              </label>
            </div>
            <div className="flex gap-3">
              <label className="flex-1 text-sm text-gray-400">
                To 금액
                <input
                  type="number"
                  step="0.01"
                  min="0"
                  aria-label="환전 To 금액"
                  className="mt-1 w-full rounded-md border border-gray-700 bg-gray-950 px-3 py-2 text-gray-200"
                  value={fxForm.toAmount}
                  onChange={(e) => setFxForm((f) => ({ ...f, toAmount: e.target.value }))}
                />
              </label>
              <label className="w-28 text-sm text-gray-400">
                To 통화
                <CurrencySelect
                  allowEmpty
                  ariaLabel="환전 To 통화"
                  value={fxForm.toCurrency}
                  onChange={(toCurrency) => setFxForm((f) => ({ ...f, toCurrency }))}
                />
              </label>
            </div>
            <label className="block text-sm text-gray-400">
              메모
              <input
                type="text"
                aria-label="환전 메모"
                className="mt-1 w-full rounded-md border border-gray-700 bg-gray-950 px-3 py-2 text-gray-200"
                value={fxForm.memo}
                onChange={(e) => setFxForm((f) => ({ ...f, memo: e.target.value }))}
              />
            </label>

            {fxValidationError && (
              <p className="text-sm text-red-400">{fxValidationError}</p>
            )}
            {fxMutation.isError && (
              <p className="text-sm text-red-400">
                {serverErrorMessage(fxMutation.error, '환전 기록에 실패했습니다.')}
              </p>
            )}

            <button
              type="submit"
              disabled={fxMutation.isPending || !api}
              className="rounded-lg bg-emerald-700 px-4 py-2 text-sm font-medium text-white hover:bg-emerald-600 disabled:opacity-50"
            >
              {fxMutation.isPending ? '기록 중…' : '환전 기록'}
            </button>
          </form>
        </section>
      </div>

      {/* 최근 내부이동 */}
      <section className="space-y-3">
        <h2 className="text-lg font-semibold">최근 내부이동</h2>
        {accountsLoading || flowsLoading ? (
          <div className="h-32 animate-pulse rounded-xl bg-gray-800" />
        ) : internalFlows.length === 0 ? (
          <p className="rounded-xl border border-dashed border-gray-700 p-8 text-center text-sm text-gray-500">
            기록된 이체·환전 내역이 없습니다.
          </p>
        ) : (
          <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
                  <th className="p-3">날짜</th><th className="p-3">유형</th>
                  <th className="p-3">계좌</th><th className="p-3 text-right">금액</th>
                  <th className="p-3 text-right">금액(KRW)</th><th className="p-3">메모</th>
                </tr>
              </thead>
              <tbody>
                {internalFlows.map((r: CashFlowItem) => (
                  <tr key={r.id} className="border-b border-gray-800 last:border-b-0">
                    <td className="p-3 tabular-nums text-gray-400">{r.flowDate}</td>
                    <td className="p-3">
                      <span className="rounded bg-gray-800 px-2 py-0.5 text-xs text-gray-300">
                        {FLOW_TYPE_KO[r.flowType] ?? r.flowType}
                      </span>
                    </td>
                    <td className="p-3 text-gray-300">{accountLabel(r.accountId)}</td>
                    <td className="p-3 text-right tabular-nums text-gray-300">
                      {r.amount.toLocaleString()} {r.currency}
                    </td>
                    <td className="p-3 text-right tabular-nums text-gray-100">{fmtKrw(r.amountKrw)}</td>
                    <td className="p-3 text-gray-400">{r.memo ?? '-'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <div className="flex gap-3">
        <Link href="/unified" className="text-sm text-gray-400 hover:text-white transition-colors">
          ← 대시보드로
        </Link>
      </div>
    </div>
  )
}
