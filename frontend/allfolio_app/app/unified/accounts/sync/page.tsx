'use client'

import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import Link from 'next/link'
import { useUnifiedApi } from '@/lib/useApi'
import type { AccountSyncStatus, SyncLogView } from '@/types/unified'

const STATUS_STYLE: Record<string, string> = {
  ACTIVE:   'bg-emerald-900/40 text-emerald-400 border-emerald-800',
  SYNCING:  'bg-yellow-900/40 text-yellow-400 border-yellow-800',
  ERROR:    'bg-red-900/40 text-red-400 border-red-800',
  INACTIVE: 'bg-gray-800 text-gray-500 border-gray-700',
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

  if (isLoading) return <div className="py-3 text-xs text-gray-500">이력 불러오는 중…</div>
  if (logs.length === 0) return <div className="py-3 text-xs text-gray-500">동기화 이력이 없습니다</div>

  return (
    <table className="mt-3 w-full text-xs">
      <thead>
        <tr className="text-left text-gray-500">
          <th className="py-1 pr-4 font-medium">시각</th>
          <th className="py-1 pr-4 font-medium">트리거</th>
          <th className="py-1 pr-4 font-medium">결과</th>
          <th className="py-1 font-medium">상세</th>
        </tr>
      </thead>
      <tbody>
        {logs.map((l: SyncLogView) => (
          <tr key={l.id} className="border-t border-gray-800">
            <td className="py-1.5 pr-4 text-gray-400">{fmt(l.createdAt)}</td>
            <td className="py-1.5 pr-4 text-gray-400">{TRIGGER_KO[l.trigger]}</td>
            <td className={`py-1.5 pr-4 ${l.status === 'SUCCESS' ? 'text-emerald-400' : 'text-red-400'}`}>
              {l.status === 'SUCCESS' ? '성공' : '실패'}
            </td>
            <td className="py-1.5 text-gray-400">
              {l.status === 'SUCCESS' ? `${l.syncedCount}개 자산` : (l.errorMessage ?? '-')}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
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
    <div className="rounded-xl border border-gray-700 bg-gray-900 p-4">
      <div className="flex items-start justify-between gap-4">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <h3 className="font-semibold truncate">{row.accountName}</h3>
            <span className="text-xs text-gray-500">{row.provider}</span>
            <span className={`rounded-full border px-2 py-0.5 text-xs font-medium ${STATUS_STYLE[row.status] ?? STATUS_STYLE.INACTIVE}`}>
              {syncing ? '동기화 중…' : (STATUS_KO[row.status] ?? row.status)}
            </span>
          </div>
          <div className="mt-1.5 flex flex-wrap gap-x-4 gap-y-1 text-xs text-gray-500">
            <span>마지막 동기화: {fmt(row.lastSyncedAt)}</span>
            {lastLog && (
              <span className={lastLog.status === 'ERROR' ? 'text-red-400' : 'text-gray-500'}>
                마지막 결과:{' '}
                {lastLog.status === 'SUCCESS'
                  ? `${lastLog.syncedCount}개 자산 (${TRIGGER_KO[lastLog.trigger]})`
                  : `실패 — ${lastLog.errorMessage ?? '사유 미상'} (${TRIGGER_KO[lastLog.trigger]})`}
              </span>
            )}
          </div>
        </div>

        <div className="flex shrink-0 items-center gap-2">
          {row.syncable && (
            <button
              onClick={onSync}
              disabled={syncDisabled}
              className="rounded-lg border border-gray-600 px-3 py-1.5 text-xs font-medium hover:border-blue-500 hover:text-blue-400 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              {syncing ? '⟳ 동기화 중' : '↻ 재동기화'}
            </button>
          )}
          <button
            onClick={onToggle}
            className="rounded-lg border border-gray-600 px-3 py-1.5 text-xs font-medium hover:border-gray-400 transition-colors"
          >
            {expanded ? '이력 닫기' : '이력'}
          </button>
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
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">동기화 상태</h1>
          <p className="mt-1 text-sm text-gray-400">계좌별 동기화 이력과 실패 사유를 확인합니다</p>
        </div>
        <Link
          href="/unified/accounts"
          className="rounded-lg border border-gray-600 px-4 py-2 text-sm font-medium hover:border-gray-400 transition-colors"
        >
          계좌 관리
        </Link>
      </div>

      {erroredCount > 0 && (
        <div className="rounded-xl border border-red-800 bg-red-900/20 px-4 py-3 text-sm text-red-400">
          ⚠ {erroredCount}개 계좌가 동기화 실패 상태입니다
        </div>
      )}

      {isLoading ? (
        <div className="space-y-3">
          {[1, 2, 3].map(i => (
            <div key={i} className="h-20 animate-pulse rounded-xl bg-gray-800" />
          ))}
        </div>
      ) : rows.length === 0 ? (
        <div className="rounded-xl border border-dashed border-gray-700 p-12 text-center">
          <p className="text-gray-400">등록된 계좌가 없습니다</p>
        </div>
      ) : (
        <>
          <div className="space-y-3">
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
            <div className="space-y-3">
              <h2 className="text-sm font-medium text-gray-500">자동 동기화 대상 아님</h2>
              {nonSyncable.map(row => (
                <div key={row.accountId} className="rounded-xl border border-gray-800 bg-gray-900/60 px-4 py-3">
                  <div className="flex items-center gap-2 flex-wrap text-sm">
                    <span className="font-medium text-gray-400">{row.accountName}</span>
                    <span className="text-xs text-gray-600">{row.provider}</span>
                    <span className="text-xs text-gray-600">수동 관리 계좌</span>
                  </div>
                </div>
              ))}
            </div>
          )}
        </>
      )}

      <div className="flex gap-3">
        <Link href="/unified" className="text-sm text-gray-400 hover:text-white transition-colors">
          ← 대시보드로
        </Link>
      </div>
    </div>
  )
}
