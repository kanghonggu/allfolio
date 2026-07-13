'use client'

import { useState, useEffect } from 'react'
import { useRouter } from 'next/navigation'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useUnifiedApi } from '@/lib/useApi'
import type { AccountProvider, AccountType, CreateAccountPayload, ConnectionTestResult } from '@/types/unified'

// 암호화폐 거래소 목록
const EXCHANGES: { provider: AccountProvider; label: string; needsPassphrase?: boolean }[] = [
  { provider: 'UPBIT',   label: '업비트' },
  { provider: 'BITHUMB', label: '빗썸' },
  { provider: 'COINONE', label: '코인원' },
  { provider: 'BINANCE', label: '바이낸스' },
  { provider: 'BYBIT',   label: '바이빗' },
  { provider: 'OKX',     label: 'OKX', needsPassphrase: true },
]

const EXCHANGE_PROVIDERS = new Set(EXCHANGES.map(e => e.provider))

// 카테고리 (거래소 제외 나머지)
type Category = 'EXCHANGE' | 'STOCK' | 'WALLET' | 'MANUAL' | 'KIS_API'
const CATEGORIES: { key: Category; label: string; description: string }[] = [
  { key: 'EXCHANGE', label: '암호화폐 거래소', description: 'API로 잔고를 자동 조회합니다' },
  { key: 'KIS_API',  label: '증권사 API 연동', description: '한국투자증권 API로 잔고를 자동 조회합니다' },
  { key: 'STOCK',    label: '증권 계좌',       description: '거래내역을 로그로 관리합니다' },
  { key: 'WALLET',   label: '블록체인 지갑',   description: '지갑 주소로 잔고를 조회합니다' },
  { key: 'MANUAL',   label: '수동 입력',       description: '부동산, 금 등 비정형 자산' },
]

const CHAIN_OPTIONS = ['ETH', 'BSC', 'POLYGON', 'ARBITRUM', 'SOLANA', 'BTC']

const BROKERAGES = [
  '미래에셋증권', '삼성증권', '키움증권', 'NH투자증권', '토스증권',
  'KB증권', '신한투자증권', '한국투자증권', '대신증권', '이베스트투자증권',
  'SK증권', 'DB금융투자', '유안타증권', '하나증권', '메리츠증권', '기타',
]

const STOCK_SUBTYPES = [
  { value: '일반',   label: '일반 주식계좌' },
  { value: 'ISA',    label: 'ISA (개인종합자산관리계좌)' },
  { value: '연금저축', label: '연금저축계좌' },
  { value: 'IRP',    label: 'IRP (개인형 퇴직연금)' },
  { value: 'CMA',    label: 'CMA' },
]

