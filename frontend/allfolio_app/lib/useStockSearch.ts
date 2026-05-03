'use client'

import { useState, useEffect, useRef } from 'react'
import { searchStocks, type StockInfo } from './stockLookup'

const BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'

async function fetchStockSearch(q: string, token: string): Promise<StockInfo[]> {
  const res = await fetch(
    `${BASE_URL}/api/unified/stocks/search?q=${encodeURIComponent(q)}`,
    { headers: { Authorization: `Bearer ${token}` } },
  )
  if (!res.ok) throw new Error('search failed')
  const data: { symbol: string; name: string; exchange: string; type: string }[] = await res.json()
  return data.map(d => ({ symbol: d.symbol, name: d.name, market: d.exchange }))
}

export function useStockSearch(accessToken: string | null) {
  const [query, setQuery]           = useState('')
  const [results, setResults]       = useState<StockInfo[]>([])
  const [loading, setLoading]       = useState(false)
  const debounceRef                 = useRef<ReturnType<typeof setTimeout> | null>(null)
  const abortRef                    = useRef<AbortController | null>(null)

  useEffect(() => {
    if (debounceRef.current) clearTimeout(debounceRef.current)
    if (!query || query.length < 1) {
      setResults([])
      return
    }

    // 로컬 룩업 먼저 즉시 표시
    setResults(searchStocks(query))

    debounceRef.current = setTimeout(async () => {
      if (!accessToken) return
      abortRef.current?.abort()
      abortRef.current = new AbortController()
      setLoading(true)
      try {
        const apiResults = await fetchStockSearch(query, accessToken)
        setResults(apiResults.length > 0 ? apiResults : searchStocks(query))
      } catch {
        // API 실패 시 로컬 룩업 유지
      } finally {
        setLoading(false)
      }
    }, 350)

    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current)
    }
  }, [query, accessToken])

  return { query, setQuery, results, loading }
}
