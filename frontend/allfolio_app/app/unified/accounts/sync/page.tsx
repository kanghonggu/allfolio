'use client'

import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import Link from 'next/link'
import { useUnifiedApi } from '@/lib/useApi'
import PageHeader from '@/components/ui/PageHeader'
import Badge, { type BadgeVariant } from '@/components/ui/Badge'
import Button from '@/components/ui/Button'
import Label from '@/components/ui/Label'
import SectionHeader from '@/components/ui/SectionHeader'
import { EmptyState, LoadingState } from '@/components/ui/states'
import type { AccountSyncStatus, SyncLogView } from '@/types/unified'

const STATUS_BADGE: Record<string, BadgeVariant> = {
  ACTIVE: 'ok', SYNCING: 'warn', ERROR: 'danger', INACTIVE: 'muted',
}
const STATUS_KO: Record<string, string> = {
  ACTIVE: '정상', SYNCING: '동기화 중', ERROR: '오류', INACTIVE: '비활성',
}
const TRIGGER_KO: Record<SyncLogView['trigger'], string> = {
  SCHEDULED: '자동', MANUAL: '수동',
}

function fmt(ts: string | null | undefined) {
  return ts ? new Date(ts).toLocaleString('ko-KR') : '없음'
}

function SyncHistory({ accountId }: { accountId: string }) {
  const api = useUnifiedApi()
  const { data: logs = [], isLoading } = useQuery({
    queryKey: ['unified', 'sync-logs', accountId],
    queryFn:  () => api!.accounts.syncLogs(accountId),
    enabled:  !!api,
  })

  if (isLoading) {
    return <div className="py-3 font-mono text-[10px] tracking-label text-fg-faint">이력 불러오는 중 …</div>
  }
  if (logs.length === 0) {
    return <div className="py-3 text-xs text-fg-faint">동기화 이력이 없습니다</div>
  }

  const GRID = 'grid grid-cols-[1.2fr_0.5fr_0.6fr_1.7fr] gap-3'
  return (
    <div className="mt-3 border-t border-line">
      <div className={`${GRID} border-b border-line-soft py-1.5`}>
        <Label size="sm" tone="faint">시각</Label>
        <Label size="sm" tone="faint">트리거</Label>
        <Label size="sm" tone="faint">결과</Label>
        <Label size="sm" tone="faint">상세</Label>
      </div>
      {logs.map((l: SyncLogView) => (
        <div key={l.id} className={`${GRID} items-baseline border-b border-line-hair py-1.5`}>
          <span className="font-mono text-[11px] text-fg-3 tnum">{fmt(l.createdAt)}</span>
          <span className="text-xs text-fg-3">{TRIGGER_KO[l.trigger]}</span>
          <Badge variant={l.status === 'SUCCESS' ? 'ok' : 'danger'}>
            {l.status === 'SUCCESS' ? '성공' : '실패'}
          </Badge>
          <span className={`text-xs ${l.status === 'SUCCESS' ? 'text-fg-3' : 'text-danger'}`}>
            {l.status === 'SUCCESS' ? `${l.syncedCount}개 자산` : (l.errorMessage ?? '-')}
          </span>
        </div>
      ))}
    </div>
  )
}

function AccountRow({
  row, expanded, onToggle, onSync, syncing, syncDisabled,
}: {
  row: AccountSyncStatus
  expanded: boolean
  onToggle: () => void
  onSync: () => void
  syncing: boolean
  syncDisabled: boolean
}) {
  const lastLog = row.lastLog
  return (
    <div className="border-b border-line-hair py-3.5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-baseline gap-2.5">
            <h3 className="m-0 truncate text-[13.5px] font-medium">{row.accountName}</h3>
            <Label size="sm" tone="ghost">{row.provider}</Label>
            <Badge variant={STATUS_BADGE[row.status] ?? 'muted'}>
              {syncing ? '동기화 중…' : (STATUS_KO[row.status] ?? row.status)}
            </Badge>
          </div>
          <div className="mt-1.5 flex flex-wrap gap-x-4 gap-y-1 text-xs text-fg-faint">
            <span>
              최종 동기화 <span className="font-mono text-[11px] tnum">{fmt(row.lastSyncedAt)}</span>
            </span>
            {lastLog && (
              <span className={lastLog.status === 'ERROR' ? 'text-danger' : undefined}>
                최근 결과:{' '}
                {lastLog.status === 'SUCCESS'
                  ? `${lastLog.syncedCount}개 자산 (${TRIGGER_KO[lastLog.trigger]})`
                  : `실패 — ${lastLog.errorMessage ?? '사유 미상'} (${TRIGGER_KO[lastLog.trigger]})`}
              </span>
            )}
          </div>
        </div>

        <div className="flex shrink-0 items-center gap-2">
          {row.syncable && (
            <Button size="sm" onClick={onSync} disabled={syncDisabled}>
              {syncing ? '동기화 중' : '재동기화'}
            </Button>
          )}
          <Button size="sm" variant="ghost" onClick={onToggle}>
            {expanded ? '이력 닫기' : '이력'}
          </Button>
        </div>
      </div>
      {expanded && <SyncHistory accountId={row.accountId} />}
    </div>
  )
}

