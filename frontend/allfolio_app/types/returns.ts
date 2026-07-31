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

export interface ReturnsAnalysis {
  from: string
  to: string
  asOfDate: string
  summary: PeriodSummary
  navSeries: NavSeriesPoint[]
  benchmark: BenchmarkComparison | null
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
