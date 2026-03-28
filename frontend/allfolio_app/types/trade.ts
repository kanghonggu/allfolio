export type Trade = {
  tradeId: string
  assetId: string
  tradeType: 'BUY' | 'SELL'
  quantity: number
  price: number
  fee: number
  tradeCurrency: string
  executedAt: string
  brokerType: string | null
}
