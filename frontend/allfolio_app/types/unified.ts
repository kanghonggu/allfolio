// ── Account ───────────────────────────────────────────────────

export type AccountProvider = 'BINANCE' | 'UPBIT' | 'BITHUMB' | 'COINONE' | 'BYBIT' | 'OKX' | 'KIS' | 'KIWOOM' | 'STOCK' | 'WALLET' | 'CSV' | 'MANUAL'
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
  brokerage:    string | null   // 기관명 (계좌번호형 externalId는 accountNumber로 분리)
  accountNumber: string | null  // 마스킹된 계좌번호 (예: 4485****_01)
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

export interface ConnectionTestResult {
  success:    boolean
  message:    string
  assetCount: number
}

// ── Asset ─────────────────────────────────────────────────────

export type AssetType     = 'STOCK' | 'CRYPTO' | 'REAL_ESTATE' | 'VEHICLE' | 'GOLD' | 'CASH' | 'ETC'
export type AssetCategory = 'FINANCIAL' | 'MANUAL'
export type AssetSourceType = 'EXCHANGE_API' | 'WALLET' | 'STOCK_API' | 'CSV' | 'MANUAL'

export type RealEstateSubType = 'OWN' | 'JEONSE' | 'MONTHLY' | 'PRESALE'
export type VehicleSubType   = 'OWN' | 'LEASE' | 'RENTAL'

export interface Asset {
  id:               string
  accountId:        string
  name:             string
  symbol:           string | null
  type:             AssetType
  subType:          string | null
  category:         AssetCategory
  sourceType:       AssetSourceType
  quantity:         number
  /** 사용자가 적은 면적(평). **전용인지 공급인지 모른다** — 표시용이다 */
  areaPyeong:       number | null
  /** 전용면적(㎡). 실거래가 매칭 키 — 소스가 확정한 값만 들어 있다 */
  exclusiveAreaM2:  number | null
  purchasePrice:    number
  currentValue:     number
  loanAmount:       number | null
  netEquity:        number
  currency:         string
  valuationMethod:  string
  confidenceLevel:  string
  unrealizedPnl:    number
  returnRate:       number
  memo:             string | null
  lastUpdatedAt:    string
  liquidityType:    string
}

export interface CreateManualAssetPayload {
  name:          string
  symbol?:       string
  type:          AssetType
  subType?:      string
  quantity:      number
  areaPyeong?:   number | null
  exclusiveAreaM2?: number | null
  purchasePrice: number
  currentValue:  number
  loanAmount?:   number | null
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
  subType:         string | null
  quantity:        number
  currentValue:    number
  loanAmount:      number | null
  netEquity:       number
  currency:        string
  unrealizedPnl:   number
  returnRate:      number
  confidenceLevel: string
  exchange?:       string
  avgCost?:        number
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

// ── 동기화 상태 (AF-9/10) ─────────────────────────────────────

export interface SyncLogView {
  id:           string
  /** AUTO = 거래 저장·삭제, 계좌 생성이 건 자동 동기화 (AF-90) */
  trigger:      'SCHEDULED' | 'MANUAL' | 'AUTO'
  status:       'SUCCESS' | 'ERROR'
  syncedCount:  number
  errorMessage: string | null
  createdAt:    string
}

export interface AccountSyncStatus {
  accountId:    string
  accountName:  string
  provider:     string
  status:       string
  lastSyncedAt: string | null
  syncable:     boolean
  lastLog:      SyncLogView | null
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
