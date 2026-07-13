'use client'

import { useState } from 'react'
import { useParams, useRouter } from 'next/navigation'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import Link from 'next/link'
import { useUnifiedApi } from '@/lib/useApi'
import { isSyncable } from '@/lib/providers'
import type { Asset, AssetType, SyncResult, CreateManualAssetPayload } from '@/types/unified'

// ── 상수 ──────────────────────────────────────────────────────

const ASSET_TYPES: AssetType[] = ['REAL_ESTATE', 'VEHICLE', 'GOLD', 'STOCK', 'CASH', 'ETC']

const TYPE_KO: Record<string, string> = {
  STOCK: '주식', CRYPTO: '암호화폐', REAL_ESTATE: '부동산',
  VEHICLE: '자동차', GOLD: '금', CASH: '현금', ETC: '기타',
}

const SUB_TYPES: Record<string, { value: string; label: string; loanLabel: string }[]> = {
  REAL_ESTATE: [
    { value: 'OWN',     label: '소유',   loanLabel: '담보대출 잔액' },
    { value: 'JEONSE',  label: '전세',   loanLabel: '전세자금대출 잔액' },
    { value: 'MONTHLY', label: '월세',   loanLabel: '보증금 대출 잔액' },
    { value: 'PRESALE', label: '분양권', loanLabel: '중도금 대출 잔액' },
  ],
  VEHICLE: [
    { value: 'OWN',    label: '소유', loanLabel: '오토론 잔액' },
    { value: 'LEASE',  label: '리스', loanLabel: '리스 잔여금액' },
    { value: 'RENTAL', label: '렌트', loanLabel: '' },
  ],
}

const SUB_TYPE_KO: Record<string, string> = {
  OWN: '소유', JEONSE: '전세', MONTHLY: '월세', PRESALE: '분양권',
  LEASE: '리스', RENTAL: '렌트',
}

type FieldConfig = {
  namePlaceholder: string
  symbolLabel?: string
  symbolPlaceholder?: string
  quantityLabel?: string
  quantityPlaceholder?: string
  showSymbol: boolean
  showQuantity: boolean
  purchasePriceLabel: string
  currentValueLabel: string
  memoLabel: string
  memoPlaceholder: string
  hint?: string
}

const TYPE_CONFIG: Record<string, FieldConfig> = {
  REAL_ESTATE: {
    namePlaceholder: '예: 강남구 역삼동 아파트 101호',
    symbolLabel: '주소',
    symbolPlaceholder: '예: 서울시 강남구 역삼동 123-45',
    showSymbol: true,
    showQuantity: false,
    purchasePriceLabel: '취득가 (원)',
    currentValueLabel: '현재 시세 (원)',
    memoLabel: '상세 설명',
    memoPlaceholder: '예: 아파트 84㎡, 10층, 2020년 취득',
    hint: '취득가는 부동산 총액을 입력하세요.',
  },
  VEHICLE: {
    namePlaceholder: '예: 현대 팰리세이드',
    symbolLabel: '연식 / 모델',
    symbolPlaceholder: '예: 2023년형 Calligraphy',
    quantityLabel: '대수',
    quantityPlaceholder: '1',
    showSymbol: true,
    showQuantity: false,
    purchasePriceLabel: '매입가 (원)',
    currentValueLabel: '현재 중고 시세 (원)',
    memoLabel: '상세 설명',
    memoPlaceholder: '예: 흰색, 주행거리 15,000km, 무사고',
    hint: '현재 시세는 중고차 시세 기준으로 입력하세요.',
  },
  GOLD: {
    namePlaceholder: '예: 순금 24K 골드바',
    symbolLabel: '단위',
    symbolPlaceholder: 'g / 돈 / oz',
    quantityLabel: '중량',
    quantityPlaceholder: '예: 37.5',
    showSymbol: true,
    showQuantity: true,
    purchasePriceLabel: '매입 단가 (단위당)',
    currentValueLabel: '현재 총 가치',
    memoLabel: '상세 설명',
    memoPlaceholder: '예: KEB하나은행 구매, 골드바 보관함 보관',
    hint: '중량 × 현재 금 시세로 현재 총 가치를 계산하세요.',
  },
  STOCK: {
    namePlaceholder: '예: (주)스타트업코리아',
    symbolLabel: '회사명 / 종류',
    symbolPlaceholder: '예: 보통주, 우선주, 스톡옵션',
    quantityLabel: '수량 (주)',
    quantityPlaceholder: '예: 1000',
    showSymbol: true,
    showQuantity: true,
    purchasePriceLabel: '평균 매입단가 (주당)',
    currentValueLabel: '현재 총 가치',
    memoLabel: '상세 설명',
    memoPlaceholder: '예: 시리즈A 투자, 우리사주, 스톡옵션 행사가 등',
    hint: '비상장주식, 스톡옵션, 우리사주 등 장외 주식을 입력하세요.',
  },
  CASH: {
    namePlaceholder: '예: 비상금 통장',
    showSymbol: false,
    showQuantity: false,
    purchasePriceLabel: '입금 금액',
    currentValueLabel: '현재 금액',
    memoLabel: '보관 위치',
    memoPlaceholder: '예: 카카오뱅크 파킹통장',
    hint: '입금 금액과 현재 금액을 동일하게 입력하세요.',
  },
  ETC: {
    namePlaceholder: '예: 개인 채권, 미술품, 특허권',
    symbolLabel: '종류/분류',
    symbolPlaceholder: '예: 채권, 미술품',
    showSymbol: true,
    showQuantity: true,
    quantityLabel: '수량',
    quantityPlaceholder: '1',
    purchasePriceLabel: '매입가',
    currentValueLabel: '현재 가치',
    memoLabel: '상세 설명',
    memoPlaceholder: '자산에 대한 상세 설명을 입력하세요.',
  },
}

