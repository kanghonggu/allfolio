// app/unified/real-assets/page.tsx
'use client'

import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRealAssetApi } from '@/lib/useApi'
import PageHeader from '@/components/ui/PageHeader'
import SectionHeader from '@/components/ui/SectionHeader'
import Panel from '@/components/ui/Panel'
import Button from '@/components/ui/Button'
import Num from '@/components/ui/Num'
import Field, { Input, Select } from '@/components/ui/Field'
import { EmptyState, ErrorState, LoadingState } from '@/components/ui/states'
import RealAssetCard from '@/components/real-asset/RealAssetCard'
import type { RealAssetCreateRequest } from '@/types/real-asset'
import { errorMessage } from '@/lib/toast'
import { won } from '@/lib/format'
import { GRAMS_PER_DON, num } from '@/lib/real-asset-format'

/**
 * 실물자산 (A1 · G8).
 *
 * **합계는 평가된 자산만 더한다.** 평가 전 자산을 0으로 세면 합계가 실제보다 작게 나오는데,
 * 화면에는 그 사실이 안 보여 사용자가 "금이 줄었다"고 읽는다. 그래서 몇 건이 빠졌는지 함께 적는다.
 */
export default function RealAssetsPage() {
  const api = useRealAssetApi()
  const queryClient = useQueryClient()
  const [showForm, setShowForm] = useState(false)

  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['real-assets'],
    queryFn: () => api!.list(),
    enabled: !!api,
  })

  const createMut = useMutation({
    mutationFn: (req: RealAssetCreateRequest) => api!.create(req),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['real-assets'] })
      setShowForm(false)
    },
  })

  if (isLoading) return <LoadingState />
  if (isError) return <ErrorState message="실물자산을 불러오지 못했습니다" onRetry={() => refetch()} />

  const assets = data ?? []
  const valuedAssets = assets.filter((a) => a.valuationKrw !== null)
  const totalValuation = valuedAssets.reduce((sum, a) => sum + (a.valuationKrw ?? 0), 0)
  const totalCost = valuedAssets.reduce((sum, a) => sum + a.acquiredCostKrw, 0)
  const pending = assets.length - valuedAssets.length

  return (
    <div className="space-y-5">
      <PageHeader
        title="실물자산"
        meta={
          assets.length > 0
            ? `${assets.length}건${pending > 0 ? ` · 평가 전 ${pending}건` : ''}`
            : undefined
        }
        actions={
          <Button variant={showForm ? 'outline' : 'primary'} onClick={() => setShowForm((v) => !v)}>
            {showForm ? '닫기' : '자산 등록'}
          </Button>
        }
      />

      {showForm && (
        <RegisterForm
          onSubmit={(req) => createMut.mutate(req)}
          pending={createMut.isPending}
          error={createMut.isError ? errorMessage(createMut.error) : null}
        />
      )}

      {valuedAssets.length > 0 && (
        <Panel className="p-4">
          <div className="flex flex-wrap items-end justify-between gap-3">
            <div>
              <div className="font-mono text-[10px] tracking-wideLabel text-fg-muted">평가액 합계</div>
              <Num className="mt-1 block text-[22px] font-semibold">{won(totalValuation)}</Num>
            </div>
            <div className="text-right text-[11px] text-fg-faint">
              <div>취득가 <Num>{won(totalCost)}</Num></div>
              {/* **빠진 건수를 적는다.** 안 적으면 합계가 왜 작은지 알 방법이 없다 */}
              {pending > 0 && <div className="mt-0.5">평가 전 {pending}건은 합계에서 빠짐</div>}
            </div>
          </div>
        </Panel>
      )}

      <div>
        <SectionHeader label="보유 자산" />
        {assets.length === 0 ? (
          <EmptyState
            title="등록된 실물자산이 없습니다"
            description="금(KRX 금현물)을 등록하면 매일 저녁 평가 스냅샷이 쌓입니다."
          />
        ) : (
          <div className="mt-3 space-y-3">
            {assets.map((asset) => (
              <RealAssetCard key={asset.id} asset={asset} />
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

/**
 * 등록 폼.
 *
 * **`sourceRef`·`includeInTwr` 입력란이 없는 것이 의도다.** 서버가 정한다 —
 * 시세 조인 키를 사용자가 넣을 수 있으면 원유 코드를 넣어 금을 평가할 수 있고,
 * TWR 포함 여부는 자산 유형이 질 판단이다.
 *
 * **v1은 금만 등록한다.** 유형 선택을 만들지 않은 이유가 그것이다 — 서버가 금 외에는
 * 400을 주므로, 고를 수 있게 해 두면 고르고 나서 거절당한다.
 */
function RegisterForm({
  onSubmit,
  pending,
  error,
}: {
  onSubmit: (req: RealAssetCreateRequest) => void
  pending: boolean
  error: string | null
}) {
  const [name, setName] = useState('')
  const [subType, setSubType] = useState('KRX_ACCOUNT')
  const [quantity, setQuantity] = useState('')
  const [acquiredAt, setAcquiredAt] = useState('')
  const [acquiredCostKrw, setAcquiredCostKrw] = useState('')

  const grams = num(quantity)
  const donHint =
    grams !== null && grams > 0 ? `${(grams / GRAMS_PER_DON).toFixed(2)}돈` : null

  const canSubmit =
    name.trim() !== '' &&
    grams !== null &&
    grams > 0 &&
    acquiredAt !== '' &&
    num(acquiredCostKrw) !== null

  return (
    <Panel className="p-4">
      <form
        className="space-y-3"
        onSubmit={(e) => {
          e.preventDefault()
          if (!canSubmit) return
          onSubmit({
            assetType: 'GOLD',
            subType,
            name: name.trim(),
            // **문자열 그대로 보낸다.** Number로 바꾸면 부동소수라 3.75가 3.7499…가 될 수
            // 있고, 서버는 NUMERIC(18,4)로 받는다. 정밀도를 잃을 이유가 없다.
            quantity: quantity.trim(),
            acquiredAt,
            acquiredCostKrw: Math.round(num(acquiredCostKrw) ?? 0),
          })
        }}
      >
        <div className="grid gap-3 sm:grid-cols-2">
          <Field id="ra-name" label="이름">
            <Input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="금 1돈"
              maxLength={200}
            />
          </Field>

          <Field id="ra-subtype" label="보유 형태">
            <Select value={subType} onChange={(e) => setSubType(e.target.value)}>
              <option value="KRX_ACCOUNT">KRX 계좌보유분</option>
              <option value="BAR">골드바</option>
            </Select>
          </Field>

          <Field
            id="ra-quantity"
            label="중량 (g)"
            hint={donHint ? `= ${donHint} (1돈 = ${GRAMS_PER_DON}g)` : `1돈 = ${GRAMS_PER_DON}g`}
          >
            <Input
              value={quantity}
              onChange={(e) => setQuantity(e.target.value)}
              placeholder="3.75"
              inputMode="decimal"
            />
          </Field>

          <Field id="ra-acquired-at" label="취득일">
            <Input
              type="date"
              value={acquiredAt}
              onChange={(e) => setAcquiredAt(e.target.value)}
            />
          </Field>

          <Field id="ra-cost" label="취득가 (원)">
            <Input
              value={acquiredCostKrw}
              onChange={(e) => setAcquiredCostKrw(e.target.value)}
              placeholder="750000"
              inputMode="numeric"
            />
          </Field>
        </div>

        {/* 순도 입력란이 없다 — v1은 24K 고정이다. 18K를 받는 날 여기 생긴다 */}
        <p className="text-[11px] text-fg-faint">
          v1은 순금(24K) KRX 금현물만 지원합니다. 시세는 공공데이터포털 금 시세를 쓰며 D+1 이상
          지연되므로, 평가액은 항상 직전 영업일 종가 기준입니다.
        </p>

        {error && <p role="alert" className="text-xs text-danger">{error}</p>}

        <div className="flex justify-end">
          <Button type="submit" disabled={!canSubmit || pending}>
            {pending ? '등록 중' : '등록'}
          </Button>
        </div>
      </form>
    </Panel>
  )
}
