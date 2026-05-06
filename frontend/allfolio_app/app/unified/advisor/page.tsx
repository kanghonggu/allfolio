'use client'

import { useState, useRef, useEffect } from 'react'
import Link from 'next/link'
import { useQuery } from '@tanstack/react-query'
import { useAiApi } from '@/lib/useApi'
import type { ChatMessage } from '@/types/ai'

export default function AdvisorPage() {
  const api = useAiApi()

  const { data: config, isLoading } = useQuery({
    queryKey: ['ai-config'],
    queryFn: () => api!.getConfig(),
    enabled: !!api,
  })

  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const abortRef = useRef<AbortController | null>(null)
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  function sendMessage() {
    if (!input.trim() || streaming || !api) return

    const userMsg: ChatMessage = { role: 'user', content: input.trim() }
    const nextMessages = [...messages, userMsg]
    setMessages(nextMessages)
    setInput('')
    setStreaming(true)

    let assistantContent = ''
    setMessages(prev => [...prev, { role: 'assistant', content: '' }])

    abortRef.current = api.chat(
      nextMessages,
      (token) => {
        assistantContent += token
        setMessages(prev => {
          const updated = [...prev]
          updated[updated.length - 1] = { role: 'assistant', content: assistantContent }
          return updated
        })
      },
      () => setStreaming(false),
      () => setStreaming(false),
    )
  }

  function handleKeyDown(e: React.KeyboardEvent) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      sendMessage()
    }
  }

  if (isLoading) {
    return (
      <div className="flex min-h-[40vh] items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-gray-600 border-t-blue-400" />
      </div>
    )
  }

  if (!config) {
    return (
      <div className="mx-auto max-w-xl space-y-6">
        <div className="flex items-center gap-3">
          <Link href="/unified/reports" className="text-gray-400 hover:text-white text-sm">← 보고서</Link>
          <h1 className="text-xl font-bold">AI 금융 상담사</h1>
        </div>
        <div className="rounded-xl border border-gray-700 bg-gray-900 p-8 text-center space-y-4">
          <div className="text-4xl">🤖</div>
          <p className="text-gray-300">LLM API 키를 등록하면 포트폴리오 기반 상담을 받을 수 있습니다.</p>
          <Link
            href="/unified/settings/ai"
            className="inline-block rounded-lg bg-blue-600 px-5 py-2.5 text-sm font-medium text-white hover:bg-blue-500"
          >
            설정하러 가기 →
          </Link>
        </div>
      </div>
    )
  }

  return (
    <div className="mx-auto flex max-w-2xl flex-col" style={{ height: 'calc(100vh - 120px)' }}>
      <div className="flex items-center gap-3 mb-4">
        <Link href="/unified/reports" className="text-gray-400 hover:text-white text-sm">← 보고서</Link>
        <h1 className="text-xl font-bold">AI 금융 상담사</h1>
        <span className="text-xs text-gray-500">{config.model}</span>
        <Link href="/unified/settings/ai" className="ml-auto text-xs text-gray-500 hover:text-gray-300">⚙ 설정</Link>
      </div>

      <div className="flex-1 overflow-y-auto space-y-4 rounded-xl border border-gray-800 bg-gray-900 p-4">
        {messages.length === 0 && (
          <div className="flex h-full items-center justify-center text-sm text-gray-600">
            포트폴리오 데이터를 분석할 준비가 되었습니다. 무엇이든 물어보세요.
          </div>
        )}
        {messages.map((msg, i) => (
          <div key={i} className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}>
            <div
              className={`max-w-[80%] rounded-xl px-4 py-2.5 text-sm whitespace-pre-wrap ${
                msg.role === 'user'
                  ? 'bg-blue-700 text-white'
                  : 'bg-gray-800 text-gray-100'
              }`}
            >
              {msg.content}
              {streaming && i === messages.length - 1 && msg.role === 'assistant' && (
                <span className="ml-1 inline-block h-3 w-1 animate-pulse bg-gray-400" />
              )}
            </div>
          </div>
        ))}
        <div ref={bottomRef} />
      </div>

      <div className="mt-3 flex gap-2">
        <textarea
          rows={1}
          placeholder="메시지를 입력하세요... (Enter로 전송)"
          value={input}
          onChange={e => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          disabled={streaming}
          className="flex-1 resize-none rounded-xl border border-gray-700 bg-gray-800 px-4 py-2.5 text-sm text-white placeholder-gray-600 focus:border-blue-500 focus:outline-none disabled:opacity-50"
        />
        <button
          onClick={sendMessage}
          disabled={!input.trim() || streaming}
          className="rounded-xl bg-blue-600 px-4 py-2.5 text-sm font-medium text-white hover:bg-blue-500 disabled:opacity-40"
        >
          전송
        </button>
      </div>
    </div>
  )
}
