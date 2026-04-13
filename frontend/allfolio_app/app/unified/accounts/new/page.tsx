'use client'

import { useState } from 'react'
import { useRouter } from 'next/navigation'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useUnifiedApi } from '@/lib/useApi'
import type { AccountProvider, AccountType, CreateAccountPayload } from '@/types/unified'

type ProviderConfig = {
  label:       string
  accountType: AccountType
  description: string
  fields:      ('apiKey' | 'apiSecret' | 'walletAddress' | 'chain' | 'brokerage')[]
}

const PROVIDERS: Record<AccountProvider, ProviderConfig> = {
  BINANCE: {
    label:       'Binance',
    accountType: 'EXCHANGE',
    description: 'Binance 거래소 API로 코인 잔고를 자동 조회합니다.',
    fields:      ['apiKey', 'apiSecret'],
  },
  STOCK: {
    label:       '증권 계좌',
    accountType: 'STOCK',
    description: '국내 증권사 계좌를 등록하고 거래내역을 로그로 관리합니다.',
    fields:      ['brokerage'],
  },
  WALLET: {
    label:       '블록체인 지갑',
    accountType: 'WALLET',
    description: '지갑 주소를 입력하면 토큰 잔고를 자동 조회합니다.',
    fields:      ['walletAddress', 'chain'],
  },
  CSV: {
    label:       'CSV 업로드',
    accountType: 'STOCK',
    description: '자산 목록 CSV를 업로드해 보유 자산을 등록합니다.',
    fields:      [],
  },
  MANUAL: {
    label:       '수동 입력',
    accountType: 'MANUAL',
    description: '부동산, 자동차, 금 등 비정형 자산을 직접 입력합니다.',
    fields:      [],
  },
}

const CHAIN_OPTIONS = ['ETH', 'BSC', 'POLYGON', 'ARBITRUM', 'SOLANA', 'BTC']

const BROKERAGES = [
  '미래에셋증권', '삼성증권', '키움증권', 'NH투자증권', '토스증권',
  'KB증권', '신한투자증권', '한국투자증권', '대신증권', '이베스트투자증권',
  'SK증권', 'DB금융투자', '유안타증권', '하나증권', '메리츠증권', '기타',
]

const STOCK_ACCOUNT_SUBTYPES = [
  { value: '일반', label: '일반 주식계좌' },
  { value: 'ISA', label: 'ISA (개인종합자산관리계좌)' },
  { value: '연금저축', label: '연금저축계좌' },
  { value: 'IRP', label: 'IRP (개인형 퇴직연금)' },
  { value: 'CMA', label: 'CMA' },
]

