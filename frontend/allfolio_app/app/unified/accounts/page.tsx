'use client'

import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import Link from 'next/link'
import { useUnifiedApi } from '@/lib/useApi'
import type { Account, SyncResult } from '@/types/unified'

const STATUS_STYLE: Record<string, string> = {
  ACTIVE:   'bg-emerald-900/40 text-emerald-400 border-emerald-800',
  SYNCING:  'bg-yellow-900/40 text-yellow-400 border-yellow-800',
  ERROR:    'bg-red-900/40 text-red-400 border-red-800',
  INACTIVE: 'bg-gray-800 text-gray-500 border-gray-700',
}
const STATUS_KO: Record<string, string> = {
  ACTIVE: '정상', SYNCING: '동기화 중', ERROR: '오류', INACTIVE: '비활성',
}
const PROVIDER_KO: Record<string, string> = {
  BINANCE: 'Binance', STOCK: '증권', WALLET: '지갑', CSV: 'CSV', MANUAL: '수동',
}

// Sync를 지원하는 프로바이더 (STOCK·MANUAL·CSV는 API 자동 sync 미지원)
const SYNCABLE = new Set(['BINANCE', 'WALLET'])

export default function AccountsPage() {
  const api = useUnifiedApi()
  const qc = useQueryClient()
  const [syncingId, setSyncingId] = useState<string | null>(null)
  const [syncResults, setSyncResults] = useState<Record<string, SyncResult>>({})

  const { data: accounts = [], isLoading } = useQuery({
    queryKey: ['unified', 'accounts'],
    queryFn:  () => api!.accounts.list(),
    enabled:  !!api,
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => api!.accounts.delete(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['unified', 'accounts'] }),
  })

  const handleSync = async (id: string) => {
    if (!api) return
    setSyncingId(id)
    try {
      const result = await api.accounts.sync(id)
      setSyncResults(prev => ({ ...prev, [id]: result }))
      qc.invalidateQueries({ queryKey: ['unified', 'accounts'] })
      qc.invalidateQueries({ queryKey: ['unified', 'portfolio'] })
    } finally {
      setSyncingId(null)
    }
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">계좌 관리</h1>
          <p className="mt-1 text-sm text-gray-400">자산 수집 계좌를 관리합니다</p>
        </div>
        <Link
          href="/unified/accounts/new"
          className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium hover:bg-blue-500 transition-colors"
        >
          + 계좌 추가
        </Link>
      </div>

      {isLoading ? (
        <div className="space-y-3">
          {[1, 2, 3].map(i => (
            <div key={i} className="h-24 animate-pulse rounded-xl bg-gray-800" />
          ))}
        </div>
      ) : accounts.length === 0 ? (
        <div className="rounded-xl border border-dashed border-gray-700 p-12 text-center">
          <p className="text-gray-400">등록된 계좌가 없습니다</p>
          <Link
            href="/unified/accounts/new"
            className="mt-4 inline-block rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium hover:bg-blue-500"
          >
            첫 계좌 추가하기
          </Link>
        </div>
      ) : (
        <div className="space-y-3">
          {accounts.map((account: Account) => {
            const syncResult = syncResults[account.id]
            const isSyncing  = syncingId === account.id
            const canSync    = SYNCABLE.has(account.provider)

            return (
              <div key={account.id} className="rounded-xl border border-gray-700 bg-gray-900 p-5">
                <div className="flex items-start justify-between gap-4">
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 flex-wrap">
                      <h3 className="font-semibold truncate">{account.accountName}</h3>
                      <span className="text-xs text-gray-500">
                        {account.brokerage ?? PROVIDER_KO[account.provider] ?? account.provider}
                      </span>
                      <span className={`rounded-full border px-2 py-0.5 text-xs font-medium ${STATUS_STYLE[account.status]}`}>
                        {isSyncing ? '동기화 중…' : STATUS_KO[account.status]}
                      </span>
                    </div>
                    <div className="mt-1.5 flex flex-wrap gap-x-4 gap-y-1 text-xs text-gray-500">
                      <span>{PROVIDER_KO[account.provider]} · {account.currency}</span>
                      <span>
                        마지막 동기화:{' '}
                        {account.lastSyncedAt
                          ? new Date(account.lastSyncedAt).toLocaleString('ko-KR')
                          : '없음'}
                      </span>
                    </div>
                    {syncResult && (
                      <div className={`mt-2 text-xs ${syncResult.error ? 'text-red-400' : 'text-emerald-400'}`}>
                        {syncResult.error
                          ? `오류: ${syncResult.error}`
                          : `✓ ${syncResult.synced}개 자산 동기화 완료`}
                      </div>
                    )}
                  </div>

                  <div className="flex shrink-0 items-center gap-2">
                    {canSync && (
                      <button
                        onClick={() => handleSync(account.id)}
                        disabled={isSyncing || syncingId !== null}
                        className="rounded-lg border border-gray-600 px-3 py-1.5 text-xs font-medium hover:border-blue-500 hover:text-blue-400 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
                      >
                        {isSyncing ? '⟳ 동기화 중' : '↻ Sync'}
                      </button>
                    )}
                    {account.provider === 'STOCK' && (
                      <Link
                        href={`/unified/accounts/${account.id}/trades`}
                        className="rounded-lg border border-gray-600 px-3 py-1.5 text-xs font-medium hover:border-blue-500 hover:text-blue-400 transition-colors"
                      >
                        거래내역
                      </Link>
                    )}
                    <Link
                      href={`/unified/accounts/${account.id}`}
                      className="rounded-lg border border-gray-600 px-3 py-1.5 text-xs font-medium hover:border-gray-400 transition-colors"
                    >
                      상세
                    </Link>
                    <button
                      onClick={() => {
                        if (confirm(`"${account.accountName}" 계좌를 삭제하시겠습니까?\n모든 자산과 거래내역도 함께 삭제됩니다.`))
                          deleteMutation.mutate(account.id)
                      }}
                      className="rounded-lg border border-gray-700 px-3 py-1.5 text-xs text-gray-500 hover:border-red-700 hover:text-red-400 transition-colors"
                    >
                      삭제
                    </button>
                  </div>
                </div>
              </div>
            )
          })}
        </div>
      )}

      <div className="flex gap-3">
        <Link href="/unified" className="text-sm text-gray-400 hover:text-white transition-colors">
          ← 대시보드로
        </Link>
      </div>
    </div>
  )
}
