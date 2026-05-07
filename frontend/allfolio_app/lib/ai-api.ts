import axios from 'axios'
import type { AiConfig, ChatMessage } from '@/types/ai'

const BASE_URL = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'}/api/ai`

export function createAiApi(accessToken: string) {
  const api = axios.create({
    baseURL: BASE_URL,
    timeout: 120_000,
    headers: { Authorization: `Bearer ${accessToken}` },
  })

  return {
    getConfig: async (): Promise<AiConfig | null> => {
      try {
        return (await api.get<AiConfig>('/config')).data
      } catch (e: unknown) {
        if (axios.isAxiosError(e) && e.response?.status === 404) return null
        throw e
      }
    },

    saveConfig: async (req: { baseUrl: string; apiKey: string; model: string }): Promise<void> => {
      await api.post('/config', req)
    },

    deleteConfig: async (): Promise<void> => {
      await api.delete('/config')
    },

    chat: async (messages: ChatMessage[]): Promise<string> => {
      const res = await api.post<{ content: string }>('/chat', { messages })
      return res.data.content
    },
  }
}
