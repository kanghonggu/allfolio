// types/cost-report.ts
import type { ArchiveMeta, ArchiveDetail } from './report-archive'
export type { ArchiveMeta, ArchiveDetail }

export interface CostSummary {
  totalCost: number
  brokerFee: number
  tradingTax: number
  tradeCount: number
  costRatio: number | null      // 0~100 스케일 (fmtPct 금지, fmtPctScaled/.toFixed 사용)
  annualizedTer: number | null  // 0~100 스케일
  costVsProfit: number | null   // 0~100 스케일
  investmentPnl: number | null  // 부호 있는 KRW
}

export interface CostByType {
  type: string
  amount: number
  weight: number                // 0~100 스케일
}

export interface CostByBroker {
  broker: string
  fee: number
  tax: number
  total: number
  weight: number                // 0~100 스케일
}

export interface CostMonthly {
  month: string                 // "YYYY-MM"
  brokerFee: number
  tradingTax: number
  total: number
}

export interface CostDetail {
  date: string                  // "YYYY-MM-DD"
  account: string
  provider: string
  tradeType: string
  stockName: string
  fee: number
  tax: number
}

export interface CostInsight {
  label: string
  value: string
  detail: string | null
}

export interface CostReportBody {
  summary: CostSummary
  byType: CostByType[]
  byBroker: CostByBroker[]
  monthly: CostMonthly[]
  details: CostDetail[]
  insights?: CostInsight[]
}
