import axios from 'axios'
import type { AiConfig, ChatMessage } from '@/types/ai'

const BASE_URL = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'}/api/ai`

export function createAiApi(accessToken: string) {
  const api = axios.create({
    baseURL: BASE_URL,
    timeout: 15_000,
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
      const { data: { jobId } } = await api.post<{ jobId: string }>('/chat', { messages })

      for (let i = 0; i < 120; i++) {
        await new Promise(resolve => setTimeout(resolve, 2000))
        const { data } = await api.get<{ status: string; content?: string; error?: string }>(`/chat/${jobId}`)
        if (data.status === 'done') return data.content!
        if (data.status === 'error') throw new Error(data.error || '오류가 발생했습니다')
      }
      throw new Error('응답 시간이 초과됐습니다')
    },
  }
}
