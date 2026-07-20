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

export interface ReturnsAnalysis {
  from: string
  to: string
  asOfDate: string
  summary: PeriodSummary
  navSeries: NavSeriesPoint[]
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
