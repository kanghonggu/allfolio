'use client'

import { useState } from 'react'
import Link from 'next/link'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useAiApi } from '@/lib/useApi'
import PageHeader from '@/components/ui/PageHeader'
import SectionHeader from '@/components/ui/SectionHeader'
import Badge from '@/components/ui/Badge'
import Button from '@/components/ui/Button'
import Field, { Input } from '@/components/ui/Field'
import { LoadingState } from '@/components/ui/states'

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
      <div className="border border-line-card bg-surface">
        <PageHeader className="px-5 pt-5 sm:px-7" title="AI 상담사 설정" />
        <div className="px-5 py-5 pb-10 sm:px-7">
          <LoadingState label="설정 불러오는 중" />
        </div>
      </div>
    )
  }

  const canSave = baseUrl.trim() && apiKey.trim() && model.trim()

  return (
    <div className="border border-line-card bg-surface">
      <div className="px-5 pt-4 sm:px-7">
        <Link
          href="/unified/reports"
          className="font-mono text-[10px] tracking-label text-fg-faint transition-colors hover:text-ink"
        >
          ← 설정
        </Link>
      </div>
      <PageHeader
        className="px-5 pt-2 sm:px-7"
        title="AI 상담사 설정"
        meta="LLM 연결 정보 관리"
      />

      <div className="max-w-xl px-5 py-5 pb-10 sm:px-7">
        {config && (
          <div className="mb-6 flex flex-wrap items-baseline gap-2 border border-line-card bg-surface-muted px-4 py-3">
            <Badge variant="ok">연결됨</Badge>
            <span className="text-[12.5px] text-fg-2">
              <span className="font-medium text-ink">{config.model}</span> · {config.baseUrl}
            </span>
          </div>
        )}

        <section className="border border-line-card bg-surface-muted p-5 sm:p-6">
          <SectionHeader label="LLM 연결 설정" />
          <div className="space-y-4">
            <Field id="ai-base-url" label="Base URL">
              <Input
                type="url"
                placeholder="https://api.openai.com/v1"
                value={baseUrl}
                onChange={e => setBaseUrl(e.target.value)}
              />
            </Field>

            <Field id="ai-api-key" label="API Key">
              <Input
                type="password"
                placeholder="sk-..."
                value={apiKey}
                onChange={e => setApiKey(e.target.value)}
              />
            </Field>

            <Field id="ai-model" label="모델">
              <Input
                type="text"
                placeholder="gpt-4o"
                value={model}
                onChange={e => setModel(e.target.value)}
              />
            </Field>

            <div className="flex gap-2.5 pt-2">
              <Button
                variant="primary"
                onClick={() => save.mutate()}
                disabled={!canSave || save.isPending}
              >
                {save.isPending ? '저장 중…' : '저장'}
              </Button>
              {config && (
                <button
                  onClick={() => del.mutate()}
                  disabled={del.isPending}
                  className="border border-danger bg-surface px-3.5 py-2 text-[12.5px] text-danger transition-colors hover:bg-danger hover:text-white disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {del.isPending ? '삭제 중…' : '삭제'}
                </button>
              )}
            </div>

            {save.isSuccess && (
              <p className="text-xs text-ok">저장되었습니다.</p>
            )}
            {save.isError && (
              <p role="alert" className="text-xs text-danger">저장 실패. 다시 시도해 주세요.</p>
            )}
          </div>
        </section>

        <section className="mt-6">
          <SectionHeader label="예시" />
          <div className="border-t-[1.5px] border-ink">
            {EXAMPLES.map(ex => (
              <button
                key={ex.label}
                onClick={() => { setBaseUrl(ex.url); setModel(ex.model) }}
                className="block w-full border-b border-line-hair px-3 py-2.5 text-left text-sm transition-colors hover:bg-surface-muted"
              >
                <span className="font-medium text-ink">{ex.label}</span>
                <span className="ml-2 font-mono text-[11px] text-fg-faint">{ex.url}</span>
                <span className="ml-2 font-mono text-[11px] text-fg-ghost">/ {ex.model}</span>
              </button>
            ))}
          </div>
        </section>
      </div>
    </div>
  )
}
