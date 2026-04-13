'use client'

import { useState } from 'react'
import { useParams, useRouter } from 'next/navigation'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import Link from 'next/link'
import { useUnifiedApi } from '@/lib/useApi'
import type { Asset, SyncResult, CreateManualAssetPayload } from '@/types/unified'

const ASSET_TYPES = ['STOCK', 'CRYPTO', 'REAL_ESTATE', 'VEHICLE', 'GOLD', 'CASH', 'ETC']
const TYPE_KO: Record<string, string> = {
  STOCK: '주식', CRYPTO: '암호화폐', REAL_ESTATE: '부동산',
  VEHICLE: '자동차', GOLD: '금', CASH: '현금', ETC: '기타',
}

function fmt(n: number, currency = 'USD') {
  return new Intl.NumberFormat('ko-KR', {
    style: 'currency', currency, maximumFractionDigits: 0,
  }).format(n)
}

export default function AccountDetailPage() {
  const { id } = useParams<{ id: string }>()
  const router  = useRouter()
  const qc      = useQueryClient()
  const api     = useUnifiedApi()

  const [syncing, setSyncing]       = useState(false)
  const [syncResult, setSyncResult] = useState<SyncResult | null>(null)
  const [showAddForm, setShowAddForm] = useState(false)
  const [assetForm, setAssetForm]   = useState<CreateManualAssetPayload>({
    name: '', type: 'CASH', quantity: 1, purchasePrice: 0, currentValue: 0, currency: 'KRW',
  })

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
      setAssetForm({ name: '', type: 'CASH', quantity: 1, purchasePrice: 0, currentValue: 0, currency: 'KRW' })
    },
  })

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

  const setField = (k: string, v: string | number) =>
    setAssetForm(prev => ({ ...prev, [k]: v }))

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
              onClick={() => setShowAddForm(v => !v)}
              className="rounded-lg border border-gray-600 px-4 py-2 text-sm hover:border-blue-500 hover:text-blue-400 transition-colors"
            >
              + 자산 추가
            </button>
          )}
          {account?.provider === 'CSV' && (
            <Link
              href={`/unified/accounts/${id}/csv`}
              className="rounded-lg border border-gray-600 px-4 py-2 text-sm hover:border-blue-500 hover:text-blue-400 transition-colors"
            >
              CSV 업로드
            </Link>
          )}
          {account?.provider === 'STOCK' && (
            <Link
              href={`/unified/accounts/${id}/trades`}
              className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium hover:bg-blue-500 transition-colors"
            >
              거래내역 관리
            </Link>
          )}
          {account?.provider === 'BINANCE' || account?.provider === 'WALLET' ? (
            <button
              onClick={handleSync}
              disabled={syncing}
              className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium hover:bg-blue-500 disabled:opacity-50 transition-colors"
            >
              {syncing ? '동기화 중…' : '↻ Sync'}
            </button>
          ) : null}
        </div>
      </div>

      {/* Sync Result */}
      {syncResult && (
        <div className={`rounded-xl border p-4 text-sm ${
          syncResult.error
            ? 'border-red-800 bg-red-950 text-red-400'
            : 'border-emerald-800 bg-emerald-950 text-emerald-400'
        }`}>
          {syncResult.error
            ? `동기화 실패: ${syncResult.error}`
            : `✓ ${syncResult.synced}개 자산 동기화 완료`}
        </div>
      )}

      {/* Add Asset Form (Manual only) */}
      {showAddForm && account?.provider === 'MANUAL' && (
        <form
          onSubmit={e => { e.preventDefault(); addAssetMutation.mutate(assetForm) }}
          className="rounded-xl border border-blue-800 bg-gray-900 p-6 space-y-4"
        >
          <h2 className="text-sm font-semibold text-blue-400">새 자산 추가</h2>
          <div className="grid grid-cols-2 gap-3">
            <div className="col-span-2">
              <label className="mb-1 block text-xs text-gray-400">자산명 *</label>
              <input required type="text" placeholder="예: 강남 아파트" value={assetForm.name}
                onChange={e => setField('name', e.target.value)} className={inputCls} />
            </div>
            <div>
              <label className="mb-1 block text-xs text-gray-400">유형 *</label>
              <select value={assetForm.type} onChange={e => setField('type', e.target.value)} className={inputCls}>
                {ASSET_TYPES.map(t => <option key={t} value={t}>{TYPE_KO[t]}</option>)}
              </select>
            </div>
            <div>
              <label className="mb-1 block text-xs text-gray-400">심볼 (선택)</label>
              <input type="text" placeholder="예: BTC" value={assetForm.symbol ?? ''}
                onChange={e => setField('symbol', e.target.value)} className={inputCls} />
            </div>
            <div>
              <label className="mb-1 block text-xs text-gray-400">수량</label>
              <input required type="number" step="any" value={assetForm.quantity}
                onChange={e => setField('quantity', parseFloat(e.target.value))} className={inputCls} />
            </div>
            <div>
              <label className="mb-1 block text-xs text-gray-400">통화</label>
              <select value={assetForm.currency} onChange={e => setField('currency', e.target.value)} className={inputCls}>
                <option value="KRW">KRW</option>
                <option value="USD">USD</option>
              </select>
            </div>
            <div>
              <label className="mb-1 block text-xs text-gray-400">매입가</label>
              <input required type="number" step="any" value={assetForm.purchasePrice}
                onChange={e => setField('purchasePrice', parseFloat(e.target.value))} className={inputCls} />
            </div>
            <div>
              <label className="mb-1 block text-xs text-gray-400">현재 가치</label>
              <input required type="number" step="any" value={assetForm.currentValue}
                onChange={e => setField('currentValue', parseFloat(e.target.value))} className={inputCls} />
            </div>
            <div className="col-span-2">
              <label className="mb-1 block text-xs text-gray-400">메모</label>
              <input type="text" placeholder="선택 사항" value={assetForm.memo ?? ''}
                onChange={e => setField('memo', e.target.value)} className={inputCls} />
            </div>
          </div>
          {addAssetMutation.isError && (
            <p className="text-xs text-red-400">{(addAssetMutation.error as Error).message}</p>
          )}
          <div className="flex gap-3">
            <button type="submit" disabled={addAssetMutation.isPending}
              className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium hover:bg-blue-500 disabled:opacity-50 transition-colors">
              {addAssetMutation.isPending ? '추가 중…' : '자산 추가'}
            </button>
            <button type="button" onClick={() => setShowAddForm(false)}
              className="rounded-lg border border-gray-600 px-4 py-2 text-sm hover:border-gray-400 transition-colors">
              취소
            </button>
          </div>
        </form>
      )}

      {/* Asset List */}
      <div className="rounded-xl border border-gray-700 bg-gray-900">
        <div className="flex items-center justify-between px-6 py-4 border-b border-gray-700">
          <h2 className="text-sm font-semibold text-gray-300">보유 자산</h2>
          <span className="text-xs text-gray-500">{assets.length}개</span>
        </div>

        {assetsLoading ? (
          <div className="space-y-2 p-4">
            {[1, 2, 3].map(i => <div key={i} className="h-12 animate-pulse rounded bg-gray-800" />)}
          </div>
        ) : assets.length === 0 ? (
          <div className="py-12 text-center text-sm text-gray-500">
            {account?.provider === 'MANUAL'
              ? '자산을 추가해 주세요.'
              : 'Sync 버튼을 눌러 자산을 조회하세요.'}
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs text-gray-500 border-b border-gray-800">
                  <th className="px-6 py-3 font-medium">자산명</th>
                  <th className="px-4 py-3 font-medium">유형</th>
                  <th className="px-4 py-3 text-right font-medium">수량</th>
                  <th className="px-4 py-3 text-right font-medium">현재 가치</th>
                  <th className="px-4 py-3 text-right font-medium">손익</th>
                  <th className="px-4 py-3 text-right font-medium">수익률</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-800">
                {assets.map((a: Asset) => {
                  const pnl = Number(a.unrealizedPnl)
                  const ret = Number(a.returnRate)
                  return (
                    <tr key={a.id} className="hover:bg-gray-800/40 transition-colors">
                      <td className="px-6 py-3">
                        <div className="font-medium">{a.name}</div>
                        {a.symbol && <div className="text-xs text-gray-500">{a.symbol}</div>}
                        {a.memo && <div className="text-xs text-gray-600">{a.memo}</div>}
                      </td>
                      <td className="px-4 py-3 text-xs text-gray-400">{TYPE_KO[a.type] ?? a.type}</td>
                      <td className="px-4 py-3 text-right tabular-nums text-gray-300">
                        {Number(a.quantity).toLocaleString('ko-KR', { maximumFractionDigits: 6 })}
                      </td>
                      <td className="px-4 py-3 text-right tabular-nums">
                        {fmt(Number(a.currentValue), a.currency)}
                      </td>
                      <td className={`px-4 py-3 text-right tabular-nums text-xs ${pnl >= 0 ? 'text-emerald-400' : 'text-red-400'}`}>
                        {pnl >= 0 ? '+' : ''}{fmt(pnl, a.currency)}
                      </td>
                      <td className={`px-4 py-3 text-right tabular-nums text-xs ${ret >= 0 ? 'text-emerald-400' : 'text-red-400'}`}>
                        {ret >= 0 ? '+' : ''}{ret.toFixed(2)}%
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
