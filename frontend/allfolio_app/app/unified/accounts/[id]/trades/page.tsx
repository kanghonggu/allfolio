'use client'

import { useState, useMemo } from 'react'
import { useParams } from 'next/navigation'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import Link from 'next/link'
import { useUnifiedApi } from '@/lib/useApi'
import type { StockTrade, StockTradeType, CreateStockTradePayload } from '@/types/unified'

// ── 거래 유형 설정 ─────────────────────────────────────────────

const TRADE_TYPE_CONFIG: Record<StockTradeType, { label: string; color: string; bg: string }> = {
  BUY:         { label: '매수',     color: 'text-blue-400',    bg: 'bg-blue-900/40 border-blue-800' },
  SELL:        { label: '매도',     color: 'text-red-400',     bg: 'bg-red-900/40 border-red-800' },
  CREDIT_BUY:  { label: '신용매수', color: 'text-cyan-400',    bg: 'bg-cyan-900/40 border-cyan-800' },
  CREDIT_SELL: { label: '신용매도', color: 'text-orange-400',  bg: 'bg-orange-900/40 border-orange-800' },
  MARGIN:      { label: '미수',     color: 'text-yellow-400',  bg: 'bg-yellow-900/40 border-yellow-800' },
  DIVIDEND:    { label: '배당',     color: 'text-emerald-400', bg: 'bg-emerald-900/40 border-emerald-800' },
}

function fmt(n: number) {
  return new Intl.NumberFormat('ko-KR').format(Math.round(n))
}

function today() {
  return new Date().toISOString().slice(0, 10)
}

const EMPTY_FORM: CreateStockTradePayload = {
  tradeType:   'BUY',
  stockName:   '',
  symbol:      '',
  quantity:    0,
  price:       0,
  totalAmount: 0,
  fee:         0,
  tax:         0,
  tradedAt:    today(),
  memo:        '',
}

// ── Page ───────────────────────────────────────────────────────