// ── 초기값 생성 ───────────────────────────────────────────────

function defaultForm(type: AssetType = 'REAL_ESTATE'): CreateManualAssetPayload {
  return {
    name: '',
    type,
    subType: SUB_TYPES[type]?.[0]?.value,
    symbol: '',
    quantity: 1,
    areaPyeong: null,
    purchasePrice: 0,
    currentValue: 0,
    loanAmount: null,
    currency: 'KRW',
    memo: '',
  }
}

const AREA_TYPES: AssetType[] = ['REAL_ESTATE']

// ── 유틸 ──────────────────────────────────────────────────────

function fmt(n: number, currency = 'KRW') {
  return new Intl.NumberFormat('ko-KR', {
    style: 'currency', currency, maximumFractionDigits: 0,
  }).format(n)
}

// 숫자만 추출
function digitsOnly(s: string) {
  return s.replace(/[^\d]/g, '')
}

// 정수에 콤마 포맷
function fmtComma(n: number | null) {
  if (n == null || n === 0) return ''
  return Math.round(n).toLocaleString('ko-KR')
}

// ── 콤마 입력 컴포넌트 ─────────────────────────────────────────

function MoneyInput({
  value, onChange, placeholder, required,
}: {
  value: number | null
  onChange: (v: number | null) => void
  placeholder?: string
  required?: boolean
}) {
  return (
    <input
      type="text"
      inputMode="numeric"
      required={required}
      placeholder={placeholder ?? '0'}
      value={fmtComma(value)}
      onChange={e => {
        const d = digitsOnly(e.target.value)
        onChange(d ? parseInt(d, 10) : null)
      }}
      className={inputCls}
    />
  )
}

// 소수점 허용 (수량용)
function DecimalInput({
  value, onChange, placeholder, required,
}: {
  value: number
  onChange: (v: number) => void
  placeholder?: string
  required?: boolean
}) {
  const [raw, setRaw] = useState(value > 0 ? String(value) : '')
  return (
    <input
      type="text"
      inputMode="decimal"
      required={required}
      placeholder={placeholder ?? '0'}
      value={raw}
      onChange={e => {
        const s = e.target.value.replace(/[^\d.]/g, '')
        setRaw(s)
        const n = parseFloat(s)
        if (!isNaN(n)) onChange(n)
      }}
      className={inputCls}
    />
  )
}

// ── 메인 페이지 ───────────────────────────────────────────────

