import axios from 'axios'

const BASE_URL = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'}/api/feedback`

export type FeedbackKind = 'BUG' | 'IMPROVEMENT' | 'QUESTION'

export interface FeedbackPayload {
  kind: FeedbackKind
  message: string
  pageUrl: string | null
  userAgent: string | null
  viewport: string | null
  lastApiError: string | null
  consoleErrors: string[]
}

export function createFeedbackApi(accessToken: string) {
  const api = axios.create({
    baseURL: BASE_URL,
    timeout: 15_000,
    headers: { Authorization: `Bearer ${accessToken}` },
  })

  return {
    submit: async (payload: FeedbackPayload): Promise<{ id: string }> =>
      (await api.post<{ id: string }>('', payload)).data,
  }
}
