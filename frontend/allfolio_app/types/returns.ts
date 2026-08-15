export interface PeriodSummary {
  twr: number | null
  mwr: number | null
  startNav: number | null
  endNav: number | null
  netFlow: number
  investmentPnl: number | null
}

export interface NavSeriesPoint {
  date: string
  nav: number
}

export type BenchmarkType = 'SPX' | 'KOSPI' | 'BTC'

export interface BenchmarkComparison {
  indexType: BenchmarkType
  label: string
  periodReturn: number | null
  excessReturn: number | null
  series: NavSeriesPoint[]
}

export interface BenchmarkOption {
  type: BenchmarkType
  label: string
}

export interface BenchmarkConfig {
  indexType: BenchmarkType | null
  available: BenchmarkOption[]
}

/** 기간 수익의 자산/환율 분해. `(1+asset)(1+fx)−1 == TWR` — 합이 아니라 곱이다 */
export interface CurrencyAttribution {
  /** percent(0~100). 백엔드가 JSON 숫자로 보낸다 — string으로 선언하지 말 것 */
  assetContribution: number
  fxContribution: number
  /** 비-KRW 통화, 정렬됨 */
  currencies: string[]
}

export interface ReturnsAnalysis {
  from: string
  to: string
  asOfDate: string
  summary: PeriodSummary
  navSeries: NavSeriesPoint[]
  benchmark: BenchmarkComparison | null
  currencyAttribution: CurrencyAttribution | null
}

export type FlowType = 'DEPOSIT' | 'WITHDRAWAL' | 'TRANSFER_IN' | 'TRANSFER_OUT' | 'FX_IN' | 'FX_OUT'

export interface CashFlowItem {
  id: string
  accountId: string | null
  flowDate: string
  flowType: FlowType
  amount: number
  currency: string
  amountKrw: number
  memo: string | null
  linkId?: string | null
}

export interface RecordCashFlowRequest {
  accountId?: string | null
  flowDate: string
  flowType: FlowType
  amount: number
  currency: string
  memo?: string | null
}

export interface TransferRequest {
  fromAccountId: string
  toAccountId: string
  flowDate: string
  amount: number
  currency: string
  memo?: string | null
}

export interface FxRequest {
  accountId?: string | null
  flowDate: string
  fromAmount: number
  fromCurrency: string
  toAmount: number
  toCurrency: string
  memo?: string | null
  toAccountId?: string | null   // 지정 시 계좌간 환전(도착 계좌). null이면 동일 계좌
}
