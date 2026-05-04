export interface DividendReport {
  userId: string
  period: string
  generatedAt: string
  totalDividend: number
  receiptCount: number
  monthlyAvg: number
  annualProjected: number
  monthlySeries: MonthlyDividend[]
  bySymbol: SymbolDividend[]
  recentHistory: DividendEntry[]
}

export interface MonthlyDividend {
  month: string       // "2025-04"
  amount: number
}

export interface SymbolDividend {
  stockName: string
  symbol: string | null
  totalAmount: number
  receiptCount: number
  lastReceivedAt: string
  pct: number
}

export interface DividendEntry {
  tradedAt: string
  stockName: string
  symbol: string | null
  amount: number
  memo: string | null
}
