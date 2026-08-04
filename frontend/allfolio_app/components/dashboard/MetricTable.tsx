'use client'

import Badge, { type BadgeVariant } from '@/components/ui/Badge'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import { signPct, dirTone, won } from '@/lib/format'
import type { DashboardMetrics, MetricGrade, MetricValue } from '@/types/dashboard'

const GRADE: Record<MetricGrade, { label: string; variant: BadgeVariant }> = {
  EXCELLENT: { label: '우수', variant: 'ok' },
  GOOD:      { label: '양호', variant: 'ink' },
  WARN:      { label: '주의', variant: 'warn' },
  BAD:       { label: '위험', variant: 'danger' },
}

function Stars({ count }: { count: number }) {
  return (
    <span className="font-mono text-[10px] leading-none" aria-label={`5점 만점에 ${count}점`}>
      <span className="text-ink">{'★'.repeat(count)}</span>
      <span className="text-line">{'★'.repeat(Math.max(0, 5 - count))}</span>
    </span>
  )
}

const PERIOD_ROWS: Array<{ key: keyof DashboardMetrics; label: string; emptyNote: string }> = [
  { key: 'returnYtd', label: '연초 이후 (YTD)', emptyNote: '연초부터의 스냅샷이 쌓이면 표시됩니다' },
  { key: 'return1m',  label: '1개월',           emptyNote: '30일 이상 스냅샷이 쌓이면 표시됩니다' },
  { key: 'return3m',  label: '3개월',           emptyNote: '90일 이상 스냅샷이 쌓이면 표시됩니다' },
]

const RISK_TILES: Array<{
  key: keyof DashboardMetrics
  label: string
  note: string
  format: (v: number) => string
}> = [
  { key: 'mdd',        label: '최대 낙폭 (MDD)', note: '최근 1년 최대 하락 폭',          format: (v) => signPct(v) },
  { key: 'volatility', label: '연간 변동성',      note: '가격 변동 폭 · 낮을수록 안정',   format: (v) => signPct(v) },
  { key: 'sharpe',     label: '샤프 지수',        note: '리스크 대비 수익 (1.0↑ 양호)',  format: (v) => v.toFixed(2) },
  { key: 'var95',      label: 'VaR 95%',          note: '5% 확률 예상 최대 손실',        format: (v) => won(Math.abs(v)) },
]

/** 기간 수익률 테이블 + 리스크 타일 — MetricValue의 grade/stars/벤치마크/dataWarning 유지 */
export default function MetricTable({ metrics }: { metrics: DashboardMetrics }) {
  // null = 커버리지 미달 — 행을 숨기지 않고 '데이터 부족'으로 명시 (QA 후속 #3)
  const periodRows = PERIOD_ROWS.map((r) => ({ ...r, metric: metrics[r.key] ?? null }))

  const riskTiles = RISK_TILES
    .map((t) => ({ ...t, metric: metrics[t.key] }))
    .filter((t): t is typeof t & { metric: MetricValue } => !!t.metric)

  const warnings = Array.from(
    new Set(
      [...periodRows, ...riskTiles]
        .map((r) => r.metric?.dataWarning)
        .filter((w): w is string => !!w),
    ),
  )

  return (
    <div>
      {periodRows.length > 0 && (
        <div className="border-t-[1.5px] border-ink">
          <div className="grid grid-cols-[1.3fr_1fr_1fr_0.9fr] gap-3 border-b border-line py-2">
            <Label size="sm" tone="faint">구분</Label>
            <Label size="sm" tone="faint" className="text-right">수익률</Label>
            <Label size="sm" tone="faint" className="text-right">코스피 대비</Label>
            <Label size="sm" tone="faint" className="text-right">판정</Label>
          </div>
          {periodRows.map((r) =>
            r.metric ? (
              <div
                key={r.key}
                className="grid grid-cols-[1.3fr_1fr_1fr_0.9fr] items-baseline gap-3 border-b border-line-hair py-2.5"
              >
                <span className="text-[13px] text-fg-2">{r.label}</span>
                <Num tone={dirTone(r.metric.value)} className="text-right text-[12.5px]">
                  {signPct(r.metric.value)}
                </Num>
                <Num className="text-right text-[12.5px] text-fg-muted">
                  {r.metric.benchmarkVsKospi != null ? `${signPct(r.metric.benchmarkVsKospi)}p` : '—'}
                </Num>
                <span className="flex items-baseline justify-end gap-2">
                  <Stars count={r.metric.stars} />
                  <Badge variant={GRADE[r.metric.grade].variant}>{GRADE[r.metric.grade].label}</Badge>
                </span>
              </div>
            ) : (
              <div
                key={r.key}
                className="grid grid-cols-[1.3fr_1fr_1fr_0.9fr] items-baseline gap-3 border-b border-line-hair py-2.5"
              >
                <span className="flex min-w-0 flex-col text-[13px] text-fg-2">
                  {r.label}
                  <span className="text-[10.5px] text-fg-ghost">{r.emptyNote}</span>
                </span>
                <span className="text-right font-mono text-[10px] tracking-label text-fg-faint">데이터 부족</span>
                <Num className="text-right text-[12.5px] text-fg-ghost">—</Num>
                <span className="text-right font-mono text-[10px] text-fg-ghost">—</span>
              </div>
            ),
          )}
        </div>
      )}

      {riskTiles.length > 0 && (
        <div className="mt-4 grid grid-cols-2 gap-px border border-line-soft bg-line-soft">
          {riskTiles.map((t, i) => (
            <div
              key={t.key}
              className={`bg-surface px-3.5 py-3 ${
                // 홀수 개일 때 마지막 타일이 빈 회색 칸을 남기지 않도록 가로로 채운다
                riskTiles.length % 2 === 1 && i === riskTiles.length - 1 ? 'col-span-2' : ''
              }`}>
              <div className="flex items-baseline justify-between gap-2">
                <Label size="sm" tone="faint">{t.label}</Label>
                <Badge variant={GRADE[t.metric.grade].variant}>{GRADE[t.metric.grade].label}</Badge>
              </div>
              <Num className="mt-1 block text-[16px]">{t.format(t.metric.value)}</Num>
              <p className="mt-0.5 text-[11px] text-fg-faint">{t.note}</p>
            </div>
          ))}
        </div>
      )}

      {warnings.length > 0 && (
        <div className="mt-3 space-y-1">
          {warnings.map((w) => (
            <p key={w} className="text-[11.5px] leading-relaxed text-warn">주의 — {w}</p>
          ))}
        </div>
      )}
    </div>
  )
}
