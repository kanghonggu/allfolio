'use client'

import { useState, useRef, useEffect } from 'react'
import Link from 'next/link'
import { useQuery } from '@tanstack/react-query'
import { useAiApi } from '@/lib/useApi'
import PageHeader from '@/components/ui/PageHeader'
import Button from '@/components/ui/Button'
import { Textarea } from '@/components/ui/Field'
import { EmptyState, LoadingState } from '@/components/ui/states'
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
  const [loading, setLoading] = useState(false)
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  async function sendMessage() {
    if (!input.trim() || loading || !api) return

    const userMsg: ChatMessage = { role: 'user', content: input.trim() }
    const nextMessages = [...messages, userMsg]
    setMessages(nextMessages)
    setInput('')
    setLoading(true)

    try {
      const content = await api.chat(nextMessages)
      setMessages(prev => [...prev, { role: 'assistant', content }])
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : '오류가 발생했습니다.'
      setMessages(prev => [...prev, { role: 'assistant', content: `[오류] ${msg}` }])
    } finally {
      setLoading(false)
    }
  }

  function handleKeyDown(e: React.KeyboardEvent) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      sendMessage()
    }
  }

  if (isLoading) {
    return (
      <div className="border border-line-card bg-surface">
        <PageHeader className="px-5 pt-5 sm:px-7" title="AI 금융 상담사" />
        <div className="px-5 py-5 pb-10 sm:px-7">
          <LoadingState label="설정 불러오는 중" />
        </div>
      </div>
    )
  }

  if (!config) {
    return (
      <div className="border border-line-card bg-surface">
        <div className="px-5 pt-4 sm:px-7">
          <Link
            href="/unified/reports"
            className="font-mono text-[10px] tracking-label text-fg-faint transition-colors hover:text-ink"
          >
            ← 보고서
          </Link>
        </div>
        <PageHeader
          className="px-5 pt-2 sm:px-7"
          title="AI 금융 상담사"
          meta="포트폴리오 기반 상담"
        />
        <div className="px-5 py-5 pb-10 sm:px-7">
          <EmptyState
            title="LLM API 키가 등록되어 있지 않습니다"
            description="LLM API 키를 등록하면 포트폴리오 기반 상담을 받을 수 있습니다."
            action={
              <Link
                href="/unified/settings/ai"
                className="border border-ink bg-ink px-4 py-2 text-sm text-white transition-colors hover:bg-fg-2"
              >
                설정하러 가기
              </Link>
            }
          />
        </div>
      </div>
    )
  }

  return (
    <div className="border border-line-card bg-surface">
      <div className="px-5 pt-4 sm:px-7">
        <Link
          href="/unified/reports"
          className="font-mono text-[10px] tracking-label text-fg-faint transition-colors hover:text-ink"
        >
          ← 보고서
        </Link>
      </div>
      <PageHeader
        className="px-5 pt-2 sm:px-7"
        title="AI 금융 상담사"
        meta={config.model}
        actions={
          <Link
            href="/unified/settings/ai"
            className="border border-line bg-surface px-3 py-1.5 text-xs text-fg-2 transition-colors hover:border-ink hover:text-ink"
          >
            설정
          </Link>
        }
      />

      <div className="flex flex-col px-5 py-5 pb-8 sm:px-7" style={{ height: 'calc(100vh - 220px)' }}>
        <div className="flex-1 space-y-3 overflow-y-auto border border-line bg-surface p-4">
          {messages.length === 0 && !loading && (
            <div className="flex h-full items-center justify-center text-center font-mono text-[10px] tracking-wideLabel text-fg-faint">
              포트폴리오 데이터를 분석할 준비가 되었습니다. 무엇이든 물어보세요.
            </div>
          )}
          {messages.map((msg, i) => (
            <div key={i} className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}>
              <div
                className={`max-w-[80%] whitespace-pre-wrap px-4 py-2.5 text-sm ${
                  msg.role === 'user'
                    ? 'bg-ink text-white'
                    : 'border border-line bg-surface-muted text-ink'
                }`}
              >
                {msg.content}
              </div>
            </div>
          ))}
          {loading && (
            <div className="flex justify-start">
              <div className="border border-line bg-surface-muted px-4 py-2.5">
                <span className="animate-pulse font-mono text-[10px] tracking-wideLabel text-fg-muted">
                  응답 생성 중 …
                </span>
              </div>
            </div>
          )}
          <div ref={bottomRef} />
        </div>

        <div className="mt-3 flex gap-2">
          <Textarea
            rows={1}
            placeholder="메시지를 입력하세요… (Enter로 전송)"
            value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            disabled={loading}
            aria-label="상담 메시지 입력"
            className="flex-1 resize-none"
          />
          <Button
            variant="primary"
            onClick={sendMessage}
            disabled={!input.trim() || loading}
          >
            전송
          </Button>
        </div>
      </div>
    </div>
  )
}
