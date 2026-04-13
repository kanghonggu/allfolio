import axios from 'axios'
import type {
  Account, CreateAccountPayload,
  Asset, CreateManualAssetPayload,
  PortfolioResponse, SyncResult,
  CsvPreviewRow, CsvImportResult,
  StockTrade, CreateStockTradePayload,
} from '@/types/unified'

const BASE_URL = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'}/api/unified`

export function createUnifiedApi(accessToken: string) {
  const api = axios.create({
    baseURL: BASE_URL,
    timeout: 30_000,
    headers: { Authorization: `Bearer ${accessToken}` },
  })

  return {
    accounts: {
      list: async (): Promise<Account[]> =>
        (await api.get<Account[]>('/accounts')).data,

      create: async (payload: CreateAccountPayload): Promise<Account> =>
        (await api.post<Account>('/accounts', payload)).data,

      delete: async (id: string): Promise<void> => {
        await api.delete(`/accounts/${id}`)
      },

      sync: async (id: string): Promise<SyncResult> =>
        (await api.post<SyncResult>(`/accounts/${id}/sync`)).data,

      getAssets: async (id: string): Promise<Asset[]> =>
        (await api.get<Asset[]>(`/accounts/${id}/assets`)).data,

      addManualAsset: async (accountId: string, payload: CreateManualAssetPayload): Promise<Asset> =>
        (await api.post<Asset>(`/accounts/${accountId}/assets`, payload)).data,

      previewCsv: async (accountId: string, file: File): Promise<CsvPreviewRow[]> => {
        const form = new FormData()
        form.append('file', file)
        return (await api.post<CsvPreviewRow[]>(`/accounts/${accountId}/csv/preview`, form, {
          headers: { 'Content-Type': 'multipart/form-data' },
        })).data
      },

      importCsv: async (accountId: string, file: File): Promise<CsvImportResult> => {
        const form = new FormData()
        form.append('file', file)
        return (await api.post<CsvImportResult>(`/accounts/${accountId}/csv`, form, {
          headers: { 'Content-Type': 'multipart/form-data' },
        })).data
      },
    },

    stockTrades: {
      list: async (accountId: string): Promise<StockTrade[]> =>
        (await api.get<StockTrade[]>(`/accounts/${accountId}/stock-trades`)).data,

      create: async (accountId: string, payload: CreateStockTradePayload): Promise<StockTrade> =>
        (await api.post<StockTrade>(`/accounts/${accountId}/stock-trades`, payload)).data,

      delete: async (accountId: string, tradeId: string): Promise<void> => {
        await api.delete(`/accounts/${accountId}/stock-trades/${tradeId}`)
      },
    },

    portfolio: {
      get: async (): Promise<PortfolioResponse> =>
        (await api.get<PortfolioResponse>('/portfolio')).data,
    },
  }
}

export { createUnifiedApi as default }
