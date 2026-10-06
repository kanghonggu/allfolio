export type PortfolioSnapshot = {
  portfolioId: string
  date: string
  performance: {
    nav: number
    dailyReturn: number
    cumulativeReturn: number
    benchmarkReturn: number | null
    alpha: number | null
  }
  /** 구간 수익률이 2건 미만이면 null — 0으로 채우면 "변동성 0%"로 읽힌다 */
  risk: {
    volatility: number
    annualizedVolatility: number
    var95: number
    maxDrawdown: number
  } | null
}

export type Position = {
  assetId: string
  quantity: number
  /** costMethod 에 따른 원가 단가 (AVG_COST: 가중평균, FIFO: 최초 lot 단가) */
  costBasis: number
  currency: string
  costMethod: 'AVG_COST' | 'FIFO'
  /** costBasis × quantity → KRW 환산 평가금액 (백엔드 계산) */
  krwValue: number
}
