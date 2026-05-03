'use client'

import { useEffect, useRef, useState, useCallback } from 'react'
import { useAuth } from '@/contexts/AuthContext'

export interface PnlUpdate {
  portfolioId: string
  assetId: string
  symbol: string
  quantity: number
  avgCost: number
  currentPrice: number
  unrealizedPnl: number
  unrealizedPnlPct: number
  timestamp: number
}

export interface PnlStreamState {
  connected: boolean
  updates: Map<string, PnlUpdate>  // assetId → 최신 PnL
  error: string | null
}

const BACKEND_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'
const RECONNECT_DELAY_MS = 3000

/**
 * 실시간 PnL SSE 훅
 *
 * 연결: GET /api/sse/pnl/{portfolioId}  (EventSource)
 * 이벤트:
 *   connected   초기 포지션 스냅샷 수신
 *   pnl_update  자산별 실시간 PnL 업데이트
 *   heartbeat   keepalive (무시)
 *
 * 자동 재연결: 연결 끊김 시 3초 후 재시도
 * 언마운트 시: EventSource 자동 close
 */
export function usePnlStream(portfolioId: string | null | undefined) {
  const { accessToken } = useAuth()
  const [state, setState] = useState<PnlStreamState>({
    connected: false,
    updates: new Map(),
    error: null,
  })

  const esRef         = useRef<EventSource | null>(null)
  const reconnectRef  = useRef<ReturnType<typeof setTimeout> | null>(null)
  const mountedRef    = useRef(true)

  const connect = useCallback(() => {
    if (!portfolioId || !accessToken || !mountedRef.current) return

    esRef.current?.close()

    // EventSource는 커스텀 헤더 지원 안 함
    // → 백엔드에서 쿼리 파라미터 토큰 지원 필요 또는 쿠키 인증으로 전환
    // 현재: X-User-Id는 JwtUserIdFilter가 Authorization Bearer에서 추출하므로
    //       토큰을 쿼리 파라미터로 전달
    const url = `${BACKEND_URL}/api/sse/pnl/${portfolioId}?token=${encodeURIComponent(accessToken)}`
    const es  = new EventSource(url)

    es.addEventListener('connected', (e: MessageEvent) => {
      if (!mountedRef.current) return
      setState(prev => ({ ...prev, connected: true, error: null }))
    })

    es.addEventListener('pnl_update', (e: MessageEvent) => {
      if (!mountedRef.current) return
      try {
        const update: PnlUpdate = JSON.parse(e.data)
        setState(prev => {
          const next = new Map(prev.updates)
          next.set(update.assetId, update)
          return { ...prev, updates: next }
        })
      } catch { /* ignore parse error */ }
    })

    es.onerror = () => {
      if (!mountedRef.current) return
      es.close()
      setState(prev => ({ ...prev, connected: false }))
      // 자동 재연결
      reconnectRef.current = setTimeout(() => {
        if (mountedRef.current) connect()
      }, RECONNECT_DELAY_MS)
    }

    esRef.current = es
  }, [portfolioId, accessToken])

  useEffect(() => {
    mountedRef.current = true
    connect()
    return () => {
      mountedRef.current = false
      esRef.current?.close()
      if (reconnectRef.current) clearTimeout(reconnectRef.current)
    }
  }, [connect])

  return state
}
