'use client'

import { useEffect, useRef, useState, useCallback } from 'react'

export interface PriceUpdate {
  exchange: string
  symbol: string
  price: string
  timestamp: number
}

// exchange+symbol → 최신 가격 맵
export type PriceMap = Map<string, string>

const BACKEND_URL     = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'
const RECONNECT_DELAY = 3000

function priceKey(exchange: string, symbol: string) {
  return `${exchange}:${symbol}`
}

/**
 * 실시간 가격 SSE 훅
 *
 * GET /api/sse/prices → price 이벤트 수신
 * prices: Map<"EXCHANGE:SYMBOL", price문자열>
 *
 * 사용 예)
 *   const { prices } = useLivePrices()
 *   const btcPrice = prices.get("BINANCE:BTCUSDT")
 */
export function useLivePrices() {
  const [prices, setPrices]       = useState<PriceMap>(new Map())
  const [connected, setConnected] = useState(false)

  const esRef        = useRef<EventSource | null>(null)
  const reconnectRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const mountedRef   = useRef(true)

  const connect = useCallback(() => {
    if (!mountedRef.current) return
    esRef.current?.close()

    const es = new EventSource(`${BACKEND_URL}/api/sse/prices`)

    es.addEventListener('connected', () => {
      if (!mountedRef.current) return
      setConnected(true)
    })

    es.addEventListener('price', (e: MessageEvent) => {
      if (!mountedRef.current) return
      try {
        const update: PriceUpdate = JSON.parse(e.data)
        setPrices(prev => {
          const next = new Map(prev)
          next.set(priceKey(update.exchange, update.symbol), update.price)
          return next
        })
      } catch { /* ignore */ }
    })

    es.onerror = () => {
      if (!mountedRef.current) return
      es.close()
      setConnected(false)
      reconnectRef.current = setTimeout(() => {
        if (mountedRef.current) connect()
      }, RECONNECT_DELAY)
    }

    esRef.current = es
  }, [])

  useEffect(() => {
    mountedRef.current = true
    connect()
    return () => {
      mountedRef.current = false
      esRef.current?.close()
      if (reconnectRef.current) clearTimeout(reconnectRef.current)
    }
  }, [connect])

  return { prices, connected }
}