export default function StockTradesPage() {
  const { id } = useParams<{ id: string }>()
  const qc  = useQueryClient()
  const api = useUnifiedApi()

  const [showForm, setShowForm]   = useState(false)
  const [form, setForm]           = useState<CreateStockTradePayload>(EMPTY_FORM)
  const [autoCalc, setAutoCalc]   = useState(true)  // 자동 금액 계산

  // 계좌 정보
  const { data: accounts = [] } = useQuery({
    queryKey: ['unified', 'accounts'],
    queryFn:  () => api!.accounts.list(),
    enabled:  !!api,
  })
  const account = accounts.find(a => a.id === id)

  // 거래내역
  const { data: trades = [], isLoading } = useQuery({
    queryKey: ['stock-trades', id],
    queryFn:  () => api!.stockTrades.list(id),
    enabled:  !!api,
  })

  const addMutation = useMutation({
    mutationFn: (payload: CreateStockTradePayload) => api!.stockTrades.create(id, payload),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['stock-trades', id] })
      setForm(EMPTY_FORM)
      setShowForm(false)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (tradeId: string) => api!.stockTrades.delete(id, tradeId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['stock-trades', id] }),
  })

  // 요약 통계
  const summary = useMemo(() => {
    const buyTotal  = trades.filter(t => t.tradeType === 'BUY' || t.tradeType === 'CREDIT_BUY').reduce((s, t) => s + t.totalAmount, 0)
    const sellTotal = trades.filter(t => t.tradeType === 'SELL' || t.tradeType === 'CREDIT_SELL').reduce((s, t) => s + t.totalAmount, 0)
    const dividend  = trades.filter(t => t.tradeType === 'DIVIDEND').reduce((s, t) => s + t.totalAmount, 0)
    const fees      = trades.reduce((s, t) => s + t.fee + t.tax, 0)
    return { buyTotal, sellTotal, dividend, fees, netInvested: buyTotal - sellTotal }
  }, [trades])

  const set = (k: keyof CreateStockTradePayload, v: string | number) => {
    setForm(prev => {
      const next = { ...prev, [k]: v }
      // 자동 금액 계산: 수량 × 단가
      if (autoCalc && (k === 'quantity' || k === 'price')) {
        const qty   = k === 'quantity' ? Number(v) : prev.quantity
        const price = k === 'price'    ? Number(v) : prev.price
        next.totalAmount = Math.round(qty * price)
      }
      return next
    })
  }

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    addMutation.mutate({
      ...form,
      quantity:    Number(form.quantity),
      price:       Number(form.price),
      totalAmount: Number(form.totalAmount),
      fee:         Number(form.fee),
      tax:         Number(form.tax),
    })
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-start justify-between gap-4">
        <div>
          <Link href={`/unified/accounts/${id}`} className="mb-2 inline-block text-xs text-gray-500 hover:text-gray-300">
            ← 계좌 상세
          </Link>
          <h1 className="text-2xl font-bold">거래내역</h1>
          <p className="mt-0.5 text-sm text-gray-400">
            {account ? `${account.brokerage ?? ''} ${account.accountName}` : ''}
          </p>
        </div>
        <button
          onClick={() => setShowForm(v => !v)}
          className="shrink-0 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium hover:bg-blue-500 transition-colors"
        >
          {showForm ? '취소' : '+ 거래 추가'}
        </button>
      </div>

      {/* 요약 카드 */}
      {trades.length > 0 && (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <SummaryCard label="총 매수금액" value={`₩${fmt(summary.buyTotal)}`} color="text-blue-400" />
          <SummaryCard label="총 매도금액" value={`₩${fmt(summary.sellTotal)}`} color="text-red-400" />
          <SummaryCard label="순 투자금액" value={`₩${fmt(summary.netInvested)}`} color="text-white" />
          <SummaryCard label="배당 + 수수료" value={`₩${fmt(summary.dividend)} / ₩${fmt(summary.fees)}`} color="text-emerald-400" />
        </div>
      )}

      {/* 거래 추가 폼 */}
      {showForm && (
        <form
          onSubmit={handleSubmit}
          className="rounded-xl border border-blue-800 bg-gray-900 p-6 space-y-4"
        >
          <h2 className="text-sm font-semibold text-blue-400">새 거래 추가</h2>

          {/* 거래 유형 선택 */}
          <div>
            <label className="mb-2 block text-xs text-gray-400">거래 유형 *</label>
            <div className="flex flex-wrap gap-2">
              {(Object.entries(TRADE_TYPE_CONFIG) as [StockTradeType, typeof TRADE_TYPE_CONFIG[StockTradeType]][]).map(([type, cfg]) => (
                <button
                  key={type}
                  type="button"
                  onClick={() => set('tradeType', type)}
                  className={`rounded-full border px-3 py-1 text-xs font-medium transition-all ${
                    form.tradeType === type
                      ? cfg.bg + ' ' + cfg.color
                      : 'border-gray-700 text-gray-400 hover:border-gray-500'
                  }`}
                >
                  {cfg.label}
                </button>
              ))}
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div className="col-span-2 grid grid-cols-3 gap-3">
              <div className="col-span-2">
                <label className="mb-1 block text-xs text-gray-400">종목명 *</label>
                <input required type="text" placeholder="예: 삼성전자"
                  value={form.stockName} onChange={e => set('stockName', e.target.value)}
                  className={inputCls} />
              </div>
              <div>
                <label className="mb-1 block text-xs text-gray-400">종목코드</label>
                <input type="text" placeholder="005930"
                  value={form.symbol ?? ''} onChange={e => set('symbol', e.target.value)}
                  className={inputCls} />
              </div>
            </div>

            <div>
              <label className="mb-1 block text-xs text-gray-400">체결일 *</label>
              <input required type="date" value={form.tradedAt}
                onChange={e => set('tradedAt', e.target.value)} className={inputCls} />
            </div>

            <div>
              <label className="mb-1 block text-xs text-gray-400">
                수량 {form.tradeType === 'DIVIDEND' ? '(선택)' : '*'}
              </label>
              <input
                type="number" step="any" min="0"
                required={form.tradeType !== 'DIVIDEND'}
                value={form.quantity || ''}
                onChange={e => set('quantity', e.target.value)}
                className={inputCls}
              />
            </div>

            <div>
              <label className="mb-1 block text-xs text-gray-400">
                단가 (원) {form.tradeType === 'DIVIDEND' ? '(선택)' : '*'}
              </label>
              <input
                type="number" step="any" min="0"
                required={form.tradeType !== 'DIVIDEND'}
                value={form.price || ''}
                onChange={e => set('price', e.target.value)}
                className={inputCls}
              />
            </div>

            <div>
              <div className="flex items-center justify-between mb-1">
                <label className="text-xs text-gray-400">거래금액 (원) *</label>
                <button
                  type="button"
                  onClick={() => setAutoCalc(v => !v)}
                  className={`text-[10px] px-1.5 py-0.5 rounded transition-colors ${
                    autoCalc ? 'text-blue-400 bg-blue-900/30' : 'text-gray-500 bg-gray-800'
                  }`}
                >
                  {autoCalc ? '자동계산 ON' : '수동'}
                </button>
              </div>
              <input
                required type="number" step="any" min="0"
                value={form.totalAmount || ''}
                onChange={e => set('totalAmount', e.target.value)}
                readOnly={autoCalc && form.tradeType !== 'DIVIDEND'}
                className={`${inputCls} ${autoCalc && form.tradeType !== 'DIVIDEND' ? 'bg-gray-700 cursor-not-allowed' : ''}`}
              />
            </div>

            <div>
              <label className="mb-1 block text-xs text-gray-400">수수료 (원)</label>
              <input type="number" step="any" min="0"
                value={form.fee || ''} onChange={e => set('fee', e.target.value)}
                className={inputCls} />
            </div>

            <div>
              <label className="mb-1 block text-xs text-gray-400">세금 (원)</label>
              <input type="number" step="any" min="0"
                value={form.tax || ''} onChange={e => set('tax', e.target.value)}
                className={inputCls} />
            </div>

            <div className="col-span-2">
              <label className="mb-1 block text-xs text-gray-400">메모</label>
              <input type="text" placeholder="선택 사항"
                value={form.memo ?? ''} onChange={e => set('memo', e.target.value)}
                className={inputCls} />
            </div>
          </div>

          {addMutation.isError && (
            <p className="text-xs text-red-400">{(addMutation.error as Error).message}</p>
          )}

          <div className="flex gap-3">
            <button
              type="submit"
              disabled={addMutation.isPending}
              className="rounded-lg bg-blue-600 px-5 py-2 text-sm font-medium hover:bg-blue-500 disabled:opacity-50 transition-colors"
            >
              {addMutation.isPending ? '저장 중…' : '거래 저장'}
            </button>
            <button
              type="button"
              onClick={() => { setShowForm(false); setForm(EMPTY_FORM) }}
              className="rounded-lg border border-gray-600 px-4 py-2 text-sm hover:border-gray-400 transition-colors"
            >
              취소
            </button>
          </div>
        </form>
      )}

      {/* 거래내역 목록 */}
      <div className="rounded-xl border border-gray-700 bg-gray-900">
        <div className="flex items-center justify-between px-6 py-4 border-b border-gray-700">
          <h2 className="text-sm font-semibold text-gray-300">거래 로그</h2>
          <span className="text-xs text-gray-500">{trades.length}건</span>
        </div>

        {isLoading ? (
          <div className="space-y-2 p-4">
            {[1, 2, 3].map(i => <div key={i} className="h-14 animate-pulse rounded bg-gray-800" />)}
          </div>
        ) : trades.length === 0 ? (
          <div className="py-12 text-center text-sm text-gray-500">
            거래내역이 없습니다.{' '}
            <button onClick={() => setShowForm(true)} className="text-blue-400 hover:underline">
              첫 거래를 추가하세요.
            </button>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs text-gray-500 border-b border-gray-800">
                  <th className="px-4 py-3 font-medium">체결일</th>
                  <th className="px-4 py-3 font-medium">유형</th>
                  <th className="px-4 py-3 font-medium">종목</th>
                  <th className="px-4 py-3 text-right font-medium">수량</th>
                  <th className="px-4 py-3 text-right font-medium">단가</th>
                  <th className="px-4 py-3 text-right font-medium">거래금액</th>
                  <th className="px-4 py-3 text-right font-medium">수수료+세금</th>
                  <th className="px-4 py-3 font-medium">메모</th>
                  <th className="px-3 py-3" />
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-800">
                {trades.map((t: StockTrade) => {
                  const cfg = TRADE_TYPE_CONFIG[t.tradeType as StockTradeType]
                  return (
                    <tr key={t.id} className="hover:bg-gray-800/40 transition-colors">
                      <td className="px-4 py-3 text-xs text-gray-400 tabular-nums whitespace-nowrap">
                        {t.tradedAt}
                      </td>
                      <td className="px-4 py-3">
                        <span className={`rounded-full border px-2 py-0.5 text-xs font-medium ${cfg.bg} ${cfg.color}`}>
                          {cfg.label}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <div className="font-medium">{t.stockName}</div>
                        {t.symbol && <div className="text-xs text-gray-500">{t.symbol}</div>}
                      </td>
                      <td className="px-4 py-3 text-right tabular-nums text-gray-300">
                        {t.quantity > 0 ? t.quantity.toLocaleString('ko-KR') : '—'}
                      </td>
                      <td className="px-4 py-3 text-right tabular-nums text-gray-300">
                        {t.price > 0 ? `₩${fmt(t.price)}` : '—'}
                      </td>
                      <td className={`px-4 py-3 text-right tabular-nums font-medium ${cfg.color}`}>
                        ₩{fmt(t.totalAmount)}
                      </td>
                      <td className="px-4 py-3 text-right tabular-nums text-xs text-gray-500">
                        {t.fee + t.tax > 0 ? `₩${fmt(t.fee + t.tax)}` : '—'}
                      </td>
                      <td className="px-4 py-3 text-xs text-gray-500 max-w-[120px] truncate">
                        {t.memo ?? ''}
                      </td>
                      <td className="px-3 py-3">
                        <button
                          onClick={() => {
                            if (confirm('이 거래내역을 삭제하시겠습니까?'))
                              deleteMutation.mutate(t.id)
                          }}
                          className="text-xs text-gray-600 hover:text-red-400 transition-colors"
                        >
                          삭제
                        </button>
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

function SummaryCard({ label, value, color }: { label: string; value: string; color: string }) {
  return (
    <div className="rounded-xl border border-gray-700 bg-gray-900 p-4">
      <p className="text-xs text-gray-500">{label}</p>
      <p className={`mt-1 text-sm font-bold tabular-nums ${color}`}>{value}</p>
    </div>
  )
}

const inputCls = 'w-full rounded-lg border border-gray-700 bg-gray-800 px-3 py-2 text-sm text-white placeholder-gray-500 focus:border-blue-500 focus:outline-none transition-colors'
