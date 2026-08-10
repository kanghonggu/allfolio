'use client'

import { useRef } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useUnifiedApi } from './useApi'
import type { AccountSyncStatus } from '@/types/unified'

/** 자동 동기화가 도는 동안의 폴링 주기 — 시세 조회를 포함해 보통 수 초 걸린다. */
const SYNCING_POLL_MS = 2_000
/** 서버 쪽이 SYNCING에서 멈춘 경우를 대비한 상한 (약 2분). */
const MAX_POLLS = 60

export const syncStatusQueryKey = ['dashboard', 'sync-status'] as const

/**
 * 계좌별 동기화 상태. AF-90에서 거래 저장·삭제, 계좌 생성이 백엔드에서 자동으로
 * 동기화를 걸게 되면서, 화면이 "반영 중"을 표시하고 완료를 감지할 수 있어야 한다.
 * SYNCING인 계좌가 하나라도 있으면 끝날 때까지만 짧게 폴링한다.
 */
export function useSyncStatus() {
  const api = useUnifiedApi()
  const polls = useRef(0)

  const { data } = useQuery<AccountSyncStatus[]>({
    queryKey: syncStatusQueryKey,
    queryFn:  () => api!.accounts.syncStatus(),
    enabled:  !!api,
    staleTime: 60_000,
    // 탭이 가려져 있어도 계속 확인한다 — 돌아왔을 때 이미 최신이어야 한다
    refetchIntervalInBackground: true,
    refetchInterval: (query) => {
      const syncing = (query.state.data ?? []).some((s) => s.status === 'SYNCING')
      if (!syncing) {
        polls.current = 0
        return false
      }
      if (polls.current >= MAX_POLLS) return false
      polls.current += 1
      return SYNCING_POLL_MS
    },
  })

  const statuses = data ?? []
  return {
    statuses,
    /** 하나라도 동기화 중 — 대시보드 숫자가 아직 확정 전이라는 뜻 */
    syncing: statuses.some((s) => s.status === 'SYNCING'),
    isSyncing: (accountId: string) =>
      statuses.some((s) => s.accountId === accountId && s.status === 'SYNCING'),
  }
}
