'use client'

import { useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '@/contexts/AuthContext'
import { useRequireAdmin } from '@/lib/useRequireAdmin'
import { createOpsAdminApi } from '@/lib/ops-admin-api'
import PageHeader from '@/components/ui/PageHeader'
import SectionHeader from '@/components/ui/SectionHeader'
import Badge, { type BadgeVariant } from '@/components/ui/Badge'
import Button from '@/components/ui/Button'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import Field, { Input, Select } from '@/components/ui/Field'
import { EmptyState } from '@/components/ui/states'
import type { OutboxEventSummary, OutboxStatus, ReprocessResult } from '@/types/ops'

const REFETCH_MS = 30_000

const STATUS_OPTIONS: OutboxStatus[] = ['DEAD', 'FAILED', 'PENDING', 'PROCESSED', 'PROCESSED_KAFKA']
const STATUS_BADGE: Record<string, BadgeVariant> = {
  PENDING:         'warn',
  PROCESSED:       'ok',
  PROCESSED_KAFKA: 'ok',
  FAILED:          'warn',
  DEAD:            'danger',
}

// 서버가 오프셋을 실어 보내므로 여기서 존을 만지지 않는다 — new Date가 읽는 쪽 존으로 맞춘다.
function fmt(ts: string | null | undefined) {
  return ts ? new Date(ts).toLocaleString('ko-KR') : '-'
}

// 0도 명시적으로 '0' 표시, 로딩·실패는 별도 문자로 구분 (QA P2)
function StatCard({ label, value, danger }: { label: string; value: number | string; danger?: boolean }) {
  const numeric = typeof value === 'number'
  const alert = danger && numeric && value > 0
  return (
    <div className={`border p-4 ${alert ? 'border-danger' : 'border-line'}`}>
      <Label size="sm" tone="faint">{label}</Label>
      <div className={`mt-1.5 text-xl font-semibold ${alert ? 'text-danger' : ''}`}>
        <Num>{numeric ? value.toLocaleString() : value}</Num>
      </div>
    </div>
  )
}

function PayloadDetail({ id, api }: { id: string; api: ReturnType<typeof createOpsAdminApi> }) {
  const { data, isLoading } = useQuery({
    queryKey: ['ops', 'outbox-detail', id],
    queryFn:  () => api.outboxDetail(id),
  })
  if (isLoading) return <div className="py-2 font-mono text-[10px] tracking-label text-fg-faint">불러오는 중 …</div>
  if (!data) return null
  return (
    <pre className="my-2 max-h-48 overflow-auto bg-surface-muted p-3 font-mono text-xs text-fg-3">
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

  const { data: summary, isLoading: summaryLoading, isError: summaryError } = useQuery({
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
      // 시작일·종료일은 이 브라우저 달력의 날짜다. 컬럼은 UTC 벽시계라 서버가 번역해야 한다.
      zone: Intl.DateTimeFormat().resolvedOptions().timeZone,
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
    <div className="border border-line-card bg-surface">
      <PageHeader
        className="px-5 pt-5 sm:px-7"
        title="운영 모니터링"
        meta={
          <>
            <Label size="sm" className="text-warn">ADMIN</Label>
            <span className="ml-3">Outbox 이벤트·DLQ 현황 · 30초 자동 갱신 · DEAD 건은 선택 후 재처리</span>
          </>
        }
      />

      <div className="px-5 py-5 pb-10 sm:px-7">
        {summaryError && (
          <div className="mb-4 border border-danger px-4 py-2.5 text-[12.5px] text-danger">
            현황 조회에 실패했습니다. 30초 후 자동 재시도합니다.
          </div>
        )}
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
          {/* 로딩 '…' / 실패 '—' / 0은 명시적 0 (QA P2) */}
          <StatCard label="Outbox PENDING" value={summaryLoading ? '…' : summaryError ? '—' : outbox.PENDING ?? 0} />
          <StatCard label="Outbox FAILED" value={summaryLoading ? '…' : summaryError ? '—' : outbox.FAILED ?? 0} danger />
          <StatCard label="Outbox DEAD" value={summaryLoading ? '…' : summaryError ? '—' : outbox.DEAD ?? 0} danger />
          <StatCard label="Outbox PROCESSED" value={summaryLoading ? '…' : summaryError ? '—' : outbox.PROCESSED ?? 0} />
          <StatCard label="DLQ 대기" value={summaryLoading ? '…' : summaryError ? '—' : dlqWaiting} />
          <StatCard label="DLQ 데드" value={summaryLoading ? '…' : summaryError ? '—' : dlqDead} danger />
        </div>

        {/* Outbox 그리드 */}
        <section className="mt-8">
          <SectionHeader label="OUTBOX 이벤트" />
          <div className="flex flex-wrap items-end gap-3">
            <Field id="ops-status" label="상태" className="w-44">
              <Select
                value={status}
                onChange={e => { setStatus(e.target.value as OutboxStatus); setSelected(new Set()) }}
              >
                {STATUS_OPTIONS.map(s => <option key={s} value={s}>{s}</option>)}
              </Select>
            </Field>
            <Field id="ops-event-type" label="이벤트 타입" className="w-48">
              <Input
                value={eventType}
                onChange={e => setEventType(e.target.value)}
                placeholder="예: TRADE_RECORDED"
              />
            </Field>
            <Field id="ops-from" label="시작일" className="w-40">
              <Input type="date" value={from} onChange={e => setFrom(e.target.value)} />
            </Field>
            <Field id="ops-to" label="종료일" className="w-40">
              <Input type="date" value={to} onChange={e => setTo(e.target.value)} />
            </Field>
            {status === 'DEAD' && (
              <Button
                variant="primary"
                className="ml-auto"
                onClick={handleReprocess}
                disabled={selected.size === 0 || reprocessing}
              >
                {reprocessing ? '재처리 중…' : `선택 재처리 (${selected.size})`}
              </Button>
            )}
          </div>

          {reprocessResult && (
            <div className="mt-3 border border-line bg-surface-muted px-4 py-2 text-[12.5px]">
              재처리 결과 — <span className="text-ok">처리 {reprocessResult.processed}</span> ·{' '}
              <span className="text-danger">실패 {reprocessResult.failed}</span> ·{' '}
              <span className="text-fg-3">스킵 {reprocessResult.skipped}</span>
            </div>
          )}

          {events.length === 0 ? (
            <EmptyState className="mt-4" title={`${status} 상태의 이벤트가 없습니다`} />
          ) : (
            <div className="mt-4 overflow-x-auto">
              <table className="w-full min-w-[640px] border-t-[1.5px] border-ink text-xs">
                <thead>
                  <tr className="border-b border-line text-left">
                    {status === 'DEAD' && <th className="py-2 pr-2" />}
                    <th className="py-2 pr-4 font-normal"><Label size="sm" tone="faint">시각</Label></th>
                    <th className="py-2 pr-4 font-normal"><Label size="sm" tone="faint">이벤트</Label></th>
                    <th className="py-2 pr-4 font-normal"><Label size="sm" tone="faint">상태</Label></th>
                    <th className="py-2 pr-4 font-normal"><Label size="sm" tone="faint">재시도</Label></th>
                    <th className="py-2 font-normal"><Label size="sm" tone="faint">오류</Label></th>
                  </tr>
                </thead>
                <tbody>
                  {events.map((e: OutboxEventSummary) => (
                    <>
                      <tr
                        key={e.id}
                        onClick={() => setExpandedId(prev => (prev === e.id ? null : e.id))}
                        className="cursor-pointer border-b border-line-hair hover:bg-surface-muted"
                      >
                        {status === 'DEAD' && (
                          <td className="py-2 pr-2" onClick={ev => ev.stopPropagation()}>
                            <input type="checkbox" checked={selected.has(e.id)} onChange={() => toggle(e.id)} />
                          </td>
                        )}
                        <td className="py-2 pr-4"><Num className="text-[11px] text-fg-3">{fmt(e.createdAt)}</Num></td>
                        <td className="py-2 pr-4">{e.eventType}</td>
                        <td className="py-2 pr-4">
                          <Badge variant={STATUS_BADGE[e.status] ?? 'muted'}>{e.status}</Badge>
                        </td>
                        <td className="py-2 pr-4"><Num className="text-[11px] text-fg-3">{e.retryCount}</Num></td>
                        <td className="max-w-xs truncate py-2 text-danger">{e.errorMessage ?? '-'}</td>
                      </tr>
                      {expandedId === e.id && api && (
                        <tr key={`${e.id}-detail`} className="border-b border-line-hair">
                          <td colSpan={status === 'DEAD' ? 6 : 5}>
                            <PayloadDetail id={e.id} api={api} />
                          </td>
                        </tr>
                      )}
                    </>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>

        {/* Redis DLQ 섹션 */}
        <section className="mt-8">
          <SectionHeader label="REDIS DLQ" />
          {requeueMsg && <div className="mb-3 font-mono text-[10.5px] tracking-[0.04em] text-ok">{requeueMsg}</div>}
          {(summary?.dlq ?? []).filter(d => d.waiting > 0 || d.dead > 0).length === 0 ? (
            <EmptyState title="대기·데드 항목이 없습니다" />
          ) : (
            <div className="space-y-3">
              {(summary?.dlq ?? []).filter(d => d.waiting > 0 || d.dead > 0).map(d => (
                <div key={d.broker} className="flex items-center gap-4 border border-line px-4 py-2 text-[13px]">
                  <Num className="text-[12px] font-medium">{d.broker}</Num>
                  <span className="text-fg-3">대기 <Num>{d.waiting}</Num></span>
                  <span className={d.dead > 0 ? 'text-danger' : 'text-fg-3'}>데드 <Num>{d.dead}</Num></span>
                  {d.dead > 0 && (
                    <Button size="sm" className="ml-auto" onClick={() => handleRequeue(d.broker)}>
                      전체 재큐
                    </Button>
                  )}
                </div>
              ))}
              {dlqDeadEvents.length > 0 && (
                <div className="overflow-x-auto">
                  <table className="w-full min-w-[640px] border-t-[1.5px] border-ink text-xs">
                    <thead>
                      <tr className="border-b border-line text-left">
                        <th className="py-2 pr-4 font-normal"><Label size="sm" tone="faint">시각</Label></th>
                        <th className="py-2 pr-4 font-normal"><Label size="sm" tone="faint">브로커</Label></th>
                        <th className="py-2 pr-4 font-normal"><Label size="sm" tone="faint">타입</Label></th>
                        <th className="py-2 pr-4 font-normal"><Label size="sm" tone="faint">재시도</Label></th>
                        <th className="py-2 font-normal"><Label size="sm" tone="faint">오류</Label></th>
                      </tr>
                    </thead>
                    <tbody>
                      {dlqDeadEvents.map(ev => (
                        <tr key={ev.id} className="border-b border-line-hair hover:bg-surface-muted">
                          <td className="py-1.5 pr-4"><Num className="text-[11px] text-fg-3">{fmt(ev.createdAt)}</Num></td>
                          <td className="py-1.5 pr-4">{ev.brokerType}</td>
                          <td className="py-1.5 pr-4 text-fg-3">{ev.payloadType}</td>
                          <td className="py-1.5 pr-4"><Num className="text-[11px] text-fg-3">{ev.retryCount}</Num></td>
                          <td className="max-w-xs truncate py-1.5 text-danger">{ev.errorMessage}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          )}
        </section>
      </div>
    </div>
  )
}
