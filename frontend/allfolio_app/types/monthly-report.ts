// types/monthly-report.ts
export type ReportStatus = 'FINAL' | 'WARNING'   // 백엔드 enum ReportStatus { FINAL, WARNING }

export interface ReportWarning {
  code: string
  message: string
}

export interface ArchiveMeta {
  id: string
  type: string
  periodStart: string   // ISO date
  periodEnd: string
  asOfDate: string
  status: ReportStatus
  warnings: ReportWarning[]
  createdAt: string      // ISO datetime
}

export interface BenchmarkBlock {
  indexType: string
  label: string
  periodReturn: number | null
  excessReturn: number | null
}

export interface MonthPerformance {
  twr: number | null
  mwr: number | null
  startNav: number | null
  endNav: number | null
  netFlow: number
  investmentPnl: number | null
  benchmark: BenchmarkBlock | null
}

export interface StandardPeriod { twr: number | null }

export interface Performance {
  month: MonthPerformance
  standard: Partial<Record<'3M' | 'YTD' | '1Y' | 'SI', StandardPeriod>>
  volatility: number | null
}

export interface Holding {
  name: string
  symbol: string
  type: string
  quantity: number
  valueKrw: number
  weight: number       // 0~100 스케일 (fmtPct 금지, fmtPctScaled/.toFixed 사용)
  returnRate: number | null   // 0~100 스케일 (fmtPct 금지, fmtPctScaled/.toFixed 사용)
}

export interface ExposureRow { valueKrw: number; weight: number; type?: string; currency?: string }   // weight: 0~100 스케일 (fmtPct 금지, fmtPctScaled/.toFixed 사용)

export interface Exposure {
  byType: (ExposureRow & { type: string })[]
  byCurrency: (ExposureRow & { currency: string })[]
}

export interface AccountRow {
  accountName: string
  provider: string
  valueKrw: number
  weight: number       // 0~100 스케일 (fmtPct 금지, fmtPctScaled/.toFixed 사용)
  assetCount: number
}

export interface FlowDecomposition {
  startNav: number
  netFlow: number
  investmentPnl: number
  endNav: number
}

export interface MonthlyReportBody {
  performance: Performance
  topHoldings: Holding[]
  exposure: Exposure
  accounts: AccountRow[]
  flowDecomposition: FlowDecomposition
  note: string
}

export interface ArchiveDetail {
  meta: ArchiveMeta
  body: string   // JSON 문자열 — parseMonthlyReportBody로 파싱
}
