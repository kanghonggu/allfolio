// types/esg-screening.ts
import type { ArchiveMeta, ArchiveDetail } from './report-archive'
export type { ArchiveMeta, ArchiveDetail }

export interface EsgScores {
  rating: string
  totalScore: number          // 0~100 점
  environmental: number       // 0~100 점
  social: number               // 0~100 점
  governance: number          // 0~100 점
}

export interface EsgBreakdownRow {
  name: string
  type: string
  weight: number              // 0~100 스케일
  e: number
  s: number
  g: number
  total: number                // 0~100 점
  rating: string
}

export interface EsgScreeningSummary {
  violationCount: number
  violationValueKrw: number
  violationWeight: number     // 0~100 스케일
}

export interface EsgViolation {
  name: string
  symbol: string | null
  listName: string
  reason: string
  valueKrw: number
  weight: number              // 0~100 스케일
}

export interface EsgScreeningReportBody {
  esg: EsgScores
  esgBreakdown: EsgBreakdownRow[]
  screening: EsgScreeningSummary
  violations: EsgViolation[]
}
