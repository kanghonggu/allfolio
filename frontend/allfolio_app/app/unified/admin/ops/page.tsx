'use client'

import { useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '@/contexts/AuthContext'
import { useRequireAdmin } from '@/lib/useRequireAdmin'
import { createOpsAdminApi } from '@/lib/ops-admin-api'
import type { OutboxEventSummary, OutboxStatus, ReprocessResult } from '@/types/ops'

const REFETCH_MS = 30_000

const STATUS_OPTIONS: OutboxStatus[] = ['DEAD', 'FAILED', 'PENDING', 'PROCESSED', 'PROCESSED_KAFKA']
const STATUS_STYLE: Record<string, string> = {
  PENDING:         'bg-yellow-900/40 text-yellow-400 border-yellow-800',
  PROCESSED:       'bg-emerald-900/40 text-emerald-400 border-emerald-800',
  PROCESSED_KAFKA: 'bg-emerald-900/40 text-emerald-400 border-emerald-800',
  FAILED:          'bg-orange-900/40 text-orange-400 border-orange-800',
  DEAD:            'bg-red-900/40 text-red-400 border-red-800',
}

function fmt(ts: string | null | undefined) {
  return ts ? new Date(ts).toLocaleString('ko-KR') : '-'
}

function StatCard({ label, value, danger }: { label: string; value: number; danger?: boolean }) {
  return (
    <div className={`rounded-xl border p-4 ${danger && value > 0 ? 'border-red-800 bg-red-900/20' : 'border-gray-700 bg-gray-900'}`}>
      <div className="text-xs text-gray-500">{label}</div>
      <div className={`mt-1 text-2xl font-bold ${danger && value > 0 ? 'text-red-400' : ''}`}>
        {value.toLocaleString()}
      </div>
    </div>
  )
}

function PayloadDetail({ id, api }: { id: string; api: ReturnType<typeof createOpsAdminApi> }) {
  const { data, isLoading } = useQuery({
    queryKey: ['ops', 'outbox-detail', id],
    queryFn:  () => api.outboxDetail(id),
  })
  if (isLoading) return <div className="py-2 text-xs text-gray-500">불러오는 중…</div>
  if (!data) return null
  return (
    <pre className="mt-2 max-h-48 overflow-auto rounded-lg bg-gray-950 p-3 text-xs text-gray-400">
      {(() => { try { return JSON.stringify(JSON.parse(data.payload), null, 2) } catch { return data.payload } })()}
    </pre>
  )
}

export default function OpsMonitorPage() {
  const { ready } = useRequireAdmin()
  const { accessToken } = useAuth()
  const qc = useQueryClient()
  const api = useMemo(() => (accessToken ? createOpsAdminApi(accessToken) : null), [accessToken])

  const [status, setStatus] = useState<OutboxStatus>('DEAD')
  const [eventType, setEventType] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const [reprocessResult, setReprocessResult] = useState<ReprocessResult | null>(null)
  const [reprocessing, setReprocessing] = useState(false)
  const [requeueMsg, setRequeueMsg] = useState<string | null>(null)

  const { data: summary } = useQuery({
    queryKey: ['ops', 'summary'],
    queryFn:  () => api!.summary(),
    enabled:  !!api && ready,
    refetchInterval: REFETCH_MS,
  })

  const { data: events = [] } = useQuery({
    queryKey: ['ops', 'outbox', status, eventType, from, to],
    queryFn:  () => api!.outboxList({
      status,
      eventType: eventType || undefined,
      from: from || undefined,
      to: to || undefined,
      limit: 100,
    }),
    enabled:  !!api && ready,
    refetchInterval: REFETCH_MS,
  })

  const deadBrokers = (summary?.dlq ?? []).filter(d => d.dead > 0)
  const { data: dlqDeadEvents = [] } = useQuery({
    queryKey: ['ops', 'dlq-dead', deadBrokers.map(d => d.broker).join(',')],
    queryFn:  async () => (await Promise.all(deadBrokers.map(d => api!.dlqDead(d.broker)))).flat(),
    enabled:  !!api && ready && deadBrokers.length > 0,
    refetchInterval: REFETCH_MS,
  })

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ['ops'] })
    setSelected(new Set())
  }

  const handleReprocess = async () => {
    if (!api || selected.size === 0) return
    setReprocessing(true)
    setReprocessResult(null)
    try {
      const result = await api.reprocess(Array.from(selected))
      setReprocessResult(result)
      invalidate()
    } finally {
      setReprocessing(false)
    }
  }

  const handleRequeue = async (broker: string) => {
    if (!api) return
    const { requeued } = await api.dlqRequeue(broker)
    setRequeueMsg(`${broker}: ${requeued}건을 재큐했습니다`)
    invalidate()
  }

  const toggle = (id: string) => setSelected(prev => {
    const next = new Set(prev)
    if (next.has(id)) next.delete(id); else next.add(id)
    return next
  })

  if (!ready) return null

  const outbox = summary?.outbox ?? {}
  const dlqWaiting = (summary?.dlq ?? []).reduce((a, d) => a + d.waiting, 0)
  const dlqDead = (summary?.dlq ?? []).reduce((a, d) => a + d.dead, 0)

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">운영 모니터링</h1>
        <p className="mt-1 text-sm text-gray-400">
          Outbox 이벤트·DLQ 현황 (30초 자동 갱신) — DEAD 건은 선택 후 재처리할 수 있습니다
        </p>
      </div>

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
        <StatCard label="Outbox PENDING" value={outbox.PENDING ?? 0} />
        <StatCard label="Outbox FAILED" value={outbox.FAILED ?? 0} danger />
        <StatCard label="Outbox DEAD" value={outbox.DEAD ?? 0} danger />
        <StatCard label="Outbox PROCESSED" value={outbox.PROCESSED ?? 0} />
        <StatCard label="DLQ 대기" value={dlqWaiting} />
        <StatCard label="DLQ 데드" value={dlqDead} danger />
      </div>

      {/* Outbox 그리드 */}
      <div className="rounded-xl border border-gray-700 bg-gray-900 p-5">
        <div className="flex flex-wrap items-end gap-3">
          <label className="text-xs text-gray-500">
            상태
            <select
              value={status}
              onChange={e => { setStatus(e.target.value as OutboxStatus); setSelected(new Set()) }}
              className="mt-1 block rounded-lg border border-gray-600 bg-gray-800 px-3 py-1.5 text-sm"
            >
              {STATUS_OPTIONS.map(s => <option key={s} value={s}>{s}</option>)}
            </select>
          </label>
          <label className="text-xs text-gray-500">
            이벤트 타입
            <input
              value={eventType}
              onChange={e => setEventType(e.target.value)}
              placeholder="예: TRADE_RECORDED"
              className="mt-1 block rounded-lg border border-gray-600 bg-gray-800 px-3 py-1.5 text-sm"
            />
          </label>
          <label className="text-xs text-gray-500">
            시작일
            <input type="date" value={from} onChange={e => setFrom(e.target.value)}
              className="mt-1 block rounded-lg border border-gray-600 bg-gray-800 px-3 py-1.5 text-sm" />
          </label>
          <label className="text-xs text-gray-500">
            종료일
            <input type="date" value={to} onChange={e => setTo(e.target.value)}
              className="mt-1 block rounded-lg border border-gray-600 bg-gray-800 px-3 py-1.5 text-sm" />
          </label>
          {status === 'DEAD' && (
            <button
              onClick={handleReprocess}
              disabled={selected.size === 0 || reprocessing}
              className="ml-auto rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium hover:bg-blue-500 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              {reprocessing ? '재처리 중…' : `선택 재처리 (${selected.size})`}
            </button>
          )}
        </div>

        {reprocessResult && (
          <div className="mt-3 rounded-lg border border-gray-600 bg-gray-800 px-4 py-2 text-sm">
            재처리 결과 — <span className="text-emerald-400">처리 {reprocessResult.processed}</span> ·{' '}
            <span className="text-red-400">실패 {reprocessResult.failed}</span> ·{' '}
            <span className="text-gray-400">스킵 {reprocessResult.skipped}</span>
          </div>
        )}

        {events.length === 0 ? (
          <div className="py-10 text-center text-sm text-gray-500">{status} 상태의 이벤트가 없습니다</div>
        ) : (
          <table className="mt-4 w-full text-xs">
            <thead>
              <tr className="text-left text-gray-500">
                {status === 'DEAD' && <th className="py-2 pr-2 font-medium" />}
                <th className="py-2 pr-4 font-medium">시각</th>
                <th className="py-2 pr-4 font-medium">이벤트</th>
                <th className="py-2 pr-4 font-medium">상태</th>
                <th className="py-2 pr-4 font-medium">재시도</th>
                <th className="py-2 font-medium">오류</th>
              </tr>
            </thead>
            <tbody>
              {events.map((e: OutboxEventSummary) => (
                <>
                  <tr
                    key={e.id}
                    onClick={() => setExpandedId(prev => (prev === e.id ? null : e.id))}
                    className="cursor-pointer border-t border-gray-800 hover:bg-gray-800/50"
                  >
                    {status === 'DEAD' && (
                      <td className="py-2 pr-2" onClick={ev => ev.stopPropagation()}>
                        <input type="checkbox" checked={selected.has(e.id)} onChange={() => toggle(e.id)} />
                      </td>
                    )}
                    <td className="py-2 pr-4 text-gray-400">{fmt(e.createdAt)}</td>
                    <td className="py-2 pr-4">{e.eventType}</td>
                    <td className="py-2 pr-4">
                      <span className={`rounded-full border px-2 py-0.5 font-medium ${STATUS_STYLE[e.status] ?? ''}`}>
                        {e.status}
                      </span>
                    </td>
                    <td className="py-2 pr-4 text-gray-400">{e.retryCount}</td>
                    <td className="py-2 text-red-400/80 max-w-xs truncate">{e.errorMessage ?? '-'}</td>
                  </tr>
                  {expandedId === e.id && api && (
                    <tr key={`${e.id}-detail`} className="border-t border-gray-800/50">
                      <td colSpan={status === 'DEAD' ? 6 : 5}>
                        <PayloadDetail id={e.id} api={api} />
                      </td>
                    </tr>
                  )}
                </>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* Redis DLQ 섹션 */}
      <div className="rounded-xl border border-gray-700 bg-gray-900 p-5">
        <h2 className="text-sm font-semibold">Redis DLQ</h2>
        {requeueMsg && <div className="mt-2 text-xs text-emerald-400">{requeueMsg}</div>}
        {(summary?.dlq ?? []).filter(d => d.waiting > 0 || d.dead > 0).length === 0 ? (
          <div className="py-6 text-center text-sm text-gray-500">대기·데드 항목이 없습니다</div>
        ) : (
          <div className="mt-3 space-y-3">
            {(summary?.dlq ?? []).filter(d => d.waiting > 0 || d.dead > 0).map(d => (
              <div key={d.broker} className="flex items-center gap-4 rounded-lg border border-gray-800 px-4 py-2 text-sm">
                <span className="font-medium">{d.broker}</span>
                <span className="text-gray-400">대기 {d.waiting}</span>
                <span className={d.dead > 0 ? 'text-red-400' : 'text-gray-400'}>데드 {d.dead}</span>
                {d.dead > 0 && (
                  <button
                    onClick={() => handleRequeue(d.broker)}
                    className="ml-auto rounded-lg border border-gray-600 px-3 py-1 text-xs hover:border-blue-500 hover:text-blue-400 transition-colors"
                  >
                    전체 재큐
                  </button>
                )}
              </div>
            ))}
            {dlqDeadEvents.length > 0 && (
              <table className="w-full text-xs">
                <thead>
                  <tr className="text-left text-gray-500">
                    <th className="py-1 pr-4 font-medium">시각</th>
                    <th className="py-1 pr-4 font-medium">브로커</th>
                    <th className="py-1 pr-4 font-medium">타입</th>
                    <th className="py-1 pr-4 font-medium">재시도</th>
                    <th className="py-1 font-medium">오류</th>
                  </tr>
                </thead>
                <tbody>
                  {dlqDeadEvents.map(ev => (
                    <tr key={ev.id} className="border-t border-gray-800">
                      <td className="py-1.5 pr-4 text-gray-400">{fmt(ev.createdAt)}</td>
                      <td className="py-1.5 pr-4">{ev.brokerType}</td>
                      <td className="py-1.5 pr-4 text-gray-400">{ev.payloadType}</td>
                      <td className="py-1.5 pr-4 text-gray-400">{ev.retryCount}</td>
                      <td className="py-1.5 text-red-400/80 max-w-xs truncate">{ev.errorMessage}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
