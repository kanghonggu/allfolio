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
  risk: {
    volatility: number
    annualizedVolatility: number
    var95: number
    maxDrawdown: number
  }
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
