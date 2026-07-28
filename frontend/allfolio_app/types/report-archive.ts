// types/report-archive.ts — 아카이브 공통 메타 (월간·배당 등 전 리포트 공용)
export type ReportStatus = 'FINAL' | 'WARNING'

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

export interface ArchiveDetail {
  meta: ArchiveMeta
  body: string   // JSON 문자열 — parseReportBody<T>로 파싱
}
