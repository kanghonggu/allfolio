import axios from 'axios'
import type { ExclusionPreset, UpsertPresetRequest } from '@/types/exclusion-preset'

const BASE_URL = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'}/api/admin/exclusion-presets`

export function createExclusionPresetAdminApi(accessToken: string) {
  const api = axios.create({
    baseURL: BASE_URL,
    timeout: 30_000,
    headers: { Authorization: `Bearer ${accessToken}` },
  })

  return {
    list: async (): Promise<ExclusionPreset[]> => (await api.get<ExclusionPreset[]>('')).data,
    upsert: async (req: UpsertPresetRequest): Promise<ExclusionPreset> => (await api.post<ExclusionPreset>('', req)).data,
    remove: async (id: string): Promise<void> => { await api.delete(`/${id}`) },
  }
}
