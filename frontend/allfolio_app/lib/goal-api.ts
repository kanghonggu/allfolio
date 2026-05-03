import axios from 'axios'
import type { GoalResponse, GoalsResponse, GoalRequest } from '@/types/goal'

const BASE_URL = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'}/api/goals`

export function createGoalApi(accessToken: string) {
  const api = axios.create({
    baseURL: BASE_URL,
    timeout: 15_000,
    headers: { Authorization: `Bearer ${accessToken}` },
  })

  return {
    list: async (): Promise<GoalsResponse> =>
      (await api.get<GoalsResponse>('/')).data,

    create: async (req: GoalRequest): Promise<GoalResponse> =>
      (await api.post<GoalResponse>('/', req)).data,

    update: async (id: string, req: GoalRequest): Promise<GoalResponse> =>
      (await api.put<GoalResponse>(`/${id}`, req)).data,

    delete: async (id: string): Promise<void> => {
      await api.delete(`/${id}`)
    },
  }
}
