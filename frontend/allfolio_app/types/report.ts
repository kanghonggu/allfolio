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
  dailyReturn:      number
  cumulativeReturn: number
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
  sharpeRatio:          number | null
  calmarRatio:          number | null
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
  benchmarkReturn: number
  alpha:           number
}

export interface BenchmarkSeries {
  date:      string
  portfolio: number
  sp500:     number
  btc:       number
  kospi:     number
}

export interface BenchmarkReport {
  userId:          string
  period:          string
  generatedAt:     string
  portfolioReturn: number
  benchmarks:      BenchmarkItem[]
  series:          BenchmarkSeries[]
}