export default function NewAccountPage() {
  const router = useRouter()
  const qc     = useQueryClient()
  const api    = useUnifiedApi()

  const [category, setCategory]   = useState<Category | null>(null)
  const [provider, setProvider]   = useState<AccountProvider | null>(null)
  const [form, setForm] = useState({
    accountName:   '',
    currency:      'USD',
    apiKey:        '',
    apiSecret:     '',
    passphrase:    '',   // OKX 전용 → chain 필드로 전송
    walletAddress: '',
    chain:         'ETH',
    brokerage:     '',
    subtype:       '일반',
    accountNo:     '',   // KIS 계좌번호 (예: 50123456-01)
  })
  const [testStatus, setTestStatus]   = useState<'idle' | 'loading' | 'success' | 'failed'>('idle')
  const [testResult, setTestResult]   = useState<ConnectionTestResult | null>(null)

  const mutation = useMutation({
    mutationFn: (payload: CreateAccountPayload) => api!.accounts.create(payload),
    onSuccess: (account) => {
      qc.invalidateQueries({ queryKey: ['unified', 'accounts'] })
        router.push('/unified/accounts')
    },
  })

  const set = (key: string, value: string) => {
    setForm(prev => ({ ...prev, [key]: value }))
    // API Key/Secret 변경 시 테스트 상태 초기화
    if (key === 'apiKey' || key === 'apiSecret' || key === 'passphrase') {
      setTestStatus('idle')
      setTestResult(null)
    }
  }

  const selectCategory = (cat: Category) => {
    setCategory(cat)
    if (cat === 'EXCHANGE') setProvider(null)
    else if (cat === 'KIS_API') setProvider('KIS')
    else setProvider(cat as unknown as AccountProvider)
    setTestStatus('idle')
    setTestResult(null)
  }

  const handleTestConnection = async () => {
    if (!provider || !api) return
    setTestStatus('loading')
    setTestResult(null)
    try {
      const result = await api.accounts.testConnection({
        provider,
        apiKey:     form.apiKey,
        apiSecret:  form.apiSecret,
        passphrase: provider === 'OKX' ? form.passphrase : undefined,
      })
      setTestResult(result)
      setTestStatus(result.success ? 'success' : 'failed')
    } catch (e) {
      setTestResult({ success: false, message: (e as Error).message, assetCount: 0 })
      setTestStatus('failed')
    }
  }

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!provider) return

    let accountName = form.accountName
    let accountType: AccountType = 'EXCHANGE'

    const payload: CreateAccountPayload = { accountName, provider, accountType, currency: 'USD' }

    if (EXCHANGE_PROVIDERS.has(provider)) {
      payload.accountType = 'EXCHANGE'
      payload.currency    = ['UPBIT', 'BITHUMB', 'COINONE'].includes(provider) ? 'KRW' : 'USD'
      payload.apiKey      = form.apiKey
      payload.apiSecret   = form.apiSecret
      if (provider === 'OKX') payload.chain = form.passphrase
    } else if (provider === 'KIS') {
      const digits = form.accountNo.replace(/[^0-9]/g, '')
      const cano = digits.slice(0, 8)
      const prdt = digits.slice(8, 10) || '01'
      accountName = form.accountName || `한국투자증권 ${cano}`
      payload.accountName = accountName
      payload.accountType = 'STOCK'
      payload.currency    = 'KRW'
      payload.apiKey      = form.apiKey
      payload.apiSecret   = form.apiSecret
      payload.externalId  = `${cano}_${prdt}`
    } else if (provider === 'STOCK') {
      accountName = form.accountName || `${form.brokerage} ${form.subtype}`
      payload.accountName  = accountName
      payload.accountType  = 'STOCK'
      payload.currency     = 'KRW'
      payload.externalId   = form.brokerage
    } else if (provider === 'WALLET') {
      payload.accountType   = 'WALLET'
      payload.currency      = form.currency
      payload.walletAddress = form.walletAddress
      payload.chain         = form.chain
    } else if (provider === 'MANUAL') {
      payload.accountType = 'MANUAL'
      payload.currency    = form.currency
    }

    mutation.mutate(payload)
  }

  const exchangeInfo = provider ? EXCHANGES.find(e => e.provider === provider) : null

  const kisDigits = form.accountNo.replace(/[^0-9]/g, '')
  const kisAccountValid = kisDigits.length === 8 || kisDigits.length === 10

  return (
    <div className="mx-auto max-w-xl space-y-6">
      <div>
        <h1 className="text-2xl font-bold">계좌 추가</h1>
        <p className="mt-1 text-sm text-gray-400">자산 수집 방식을 선택하세요</p>
      </div>

      {/* 카테고리 선택 */}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
        {CATEGORIES.map(cat => (
          <button
            key={cat.key}
            type="button"
            onClick={() => selectCategory(cat.key)}
            className={`rounded-xl border p-4 text-left transition-all ${
              category === cat.key
                ? 'border-blue-500 bg-blue-600/10'
                : 'border-gray-700 bg-gray-900 hover:border-gray-500'
            }`}
          >
            <p className="font-medium text-sm">{cat.label}</p>
            <p className="mt-1 text-xs text-gray-500 leading-snug">{cat.description}</p>
          </button>
        ))}
      </div>

      {/* 거래소 선택 (암호화폐 거래소 카테고리 선택 시) */}
      {category === 'EXCHANGE' && (
        <div>
          <p className="mb-2 text-xs font-medium text-gray-400">거래소 선택</p>
          <div className="flex flex-wrap gap-2">
            {EXCHANGES.map(ex => (
              <button
                key={ex.provider}
                type="button"
                onClick={() => setProvider(ex.provider)}
                className={`rounded-lg border px-4 py-2 text-sm font-medium transition-colors ${
                  provider === ex.provider
                    ? 'border-blue-500 bg-blue-600/20 text-blue-300'
                    : 'border-gray-700 text-gray-400 hover:border-gray-500'
                }`}
              >
                {ex.label}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* 폼 */}
      {provider && (
        <form onSubmit={handleSubmit} className="space-y-4 rounded-xl border border-gray-700 bg-gray-900 p-6">
          <div>
            <p className="text-sm font-medium text-blue-400">
              {category === 'EXCHANGE' ? `${exchangeInfo?.label} 연동` :
               category === 'KIS_API'  ? '한국투자증권 API 연동' :
               category === 'STOCK'    ? '증권 계좌' :
               category === 'WALLET'   ? '블록체인 지갑' : '수동 입력'}
            </p>
          </div>

          <div className="space-y-3">

            {/* ── 거래소 공통 필드 ── */}
            {category === 'EXCHANGE' && (
              <>
                <Field label="계좌명 *" required>
                  <input required type="text"
                    placeholder={`예: 내 ${exchangeInfo?.label} 계좌`}
                    value={form.accountName}
                    onChange={e => set('accountName', e.target.value)}
                    className={inputCls}
                  />
                </Field>
                <Field label="API Key *" required>
                  <input required type="password" placeholder="API Key"
                    value={form.apiKey} onChange={e => set('apiKey', e.target.value)}
                    className={inputCls}
                  />
                </Field>
                <Field label="API Secret *" required>
                  <input required type="password" placeholder="API Secret"
                    value={form.apiSecret} onChange={e => set('apiSecret', e.target.value)}
                    className={inputCls}
                  />
                </Field>
                {provider === 'OKX' && (
                  <Field label="API Passphrase *" required>
                    <input required type="password" placeholder="OKX API Passphrase"
                      value={form.passphrase} onChange={e => set('passphrase', e.target.value)}
                      className={inputCls}
                    />
                  </Field>
                )}
                <div className="rounded-lg border border-gray-700 bg-gray-800/50 px-3 py-2 text-xs text-gray-400">
                  읽기 전용(Read Only) 권한의 API 키만 필요합니다. 출금 권한은 부여하지 마세요.
                </div>

                {/* 연결 테스트 */}
                <div className="space-y-2">
                  <button
                    type="button"
                    onClick={handleTestConnection}
                    disabled={testStatus === 'loading' || !form.apiKey || !form.apiSecret || (provider === 'OKX' && !form.passphrase)}
                    className="w-full rounded-lg border border-blue-700 py-2 text-sm font-medium text-blue-400 hover:bg-blue-950/30 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                  >
                    {testStatus === 'loading' ? (
                      <span className="flex items-center justify-center gap-2">
                        <span className="animate-spin">⟳</span> 연결 테스트 중…
                      </span>
                    ) : '🔌 연결 테스트'}
                  </button>

                  {testResult && (
                    <div className={`rounded-lg border px-3 py-2.5 text-sm ${
                      testResult.success
                        ? 'border-emerald-800 bg-emerald-950/30 text-emerald-400'
                        : 'border-red-800 bg-red-950/30 text-red-400'
                    }`}>
                      {testResult.success ? '✓ ' : '✗ '}{testResult.message}
                    </div>
                  )}
                </div>
              </>
            )}

            {/* ── 증권사 API 연동 (KIS) ── */}
            {provider === 'KIS' && (
              <>
                <Field label="별칭 (선택)">
                  <input type="text" placeholder="예: 한투 주식계좌"
                    value={form.accountName} onChange={e => set('accountName', e.target.value)}
                    className={inputCls}
                  />
                </Field>
                <Field label="앱키 (App Key) *" required>
                  <input required type="password" placeholder="한국투자증권 App Key"
                    value={form.apiKey} onChange={e => set('apiKey', e.target.value)}
                    className={inputCls}
                  />
                </Field>
                <Field label="앱시크릿 (App Secret) *" required>
                  <input required type="password" placeholder="한국투자증권 App Secret"
                    value={form.apiSecret} onChange={e => set('apiSecret', e.target.value)}
                    className={inputCls}
                  />
                </Field>
                <Field label="계좌번호 *" required>
                  <input required type="text" inputMode="numeric" placeholder="예: 5012345601"
                    value={form.accountNo}
                    onChange={e => set('accountNo', e.target.value.replace(/[^0-9]/g, '').slice(0, 10))}
                    className={inputCls}
                  />
                  <p className="mt-1 text-xs text-gray-500">숫자만 입력 (앞 8자리 계좌번호 + 상품코드 2자리, 하이픈 자동 제거)</p>
                  <p className="mt-1 text-xs text-gray-500">연결 테스트는 앱키/앱시크릿만 검증합니다. 계좌번호는 확인되지 않으니 정확히 입력하세요.</p>
                  {form.accountNo && !kisAccountValid && (
                    <p className="mt-1 text-xs text-red-400">계좌번호는 숫자 8자리(+상품코드 2자리)여야 합니다.</p>
                  )}
                </Field>
                <div className="rounded-lg border border-gray-700 bg-gray-800/50 px-3 py-2 text-xs text-gray-400">
                  KIS Developers에서 발급한 App Key/Secret이 필요합니다. 조회 전용 권한을 권장합니다.
                </div>

                <div className="space-y-2">
                  <button
                    type="button"
                    onClick={handleTestConnection}
                    disabled={testStatus === 'loading' || !form.apiKey || !form.apiSecret}
                    className="w-full rounded-lg border border-blue-700 py-2 text-sm font-medium text-blue-400 hover:bg-blue-950/30 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                  >
                    {testStatus === 'loading' ? (
                      <span className="flex items-center justify-center gap-2">
                        <span className="animate-spin">⟳</span> 연결 테스트 중…
                      </span>
                    ) : '🔌 연결 테스트'}
                  </button>
                  {testResult && (
                    <div className={`rounded-lg border px-3 py-2.5 text-sm ${
                      testResult.success
                        ? 'border-emerald-800 bg-emerald-950/30 text-emerald-400'
                        : 'border-red-800 bg-red-950/30 text-red-400'
                    }`}>
                      {testResult.success ? '✓ ' : '✗ '}{testResult.message}
                    </div>
                  )}
                </div>
              </>
            )}

            {/* ── 증권 계좌 ── */}
            {provider === 'STOCK' && (
              <>
                <Field label="증권사 *" required>
                  <select required value={form.brokerage}
                    onChange={e => set('brokerage', e.target.value)} className={inputCls}>
                    <option value="">증권사를 선택하세요</option>
                    {BROKERAGES.map(b => <option key={b} value={b}>{b}</option>)}
                  </select>
                </Field>
                <Field label="계좌 유형">
                  <select value={form.subtype}
                    onChange={e => set('subtype', e.target.value)} className={inputCls}>
                    {STOCK_SUBTYPES.map(s => (
                      <option key={s.value} value={s.value}>{s.label}</option>
                    ))}
                  </select>
                </Field>
                <Field label="별칭 (선택)">
                  <input type="text"
                    placeholder={`예: ${form.brokerage || '키움'} ${form.subtype} 계좌`}
                    value={form.accountName}
                    onChange={e => set('accountName', e.target.value)}
                    className={inputCls}
                  />
                  <p className="mt-1 text-xs text-gray-500">
                    비워두면 "{form.brokerage || '증권사'} {form.subtype}"으로 자동 설정됩니다.
                  </p>
                </Field>
                <div className="rounded-lg border border-blue-900 bg-blue-950/30 p-3 text-xs text-blue-300">
                  계좌 생성 후 거래내역(매수/매도/신용/미수/배당)을 로그로 입력할 수 있습니다.
                </div>
              </>
            )}

            {/* ── 블록체인 지갑 ── */}
            {provider === 'WALLET' && (
              <>
                <Field label="계좌명 *" required>
                  <input required type="text" placeholder="예: 내 ETH 지갑"
                    value={form.accountName} onChange={e => set('accountName', e.target.value)}
                    className={inputCls}
                  />
                </Field>
                <Field label="지갑 주소 *" required>
                  <input required type="text" placeholder="0x..."
                    value={form.walletAddress} onChange={e => set('walletAddress', e.target.value)}
                    className={inputCls}
                  />
                </Field>
                <Field label="체인">
                  <select value={form.chain} onChange={e => set('chain', e.target.value)} className={inputCls}>
                    {CHAIN_OPTIONS.map(c => <option key={c} value={c}>{c}</option>)}
                  </select>
                </Field>
              </>
            )}

            {/* ── 수동 입력 ── */}
            {provider === 'MANUAL' && (
              <>
                <Field label="계좌명 *" required>
                  <input required type="text"
                    placeholder="예: 부동산 / 실물자산"
                    value={form.accountName} onChange={e => set('accountName', e.target.value)}
                    className={inputCls}
                  />
                </Field>
                <Field label="기준 통화">
                  <select value={form.currency} onChange={e => set('currency', e.target.value)} className={inputCls}>
                    <option value="KRW">KRW</option>
                    <option value="USD">USD</option>
                  </select>
                </Field>
              </>
            )}
          </div>

          {mutation.isError && (
            <div className="rounded-lg border border-red-800 bg-red-950 p-3 text-xs text-red-400">
              오류: {(mutation.error as Error).message}
            </div>
          )}

          <div className="flex gap-3 pt-2">
            <button type="submit"
              disabled={
                mutation.isPending || !api ||
                ((category === 'EXCHANGE' || provider === 'KIS') && testStatus !== 'success') ||
                (provider === 'KIS' && !kisAccountValid)
              }
              className="flex-1 rounded-lg bg-blue-600 py-2.5 text-sm font-medium hover:bg-blue-500 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              {mutation.isPending ? '생성 중…' :
               (category === 'EXCHANGE' || provider === 'KIS') && testStatus !== 'success'
                 ? '연결 테스트 후 추가 가능' : '계좌 추가'}
            </button>
            <button type="button" onClick={() => router.back()}
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

function Field({ label, required, children }: { label: string; required?: boolean; children: React.ReactNode }) {
  return (
    <div>
      <label className="mb-1 block text-xs font-medium text-gray-400">
        {label}{required && <span className="text-red-400 ml-0.5">*</span>}
      </label>
      {children}
    </div>
  )
}