export default function SyncStatusPage() {
  const api = useUnifiedApi()
  const qc = useQueryClient()
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const [syncingId, setSyncingId] = useState<string | null>(null)

  const { data: rows = [], isLoading } = useQuery({
    queryKey: ['unified', 'sync-status'],
    queryFn:  () => api!.accounts.syncStatus(),
    enabled:  !!api,
  })

  const handleSync = async (id: string) => {
    if (!api) return
    setSyncingId(id)
    try {
      await api.accounts.sync(id)
      qc.invalidateQueries({ queryKey: ['unified', 'sync-status'] })
      qc.invalidateQueries({ queryKey: ['unified', 'sync-logs', id] })
      qc.invalidateQueries({ queryKey: ['unified', 'accounts'] })
      qc.invalidateQueries({ queryKey: ['unified', 'portfolio'] })
    } finally {
      setSyncingId(null)
    }
  }

  const syncable = rows.filter(r => r.syncable)
  // 실패 계좌를 상단으로
  const sorted = [...syncable].sort(
    (a, b) => Number(b.status === 'ERROR') - Number(a.status === 'ERROR'),
  )
  const nonSyncable = rows.filter(r => !r.syncable)
  const erroredCount = syncable.filter(r => r.status === 'ERROR').length

  return (
    <div className="border border-line-card bg-surface">
      <PageHeader
        className="px-5 pt-5 sm:px-7"
        title="동기화 상태"
        meta="계좌별 동기화 이력과 실패 사유"
        actions={
          <Link
            href="/unified/accounts"
            className="border border-line bg-surface px-3.5 py-2 text-[12.5px] text-fg-2 transition-colors hover:border-ink hover:text-ink"
          >
            계좌 관리
          </Link>
        }
      />

      <div className="px-5 py-5 pb-10 sm:px-7">
        {erroredCount > 0 && (
          <div className="mb-5 flex items-center gap-3 border border-warn-line bg-warn-bg px-4 py-2.5">
            <Label size="sm" className="text-warn">주의</Label>
            <span className="text-[12.5px] text-fg-2">
              {erroredCount}개 계좌가 동기화 실패 상태입니다
            </span>
          </div>
        )}

        {isLoading ? (
          <LoadingState label="동기화 상태 불러오는 중" />
        ) : rows.length === 0 ? (
          <EmptyState title="등록된 계좌가 없습니다" />
        ) : (
          <>
            <div className="border-t-[1.5px] border-ink">
              {sorted.map(row => (
                <AccountRow
                  key={row.accountId}
                  row={row}
                  expanded={expandedId === row.accountId}
                  onToggle={() => setExpandedId(prev => (prev === row.accountId ? null : row.accountId))}
                  onSync={() => handleSync(row.accountId)}
                  syncing={syncingId === row.accountId}
                  syncDisabled={syncingId !== null}
                />
              ))}
            </div>

            {nonSyncable.length > 0 && (
              <div className="mt-8">
                <SectionHeader label="자동 동기화 대상 아님" note="수동 관리 계좌" />
                <div className="border-t border-line">
                  {nonSyncable.map(row => (
                    <div key={row.accountId} className="flex flex-wrap items-baseline gap-2.5 border-b border-line-hair py-2.5">
                      <span className="text-[13px] text-fg-2">{row.accountName}</span>
                      <Label size="sm" tone="ghost">{row.provider}</Label>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </>
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
