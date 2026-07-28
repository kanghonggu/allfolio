// types/holdings-report.ts
import type { ArchiveMeta, ArchiveDetail } from './report-archive'
export type { ArchiveMeta, ArchiveDetail }

export interface HoldingsSummary {
  totalValueKrw: number
  holdingCount: number
  accountCount: number
  cashWeight: number         // 0~100 스케일 (fmtPct 금지, fmtPctScaled/.toFixed 사용)
  unrealizedPnlKrw: number   // 부호 있는 KRW
}

export interface Holding {
  name: string
  symbol: string | null
  type: string
  account: string
  provider: string
  quantity: number
  avgPrice: number           // 원통화 평단
  currentValue: number       // 원통화 평가액
  valueKrw: number
  weight: number             // 0~100 스케일
  unrealizedPnl: number      // KRW, 부호
  returnRate: number         // 0~100 스케일
}

export interface HoldingByAccount {
  account: string
  provider: string
  valueKrw: number
  weight: number             // 0~100 스케일
  holdingCount: number
}

export interface HoldingByType {
  type: string
  valueKrw: number
  weight: number             // 0~100 스케일
  holdingCount: number
}

export interface HoldingCash {
  account: string
  currency: string
  valueKrw: number
}

export interface HoldingsReportBody {
  summary: HoldingsSummary
  holdings: Holding[]
  byAccount: HoldingByAccount[]
  byType: HoldingByType[]
  cash: HoldingCash[]
}
