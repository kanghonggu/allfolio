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

    chat: (
      messages: ChatMessage[],
      onToken: (token: string) => void,
      onDone: () => void,
      onError: (e: Error) => void,
    ): AbortController => {
      const controller = new AbortController()

      fetch(`${BASE_URL}/chat`, {
        method: 'POST',
        signal: controller.signal,
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${accessToken}`,
        },
        body: JSON.stringify({ messages }),
      })
        .then(async (res) => {
          if (!res.ok) throw new Error(`HTTP ${res.status}`)
          const reader = res.body?.getReader()
          if (!reader) throw new Error('no response body')
          const decoder = new TextDecoder()
          while (true) {
            const { done, value } = await reader.read()
            if (done) break
            const chunk = decoder.decode(value, { stream: true })
            for (const line of chunk.split('\n')) {
              if (line.startsWith('data:')) {
                const token = line.slice(5)
                if (token) onToken(token)
              }
            }
          }
          onDone()
        })
        .catch((e: unknown) => {
          if (e instanceof Error && e.name === 'AbortError') return
          onError(e instanceof Error ? e : new Error(String(e)))
        })

      return controller
    },
  }
}
