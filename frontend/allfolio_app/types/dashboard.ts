export type MetricGrade = 'EXCELLENT' | 'GOOD' | 'WARN' | 'BAD'

export interface MetricValue {
  value: number
  grade: MetricGrade
  stars: number
  benchmarkVsKospi: number | null
  benchmarkVsBtc: number | null
  dataWarning: string | null
}

export interface AllocationItem {
  type: string
  ratio: number
  value: number
  grade: MetricGrade
}

export interface Position {
  id: string
  name: string
  symbol: string | null
  type: string
  currentValue: number
  /** KRW 환산 평가액 — 먼지 포지션 판정 기준 (QA 후속 #4) */
  currentValueKrw: number
  returnRate: number
  weight: number
  currency: string
}

export interface RealAsset {
  id: string
  name: string
  type: string
  value: number
  currency: string
  maturityDate: string | null
  daysUntilMaturity: number | null
}

export interface DashboardMetrics {
  returnYtd: MetricValue | null
  return1m: MetricValue | null
  return3m: MetricValue | null
  mdd: MetricValue | null
  sharpe: MetricValue | null
  var95: MetricValue | null
  volatility: MetricValue | null
}

export interface DashboardResponse {
  netWorth: {
    total: number
    liquid: number
    illiquid: number
    debt: number
    /** 입출금을 차감한 30일 투자손익 — 순자산 총변화가 아니다 (AF-95) */
    change30d: number | null
    changeRate30d: number | null
    /** 기간 내 순 외부 입출금 */
    netFlow30d: number | null
  }
  portfolio: {
    totalValue: number
    currency: string
    metrics: DashboardMetrics
    allocation: AllocationItem[]
    positions: Position[]
  }
  realAssets: RealAsset[]
}