export default function AccountDetailPage() {
  const { id } = useParams<{ id: string }>()
  const qc  = useQueryClient()
  const api = useUnifiedApi()

  const [syncing, setSyncing]         = useState(false)
  const [syncResult, setSyncResult]   = useState<SyncResult | null>(null)
  const [showAddForm, setShowAddForm] = useState(false)
  const [assetForm, setAssetForm]     = useState<CreateManualAssetPayload>(defaultForm())

  const { data: accounts = [] } = useQuery({
    queryKey: ['unified', 'accounts'],
    queryFn:  () => api!.accounts.list(),
    enabled:  !!api,
  })
  const account = accounts.find(a => a.id === id)

  const { data: assets = [], isLoading: assetsLoading } = useQuery({
    queryKey: ['unified', 'account-assets', id],
    queryFn:  () => api!.accounts.getAssets(id),
    enabled:  !!api,
  })

  const addAssetMutation = useMutation({
    mutationFn: (payload: CreateManualAssetPayload) => api!.accounts.addManualAsset(id, payload),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['unified', 'account-assets', id] })
      qc.invalidateQueries({ queryKey: ['unified', 'portfolio'] })
      setShowAddForm(false)
      setAssetForm(defaultForm())
    },
  })

  // 유형 탭 전환 — 모든 필드 초기화
  const switchType = (type: AssetType) => {
    setAssetForm(defaultForm(type))
    addAssetMutation.reset()
  }

  const set = <K extends keyof CreateManualAssetPayload>(k: K, v: CreateManualAssetPayload[K]) =>
    setAssetForm(prev => ({ ...prev, [k]: v }))

  const handleSync = async () => {
    setSyncing(true)
    setSyncResult(null)
    try {
      const result = await api!.accounts.sync(id)
      setSyncResult(result)
      qc.invalidateQueries({ queryKey: ['unified', 'account-assets', id] })
      qc.invalidateQueries({ queryKey: ['unified', 'portfolio'] })
    } finally {
      setSyncing(false)
    }
  }

  const isAreaType = AREA_TYPES.includes(assetForm.type)

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    addAssetMutation.mutate({
      ...assetForm,
      quantity:      isAreaType ? 1 : assetForm.quantity,
      areaPyeong:    isAreaType ? (assetForm.areaPyeong ?? undefined) : undefined,
      purchasePrice: assetForm.purchasePrice ?? 0,
      currentValue:  assetForm.currentValue  ?? 0,
      loanAmount:    assetForm.loanAmount ?? undefined,
      symbol: assetForm.symbol || undefined,
      memo:   assetForm.memo   || undefined,
    })
  }

  const cfg      = TYPE_CONFIG[assetForm.type] ?? TYPE_CONFIG['ETC']
  const subInfo  = SUB_TYPES[assetForm.type]?.find(s => s.value === assetForm.subType)
  const currency = assetForm.currency ?? 'KRW'

  // Axios 에러 메시지 추출
  const errMsg = addAssetMutation.error
    ? ((addAssetMutation.error as any)?.response?.data?.message
        ?? (addAssetMutation.error as any)?.response?.data
        ?? (addAssetMutation.error as Error).message)
    : null

  if (!account && accounts.length > 0) {
    return <div className="text-gray-400">계좌를 찾을 수 없습니다.</div>
  }

  return (
    <div className="space-y-6">

      {/* Header */}
      <div className="flex items-start justify-between gap-4">
        <div>
          <Link href="/unified/accounts" className="mb-2 inline-block text-xs text-gray-500 hover:text-gray-300">
            ← 계좌 목록
          </Link>
          <h1 className="text-2xl font-bold">{account?.accountName ?? '계좌 상세'}</h1>
          <p className="mt-1 text-sm text-gray-400">
            {account?.provider} · {account?.accountType} · {account?.currency}
          </p>
          {account?.lastSyncedAt && (
            <p className="mt-0.5 text-xs text-gray-500">
              마지막 동기화: {new Date(account.lastSyncedAt).toLocaleString('ko-KR')}
            </p>
          )}
        </div>

        <div className="flex shrink-0 gap-2">
          {account?.provider === 'MANUAL' && (
            <button
              onClick={() => { setShowAddForm(v => !v); addAssetMutation.reset() }}
              className="rounded-lg border border-gray-600 px-4 py-2 text-sm hover:border-blue-500 hover:text-blue-400 transition-colors"
            >
              {showAddForm ? '✕ 닫기' : '+ 자산 추가'}
            </button>
          )}
          {account?.provider === 'STOCK' && (
            <Link
              href={`/unified/accounts/${id}/trades`}
              className="rounded-lg border border-gray-600 px-4 py-2 text-sm font-medium hover:border-blue-500 hover:text-blue-400 transition-colors"
            >
              거래내역 관리
            </Link>
          )}
          {/* STOCK은 수동 거래내역 재계산 sync라 상세에서만 노출(목록 제외) */}
          {(isSyncable(account?.provider) || account?.provider === 'STOCK') && (
            <button
              onClick={handleSync}
              disabled={syncing}
              className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium hover:bg-blue-500 disabled:opacity-50 transition-colors"
            >
              {syncing ? '동기화 중…' : '↻ Sync'}
            </button>
          )}
        </div>
      </div>

      {/* Sync Result */}
      {syncResult && (
        <div className={`rounded-xl border p-4 text-sm ${
          syncResult.error
            ? 'border-red-800 bg-red-950 text-red-400'
            : 'border-emerald-800 bg-emerald-950 text-emerald-400'
        }`}>
          {syncResult.error ? `동기화 실패: ${syncResult.error}` : `✓ ${syncResult.synced}개 자산 동기화 완료`}
        </div>
      )}

      {/* Add Asset Form */}
      {showAddForm && account?.provider === 'MANUAL' && (
        <form onSubmit={handleSubmit} className="rounded-xl border border-blue-800 bg-gray-900 p-6 space-y-5">
          <h2 className="text-sm font-semibold text-blue-400">새 자산 추가</h2>

          {/* 자산 유형 탭 */}
          <div>
            <label className="mb-2 block text-xs font-medium text-gray-400">자산 유형 *</label>
            <div className="flex flex-wrap gap-2">
              {ASSET_TYPES.map(t => (
                <button key={t} type="button" onClick={() => switchType(t)}
                  className={`rounded-lg border px-3 py-1.5 text-xs font-medium transition-colors ${
                    assetForm.type === t
                      ? 'border-blue-500 bg-blue-600/20 text-blue-300'
                      : 'border-gray-700 text-gray-400 hover:border-gray-500'
                  }`}
                >
                  {TYPE_KO[t]}
                </button>
              ))}
            </div>
          </div>

          {/* 세부 유형 */}
          {SUB_TYPES[assetForm.type] && (
            <div>
              <label className="mb-2 block text-xs font-medium text-gray-400">세부 유형</label>
              <div className="flex flex-wrap gap-2">
                {SUB_TYPES[assetForm.type].map(s => (
                  <button key={s.value} type="button"
                    onClick={() => set('subType', s.value)}
                    className={`rounded-lg border px-3 py-1.5 text-xs font-medium transition-colors ${
                      assetForm.subType === s.value
                        ? 'border-emerald-500 bg-emerald-600/20 text-emerald-300'
                        : 'border-gray-700 text-gray-400 hover:border-gray-500'
                    }`}
                  >
                    {s.label}
                  </button>
                ))}
              </div>
            </div>
          )}

          {/* 힌트 */}
          {cfg.hint && (
            <div className="rounded-lg border border-gray-700 bg-gray-800/50 px-3 py-2 text-xs text-gray-400">
              ℹ️ {cfg.hint}
            </div>
          )}

          <div className="grid grid-cols-2 gap-3">

            {/* 면적 (평) — 부동산 전용 */}
            {isAreaType && (
              <div>
                <label className="mb-1 block text-xs text-gray-400">면적 (평)</label>
                <DecimalInput
                  placeholder="예: 30"
                  value={assetForm.areaPyeong ?? 0}
                  onChange={v => set('areaPyeong', v)}
                />
              </div>
            )}

            {/* 자산명 */}
            <div className="col-span-2">
              <label className="mb-1 block text-xs text-gray-400">자산명 *</label>
              <input required type="text"
                placeholder={cfg.namePlaceholder}
                value={assetForm.name}
                onChange={e => set('name', e.target.value)}
                className={inputCls}
              />
            </div>

            {/* 심볼/주소/단위 */}
            {cfg.showSymbol && (
              <div className="col-span-2">
                <label className="mb-1 block text-xs text-gray-400">{cfg.symbolLabel}</label>
                <input type="text"
                  placeholder={cfg.symbolPlaceholder}
                  value={assetForm.symbol ?? ''}
                  onChange={e => set('symbol', e.target.value)}
                  className={inputCls}
                />
              </div>
            )}

            {/* 수량 */}
            {cfg.showQuantity && (
              <div>
                <label className="mb-1 block text-xs text-gray-400">{cfg.quantityLabel}</label>
                <DecimalInput
                  required
                  placeholder={cfg.quantityPlaceholder}
                  value={assetForm.quantity}
                  onChange={v => set('quantity', v)}
                />
              </div>
            )}

            {/* 통화 */}
            <div>
              <label className="mb-1 block text-xs text-gray-400">통화</label>
              <select value={currency} onChange={e => set('currency', e.target.value)} className={inputCls}>
                <option value="KRW">KRW</option>
                <option value="USD">USD</option>
              </select>
            </div>

            {/* 취득가 / 매입가 */}
            <div>
              <label className="mb-1 block text-xs text-gray-400">{cfg.purchasePriceLabel} *</label>
              <MoneyInput
                required
                placeholder="0"
                value={assetForm.purchasePrice}
                onChange={v => set('purchasePrice', v ?? 0)}
              />
            </div>

            {/* 현재 시세 / 현재 가치 */}
            <div>
              <label className="mb-1 block text-xs text-gray-400">{cfg.currentValueLabel} *</label>
              <MoneyInput
                required
                placeholder="0"
                value={assetForm.currentValue}
                onChange={v => set('currentValue', v ?? 0)}
              />
            </div>

            {/* 대출 잔액 */}
            {subInfo?.loanLabel && (
              <div className="col-span-2">
                <label className="mb-1 block text-xs text-gray-400">
                  {subInfo.loanLabel}
                  <span className="ml-1 text-gray-600">(없으면 빈칸)</span>
                </label>
                <MoneyInput
                  placeholder="0"
                  value={assetForm.loanAmount ?? null}
                  onChange={v => set('loanAmount', v)}
                />
                {(assetForm.loanAmount ?? 0) > 0 && (
                  <p className="mt-1 text-xs text-emerald-400">
                    순자산: {fmt((assetForm.currentValue ?? 0) - (assetForm.loanAmount ?? 0), currency)}
                  </p>
                )}
              </div>
            )}

            {/* 메모 */}
            <div className="col-span-2">
              <label className="mb-1 block text-xs text-gray-400">{cfg.memoLabel}</label>
              <textarea rows={2}
                placeholder={cfg.memoPlaceholder}
                value={assetForm.memo ?? ''}
                onChange={e => set('memo', e.target.value)}
                className={`${inputCls} resize-none`}
              />
            </div>
          </div>

          {/* 에러 */}
          {errMsg && (
            <div className="rounded-lg border border-red-800 bg-red-950/50 px-3 py-2 text-xs text-red-400">
              {typeof errMsg === 'string' ? errMsg : JSON.stringify(errMsg)}
            </div>
          )}

          <div className="flex gap-3">
            <button type="submit" disabled={addAssetMutation.isPending}
              className="rounded-lg bg-blue-600 px-5 py-2 text-sm font-medium hover:bg-blue-500 disabled:opacity-50 transition-colors"
            >
              {addAssetMutation.isPending ? '저장 중…' : '자산 저장'}
            </button>
            <button type="button" onClick={() => { setShowAddForm(false); addAssetMutation.reset() }}
              className="rounded-lg border border-gray-600 px-4 py-2 text-sm hover:border-gray-400 transition-colors"
            >
              취소
            </button>
          </div>
        </form>
      )}

      {/* 보유 자산 목록 */}
      <div className="rounded-xl border border-gray-700 bg-gray-900">
        <div className="flex items-center justify-between px-6 py-4 border-b border-gray-700">
          <h2 className="text-sm font-semibold text-gray-300">보유 자산</h2>
          <span className="text-xs text-gray-500">{assets.length}개</span>
        </div>

        {assetsLoading ? (
          <div className="space-y-2 p-4">
            {[1,2,3].map(i => <div key={i} className="h-12 animate-pulse rounded bg-gray-800" />)}
          </div>
        ) : assets.length === 0 ? (
          <div className="py-12 text-center text-sm text-gray-500">
            {account?.provider === 'MANUAL' ? '자산을 추가해 주세요.' : 'Sync 버튼을 눌러 자산을 조회하세요.'}
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs text-gray-500 border-b border-gray-800">
                  <th className="px-6 py-3 font-medium">자산명</th>
                  <th className="px-4 py-3 font-medium">유형</th>
                  <th className="px-4 py-3 text-right font-medium">수량</th>
                  <th className="px-4 py-3 text-right font-medium">매입가 / 현재가</th>
                  <th className="px-4 py-3 text-right font-medium">현재 가치</th>
                  <th className="px-4 py-3 text-right font-medium">대출</th>
                  <th className="px-4 py-3 text-right font-medium">순자산</th>
                  <th className="px-4 py-3 text-right font-medium">손익 / 수익률</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-800">
                {assets.map((a: Asset) => {
                  const pnl        = Number(a.unrealizedPnl)
                  const qty        = Number(a.quantity)
                  const purchase   = Number(a.purchasePrice)
                  const isIlliquid = a.liquidityType === 'ILLIQUID'
                  const curPerUnit = isIlliquid ? Number(a.currentValue) : (qty > 0 ? Number(a.currentValue) / qty : 0)
                  const totalCost  = isIlliquid ? purchase : purchase * qty
                  const returnPct  = totalCost > 0 ? (pnl / totalCost) * 100 : 0
                  const isPos      = pnl >= 0
                  const qtyDisplay = a.areaPyeong != null
                    ? `${Number(a.areaPyeong).toFixed(0)}평`
                    : qty.toLocaleString('ko-KR', { maximumFractionDigits: 6 })
                  return (
                    <tr key={a.id} className="hover:bg-gray-800/40 transition-colors">
                      <td className="px-6 py-3">
                        <div className="font-medium">{a.name}</div>
                        {a.symbol && <div className="text-xs text-gray-500">{a.symbol}</div>}
                        {a.memo && <div className="text-xs text-gray-600 max-w-xs truncate">{a.memo}</div>}
                      </td>
                      <td className="px-4 py-3">
                        <div className="text-xs text-gray-400">{TYPE_KO[a.type] ?? a.type}</div>
                        {a.subType && (
                          <span className="mt-0.5 inline-block rounded-full bg-emerald-900/50 px-2 py-0.5 text-xs text-emerald-400">
                            {SUB_TYPE_KO[a.subType] ?? a.subType}
                          </span>
                        )}
                      </td>
                      <td className="px-4 py-3 text-right tabular-nums text-xs text-gray-300">
                        {qtyDisplay}
                      </td>
                      <td className="px-4 py-3 text-right tabular-nums text-xs">
                        <div className="text-gray-400">
                          {purchase > 0 ? fmt(purchase, a.currency) : '—'}
                        </div>
                        <div className={isPos ? 'text-emerald-400' : 'text-red-400'}>
                          {curPerUnit > 0 ? fmt(curPerUnit, a.currency) : '—'}
                        </div>
                      </td>
                      <td className="px-4 py-3 text-right tabular-nums">
                        {fmt(Number(a.currentValue), a.currency)}
                      </td>
                      <td className="px-4 py-3 text-right tabular-nums text-xs text-red-400">
                        {a.loanAmount ? `-${fmt(Number(a.loanAmount), a.currency)}` : '—'}
                      </td>
                      <td className="px-4 py-3 text-right tabular-nums font-medium">
                        {fmt(Number(a.netEquity), a.currency)}
                      </td>
                      <td className={`px-4 py-3 text-right tabular-nums text-xs ${isPos ? 'text-emerald-400' : 'text-red-400'}`}>
                        <div>{isPos ? '+' : ''}{fmt(pnl, a.currency)}</div>
                        {totalCost > 0 && (
                          <div className="font-semibold">
                            {isPos ? '+' : ''}{returnPct.toFixed(2)}%
                          </div>
                        )}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

    </div>
  )
}

const inputCls = 'w-full rounded-lg border border-gray-700 bg-gray-800 px-3 py-2 text-sm text-white placeholder-gray-500 focus:border-blue-500 focus:outline-none transition-colors'
