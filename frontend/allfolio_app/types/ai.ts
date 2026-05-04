export interface AiConfig {
  baseUrl: string
  model: string
  hasKey: boolean
}

export interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
}
