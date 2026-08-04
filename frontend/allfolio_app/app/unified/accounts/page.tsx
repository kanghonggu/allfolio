'use client'

import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import Link from 'next/link'
import { useUnifiedApi } from '@/lib/useApi'
import { isSyncable } from '@/lib/providers'
import PageHeader from '@/components/ui/PageHeader'
import Badge, { type BadgeVariant } from '@/components/ui/Badge'
import Button from '@/components/ui/Button'
import Label from '@/components/ui/Label'
import { EmptyState, LoadingState } from '@/components/ui/states'
import type { Account, SyncResult } from '@/types/unified'

const STATUS_BADGE: Record<string, BadgeVariant> = {
  ACTIVE: 'ok', SYNCING: 'warn', ERROR: 'danger', INACTIVE: 'muted',
}
const STATUS_KO: Record<string, string> = {
  ACTIVE: '정상', SYNCING: '동기화 중', ERROR: '오류', INACTIVE: '비활성',
}
const PROVIDER_KO: Record<string, string> = {
  BINANCE: '바이낸스', UPBIT: '업비트', BITHUMB: '빗썸', COINONE: '코인원',
  BYBIT: '바이빗', OKX: 'OKX',
  KIS: '한국투자증권', KIWOOM: '키움증권',
  STOCK: '증권', WALLET: '지갑', MANUAL: '수동', CSV: 'CSV',
}

const GRID = 'grid grid-cols-[1.7fr_1fr_0.6fr_1.2fr_1.9fr] gap-3'

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

  const okCount = accounts.filter(a => a.status === 'ACTIVE').length
  const errCount = accounts.filter(a => a.status === 'ERROR').length

  return (
    <div className="border border-line-card bg-surface">
      <PageHeader
        className="px-5 pt-5 sm:px-7"
        title="연결 계좌"
        meta={
          accounts.length > 0
            ? `연결 ${accounts.length} · 정상 ${okCount} · 오류 ${errCount}`
            : '자산 수집 계좌를 관리합니다'
        }
        actions={
          <>
            <Link
              href="/unified/accounts/sync"
              className="border border-line bg-surface px-3.5 py-2 text-[12.5px] text-fg-2 transition-colors hover:border-ink hover:text-ink"
            >
              동기화 상태
            </Link>
            <Link
              href="/unified/accounts/new"
              className="border border-ink bg-ink px-3.5 py-2 text-[12.5px] text-white transition-colors hover:bg-fg-2"
            >
              계좌 연결
            </Link>
          </>
        }
      />

      <div className="px-5 py-5 pb-10 sm:px-7">
        {isLoading ? (
          <LoadingState label="계좌 불러오는 중" />
        ) : accounts.length === 0 ? (
          <EmptyState
            title="등록된 계좌가 없습니다"
            description="증권사·거래소 API 키 또는 수동 등록으로 자산 수집을 시작하세요"
            action={
              <Link
                href="/unified/accounts/new"
                className="border border-ink bg-ink px-4 py-2 text-sm text-white transition-colors hover:bg-fg-2"
              >
                첫 계좌 연결
              </Link>
            }
          />
        ) : (
          <div className="overflow-x-auto">
            <div className="min-w-[880px] border-t-[1.5px] border-ink">
              <div className={`${GRID} border-b border-line py-2`}>
                <Label size="sm" tone="faint">계좌명 / 계좌번호</Label>
                <Label size="sm" tone="faint">기관</Label>
                <Label size="sm" tone="faint">통화</Label>
                <Label size="sm" tone="faint" className="text-right">최종 동기화</Label>
                <Label size="sm" tone="faint" className="text-right">상태</Label>
              </div>
              {accounts.map((account: Account) => {
                const syncResult = syncResults[account.id]
                const isSyncing  = syncingId === account.id
                const canSync    = isSyncable(account.provider)

                return (
                  <div key={account.id} className="border-b border-line-hair hover:bg-surface-muted">
                    <div className={`${GRID} items-center py-3`}>
                      <span className="flex min-w-0 flex-col gap-0.5">
                        <span className="truncate text-[13.5px]">{account.accountName}</span>
                        {account.accountNumber && (
                          <span className="font-mono text-[10px] tracking-[0.06em] text-fg-ghost">
                            {account.accountNumber}
                          </span>
                        )}
                      </span>
                      <span className="truncate text-[13px] text-fg-2">
                        {account.brokerage ?? PROVIDER_KO[account.provider] ?? account.provider}
                      </span>
                      <span className="font-mono text-[10px] tracking-label text-fg-3">
                        {account.currency}
                      </span>
                      <span className="whitespace-nowrap text-right font-mono text-[11.5px] tracking-[0.02em] text-fg-3 tnum">
                        {account.lastSyncedAt
                          ? new Date(account.lastSyncedAt).toLocaleString('ko-KR')
                          : '없음'}
                      </span>
                      <span className="flex items-center justify-end gap-2.5">
                        <Badge variant={STATUS_BADGE[account.status] ?? 'muted'}>
                          {isSyncing ? '동기화 중…' : STATUS_KO[account.status] ?? account.status}
                        </Badge>
                        {canSync && (
                          <Button
                            size="sm"
                            onClick={() => handleSync(account.id)}
                            disabled={isSyncing || syncingId !== null}
                          >
                            {isSyncing ? '동기화 중' : '동기화'}
                          </Button>
                        )}
                        {account.provider === 'STOCK' && (
                          <Link
                            href={`/unified/accounts/${account.id}/trades`}
                            className="border border-line px-3 py-1.5 text-xs text-fg-2 transition-colors hover:border-ink hover:text-ink"
                          >
                            거래내역
                          </Link>
                        )}
                        <Link
                          href={`/unified/accounts/${account.id}`}
                          className="border border-line px-3 py-1.5 text-xs text-fg-2 transition-colors hover:border-ink hover:text-ink"
                        >
                          상세
                        </Link>
                        <button
                          onClick={() => {
                            if (confirm(`"${account.accountName}" 계좌를 삭제하시겠습니까?\n모든 자산과 거래내역도 함께 삭제됩니다.`))
                              deleteMutation.mutate(account.id)
                          }}
                          className="border border-transparent px-2 py-1.5 text-xs text-fg-faint transition-colors hover:border-danger hover:text-danger"
                        >
                          삭제
                        </button>
                      </span>
                    </div>
                    {syncResult && (
                      <div className={`pb-2.5 font-mono text-[10.5px] tracking-[0.04em] ${syncResult.error ? 'text-danger' : 'text-ok'}`}>
                        {syncResult.error
                          ? `오류 — ${syncResult.error}`
                          : `동기화 완료 · ${syncResult.synced}개 자산`}
                      </div>
                    )}
                  </div>
                )
              })}
            </div>
          </div>
        )}

        <div className="mt-6">
          <Link href="/unified" className="text-[13px] text-link transition-colors hover:text-link-hover">
            ← 통합 자산으로
          </Link>
        </div>
      </div>
    </div>
  )
}
