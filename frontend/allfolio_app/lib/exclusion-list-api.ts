// lib/exclusion-list-api.ts
import axios from 'axios'
import type { ExclusionList, ExclusionItem, Preset, CreateList, UpdateList } from '@/types/exclusion-list'

const BASE_URL = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'}/api/exclusion-lists`

export function createExclusionListApi(accessToken: string) {
  const api = axios.create({ baseURL: BASE_URL, timeout: 30_000, headers: { Authorization: `Bearer ${accessToken}` } })
  return {
    list: async (): Promise<ExclusionList[]> => (await api.get<ExclusionList[]>('')).data,
    presets: async (): Promise<Preset[]> => (await api.get<Preset[]>('/presets')).data,
    create: async (body: CreateList): Promise<ExclusionList> => (await api.post<ExclusionList>('', body)).data,
    update: async (id: string, body: UpdateList): Promise<ExclusionList> => (await api.put<ExclusionList>(`/${id}`, body)).data,
    remove: async (id: string): Promise<void> => { await api.delete(`/${id}`) },
    addItem: async (id: string, symbol: string, memo?: string): Promise<ExclusionItem> =>
      (await api.post<ExclusionItem>(`/${id}/items`, { symbol, memo: memo ?? null })).data,
    removeItem: async (id: string, itemId: string): Promise<void> => { await api.delete(`/${id}/items/${itemId}`) },
    importCsv: async (id: string, csv: string): Promise<{ added: number }> =>
      (await api.post<{ added: number }>(`/${id}/items/import`, { csv })).data,
    clonePreset: async (presetName: string): Promise<ExclusionList> =>
      (await api.post<ExclusionList>('/presets/clone', { presetName })).data,
  }
}
