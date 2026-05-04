'use client'

import { useState } from 'react'
import Link from 'next/link'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useAiApi } from '@/lib/useApi'

const EXAMPLES = [
  { label: 'OpenAI', url: 'https://api.openai.com/v1', model: 'gpt-4o' },
  { label: 'OpenRouter', url: 'https://openrouter.ai/api/v1', model: 'anthropic/claude-3.5-sonnet' },
  { label: 'Groq', url: 'https://api.groq.com/openai/v1', model: 'llama-3.3-70b-versatile' },
  { label: 'Ollama (local)', url: 'http://localhost:11434/v1', model: 'llama3.2' },
]

export default function AiSettingsPage() {
  const api = useAiApi()
  const qc = useQueryClient()

  const { data: config, isLoading } = useQuery({
    queryKey: ['ai-config'],
    queryFn: () => api!.getConfig(),
    enabled: !!api,
  })

  const [baseUrl, setBaseUrl] = useState('')
  const [apiKey, setApiKey] = useState('')
  const [model, setModel] = useState('')

  const save = useMutation({
    mutationFn: () => api!.saveConfig({ baseUrl, apiKey, model }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['ai-config'] })
      setApiKey('')
    },
  })

  const del = useMutation({
    mutationFn: () => api!.deleteConfig(),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['ai-config'] }),
  })

  if (isLoading) {
    return (
      <div className="flex min-h-[40vh] items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-gray-600 border-t-blue-400" />
      </div>
    )
  }

  const canSave = baseUrl.trim() && apiKey.trim() && model.trim()

  return (
    <div className="mx-auto max-w-xl space-y-8">
      <div className="flex items-center gap-3">
        <Link href="/unified/reports" className="text-gray-400 hover:text-white text-sm">← 설정</Link>
        <h1 className="text-xl font-bold">AI 상담사 설정</h1>
      </div>

      {config && (
        <div className="rounded-lg border border-green-700 bg-green-950/30 px-4 py-3 text-sm text-green-300">
          연결됨: <span className="font-medium">{config.model}</span> · {config.baseUrl}
        </div>
      )}

      <div className="rounded-xl border border-gray-700 bg-gray-900 p-6 space-y-4">
        <h2 className="text-sm font-semibold text-gray-300">LLM 연결 설정</h2>

        <div>
          <label className="mb-1 block text-xs text-gray-400">Base URL</label>
          <input
            type="url"
            placeholder="https://api.openai.com/v1"
            value={baseUrl}
            onChange={e => setBaseUrl(e.target.value)}
            className="w-full rounded-lg border border-gray-700 bg-gray-800 px-3 py-2 text-sm text-white placeholder-gray-600 focus:border-blue-500 focus:outline-none"
          />
        </div>

        <div>
          <label className="mb-1 block text-xs text-gray-400">API Key</label>
          <input
            type="password"
            placeholder="sk-..."
            value={apiKey}
            onChange={e => setApiKey(e.target.value)}
            className="w-full rounded-lg border border-gray-700 bg-gray-800 px-3 py-2 text-sm text-white placeholder-gray-600 focus:border-blue-500 focus:outline-none"
          />
        </div>

        <div>
          <label className="mb-1 block text-xs text-gray-400">모델</label>
          <input
            type="text"
            placeholder="gpt-4o"
            value={model}
            onChange={e => setModel(e.target.value)}
            className="w-full rounded-lg border border-gray-700 bg-gray-800 px-3 py-2 text-sm text-white placeholder-gray-600 focus:border-blue-500 focus:outline-none"
          />
        </div>

        <div className="flex gap-2 pt-2">
          <button
            onClick={() => save.mutate()}
            disabled={!canSave || save.isPending}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-500 disabled:opacity-40"
          >
            {save.isPending ? '저장 중...' : '저장'}
          </button>
          {config && (
            <button
              onClick={() => del.mutate()}
              disabled={del.isPending}
              className="rounded-lg border border-red-700 px-4 py-2 text-sm font-medium text-red-400 hover:border-red-500 disabled:opacity-40"
            >
              {del.isPending ? '삭제 중...' : '삭제'}
            </button>
          )}
        </div>

        {save.isSuccess && (
          <p className="text-xs text-green-400">저장되었습니다.</p>
        )}
        {save.isError && (
          <p className="text-xs text-red-400">저장 실패. 다시 시도해 주세요.</p>
        )}
      </div>

      <div className="rounded-xl border border-gray-800 bg-gray-900/50 p-6 space-y-3">
        <h2 className="text-xs font-semibold text-gray-500 uppercase tracking-wide">예시</h2>
        <div className="space-y-2">
          {EXAMPLES.map(ex => (
            <button
              key={ex.label}
              onClick={() => { setBaseUrl(ex.url); setModel(ex.model) }}
              className="w-full text-left rounded-lg border border-gray-700 px-3 py-2 text-sm hover:border-gray-500 hover:bg-gray-800 transition-colors"
            >
              <span className="font-medium text-gray-200">{ex.label}</span>
              <span className="ml-2 text-gray-500">{ex.url}</span>
              <span className="ml-2 text-gray-600">/ {ex.model}</span>
            </button>
          ))}
        </div>
      </div>
    </div>
  )
}