export default function NewAccountPage() {
  const router = useRouter()
  const qc = useQueryClient()
  const api = useUnifiedApi()

  const [selected, setSelected] = useState<AccountProvider | null>(null)
  const [form, setForm] = useState({
    accountName:   '',
    currency:      'USD',
    apiKey:        '',
    apiSecret:     '',
    walletAddress: '',
    chain:         'ETH',
    brokerage:     '',
    subtype:       '일반',
  })

  const mutation = useMutation({
    mutationFn: (payload: CreateAccountPayload) => api!.accounts.create(payload),
    onSuccess: (account) => {
      qc.invalidateQueries({ queryKey: ['unified', 'accounts'] })
      if (selected === 'CSV') {
        router.push(`/unified/accounts/${account.id}/csv`)
      } else {
        router.push('/unified/accounts')
      }
    },
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!selected) return

    const config = PROVIDERS[selected]

    // 증권 계좌: 계좌명을 "증권사 + 유형 + 사용자 입력명" 조합으로 구성
    let accountName = form.accountName
    if (selected === 'STOCK' && form.brokerage) {
      accountName = form.accountName || `${form.brokerage} ${form.subtype}`
    }

    const payload: CreateAccountPayload = {
      accountName,
      provider:    selected,
      accountType: config.accountType,
      currency:    selected === 'STOCK' ? 'KRW' : form.currency,
      ...(config.fields.includes('apiKey')        && { apiKey:        form.apiKey }),
      ...(config.fields.includes('apiSecret')     && { apiSecret:     form.apiSecret }),
      ...(config.fields.includes('walletAddress') && { walletAddress: form.walletAddress }),
      ...(config.fields.includes('chain')         && { chain:         form.chain }),
      ...(config.fields.includes('brokerage')     && { externalId:    form.brokerage }),
    }
    mutation.mutate(payload)
  }

  const set = (key: string, value: string) =>
    setForm(prev => ({ ...prev, [key]: value }))

  return (
    <div className="mx-auto max-w-xl space-y-6">
      <div>
        <h1 className="text-2xl font-bold">계좌 추가</h1>
        <p className="mt-1 text-sm text-gray-400">자산 수집 방식을 선택하세요</p>
      </div>

      {/* Provider Selection */}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
        {(Object.entries(PROVIDERS) as [AccountProvider, ProviderConfig][]).map(([key, cfg]) => (
          <button
            key={key}
            onClick={() => setSelected(key)}
            className={`rounded-xl border p-4 text-left transition-all ${
              selected === key
                ? 'border-blue-500 bg-blue-600/10'
                : 'border-gray-700 bg-gray-900 hover:border-gray-500'
            }`}
          >
            <p className="font-medium text-sm">{cfg.label}</p>
            <p className="mt-1 text-xs text-gray-500 leading-snug">{cfg.description.slice(0, 28)}…</p>
          </button>
        ))}
      </div>

      {/* Form */}
      {selected && (
        <form onSubmit={handleSubmit} className="space-y-4 rounded-xl border border-gray-700 bg-gray-900 p-6">
          <div>
            <p className="text-sm font-medium text-blue-400">{PROVIDERS[selected].label}</p>
            <p className="mt-0.5 text-xs text-gray-400">{PROVIDERS[selected].description}</p>
          </div>

          <div className="space-y-3">
            {/* 증권 계좌 전용 필드 */}
            {selected === 'STOCK' && (
              <>
                <Field label="증권사 *" required>
                  <select
                    required
                    value={form.brokerage}
                    onChange={e => set('brokerage', e.target.value)}
                    className={inputCls}
                  >
                    <option value="">증권사를 선택하세요</option>
                    {BROKERAGES.map(b => <option key={b} value={b}>{b}</option>)}
                  </select>
                </Field>

                <Field label="계좌 유형">
                  <select value={form.subtype} onChange={e => set('subtype', e.target.value)} className={inputCls}>
                    {STOCK_ACCOUNT_SUBTYPES.map(s => (
                      <option key={s.value} value={s.value}>{s.label}</option>
                    ))}
                  </select>
                </Field>

                <Field label="별칭 (선택)">
                  <input
                    type="text"
                    placeholder={`예: ${form.brokerage || '키움'} ${form.subtype} 계좌`}
                    value={form.accountName}
                    onChange={e => set('accountName', e.target.value)}
                    className={inputCls}
                  />
                  <p className="mt-1 text-xs text-gray-500">
                    비워두면 "{form.brokerage || '증권사'} {form.subtype}"으로 자동 설정됩니다.
                  </p>
                </Field>
              </>
            )}

            {/* 일반 계좌명 (STOCK 제외) */}
            {selected !== 'STOCK' && (
              <Field label="계좌명 *" required>
                <input
                  required
                  type="text"
                  placeholder="예: 내 Binance 계좌"
                  value={form.accountName}
                  onChange={e => set('accountName', e.target.value)}
                  className={inputCls}
                />
              </Field>
            )}

            {/* 통화 (STOCK은 KRW 고정) */}
            {selected !== 'STOCK' && (
              <Field label="기준 통화">
                <select value={form.currency} onChange={e => set('currency', e.target.value)} className={inputCls}>
                  <option value="USD">USD</option>
                  <option value="KRW">KRW</option>
                  <option value="EUR">EUR</option>
                </select>
              </Field>
            )}

            {PROVIDERS[selected].fields.includes('apiKey') && (
              <Field label="API Key *" required>
                <input
                  required type="password" placeholder="Binance API Key"
                  value={form.apiKey} onChange={e => set('apiKey', e.target.value)}
                  className={inputCls}
                />
              </Field>
            )}

            {PROVIDERS[selected].fields.includes('apiSecret') && (
              <Field label="API Secret *" required>
                <input
                  required type="password" placeholder="Binance API Secret"
                  value={form.apiSecret} onChange={e => set('apiSecret', e.target.value)}
                  className={inputCls}
                />
                <p className="mt-1 text-xs text-gray-500">읽기 전용 권한만 필요합니다.</p>
              </Field>
            )}

            {PROVIDERS[selected].fields.includes('walletAddress') && (
              <Field label="지갑 주소 *" required>
                <input
                  required type="text" placeholder="0x..."
                  value={form.walletAddress} onChange={e => set('walletAddress', e.target.value)}
                  className={inputCls}
                />
              </Field>
            )}

            {PROVIDERS[selected].fields.includes('chain') && (
              <Field label="체인">
                <select value={form.chain} onChange={e => set('chain', e.target.value)} className={inputCls}>
                  {CHAIN_OPTIONS.map(c => <option key={c} value={c}>{c}</option>)}
                </select>
              </Field>
            )}
          </div>

          {/* 증권 계좌 안내 */}
          {selected === 'STOCK' && (
            <div className="rounded-lg border border-blue-900 bg-blue-950/30 p-3 text-xs text-blue-300">
              계좌 생성 후 거래내역(매수/매도/신용/미수/배당)을 로그로 입력할 수 있습니다.
            </div>
          )}

          {mutation.isError && (
            <div className="rounded-lg border border-red-800 bg-red-950 p-3 text-xs text-red-400">
              오류: {(mutation.error as Error).message}
            </div>
          )}

          <div className="flex gap-3 pt-2">
            <button
              type="submit"
              disabled={mutation.isPending || !api}
              className="flex-1 rounded-lg bg-blue-600 py-2.5 text-sm font-medium hover:bg-blue-500 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              {mutation.isPending ? '생성 중…' : '계좌 추가'}
            </button>
            <button
              type="button"
              onClick={() => router.back()}
              className="rounded-lg border border-gray-600 px-4 py-2.5 text-sm hover:border-gray-400 transition-colors"
            >
              취소
            </button>
          </div>
        </form>
      )}
    </div>
  )
}

const inputCls = 'w-full rounded-lg border border-gray-700 bg-gray-800 px-3 py-2 text-sm text-white placeholder-gray-500 focus:border-blue-500 focus:outline-none transition-colors'

function Field({
  label, required, children,
}: { label: string; required?: boolean; children: React.ReactNode }) {
  return (
    <div>
      <label className="mb-1 block text-xs font-medium text-gray-400">
        {label}{required && <span className="text-red-400 ml-0.5">*</span>}
      </label>
      {children}
    </div>
  )
}
