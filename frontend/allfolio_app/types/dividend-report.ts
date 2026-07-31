// types/dividend-report.ts
import type { ArchiveMeta, ArchiveDetail } from './report-archive'
export type { ArchiveMeta, ArchiveDetail }

export interface DividendSummary {
  grossTotal: number
  withholdingTax: number
  netTotal: number
  effectiveTaxRate: number   // 0~100 스케일 (fmtPct 금지, fmtPctScaled/.toFixed 사용)
  receiptCount: number
  ttmYield: number | null    // 0~100 스케일 (fmtPct 금지, fmtPctScaled/.toFixed 사용)
}

export interface DividendReceipt {
  payDate: string            // "YYYY-MM-DD"
  stockName: string
  symbol: string | null
  account: string
  gross: number
  tax: number
  net: number
}

export interface DividendMonthly {
  month: string              // "YYYY-MM"
  net: number
}

export interface DividendBySymbol {
  stockName: string
  symbol: string | null
  gross: number
  tax: number
  net: number
  weight: number             // 0~100 스케일 (fmtPct 금지, fmtPctScaled/.toFixed 사용)
}

export interface DividendByCountry {
  country: string            // "국내" | "해외"
  gross: number
  tax: number
  net: number
  effectiveTaxRate: number   // 0~100 스케일 (fmtPct 금지, fmtPctScaled/.toFixed 사용)
  expectedTaxRate?: number | null
  taxDeviationPp?: number | null
  taxFlagged?: boolean
}

export interface DividendCalendarEntry {
  symbol: string | null
  stockName: string
  cadence: string
  paidMonths: number[]
  payCount: number
  lastPayDate: string
  ttmNet: number
}

export interface DividendReportBody {
  summary: DividendSummary
  receipts: DividendReceipt[]
  monthly: DividendMonthly[]
  bySymbol: DividendBySymbol[]
  byCountry: DividendByCountry[]
  dividendCalendar?: DividendCalendarEntry[]
}
