'use client'

import { useState } from 'react'
import { useParams, useRouter } from 'next/navigation'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import Link from 'next/link'
import { useUnifiedApi } from '@/lib/useApi'
import { isSyncable } from '@/lib/providers'
import PageHeader from '@/components/ui/PageHeader'
import SectionHeader from '@/components/ui/SectionHeader'
import Badge from '@/components/ui/Badge'
import Button from '@/components/ui/Button'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import Field, { Input, Select, Textarea } from '@/components/ui/Field'
import { EmptyState, ErrorState, LoadingState } from '@/components/ui/states'
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
    exclusiveAreaM2: null,
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
    <Input
      type="text"
      inputMode="numeric"
      required={required}
      placeholder={placeholder ?? '0'}
      value={fmtComma(value)}
      onChange={e => {
        const d = digitsOnly(e.target.value)
        onChange(d ? parseInt(d, 10) : null)
      }}
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
    <Input
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
    />
  )
}

const ASSET_GRID = 'grid grid-cols-[1.6fr_0.9fr_0.7fr_1fr_1fr_0.9fr_1fr_1fr] gap-3'

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
      // 헤더의 '최종 동기화'는 account.lastSyncedAt — 계좌 목록 쿼리에서 온다.
      qc.invalidateQueries({ queryKey: ['unified', 'accounts'] })
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
      exclusiveAreaM2: isAreaType ? (assetForm.exclusiveAreaM2 ?? undefined) : undefined,
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
    return (
      <div className="border border-line-card bg-surface px-5 py-5 sm:px-7">
        <ErrorState message="계좌를 찾을 수 없습니다." />
      </div>
    )
  }

  return (
    <div className="border border-line-card bg-surface">

      {/* Header */}
      <div className="px-5 pt-4 sm:px-7">
        <Link
          href="/unified/accounts"
          className="font-mono text-[10px] tracking-label text-fg-faint transition-colors hover:text-ink"
        >
          ← 계좌 목록
        </Link>
      </div>
      <PageHeader
        className="px-5 pt-2 sm:px-7"
        title={account?.accountName ?? '계좌 상세'}
        meta={
          <>
            {account?.provider} · {account?.accountType} · {account?.currency}
            {account?.lastSyncedAt && (
              <> · 최종 동기화 {new Date(account.lastSyncedAt).toLocaleString('ko-KR')}</>
            )}
          </>
        }
        actions={
          <>
            {account?.provider === 'MANUAL' && (
              <Button onClick={() => { setShowAddForm(v => !v); addAssetMutation.reset() }}>
                {showAddForm ? '닫기' : '자산 추가'}
              </Button>
            )}
            {account?.provider === 'STOCK' && (
              <Link
                href={`/unified/accounts/${id}/trades`}
                className="border border-line bg-surface px-3.5 py-2 text-[12.5px] text-fg-2 transition-colors hover:border-ink hover:text-ink"
              >
                거래내역 관리
              </Link>
            )}
            {/* STOCK은 수동 거래내역 재계산 sync라 상세에서만 노출(목록 제외) */}
            {(isSyncable(account?.provider) || account?.provider === 'STOCK') && (
              <Button variant="primary" onClick={handleSync} disabled={syncing}>
                {syncing ? '동기화 중…' : 'Sync'}
              </Button>
            )}
          </>
        }
      />

      <div className="px-5 py-5 pb-10 sm:px-7">

        {/* Sync Result */}
        {syncResult && (
          <p className={`mb-5 font-mono text-[10.5px] tracking-[0.04em] ${syncResult.error ? 'text-danger' : 'text-ok'}`}>
            {syncResult.error ? `동기화 실패: ${syncResult.error}` : `${syncResult.synced}개 자산 동기화 완료`}
          </p>
        )}

        {/* Add Asset Form */}
        {showAddForm && account?.provider === 'MANUAL' && (
          <form onSubmit={handleSubmit} className="mb-6 border border-ink bg-surface-muted p-5 sm:p-6">
            <SectionHeader label="새 자산 추가" />

            {/* 자산 유형 탭 */}
            <div className="mb-4">
              <span className="mb-2 block font-mono text-[10px] tracking-label text-fg-muted">자산 유형 *</span>
              <div className="flex flex-wrap gap-1.5">
                {ASSET_TYPES.map(t => (
                  <button key={t} type="button" onClick={() => switchType(t)}
                    aria-pressed={assetForm.type === t}
                    className={`border px-3 py-1.5 text-xs transition-colors ${
                      assetForm.type === t
                        ? 'border-ink bg-ink text-white'
                        : 'border-line bg-surface text-fg-3 hover:border-ink hover:text-ink'
                    }`}
                  >
                    {TYPE_KO[t]}
                  </button>
                ))}
              </div>
            </div>

            {/* 세부 유형 */}
            {SUB_TYPES[assetForm.type] && (
              <div className="mb-4">
                <span className="mb-2 block font-mono text-[10px] tracking-label text-fg-muted">세부 유형</span>
                <div className="flex flex-wrap gap-1.5">
                  {SUB_TYPES[assetForm.type].map(s => (
                    <button key={s.value} type="button"
                      onClick={() => set('subType', s.value)}
                      aria-pressed={assetForm.subType === s.value}
                      className={`border px-3 py-1.5 text-xs transition-colors ${
                        assetForm.subType === s.value
                          ? 'border-ink bg-ink text-white'
                          : 'border-line bg-surface text-fg-3 hover:border-ink hover:text-ink'
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
              <div className="mb-4 border border-line bg-surface px-3 py-2 text-xs text-fg-3">
                {cfg.hint}
              </div>
            )}

            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">

              {/* 면적 — 부동산 전용.
                  평과 전용면적을 나눠 받는다. "34평"은 보통 공급면적이고 그 집의 전용은
                  84㎡(≈25.4평)라, 한 칸으로 받으면 어느 쪽인지 알 수 없다. 그 값으로
                  실거래를 고르면 한 평형 위 단지의 시세를 가져오는데 금액이 그럴듯해서
                  화면으로는 안 보인다. 그래서 매칭에 쓸 값은 단위를 명시해 따로 받는다. */}
              {isAreaType && (
                <div>
                  <label className="mb-1.5 block font-mono text-[10px] tracking-label text-fg-muted">면적 (평)</label>
                  <DecimalInput
                    placeholder="예: 30"
                    value={assetForm.areaPyeong ?? 0}
                    onChange={v => set('areaPyeong', v)}
                  />
                  <p className="mt-1 text-[10px] text-fg-muted">표시용입니다.</p>
                </div>
              )}
              {isAreaType && (
                <div>
                  <label className="mb-1.5 block font-mono text-[10px] tracking-label text-fg-muted">전용면적 (㎡)</label>
                  <DecimalInput
                    placeholder="예: 84.97"
                    value={assetForm.exclusiveAreaM2 ?? 0}
                    onChange={v => set('exclusiveAreaM2', v)}
                  />
                  <p className="mt-1 text-[10px] text-fg-muted">
                    등기부·분양계약서의 전용면적입니다. 공급(분양)면적이 아닙니다.
                  </p>
                </div>
              )}

              {/* 자산명 */}
              <Field id="asset-name" label="자산명 *" className="sm:col-span-2">
                <Input required type="text"
                  placeholder={cfg.namePlaceholder}
                  value={assetForm.name}
                  onChange={e => set('name', e.target.value)}
                />
              </Field>

              {/* 심볼/주소/단위 */}
              {cfg.showSymbol && (
                <Field id="asset-symbol" label={cfg.symbolLabel ?? ''} className="sm:col-span-2">
                  <Input type="text"
                    placeholder={cfg.symbolPlaceholder}
                    value={assetForm.symbol ?? ''}
                    onChange={e => set('symbol', e.target.value)}
                  />
                </Field>
              )}

              {/* 수량 */}
              {cfg.showQuantity && (
                <div>
                  <label className="mb-1.5 block font-mono text-[10px] tracking-label text-fg-muted">{cfg.quantityLabel}</label>
                  <DecimalInput
                    required
                    placeholder={cfg.quantityPlaceholder}
                    value={assetForm.quantity}
                    onChange={v => set('quantity', v)}
                  />
                </div>
              )}

              {/* 통화 */}
              <Field id="asset-currency" label="통화">
                <Select value={currency} onChange={e => set('currency', e.target.value)}>
                  <option value="KRW">KRW</option>
                  <option value="USD">USD</option>
                </Select>
              </Field>

              {/* 취득가 / 매입가 */}
              <div>
                <label className="mb-1.5 block font-mono text-[10px] tracking-label text-fg-muted">{cfg.purchasePriceLabel} *</label>
                <MoneyInput
                  required
                  placeholder="0"
                  value={assetForm.purchasePrice}
                  onChange={v => set('purchasePrice', v ?? 0)}
                />
              </div>

              {/* 현재 시세 / 현재 가치 */}
              <div>
                <label className="mb-1.5 block font-mono text-[10px] tracking-label text-fg-muted">{cfg.currentValueLabel} *</label>
                <MoneyInput
                  required
                  placeholder="0"
                  value={assetForm.currentValue}
                  onChange={v => set('currentValue', v ?? 0)}
                />
              </div>

              {/* 대출 잔액 */}
              {subInfo?.loanLabel && (
                <div className="sm:col-span-2">
                  <label className="mb-1.5 block font-mono text-[10px] tracking-label text-fg-muted">
                    {subInfo.loanLabel}
                    <span className="ml-1 text-fg-ghost">(없으면 빈칸)</span>
                  </label>
                  <MoneyInput
                    placeholder="0"
                    value={assetForm.loanAmount ?? null}
                    onChange={v => set('loanAmount', v)}
                  />
                  {(assetForm.loanAmount ?? 0) > 0 && (
                    <p className="mt-1 text-xs text-ok">
                      순자산: {fmt((assetForm.currentValue ?? 0) - (assetForm.loanAmount ?? 0), currency)}
                    </p>
                  )}
                </div>
              )}

              {/* 메모 */}
              <Field id="asset-memo" label={cfg.memoLabel} className="sm:col-span-2">
                <Textarea rows={2}
                  placeholder={cfg.memoPlaceholder}
                  value={assetForm.memo ?? ''}
                  onChange={e => set('memo', e.target.value)}
                  className="resize-none"
                />
              </Field>
            </div>

            {/* 에러 */}
            {errMsg && (
              <p role="alert" className="mt-4 text-xs text-danger">
                {typeof errMsg === 'string' ? errMsg : JSON.stringify(errMsg)}
              </p>
            )}

            <div className="mt-5 flex gap-2.5">
              <Button type="submit" variant="primary" disabled={addAssetMutation.isPending}>
                {addAssetMutation.isPending ? '저장 중…' : '자산 저장'}
              </Button>
              <Button type="button" onClick={() => { setShowAddForm(false); addAssetMutation.reset() }}>
                취소
              </Button>
            </div>
          </form>
        )}

        {/* 보유 자산 목록 */}
        <SectionHeader label="보유 자산" note={`${assets.length}개`} />

        {assetsLoading ? (
          <LoadingState label="자산 불러오는 중" />
        ) : assets.length === 0 ? (
          <EmptyState
            title={account?.provider === 'MANUAL' ? '자산을 추가해 주세요' : 'Sync 버튼을 눌러 자산을 조회하세요'}
          />
        ) : (
          <div className="overflow-x-auto">
            <div className="min-w-[900px] border-t-[1.5px] border-ink">
              <div className={`${ASSET_GRID} border-b border-line py-2`}>
                <Label size="sm" tone="faint">자산명</Label>
                <Label size="sm" tone="faint">유형</Label>
                <Label size="sm" tone="faint" className="text-right">수량</Label>
                <Label size="sm" tone="faint" className="text-right">매입가 / 현재가</Label>
                <Label size="sm" tone="faint" className="text-right">현재 가치</Label>
                <Label size="sm" tone="faint" className="text-right">대출</Label>
                <Label size="sm" tone="faint" className="text-right">순자산</Label>
                <Label size="sm" tone="faint" className="text-right">손익 / 수익률</Label>
              </div>
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
                  <div key={a.id} className={`${ASSET_GRID} items-baseline border-b border-line-hair py-2.5 hover:bg-surface-muted`}>
                    <span className="flex min-w-0 flex-col gap-0.5">
                      <span className="truncate text-[13px]">{a.name}</span>
                      {a.symbol && (
                        <span className="font-mono text-[9.5px] tracking-[0.08em] text-fg-ghost">{a.symbol}</span>
                      )}
                      {a.memo && (
                        <span className="max-w-xs truncate text-[10.5px] text-fg-ghost">{a.memo}</span>
                      )}
                    </span>
                    <span className="flex min-w-0 flex-col gap-0.5">
                      <span className="text-xs text-fg-3">{TYPE_KO[a.type] ?? a.type}</span>
                      {a.subType && (
                        <Badge variant="muted">{SUB_TYPE_KO[a.subType] ?? a.subType}</Badge>
                      )}
                    </span>
                    <Num className="text-right text-xs text-fg-3">
                      {qtyDisplay}
                    </Num>
                    <span className="flex min-w-0 flex-col items-end gap-0.5 text-right">
                      <Num className="text-[11.5px] text-fg-3">
                        {purchase > 0 ? fmt(purchase, a.currency) : '—'}
                      </Num>
                      <Num className={`text-[11.5px] ${isPos ? 'text-gain' : 'text-loss'}`}>
                        {curPerUnit > 0 ? fmt(curPerUnit, a.currency) : '—'}
                      </Num>
                    </span>
                    <Num className="text-right text-[12.5px]">
                      {fmt(Number(a.currentValue), a.currency)}
                    </Num>
                    <Num className="text-right text-[11.5px] text-danger">
                      {a.loanAmount ? `-${fmt(Number(a.loanAmount), a.currency)}` : '—'}
                    </Num>
                    <Num className="text-right text-[12.5px] font-medium">
                      {fmt(Number(a.netEquity), a.currency)}
                    </Num>
                    <span className={`flex min-w-0 flex-col items-end gap-0.5 text-right ${isPos ? 'text-gain' : 'text-loss'}`}>
                      <Num className="text-[11.5px]">{isPos ? '+' : ''}{fmt(pnl, a.currency)}</Num>
                      {totalCost > 0 && (
                        <Num className="text-[11.5px] font-semibold">
                          {isPos ? '+' : ''}{returnPct.toFixed(2)}%
                        </Num>
                      )}
                    </span>
                  </div>
                )
              })}
            </div>
          </div>
        )}

      </div>
    </div>
  )
}
