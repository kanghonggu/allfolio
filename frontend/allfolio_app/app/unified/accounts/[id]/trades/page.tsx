'use client'

import { useState, useMemo, useRef, useEffect } from 'react'
import { useParams } from 'next/navigation'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import Link from 'next/link'
import { useUnifiedApi } from '@/lib/useApi'
import { useAuth } from '@/contexts/AuthContext'
import PageHeader from '@/components/ui/PageHeader'
import SectionHeader from '@/components/ui/SectionHeader'
import Button from '@/components/ui/Button'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import Field, { Input } from '@/components/ui/Field'
import { EmptyState, LoadingState } from '@/components/ui/states'
import type { StockTrade, StockTradeType, CreateStockTradePayload } from '@/types/unified'
import { useStockSearch } from '@/lib/useStockSearch'

// ── 거래 유형 설정 ─────────────────────────────────────────────
// 색은 국내 HTS 관례: 매수 계열 빨강 / 매도 계열 파랑 / 미수 경고 / 배당 녹색

const TRADE_TYPE_CONFIG: Record<StockTradeType, { label: string; color: string }> = {
  BUY:         { label: '매수',     color: 'text-gain' },
  SELL:        { label: '매도',     color: 'text-loss' },
  CREDIT_BUY:  { label: '신용매수', color: 'text-gain' },
  CREDIT_SELL: { label: '신용매도', color: 'text-loss' },
  MARGIN:      { label: '미수',     color: 'text-warn' },
  DIVIDEND:    { label: '배당',     color: 'text-ok' },
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

const TABLE_GRID = 'grid grid-cols-[0.9fr_0.6fr_1.6fr_0.7fr_0.8fr_1fr_0.9fr_1fr_0.4fr] gap-3'

// ── Page ───────────────────────────────────────────────────────

export default function StockTradesPage() {
  const { id } = useParams<{ id: string }>()
  const qc  = useQueryClient()
  const api = useUnifiedApi()

  const { accessToken } = useAuth()
  const [showForm, setShowForm] = useState(false)
  const [form, setForm]         = useState<CreateStockTradePayload>(EMPTY_FORM)
  const [autoCalc, setAutoCalc] = useState(true)
  const [showSuggest, setShowSuggest] = useState(false)
  const suggestRef = useRef<HTMLDivElement>(null)

  const { query: stockQuery, setQuery: setStockQuery, results: suggestions, loading: searchLoading } =
    useStockSearch(accessToken ?? null)

  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (suggestRef.current && !suggestRef.current.contains(e.target as Node))
        setShowSuggest(false)
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [])

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
    <div className="border border-line-card bg-surface">
      <div className="px-5 pt-4 sm:px-7">
        <Link
          href={`/unified/accounts/${id}`}
          className="font-mono text-[10px] tracking-label text-fg-faint transition-colors hover:text-ink"
        >
          ← 계좌 상세
        </Link>
      </div>
      <PageHeader
        className="px-5 pt-2 sm:px-7"
        title="거래내역"
        meta={account ? `${account.brokerage ?? ''} ${account.accountName}`.trim() : undefined}
        actions={
          <Button variant={showForm ? 'outline' : 'primary'} onClick={() => setShowForm(v => !v)}>
            {showForm ? '취소' : '거래 추가'}
          </Button>
        }
      />

      <div className="px-5 py-5 pb-10 sm:px-7">
        {/* 요약 */}
        {trades.length > 0 && (
          <div className="mb-6 grid grid-cols-2 gap-px border border-line-soft bg-line-soft sm:grid-cols-4">
            <SummaryTile label="총 매수금액" value={`₩${fmt(summary.buyTotal)}`} color="text-gain" />
            <SummaryTile label="총 매도금액" value={`₩${fmt(summary.sellTotal)}`} color="text-loss" />
            <SummaryTile label="순 투자금액" value={`₩${fmt(summary.netInvested)}`} color="text-ink" />
            <SummaryTile label="배당 / 수수료·세금" value={`₩${fmt(summary.dividend)} / ₩${fmt(summary.fees)}`} color="text-ok" />
          </div>
        )}

        {/* 거래 추가 폼 */}
        {showForm && (
          <form onSubmit={handleSubmit} className="mb-6 border border-ink bg-surface-muted p-5 sm:p-6">
            <SectionHeader label="새 거래 추가" />

            {/* 거래 유형 선택 */}
            <div className="mb-4">
              <span className="mb-2 block font-mono text-[10px] tracking-label text-fg-muted">거래 유형 *</span>
              <div className="flex flex-wrap gap-1.5">
                {(Object.entries(TRADE_TYPE_CONFIG) as [StockTradeType, typeof TRADE_TYPE_CONFIG[StockTradeType]][]).map(([type, cfg]) => (
                  <button
                    key={type}
                    type="button"
                    onClick={() => set('tradeType', type)}
                    aria-pressed={form.tradeType === type}
                    className={`border px-3 py-1.5 text-xs transition-colors ${
                      form.tradeType === type
                        ? 'border-ink bg-ink text-white'
                        : 'border-line bg-surface text-fg-3 hover:border-ink hover:text-ink'
                    }`}
                  >
                    {cfg.label}
                  </button>
                ))}
              </div>
            </div>

            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <div className="sm:col-span-2 grid grid-cols-1 gap-4 sm:grid-cols-3">
                <div className="relative sm:col-span-2" ref={suggestRef}>
                  <label htmlFor="trade-stock-name" className="mb-1.5 block font-mono text-[10px] tracking-label text-fg-muted">
                    종목명 *
                    {searchLoading && <span className="ml-2 text-fg-ghost">검색 중…</span>}
                  </label>
                  <Input
                    id="trade-stock-name"
                    required type="text" placeholder="예: 삼성전자, HANARO 반도체"
                    value={stockQuery || form.stockName}
                    onChange={e => {
                      set('stockName', e.target.value)
                      setStockQuery(e.target.value)
                      setShowSuggest(true)
                    }}
                    onFocus={() => {
                      if (form.stockName) setStockQuery(form.stockName)
                      setShowSuggest(true)
                    }}
                    autoComplete="off"
                  />
                  {showSuggest && suggestions.length > 0 && (
                    <ul className="absolute z-10 mt-1 w-full overflow-hidden border border-ink bg-surface">
                      {suggestions.map(s => (
                        <li
                          key={s.symbol}
                          onMouseDown={() => {
                            setForm(prev => ({ ...prev, stockName: s.name, symbol: s.symbol }))
                            setStockQuery('')
                            setShowSuggest(false)
                          }}
                          className="flex cursor-pointer items-center justify-between border-b border-line-hair px-3 py-2 text-sm transition-colors last:border-b-0 hover:bg-surface-muted"
                        >
                          <span className="mr-2 truncate">{s.name}</span>
                          <Num className="shrink-0 text-xs text-fg-muted">
                            {s.symbol} <span className="text-fg-ghost">{s.market}</span>
                          </Num>
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
                <Field id="trade-symbol" label="종목코드">
                  <Input type="text" placeholder="005930"
                    value={form.symbol ?? ''} onChange={e => set('symbol', e.target.value)} />
                </Field>
              </div>

              <Field id="trade-date" label="체결일 *">
                <Input required type="date" value={form.tradedAt}
                  onChange={e => set('tradedAt', e.target.value)} />
              </Field>

              <Field id="trade-qty" label={`수량 ${form.tradeType === 'DIVIDEND' ? '(선택)' : '*'}`}>
                <Input
                  type="number" step="any" min="0"
                  required={form.tradeType !== 'DIVIDEND'}
                  value={form.quantity || ''}
                  onChange={e => set('quantity', e.target.value)}
                />
              </Field>

              <Field id="trade-price" label={`단가 (원) ${form.tradeType === 'DIVIDEND' ? '(선택)' : '*'}`}>
                <Input
                  type="number" step="any" min="0"
                  required={form.tradeType !== 'DIVIDEND'}
                  value={form.price || ''}
                  onChange={e => set('price', e.target.value)}
                />
              </Field>

              <div>
                <div className="mb-1.5 flex items-center justify-between">
                  <label htmlFor="trade-total" className="font-mono text-[10px] tracking-label text-fg-muted">
                    거래금액 (원) *
                  </label>
                  <button
                    type="button"
                    onClick={() => setAutoCalc(v => !v)}
                    className={`px-1.5 py-0.5 font-mono text-[9.5px] tracking-label transition-colors ${
                      autoCalc ? 'bg-ink text-white' : 'bg-line-soft text-fg-3'
                    }`}
                  >
                    {autoCalc ? '자동계산 ON' : '수동'}
                  </button>
                </div>
                <Input
                  id="trade-total"
                  required type="number" step="any" min="0"
                  value={form.totalAmount || ''}
                  onChange={e => set('totalAmount', e.target.value)}
                  readOnly={autoCalc && form.tradeType !== 'DIVIDEND'}
                  className={autoCalc && form.tradeType !== 'DIVIDEND' ? 'cursor-not-allowed bg-surface-muted' : ''}
                />
              </div>

              <Field id="trade-fee" label="수수료 (원)">
                <Input type="number" step="any" min="0"
                  value={form.fee || ''} onChange={e => set('fee', e.target.value)} />
              </Field>

              <Field id="trade-tax" label="세금 (원)">
                <Input type="number" step="any" min="0"
                  value={form.tax || ''} onChange={e => set('tax', e.target.value)} />
              </Field>

              <Field id="trade-memo" label="메모" className="sm:col-span-2">
                <Input type="text" placeholder="선택 사항"
                  value={form.memo ?? ''} onChange={e => set('memo', e.target.value)} />
              </Field>
            </div>

            {addMutation.isError && (
              <p role="alert" className="mt-4 text-xs text-danger">
                {(addMutation.error as Error).message}
              </p>
            )}

            <div className="mt-5 flex gap-2.5">
              <Button type="submit" variant="primary" disabled={addMutation.isPending}>
                {addMutation.isPending ? '저장 중…' : '거래 저장'}
              </Button>
              <Button type="button" onClick={() => { setShowForm(false); setForm(EMPTY_FORM) }}>
                취소
              </Button>
            </div>
          </form>
        )}

        {/* 거래내역 목록 */}
        <SectionHeader label="거래 로그" note={`${trades.length}건`} />
        {isLoading ? (
          <LoadingState label="거래내역 불러오는 중" />
        ) : trades.length === 0 ? (
          <EmptyState
            title="거래내역이 없습니다"
            action={
              <Button variant="primary" size="sm" onClick={() => setShowForm(true)}>
                첫 거래 추가
              </Button>
            }
          />
        ) : (
          <div className="overflow-x-auto">
            <div className="min-w-[860px] border-t-[1.5px] border-ink">
              <div className={`${TABLE_GRID} border-b border-line py-2`}>
                <Label size="sm" tone="faint">체결일</Label>
                <Label size="sm" tone="faint">유형</Label>
                <Label size="sm" tone="faint">종목</Label>
                <Label size="sm" tone="faint" className="text-right">수량</Label>
                <Label size="sm" tone="faint" className="text-right">단가</Label>
                <Label size="sm" tone="faint" className="text-right">거래금액</Label>
                <Label size="sm" tone="faint" className="text-right">수수료+세금</Label>
                <Label size="sm" tone="faint">메모</Label>
                <span />
              </div>
              {trades.map((t: StockTrade) => {
                const cfg = TRADE_TYPE_CONFIG[t.tradeType as StockTradeType]
                return (
                  <div key={t.id} className={`${TABLE_GRID} items-baseline border-b border-line-hair py-2.5 hover:bg-surface-muted`}>
                    <Num className="text-[11.5px] text-fg-3">{t.tradedAt}</Num>
                    <span className={`font-mono text-[10px] tracking-label ${cfg.color}`}>{cfg.label}</span>
                    <span className="flex min-w-0 flex-col">
                      <span className="truncate text-[13px]">{t.stockName}</span>
                      {t.symbol && (
                        <span className="font-mono text-[9.5px] tracking-[0.08em] text-fg-ghost">{t.symbol}</span>
                      )}
                    </span>
                    <Num className="text-right text-[12px] text-fg-3">
                      {t.quantity > 0 ? t.quantity.toLocaleString('ko-KR') : '—'}
                    </Num>
                    <Num className="text-right text-[12px] text-fg-3">
                      {t.price > 0 ? fmt(t.price) : '—'}
                    </Num>
                    <Num className={`text-right text-[12.5px] ${cfg.color}`}>₩{fmt(t.totalAmount)}</Num>
                    <Num className="text-right text-[11.5px] text-fg-faint">
                      {t.fee + t.tax > 0 ? `₩${fmt(t.fee + t.tax)}` : '—'}
                    </Num>
                    <span className="max-w-[120px] truncate text-xs text-fg-faint">{t.memo ?? ''}</span>
                    <button
                      onClick={() => {
                        if (confirm('이 거래내역을 삭제하시겠습니까?'))
                          deleteMutation.mutate(t.id)
                      }}
                      className="text-right text-xs text-fg-ghost transition-colors hover:text-danger"
                    >
                      삭제
                    </button>
                  </div>
                )
              })}
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

function SummaryTile({ label, value, color }: { label: string; value: string; color: string }) {
  return (
    <div className="bg-surface px-3.5 py-3">
      <Label size="sm" tone="faint">{label}</Label>
      <Num className={`mt-1 block text-[14px] ${color}`}>{value}</Num>
    </div>
  )
}
