// types/cashflow-report.ts
import type { ArchiveMeta, ArchiveDetail } from './report-archive'
export type { ArchiveMeta, ArchiveDetail }

export interface CashflowSummary {
  totalInflow: number
  totalOutflow: number
  netFlow: number            // 부호 (유출 초과 시 음수)
}

export interface CashflowByTypeRow {
  type: string
  amount: number             // 부호 (유출 음수)
  direction: 'IN' | 'OUT'
}

export interface CashflowReconciliation {
  openingBalance: number
  changes: CashflowByTypeRow[]
  closingCalculated: number
  actualCash: number
  difference: number
  reconcilable: boolean
  reconciled: boolean
}

export interface CashflowMonthly {
  month: string              // "YYYY-MM"
  inflow: number
  outflow: number
  net: number
}

export interface CashflowDetail {
  date: string               // "YYYY-MM-DD"
  account: string
  type: string
  description: string
  amount: number             // 부호
}

export interface CashflowLargeMovement {
  date: string
  account: string
  type: string
  description: string
  amountKrw: number
}

export interface CashflowUnclassified {
  date: string
  account: string
  tradeType: string
  amountKrw: number
}

export interface CashflowSpecialTransactions {
  thresholdRatio: number
  largeMovements: CashflowLargeMovement[]
  unclassified: CashflowUnclassified[]
}

export interface CashflowInternalFlow {
  date: string
  kind: string
  fromAccount: string | null
  toAccount: string | null
  fromCurrency: string | null
  toCurrency: string | null
  fromAmount: number | null
  toAmount: number | null
  amountKrw: number
  toAmountKrw?: number        // 구 아카이브 호환(옵셔널)
  spreadKrw?: number          // 전환 비용(환전) — 구 아카이브 부재
}

export interface CashflowReportBody {
  summary: CashflowSummary
  byType: CashflowByTypeRow[]
  monthly: CashflowMonthly[]
  details: CashflowDetail[]
  reconciliation?: CashflowReconciliation
  specialTransactions?: CashflowSpecialTransactions
  internalFlows?: CashflowInternalFlow[]
}
