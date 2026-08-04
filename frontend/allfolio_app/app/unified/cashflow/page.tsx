// app/unified/cashflow/page.tsx
'use client'

import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import Link from 'next/link'
import { useCashFlowApi, useUnifiedApi } from '@/lib/useApi'
import CurrencySelect from '@/components/CurrencySelect'
import PageHeader from '@/components/ui/PageHeader'
import SectionHeader from '@/components/ui/SectionHeader'
import Button from '@/components/ui/Button'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import Field, { Input, Select } from '@/components/ui/Field'
import { EmptyState, LoadingState } from '@/components/ui/states'
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

const FLOW_GRID = 'grid grid-cols-[0.9fr_0.8fr_1.5fr_1.1fr_1.1fr_1fr] gap-3'

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

  const accountOptions = accounts.map((a: Account) => (
    <option key={a.id} value={a.id}>{a.accountName} ({a.provider})</option>
  ))

  return (
    <div className="border border-line-card bg-surface">
      <PageHeader
        className="px-5 pt-5 sm:px-7"
        title="현금흐름 기록"
        meta="계좌간이체 · 환전 입력"
      />

      <div className="px-5 py-5 pb-10 sm:px-7">
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
          {/* 계좌간이체 */}
          <section className="border border-line-card bg-surface-muted p-5">
            <SectionHeader label="계좌간이체" />
            <form onSubmit={submitTransfer} className="space-y-3.5">
              <Field id="tf-from" label="출발 계좌">
                <Select
                  aria-label="이체 출발 계좌"
                  value={transferForm.fromAccountId}
                  onChange={(e) => handleFromAccountChange(e.target.value)}
                >
                  <option value="">선택하세요</option>
                  {accountOptions}
                </Select>
              </Field>
              <Field id="tf-to" label="도착 계좌">
                <Select
                  aria-label="이체 도착 계좌"
                  value={transferForm.toAccountId}
                  onChange={(e) => setTransferForm((f) => ({ ...f, toAccountId: e.target.value }))}
                >
                  <option value="">선택하세요</option>
                  {accountOptions}
                </Select>
              </Field>
              <Field id="tf-date" label="날짜">
                <Input
                  type="date"
                  required
                  aria-label="이체 날짜"
                  value={transferForm.flowDate}
                  onChange={(e) => setTransferForm((f) => ({ ...f, flowDate: e.target.value }))}
                />
              </Field>
              <div className="flex gap-3">
                <Field id="tf-amount" label="금액" className="flex-1">
                  <Input
                    aria-label="이체 금액"
                    type="number"
                    step="0.01"
                    min="0"
                    value={transferForm.amount}
                    onChange={(e) => setTransferForm((f) => ({ ...f, amount: e.target.value }))}
                  />
                </Field>
                <div className="w-28">
                  <label htmlFor="tf-currency" className="mb-1.5 block font-mono text-[10px] tracking-label text-fg-muted">
                    통화
                  </label>
                  <CurrencySelect
                    id="tf-currency"
                    ariaLabel="이체 통화"
                    allowEmpty
                    value={transferForm.currency}
                    onChange={(currency) => setTransferForm((f) => ({ ...f, currency }))}
                  />
                </div>
              </div>
              <Field id="tf-memo" label="메모">
                <Input
                  aria-label="이체 메모"
                  type="text"
                  value={transferForm.memo}
                  onChange={(e) => setTransferForm((f) => ({ ...f, memo: e.target.value }))}
                />
              </Field>

              {transferValidationError && (
                <p role="alert" className="m-0 text-xs text-danger">{transferValidationError}</p>
              )}
              {transferMutation.isError && (
                <p role="alert" className="m-0 text-xs text-danger">
                  {serverErrorMessage(transferMutation.error, '이체 기록에 실패했습니다.')}
                </p>
              )}

              <Button type="submit" variant="primary" disabled={transferMutation.isPending || !api}>
                {transferMutation.isPending ? '기록 중…' : '이체 기록'}
              </Button>
            </form>
          </section>

          {/* 환전 */}
          <section className="border border-line-card bg-surface-muted p-5">
            <SectionHeader label="환전" />
            <form onSubmit={submitFx} className="space-y-3.5">
              <Field id="fx-from" label="출발 계좌">
                <Select
                  aria-label="환전 출발 계좌"
                  value={fxForm.accountId}
                  onChange={(e) => setFxForm((f) => ({ ...f, accountId: e.target.value }))}
                >
                  <option value="">선택하세요</option>
                  {accountOptions}
                </Select>
              </Field>
              <Field
                id="fx-to"
                label="도착 계좌 (선택)"
                hint="미지정 시 동일 계좌, 지정 시 계좌간 환전"
              >
                <Select
                  aria-label="환전 도착 계좌"
                  value={fxForm.toAccountId}
                  onChange={(e) => setFxForm((f) => ({ ...f, toAccountId: e.target.value }))}
                >
                  <option value="">동일 계좌</option>
                  {accountOptions}
                </Select>
              </Field>
              <Field id="fx-date" label="날짜">
                <Input
                  type="date"
                  required
                  aria-label="환전 날짜"
                  value={fxForm.flowDate}
                  onChange={(e) => setFxForm((f) => ({ ...f, flowDate: e.target.value }))}
                />
              </Field>
              <div className="flex gap-3">
                <Field id="fx-from-amount" label="From 금액" className="flex-1">
                  <Input
                    aria-label="환전 From 금액"
                    type="number"
                    step="0.01"
                    min="0"
                    value={fxForm.fromAmount}
                    onChange={(e) => setFxForm((f) => ({ ...f, fromAmount: e.target.value }))}
                  />
                </Field>
                <div className="w-28">
                  <label htmlFor="fx-from-currency" className="mb-1.5 block font-mono text-[10px] tracking-label text-fg-muted">
                    From 통화
                  </label>
                  <CurrencySelect
                    id="fx-from-currency"
                    ariaLabel="환전 From 통화"
                    allowEmpty
                    value={fxForm.fromCurrency}
                    onChange={(fromCurrency) => setFxForm((f) => ({ ...f, fromCurrency }))}
                  />
                </div>
              </div>
              <div className="flex gap-3">
                <Field id="fx-to-amount" label="To 금액" className="flex-1">
                  <Input
                    aria-label="환전 To 금액"
                    type="number"
                    step="0.01"
                    min="0"
                    value={fxForm.toAmount}
                    onChange={(e) => setFxForm((f) => ({ ...f, toAmount: e.target.value }))}
                  />
                </Field>
                <div className="w-28">
                  <label htmlFor="fx-to-currency" className="mb-1.5 block font-mono text-[10px] tracking-label text-fg-muted">
                    To 통화
                  </label>
                  <CurrencySelect
                    id="fx-to-currency"
                    ariaLabel="환전 To 통화"
                    allowEmpty
                    value={fxForm.toCurrency}
                    onChange={(toCurrency) => setFxForm((f) => ({ ...f, toCurrency }))}
                  />
                </div>
              </div>
              <Field id="fx-memo" label="메모">
                <Input
                  aria-label="환전 메모"
                  type="text"
                  value={fxForm.memo}
                  onChange={(e) => setFxForm((f) => ({ ...f, memo: e.target.value }))}
                />
              </Field>

              {fxValidationError && (
                <p role="alert" className="m-0 text-xs text-danger">{fxValidationError}</p>
              )}
              {fxMutation.isError && (
                <p role="alert" className="m-0 text-xs text-danger">
                  {serverErrorMessage(fxMutation.error, '환전 기록에 실패했습니다.')}
                </p>
              )}

              <Button type="submit" variant="primary" disabled={fxMutation.isPending || !api}>
                {fxMutation.isPending ? '기록 중…' : '환전 기록'}
              </Button>
            </form>
          </section>
        </div>

        {/* 최근 내부이동 */}
        <section className="mt-8">
          <SectionHeader label="최근 내부이동" note={internalFlows.length > 0 ? `${internalFlows.length}건` : undefined} />
          {accountsLoading || flowsLoading ? (
            <LoadingState label="내역 불러오는 중" />
          ) : internalFlows.length === 0 ? (
            <EmptyState
              title="기록된 이체·환전 내역이 없습니다"
              description="위 폼에서 계좌간이체 또는 환전을 기록하면 여기에 표시됩니다"
            />
          ) : (
            <div className="overflow-x-auto">
              <div className="min-w-[720px] border-t-[1.5px] border-ink">
                <div className={`${FLOW_GRID} border-b border-line py-2`}>
                  <Label size="sm" tone="faint">날짜</Label>
                  <Label size="sm" tone="faint">유형</Label>
                  <Label size="sm" tone="faint">계좌</Label>
                  <Label size="sm" tone="faint" className="text-right">금액</Label>
                  <Label size="sm" tone="faint" className="text-right">금액(KRW)</Label>
                  <Label size="sm" tone="faint">메모</Label>
                </div>
                {internalFlows.map((r: CashFlowItem) => (
                  <div key={r.id} className={`${FLOW_GRID} items-baseline border-b border-line-hair py-2.5 hover:bg-surface-muted`}>
                    <Num className="text-[11.5px] text-fg-3">{r.flowDate}</Num>
                    <span className="font-mono text-[10px] tracking-label text-fg-3">
                      {FLOW_TYPE_KO[r.flowType] ?? r.flowType}
                    </span>
                    <span className="truncate text-[13px] text-fg-2">{accountLabel(r.accountId)}</span>
                    <Num className="text-right text-[12.5px] text-fg-2">
                      {r.amount.toLocaleString()} {r.currency}
                    </Num>
                    <Num className="text-right text-[12.5px]">{fmtKrw(r.amountKrw)}</Num>
                    <span className="truncate text-xs text-fg-faint">{r.memo ?? '-'}</span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </section>

        <div className="mt-6">
          <Link href="/unified" className="text-[13px] text-link transition-colors hover:text-link-hover">
            ← 통합 자산으로
          </Link>
        </div>
      </div>
    </div>
  )
}
