import axios from 'axios'
import type {
  ReconRun, ReconRunDetail, ReconDiffDetail, RunType,
  ReconKd, RegisterKdPayload,
} from '@/types/recon'

const BASE_URL = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'}/api/recon`

export function createReconApi(accessToken: string) {
  const api = axios.create({
    baseURL: BASE_URL,
    timeout: 60_000,
    headers: { Authorization: `Bearer ${accessToken}` },
  })

  return {
    runs: {
      execute: async (runDate: string, runType: RunType = 'ALL'): Promise<ReconRun> =>
        (await api.post<ReconRun>('/runs', { runDate, runType })).data,

      list: async (): Promise<ReconRun[]> =>
        (await api.get<ReconRun[]>('/runs')).data,

      get: async (id: string): Promise<ReconRunDetail> =>
        (await api.get<ReconRunDetail>(`/runs/${id}`)).data,

      details: async (id: string, ruleCode?: string): Promise<ReconDiffDetail[]> =>
        (await api.get<ReconDiffDetail[]>(`/runs/${id}/details`, { params: { ruleCode } })).data,
    },
    kds: {
      list: async (): Promise<ReconKd[]> =>
        (await api.get<ReconKd[]>('/kds')).data,

      register: async (payload: RegisterKdPayload): Promise<ReconKd> =>
        (await api.post<ReconKd>('/kds', payload)).data,

      deactivate: async (id: string): Promise<void> => {
        await api.delete(`/kds/${id}`)
      },
    },
  }
}

export type ReconApi = ReturnType<typeof createReconApi>
