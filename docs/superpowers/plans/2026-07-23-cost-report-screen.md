# 비용 보고서 화면 (R-04, SCR-RPT-07) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** #40 BE 엔진이 아카이브한 `COST` 본문을 목록·상세 2라우트로 렌더링하고 브라우저 인쇄(PDF)를 제공한다.

**Architecture:** Next.js App Router. #38 배당 화면과 동형, #41의 일반화된 아카이브 인프라 위 스택. `report-archive-api.ts`에 `COST` 상수만 추가하고, 비용 타입·섹션 컴포넌트·2페이지를 신규 작성. 프레젠테이션 컴포넌트는 typed props만 받는다.

**Tech Stack:** TypeScript · Next.js(App Router) · @tanstack/react-query · axios · recharts · Tailwind. **테스트 러너 없음** — `next build` + 브라우저 프리뷰 검증.

**Spec:** `docs/superpowers/specs/2026-07-23-cost-report-screen-design.md`

**주의:** 브랜치 `feat/cost-report-screen`는 `feat/dividend-report-screen`(#41) 위 스택. 명령은 `frontend/allfolio_app/`에서. 검증: `npx tsc --noEmit`, 최종 `npx next build`. `tsconfig.tsbuildinfo` 커밋 금지(명시 경로 add).

---

## File Structure

- Modify `lib/report-archive-api.ts` — `COST` 상수 + `ReportType` 유니온 확장
- Create `types/cost-report.ts`
- Create `components/cost-report/{CostSummary,ByTypeTable,ByBrokerMatrix,MonthlyCostTrend,CostDetailsTable}.tsx`
- Create `app/unified/reports/cost-report/page.tsx`, `.../[id]/page.tsx`
- Modify `app/unified/reports/page.tsx` — 허브 카드

---

## Task 1: COST 상수 + 비용 타입

**Files:**
- Modify: `frontend/allfolio_app/lib/report-archive-api.ts`
- Create: `frontend/allfolio_app/types/cost-report.ts`

- [ ] **Step 1: report-archive-api.ts에 COST 추가**

`lib/report-archive-api.ts`에서 `export const DIVIDEND_INTEREST = 'DIVIDEND_INTEREST'` 다음 줄에 추가:
```typescript
export const COST = 'COST'
```
그리고 `ReportType` 유니온을 확장:
```typescript
export type ReportType = typeof MONTHLY_REPORT | typeof DIVIDEND_INTEREST | typeof COST
```

- [ ] **Step 2: 비용 타입 작성**

`types/cost-report.ts`:
```typescript
// types/cost-report.ts
import type { ArchiveMeta, ArchiveDetail } from './report-archive'
export type { ArchiveMeta, ArchiveDetail }

export interface CostSummary {
  totalCost: number
  brokerFee: number
  tradingTax: number
  tradeCount: number
  costRatio: number | null      // 0~100 스케일 (fmtPct 금지, fmtPctScaled/.toFixed 사용)
  annualizedTer: number | null  // 0~100 스케일
  costVsProfit: number | null   // 0~100 스케일
  investmentPnl: number | null  // 부호 있는 KRW
}

export interface CostByType {
  type: string
  amount: number
  weight: number                // 0~100 스케일
}

export interface CostByBroker {
  broker: string
  fee: number
  tax: number
  total: number
  weight: number                // 0~100 스케일
}

export interface CostMonthly {
  month: string                 // "YYYY-MM"
  brokerFee: number
  tradingTax: number
  total: number
}

export interface CostDetail {
  date: string                  // "YYYY-MM-DD"
  account: string
  provider: string
  tradeType: string
  stockName: string
  fee: number
  tax: number
}

export interface CostReportBody {
  summary: CostSummary
  byType: CostByType[]
  byBroker: CostByBroker[]
  monthly: CostMonthly[]
  details: CostDetail[]
}
```

- [ ] **Step 3: 타입체크 + 커밋**

Run: `npx tsc --noEmit` (Expected: 에러 없음 — 월간·배당 회귀 없음)
```bash
git add lib/report-archive-api.ts types/cost-report.ts
git commit -m "feat(cost-fe): COST 리포트타입 + 비용 보고서 본문 타입 (R1 #39 FE)"
```

---

## Task 2: 요약 카드 + 유형별 테이블

**Files:**
- Create: `frontend/allfolio_app/components/cost-report/CostSummary.tsx`
- Create: `frontend/allfolio_app/components/cost-report/ByTypeTable.tsx`

- [ ] **Step 1: CostSummary 작성**

```tsx
// components/cost-report/CostSummary.tsx
import type { CostSummary as Summary } from '@/types/cost-report'
import { fmtKrw, fmtPctScaled, pctColor } from '@/lib/report-format'

export function CostSummary({ summary }: { summary: Summary }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">요약</h2>
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Card label="총비용" value={fmtKrw(summary.totalCost)} />
        <Card label="비용률" value={fmtPctScaled(summary.costRatio)} />
        <Card label="연환산 TER" value={fmtPctScaled(summary.annualizedTer)} />
        <Card label="수익 대비 비용" value={fmtPctScaled(summary.costVsProfit)} />
      </div>
      <p className="text-xs text-gray-500">
        매매수수료 {fmtKrw(summary.brokerFee)} · 거래세 {fmtKrw(summary.tradingTax)} · 거래 {summary.tradeCount}건 ·
        기간 손익 <span className={pctColor(summary.investmentPnl)}>{fmtKrw(summary.investmentPnl)}</span>
      </p>
    </section>
  )
}

function Card({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-xl border border-gray-700 bg-gray-900 p-5">
      <p className="text-xs text-gray-500">{label}</p>
      <p className="mt-2 text-xl font-bold tabular-nums text-gray-100">{value}</p>
    </div>
  )
}
```

- [ ] **Step 2: ByTypeTable 작성**

```tsx
// components/cost-report/ByTypeTable.tsx
import type { CostByType } from '@/types/cost-report'
import { fmtKrw } from '@/lib/report-format'

export function ByTypeTable({ rows }: { rows: CostByType[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">유형별 비용</h2>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">유형</th><th className="p-3 text-right">금액</th><th className="p-3 text-right">비중</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.type} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3 font-medium text-gray-100">{r.type}</td>
                <td className="p-3 text-right tabular-nums">{fmtKrw(r.amount)}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{r.weight.toFixed(2)}%</td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={3} className="p-4 text-center text-gray-500">데이터가 없습니다.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}
```

- [ ] **Step 3: 타입체크 + 커밋**

Run: `npx tsc --noEmit` (Expected: 에러 없음)
```bash
git add components/cost-report/CostSummary.tsx components/cost-report/ByTypeTable.tsx
git commit -m "feat(cost-fe): 요약 카드·유형별 비용 섹션 (R1 #39 FE)"
```

---

## Task 3: 브로커 매트릭스 + 상세 내역

**Files:**
- Create: `frontend/allfolio_app/components/cost-report/ByBrokerMatrix.tsx`
- Create: `frontend/allfolio_app/components/cost-report/CostDetailsTable.tsx`

- [ ] **Step 1: ByBrokerMatrix 작성**

```tsx
// components/cost-report/ByBrokerMatrix.tsx
import type { CostByBroker } from '@/types/cost-report'
import { fmtKrw } from '@/lib/report-format'

export function ByBrokerMatrix({ rows }: { rows: CostByBroker[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">브로커×유형 매트릭스</h2>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">브로커</th><th className="p-3 text-right">매매수수료</th>
              <th className="p-3 text-right">거래세</th><th className="p-3 text-right">합계</th><th className="p-3 text-right">비중</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.broker} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3 font-medium text-gray-100">{r.broker}</td>
                <td className="p-3 text-right tabular-nums">{fmtKrw(r.fee)}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{fmtKrw(r.tax)}</td>
                <td className="p-3 text-right tabular-nums">{fmtKrw(r.total)}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{r.weight.toFixed(2)}%</td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={5} className="p-4 text-center text-gray-500">데이터가 없습니다.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}
```

- [ ] **Step 2: CostDetailsTable 작성**

```tsx
// components/cost-report/CostDetailsTable.tsx
import type { CostDetail } from '@/types/cost-report'
import { fmtKrw } from '@/lib/report-format'

export function CostDetailsTable({ rows }: { rows: CostDetail[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">상세 내역</h2>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">일자</th><th className="p-3">계좌</th><th className="p-3">유형</th>
              <th className="p-3">종목</th><th className="p-3 text-right">매매수수료</th><th className="p-3 text-right">거래세</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={`${r.date}-${r.stockName}-${i}`} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3 text-gray-300">{r.date}</td>
                <td className="p-3 text-gray-400">{r.account}</td>
                <td className="p-3 text-gray-400">{r.tradeType}</td>
                <td className="p-3 font-medium text-gray-100">{r.stockName}</td>
                <td className="p-3 text-right tabular-nums">{fmtKrw(r.fee)}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{fmtKrw(r.tax)}</td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={6} className="p-4 text-center text-gray-500">내역이 없습니다.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}
```

- [ ] **Step 3: 타입체크 + 커밋**

Run: `npx tsc --noEmit` (Expected: 에러 없음)
```bash
git add components/cost-report/ByBrokerMatrix.tsx components/cost-report/CostDetailsTable.tsx
git commit -m "feat(cost-fe): 브로커 매트릭스·상세 내역 테이블 (R1 #39 FE)"
```

---

## Task 4: 월별 추이 (스택 바)

**Files:**
- Create: `frontend/allfolio_app/components/cost-report/MonthlyCostTrend.tsx`

- [ ] **Step 1: 컴포넌트 작성**

```tsx
// components/cost-report/MonthlyCostTrend.tsx
'use client'

import {
  Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'
import type { CostMonthly } from '@/types/cost-report'
import { fmtKrw } from '@/lib/report-format'

export function MonthlyCostTrend({ rows }: { rows: CostMonthly[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">월별 비용 추이</h2>
      <div className="rounded-xl border border-gray-700 bg-gray-900 p-4">
        {rows.length === 0 ? (
          <div className="flex h-[240px] items-center justify-center text-sm text-gray-500">데이터 없음</div>
        ) : (
          <ResponsiveContainer width="100%" height={240}>
            <BarChart data={rows}>
              <CartesianGrid strokeDasharray="3 3" stroke="#374151" />
              <XAxis dataKey="month" tick={{ fill: '#9ca3af', fontSize: 12 }} />
              <YAxis tickFormatter={(v) => fmtKrw(v)} tick={{ fill: '#9ca3af', fontSize: 11 }} width={80} />
              <Tooltip
                formatter={(v: number, name: string) => [fmtKrw(v), name]}
                contentStyle={{ background: '#111827', border: '1px solid #374151' }}
              />
              <Legend formatter={(v) => <span className="text-xs text-gray-300">{v}</span>} />
              <Bar dataKey="brokerFee" stackId="c" fill="#3b82f6" name="매매수수료" />
              <Bar dataKey="tradingTax" stackId="c" fill="#f59e0b" name="거래세" radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        )}
      </div>
    </section>
  )
}
```

- [ ] **Step 2: 타입체크 + 커밋**

Run: `npx tsc --noEmit` (Expected: 에러 없음)
```bash
git add components/cost-report/MonthlyCostTrend.tsx
git commit -m "feat(cost-fe): 월별 비용 추이 스택 차트 (R1 #39 FE)"
```

---

## Task 5: 상세 페이지 (조립 + 인쇄)

**Files:**
- Create: `frontend/allfolio_app/app/unified/reports/cost-report/[id]/page.tsx`

- [ ] **Step 1: 상세 페이지 작성**

```tsx
// app/unified/reports/cost-report/[id]/page.tsx
'use client'

import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useParams } from 'next/navigation'
import { useReportArchiveApi } from '@/lib/useApi'
import { parseReportBody, COST } from '@/lib/report-archive-api'
import type { CostReportBody } from '@/types/cost-report'
import { CostSummary } from '@/components/cost-report/CostSummary'
import { ByTypeTable } from '@/components/cost-report/ByTypeTable'
import { ByBrokerMatrix } from '@/components/cost-report/ByBrokerMatrix'
import { MonthlyCostTrend } from '@/components/cost-report/MonthlyCostTrend'
import { CostDetailsTable } from '@/components/cost-report/CostDetailsTable'

export default function CostReportDetailPage() {
  const { id } = useParams<{ id: string }>()
  const api = useReportArchiveApi(COST)
  const { data, isLoading, isError } = useQuery({
    queryKey: ['cost-report', id],
    queryFn: async () => {
      const detail = await api!.detail(id)
      return { meta: detail.meta, body: parseReportBody<CostReportBody>(detail.body) }
    },
    enabled: !!api && !!id,
    retry: false,
  })

  if (!api || isLoading) return <div className="h-96 animate-pulse rounded-xl bg-gray-800" />
  if (isError || !data) {
    return (
      <div className="space-y-4">
        <div className="rounded-xl border border-red-800 bg-red-950 p-6 text-sm text-red-400">
          보고서를 찾을 수 없습니다.
        </div>
        <Link href="/unified/reports/cost-report" className="text-sm text-gray-400 hover:text-gray-200">← 목록</Link>
      </div>
    )
  }

  const { meta } = data
  const body = data.body
  const [y, m] = [meta.periodStart.slice(0, 4), meta.periodStart.slice(5, 7)]

  return (
    <div className="space-y-8 print-invert">
      <div className="flex items-center justify-between gap-3 no-print">
        <div className="flex items-center gap-3">
          <Link href="/unified/reports/cost-report" className="text-sm text-gray-500 hover:text-gray-300">← 목록</Link>
          <h1 className="text-2xl font-bold">{y}년 {Number(m)}월 비용 보고서</h1>
        </div>
        <button
          onClick={() => window.print()}
          className="rounded-lg bg-gray-800 px-4 py-2 text-sm font-medium text-gray-100 hover:bg-gray-700"
        >
          🖨 인쇄 / PDF
        </button>
      </div>

      <p className="text-xs text-gray-500">
        기준일 {meta.asOfDate} · 생성 {new Date(meta.createdAt).toLocaleString('ko-KR')}
      </p>

      {meta.status === 'WARNING' && meta.warnings.length > 0 && (
        <div className="rounded-xl border border-yellow-700 bg-yellow-950/40 p-4 text-sm text-yellow-300">
          <p className="mb-1 font-medium">경고</p>
          <ul className="list-inside list-disc space-y-0.5">
            {meta.warnings.map((w) => <li key={w.code}>{w.message}</li>)}
          </ul>
        </div>
      )}

      <CostSummary summary={body.summary} />
      <ByTypeTable rows={body.byType} />
      <ByBrokerMatrix rows={body.byBroker} />
      <MonthlyCostTrend rows={body.monthly} />
      <CostDetailsTable rows={body.details} />

      <p className="text-xs text-gray-500">
        ※ 매매수수료는 손익 계산에 이미 반영되어 있습니다 — 본 보고서는 비용 가시화용이며 수익률을 다시 차감하지 않습니다.
      </p>
    </div>
  )
}
```

- [ ] **Step 2: 타입체크 + 커밋**

Run: `npx tsc --noEmit` (Expected: 에러 없음)
```bash
git add "app/unified/reports/cost-report/[id]/page.tsx"
git commit -m "feat(cost-fe): 비용 보고서 상세 화면 + 인쇄 (R1 #39 FE)"
```

---

## Task 6: 목록/생성 페이지

**Files:**
- Create: `frontend/allfolio_app/app/unified/reports/cost-report/page.tsx`

- [ ] **Step 1: 목록/생성 페이지 작성**

```tsx
// app/unified/reports/cost-report/page.tsx
'use client'

import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { useReportArchiveApi } from '@/lib/useApi'
import { COST } from '@/lib/report-archive-api'
import type { ArchiveMeta } from '@/types/report-archive'

const NOW = new Date()
const YEARS = Array.from({ length: 6 }, (_, i) => NOW.getFullYear() - i)
const MONTHS = Array.from({ length: 12 }, (_, i) => i + 1)

export default function CostReportListPage() {
  const api = useReportArchiveApi(COST)
  const router = useRouter()
  const qc = useQueryClient()
  const [year, setYear] = useState(NOW.getMonth() === 0 ? NOW.getFullYear() - 1 : NOW.getFullYear())
  const [month, setMonth] = useState(NOW.getMonth() === 0 ? 12 : NOW.getMonth())
  const [error, setError] = useState<string | null>(null)

  const { data: list, isLoading } = useQuery({
    queryKey: ['cost-report', 'list'],
    queryFn: () => api!.list(),
    enabled: !!api,
    retry: false,
  })

  const gen = useMutation({
    mutationFn: () => api!.generate(year, month),
    onSuccess: (meta) => {
      qc.invalidateQueries({ queryKey: ['cost-report', 'list'] })
      router.push(`/unified/reports/cost-report/${meta.id}`)
    },
    onError: (e: unknown) => {
      const msg =
        (e as { response?: { data?: { error?: string } } })?.response?.data?.error ??
        '생성에 실패했습니다.'
      setError(msg)
    },
  })

  return (
    <div className="space-y-8">
      <div className="flex items-center gap-3">
        <Link href="/unified/reports" className="text-sm text-gray-500 hover:text-gray-300">← 보고서</Link>
        <h1 className="text-2xl font-bold">비용 보고서</h1>
      </div>

      <div className="flex flex-wrap items-end gap-3 rounded-xl border border-gray-700 bg-gray-900 p-4">
        <label className="text-sm text-gray-400">
          연도
          <select value={year} onChange={(e) => setYear(Number(e.target.value))}
            className="ml-2 rounded-md border border-gray-700 bg-gray-950 px-3 py-2 text-gray-200">
            {YEARS.map((y) => <option key={y} value={y}>{y}</option>)}
          </select>
        </label>
        <label className="text-sm text-gray-400">
          월
          <select value={month} onChange={(e) => setMonth(Number(e.target.value))}
            className="ml-2 rounded-md border border-gray-700 bg-gray-950 px-3 py-2 text-gray-200">
            {MONTHS.map((mm) => <option key={mm} value={mm}>{mm}</option>)}
          </select>
        </label>
        <button
          onClick={() => { setError(null); gen.mutate() }}
          disabled={gen.isPending || !api}
          className="rounded-lg bg-emerald-700 px-4 py-2 text-sm font-medium text-white hover:bg-emerald-600 disabled:opacity-50"
        >
          {gen.isPending ? '생성 중…' : '보고서 생성'}
        </button>
      </div>

      {error && (
        <div className="rounded-xl border border-red-800 bg-red-950 p-4 text-sm text-red-400">{error}</div>
      )}

      <div className="space-y-3">
        <h2 className="text-lg font-semibold">생성 이력</h2>
        {!api || isLoading ? (
          <div className="h-32 animate-pulse rounded-xl bg-gray-800" />
        ) : !list || list.length === 0 ? (
          <p className="rounded-xl border border-gray-800 bg-gray-900 p-6 text-center text-sm text-gray-500">
            아직 생성된 비용 보고서가 없습니다. 위에서 연·월을 골라 생성하세요.
          </p>
        ) : (
          <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
                  <th className="p-3">기간</th><th className="p-3">기준일</th>
                  <th className="p-3">상태</th><th className="p-3">생성일시</th>
                </tr>
              </thead>
              <tbody>
                {list.map((r: ArchiveMeta) => (
                  <tr
                    key={r.id}
                    role="button"
                    tabIndex={0}
                    onClick={() => router.push(`/unified/reports/cost-report/${r.id}`)}
                    onKeyDown={(e) => { if (e.key === 'Enter') router.push(`/unified/reports/cost-report/${r.id}`) }}
                    className="cursor-pointer border-b border-gray-800 last:border-b-0 hover:bg-gray-800/50 focus:bg-gray-800/50 focus:outline-none"
                  >
                    <td className="p-3 font-medium text-gray-100">
                      {r.periodStart.slice(0, 4)}년 {Number(r.periodStart.slice(5, 7))}월
                    </td>
                    <td className="p-3 text-gray-400">{r.asOfDate}</td>
                    <td className="p-3">
                      <span className={r.status === 'WARNING' ? 'text-yellow-400' : 'text-emerald-400'}>{r.status}</span>
                    </td>
                    <td className="p-3 text-gray-400">{new Date(r.createdAt).toLocaleString('ko-KR')}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  )
}
```

- [ ] **Step 2: 타입체크 + 커밋**

Run: `npx tsc --noEmit` (Expected: 에러 없음)
```bash
git add app/unified/reports/cost-report/page.tsx
git commit -m "feat(cost-fe): 비용 보고서 목록·생성 화면 (R1 #39 FE)"
```

---

## Task 7: 허브 카드 + 빌드 검증

**Files:**
- Modify: `frontend/allfolio_app/app/unified/reports/page.tsx`

- [ ] **Step 1: REPORTS 배열에 카드 추가**

`app/unified/reports/page.tsx`의 `REPORTS` 배열에서 `href: '/unified/reports/dividend-report'` 카드 객체 **바로 뒤**에 추가:
```tsx
  {
    href:  '/unified/reports/cost-report',
    title: '비용 보고서',
    desc:  'R-04 기관급 비용 리포트 — 수수료·거래세, 비용률·TER, 브로커별 매트릭스, PDF 인쇄',
    color: 'border-teal-700 hover:border-teal-500',
    badge: '🧾',
  },
```

- [ ] **Step 2: 프로덕션 빌드 검증**

Run: `npx next build`
Expected: 성공. 라우트 목록에 `/unified/reports/cost-report`(정적)·`/unified/reports/cost-report/[id]`(동적)가 나타나고, 기존 `monthly-report`·`dividend-report` 계열도 그대로 컴파일(일반화 회귀 없음).

- [ ] **Step 3: 커밋**

```bash
git add app/unified/reports/page.tsx
git commit -m "feat(cost-fe): 보고서 허브에 비용 카드 (R1 #39 FE)"
```

---

## Task 8: 브라우저 검증 + 스모크

**Files:** (없음 — 검증 전용)

- [ ] **Step 1: dev 기동** — preview_start(포트 3000). Bash 서버 기동 금지.
- [ ] **Step 2: 목록/생성** — `/unified/reports/cost-report` → 연·월 → 생성. #40 BE 미머지 시 400 배너, 머지 시 상세 이동. console 에러 없음.
- [ ] **Step 3: 상세 렌더** — 요약카드(총비용·비용률·TER·수익대비 + 부가정보)·유형별·브로커 매트릭스·월별 스택추이·상세내역·각주. null 지표→"—". read_page + 스크린샷.
- [ ] **Step 4: 인쇄 미리보기** — 배경 반전·네비 숨김·손익색 보존(#38 CSS 공용).
- [ ] **Step 5: 최종 커밋(있으면)**

---

## 완료 기준

- `/unified/reports/cost-report` 목록·생성, `/[id]` 상세·인쇄 동작, 요약+5섹션+각주 정상, null 폴백
- `npx tsc --noEmit`·`npx next build` 통과 — 월간·배당 화면 회귀 없음
- 허브 카드 노출, 환전·파생·인사이트는 후속
