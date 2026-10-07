// ── Shared ─────────────────────────────────────────────────────

export interface TypeBreakdown {
  type:  string
  value: number
  pct:   number
  count: number
}

export interface CurrencyBreakdown {
  currency: string
  value:    number
  pct:      number
}

export interface TopHolding {
  name:   string
  symbol: string | null
  type:   string
  value:  number
  pct:    number
}

// ── Summary ────────────────────────────────────────────────────

export interface SummaryReport {
  userId:           string
  generatedAt:      string
  nav:              number
  totalPurchaseCost: number
  unrealizedPnl:    number
  unrealizedPnlPct: number
  assetCount:       number
  accountCount:     number
  byType:           TypeBreakdown[]
  byCurrency:       CurrencyBreakdown[]
  topHoldings:      TopHolding[]
}

// ── Allocation ─────────────────────────────────────────────────

export interface AllocationReport {
  userId:              string
  generatedAt:         string
  totalValue:          number
  byType:              TypeBreakdown[]
  byCurrency:          CurrencyBreakdown[]
  topHoldings:         TopHolding[]
  concentrationHHI:    number
  top5Concentration:   number
}

// ── Performance ────────────────────────────────────────────────

export interface DailyPerf {
  date:             string
  nav:              number
  // percent. 현금흐름을 뺀 그날 구간 수익률 — 구간이 없는 날(첫 관측 등)은 null
  dailyReturn:      number | null
  // percent. 선택 기간 시작부터 그날까지의 TWR — 스냅샷이 기간 시작을 못 덮으면 null
  cumulativeReturn: number | null
  benchmarkReturn:  number | null
  alpha:            number | null
}

export interface PerformanceReport {
  userId:         string
  period:         string
  generatedAt:    string
  totalReturn:    number
  periodReturns:  Record<string, number | null>
  dailySeries:    DailyPerf[]
  twr:            number | null
  benchmarkAlpha: number | null
  coverageDays:   number   // 시계열이 덮는 일수 — 기간 버튼 비활성 판단
}

// ── Risk ───────────────────────────────────────────────────────

export interface DailyRisk {
  date:                string
  volatility:          number
  annualizedVolatility: number
  var95:               number
  maxDrawdown:         number
}

export interface RiskReport {
  userId:               string
  generatedAt:          string
  volatility:           number | null
  annualizedVolatility: number | null
  var95:                number | null
  maxDrawdown:          number | null
  /** 설정 이후 연환산 기준 — 위 30일 지표와 창이 다르다 */
  sharpeRatio:          number | null
  calmarRatio:          number | null
  /** 샤프·칼마 창(설정 이후)의 MDD. 구간 수익률 30건 미만이면 null */
  ratioMaxDrawdown:     number | null
  /** 샤프에 쓴 무위험 수익률(연 %, CD 91일). 수집값이 없으면 null */
  riskFreeRate:         number | null
  riskFreeRateDate:     string | null
  latestDate:           string | null
  series:               DailyRisk[]
}

// ── Positions ──────────────────────────────────────────────────

export interface PositionRow {
  name:              string
  symbol:            string | null
  type:              string
  accountName:       string
  quantity:          number
  avgCost:           number
  purchaseCost:      number
  currentValue:      number
  currentValueKrw:   number   // 표시 통화(KRW) 환산 평가액
  unrealizedPnl:     number
  unrealizedPnlPct:  number
  currency:          string
  confidenceLevel:   string
}

export interface PositionsReport {
  userId:            string
  generatedAt:       string
  positions:         PositionRow[]
  totalUnrealizedPnl: number
  totalPurchaseCost: number
  totalCurrentValue: number
  totalReturnPct:    number
}

// ── Benchmark ──────────────────────────────────────────────────

export interface BenchmarkItem {
  name:            string
  // 사용자가 이 지수에 해당하는 시장을 보유 중인가 — 칩 **기본값**으로만 쓴다 (AF-107).
  // 통화 기준 근사라 틀릴 수 있어 목록에서 지우지는 않는다.
  held:            boolean
  benchmarkReturn: number
  // null = 포트폴리오 쪽 기저가 없어 알파를 낼 수 없음 (AF-107)
  alpha:           number | null
}

export interface BenchmarkSeries {
  date:      string
  // null = 스냅샷이 기간 시작을 못 덮음 — portfolioReturn이 null인 것과 같은 경우
  portfolio: number | null
  // null = 해당 날짜 실데이터 없음 (합성값으로 채우지 않음)
  sp500:     number | null
  btc:       number | null
  kospi:     number | null
}

export interface BenchmarkReport {
  userId:          string
  period:          string
  generatedAt:     string
  // null = 스냅샷이 선택 기간을 못 덮음. **취득가 기준 수익률로 대체하지 않는다** (AF-107)
  portfolioReturn: number | null
  benchmarks:      BenchmarkItem[]
  series:          BenchmarkSeries[]
}

// ── NetWorth ───────────────────────────────────────────────────

export interface NetWorthBreakdown {
  type:     string
  assets:   number
  loan:     number
  netWorth: number
  pct:      number
}

export interface NetWorthPoint {
  date: string
  nav:  number
}

export interface NetWorthReport {
  userId:       string
  generatedAt:  string
  totalAssets:  number
  totalLoan:    number
  netWorth:     number
  byType:       NetWorthBreakdown[]
  trend:        NetWorthPoint[]
}

// ── MonthlyPnl ────────────────────────────────────────────────

export interface MonthlyPnlRow {
  yearMonth:   string
  startNav:    number
  endNav:      number
  absolutePnl: number
  returnPct:   number
}

export interface MonthlyPnlReport {
  userId:          string
  generatedAt:     string
  months:          MonthlyPnlRow[]
  bestMonth:       MonthlyPnlRow | null
  worstMonth:      MonthlyPnlRow | null
  totalAbsolutePnl: number
  winMonths:       number
  loseMonths:      number
}

// ── ESG ────────────────────────────────────────────────────────

export interface AssetEsgRow {
  name:          string
  type:          string
  currentValue:  number
  weight:        number
  environmental: number
  social:        number
  governance:    number
  total:         number
  rating:        string
}

export interface EsgReport {
  userId:             string
  generatedAt:        string
  rating:             string
  totalScore:         number
  environmentalScore: number
  socialScore:        number
  governanceScore:    number
  assetBreakdown:     AssetEsgRow[]
  topAssets:          AssetEsgRow[]
  bottomAssets:       AssetEsgRow[]
}
