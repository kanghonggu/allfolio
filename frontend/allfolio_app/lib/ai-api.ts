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
      const xhr = new XMLHttpRequest()
      let processed = 0

      xhr.open('POST', `${BASE_URL}/chat`, true)
      xhr.setRequestHeader('Content-Type', 'application/json')
      xhr.setRequestHeader('Authorization', `Bearer ${accessToken}`)

      xhr.onprogress = () => {
        const newText = xhr.responseText.slice(processed)
        processed = xhr.responseText.length
        for (const line of newText.split('\n')) {
          if (line.startsWith('data:')) {
            const token = line.slice(5)
            if (token) onToken(token)
          }
        }
      }

      xhr.onload = () => {
        if (xhr.status >= 400) {
          onError(new Error(`HTTP ${xhr.status}`))
        } else {
          onDone()
        }
      }

      xhr.onerror = () => onError(new Error(`Network error (${xhr.status})`))
      xhr.ontimeout = () => onError(new Error('Request timeout'))

      controller.signal.addEventListener('abort', () => xhr.abort())

      xhr.send(JSON.stringify({ messages }))

      return controller
    },
  }
}
