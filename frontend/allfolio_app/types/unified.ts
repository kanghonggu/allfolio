// ── Account ───────────────────────────────────────────────────

export type AccountProvider = 'BINANCE' | 'STOCK' | 'WALLET' | 'CSV' | 'MANUAL'
export type AccountType     = 'EXCHANGE' | 'STOCK' | 'WALLET' | 'BANK' | 'MANUAL'
export type AccountStatus   = 'ACTIVE' | 'SYNCING' | 'ERROR' | 'INACTIVE'

export interface Account {
  id:           string
  userId:       string
  provider:     AccountProvider
  accountType:  AccountType
  accountName:  string
  currency:     string
  status:       AccountStatus
  lastSyncedAt: string | null
  createdAt:    string
  brokerage:    string | null   // externalId (증권사명)
}

export interface CreateAccountPayload {
  accountName:   string
  provider:      AccountProvider
  accountType:   AccountType
  currency?:     string
  apiKey?:       string
  apiSecret?:    string
  walletAddress?: string
  chain?:        string
  externalId?:   string
}

// ── Asset ─────────────────────────────────────────────────────

export type AssetType     = 'STOCK' | 'CRYPTO' | 'REAL_ESTATE' | 'VEHICLE' | 'GOLD' | 'CASH' | 'ETC'
export type AssetCategory = 'FINANCIAL' | 'MANUAL'
export type AssetSourceType = 'EXCHANGE_API' | 'WALLET' | 'STOCK_API' | 'CSV' | 'MANUAL'

export interface Asset {
  id:               string
  accountId:        string
  name:             string
  symbol:           string | null
  type:             AssetType
  category:         AssetCategory
  sourceType:       AssetSourceType
  quantity:         number
  purchasePrice:    number
  currentValue:     number
  currency:         string
  valuationMethod:  string
  confidenceLevel:  string
  unrealizedPnl:    number
  returnRate:       number
  memo:             string | null
  lastUpdatedAt:    string
}

export interface CreateManualAssetPayload {
  name:          string
  symbol?:       string
  type:          AssetType
  quantity:      number
  purchasePrice: number
  currentValue:  number
  currency?:     string
  memo?:         string
}

// ── Portfolio ─────────────────────────────────────────────────

export interface TypeAllocation {
  type:       AssetType
  totalValue: number
  percentage: number
  count:      number
}

export interface AssetSummary {
  id:              string
  accountId:       string
  accountName:     string
  name:            string
  symbol:          string | null
  type:            AssetType
  quantity:        number
  currentValue:    number
  currency:        string
  unrealizedPnl:   number
  returnRate:      number
  confidenceLevel: string
}

export interface PortfolioResponse {
  userId:     string
  totalValue: number
  currency:   string
  byType:     Record<string, TypeAllocation>
  assets:     AssetSummary[]
}

// ── CSV ───────────────────────────────────────────────────────

export interface CsvPreviewRow {
  line:          number
  name:          string
  symbol:        string | null
  type:          string
  quantity:      string
  purchasePrice: string
  currentValue:  string
  error:         string | null
}

export interface CsvImportResult {
  accountId: string
  imported:  number
  skipped:   number
  errors:    string[]
}

export interface SyncResult {
  accountId: string
  synced:    number
  status:    AccountStatus
  error:     string | null
}

// ── StockTrade ────────────────────────────────────────────────

export type StockTradeType =
  | 'BUY'
  | 'SELL'
  | 'CREDIT_BUY'
  | 'CREDIT_SELL'
  | 'MARGIN'
  | 'DIVIDEND'

export interface StockTrade {
  id:          string
  accountId:   string
  tradeType:   StockTradeType
  stockName:   string
  symbol:      string | null
  quantity:    number
  price:       number
  totalAmount: number
  fee:         number
  tax:         number
  tradedAt:    string   // YYYY-MM-DD
  memo:        string | null
  createdAt:   string
}

export interface CreateStockTradePayload {
  tradeType:   StockTradeType
  stockName:   string
  symbol?:     string
  quantity:    number
  price:       number
  totalAmount: number
  fee?:        number
  tax?:        number
  tradedAt:    string   // YYYY-MM-DD
  memo?:       string
}
