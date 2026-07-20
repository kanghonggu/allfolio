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

export type FlowType = 'DEPOSIT' | 'WITHDRAWAL'

export interface CashFlowItem {
  id: string
  accountId: string | null
  flowDate: string
  flowType: FlowType
  amount: number
  currency: string
  amountKrw: number
  memo: string | null
}

export interface RecordCashFlowRequest {
  accountId?: string | null
  flowDate: string
  flowType: FlowType
  amount: number
  currency: string
  memo?: string | null
}
