# 배당·이자 보고서 화면 (R-03, SCR-RPT-05) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** #38 BE 엔진이 아카이브한 `DIVIDEND_INTEREST` 본문을 목록·상세 2라우트로 렌더링하고 브라우저 인쇄(PDF)를 제공한다. 공유 아카이브 인프라(#37)를 리포트 타입 파라미터화로 일반화한다.

**Architecture:** Next.js App Router. #37 월간 화면 인프라(아카이브 API 클라이언트·포맷 헬퍼·인쇄 CSS) 위 스택. `createReportArchiveApi`/`useReportArchiveApi`를 `reportType` 파라미터로 일반화하고 아카이브 공통 타입을 `types/report-archive.ts`로 추출. 배당 상세는 프레젠테이션 섹션 컴포넌트로 조립.

**Tech Stack:** TypeScript · Next.js(App Router) · @tanstack/react-query · axios · recharts · Tailwind. **테스트 러너 없음** — `next build` + 브라우저 프리뷰 검증.

**Spec:** `docs/superpowers/specs/2026-07-23-dividend-report-screen-design.md`

**주의:** 브랜치 `feat/dividend-report-screen`는 `feat/monthly-report-screen`(PR #36) 위 스택. 모든 명령은 `frontend/allfolio_app/`에서. 검증: `npx tsc --noEmit`, 최종 `npx next build`.

---

## File Structure

- Create `types/report-archive.ts` — 아카이브 공통 메타(월간·배당 공용)
- Modify `types/monthly-report.ts` — 공통 타입을 report-archive에서 re-export
- Modify `lib/report-archive-api.ts` — `reportType` 파라미터화 + `parseReportBody<T>`
- Modify `lib/useApi.ts` — `useReportArchiveApi(reportType)`
- Modify `app/unified/reports/monthly-report/page.tsx`, `.../[id]/page.tsx` — 훅 호출에 `'MONTHLY_REPORT'` 전달
- Create `types/dividend-report.ts`
- Create `components/dividend-report/{DividendSummary,ReceiptsTable,MonthlyNetTrend,BySymbolTable,ByCountryTable}.tsx`
- Create `app/unified/reports/dividend-report/page.tsx`, `.../[id]/page.tsx`
- Modify `app/unified/reports/page.tsx` — 허브 카드

---

## Task 1: 공유 아카이브 인프라 일반화

**Files:**
- Create: `frontend/allfolio_app/types/report-archive.ts`
- Modify: `frontend/allfolio_app/types/monthly-report.ts`
- Modify: `frontend/allfolio_app/lib/report-archive-api.ts`
- Modify: `frontend/allfolio_app/lib/useApi.ts`
- Modify: `frontend/allfolio_app/app/unified/reports/monthly-report/page.tsx`
- Modify: `frontend/allfolio_app/app/unified/reports/monthly-report/[id]/page.tsx`

- [ ] **Step 1: 공통 타입 파일 생성**

`types/report-archive.ts`:
```typescript
// types/report-archive.ts — 아카이브 공통 메타 (월간·배당 등 전 리포트 공용)
export type ReportStatus = 'FINAL' | 'WARNING'

export interface ReportWarning {
  code: string
  message: string
}

export interface ArchiveMeta {
  id: string
  type: string
  periodStart: string   // ISO date
  periodEnd: string
  asOfDate: string
  status: ReportStatus
  warnings: ReportWarning[]
  createdAt: string      // ISO datetime
}

export interface ArchiveDetail {
  meta: ArchiveMeta
  body: string   // JSON 문자열 — parseReportBody<T>로 파싱
}
```

- [ ] **Step 2: monthly-report.ts에서 공통 타입 제거 → re-export**

`types/monthly-report.ts`에서 `ReportStatus`(line 2), `ReportWarning`, `ArchiveMeta`(전체 정의)와 하단 `ArchiveDetail` 정의(interface ArchiveDetail { meta; body })를 **삭제**하고, 파일 상단(첫 줄 주석 다음)에 re-export를 추가한다:
```typescript
// types/monthly-report.ts
export type { ReportStatus, ReportWarning, ArchiveMeta, ArchiveDetail } from './report-archive'
```
나머지 월간 전용 타입(`BenchmarkBlock`·`MonthPerformance`·`Performance`·`Holding`·`Exposure`·`AccountRow`·`FlowDecomposition`·`MonthlyReportBody`)은 그대로 둔다.

- [ ] **Step 3: report-archive-api.ts 일반화**

`lib/report-archive-api.ts` 전체를 다음으로 교체:
```typescript
// lib/report-archive-api.ts
import axios from 'axios'
import type { ArchiveMeta, ArchiveDetail } from '@/types/report-archive'
import type { MonthlyReportBody } from '@/types/monthly-report'

const BASE_URL = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'}/api/reports/archive`

export const MONTHLY_REPORT = 'MONTHLY_REPORT'
export const DIVIDEND_INTEREST = 'DIVIDEND_INTEREST'

export function createReportArchiveApi(accessToken: string, reportType: string) {
  const api = axios.create({
    baseURL: BASE_URL,
    timeout: 30_000,
    headers: { Authorization: `Bearer ${accessToken}` },
  })

  return {
    generate: async (year: number, month: number): Promise<ArchiveMeta> =>
      (await api.post<ArchiveMeta>('/generate', { type: reportType, year, month })).data,

    list: async (): Promise<ArchiveMeta[]> =>
      (await api.get<ArchiveMeta[]>('', { params: { type: reportType } })).data,

    detail: async (id: string): Promise<ArchiveDetail> =>
      (await api.get<ArchiveDetail>(`/${id}`)).data,
  }
}

export function parseReportBody<T>(body: string): T {
  return JSON.parse(body) as T
}

export function parseMonthlyReportBody(body: string): MonthlyReportBody {
  return parseReportBody<MonthlyReportBody>(body)
}
```

- [ ] **Step 4: useApi.ts 훅 파라미터화**

`lib/useApi.ts`의 `useReportArchiveApi`를 교체:
```typescript
export function useReportArchiveApi(reportType: string) {
  const { accessToken } = useAuth()
  return useMemo(
    () => (accessToken ? createReportArchiveApi(accessToken, reportType) : null),
    [accessToken, reportType],
  )
}
```

- [ ] **Step 5: 월간 페이지 2곳 호출 수정**

- `app/unified/reports/monthly-report/page.tsx`: `const api = useReportArchiveApi()` → `const api = useReportArchiveApi('MONTHLY_REPORT')`
- `app/unified/reports/monthly-report/[id]/page.tsx`: `const api = useReportArchiveApi()` → `const api = useReportArchiveApi('MONTHLY_REPORT')`

- [ ] **Step 6: 타입체크 + 커밋**

Run: `npx tsc --noEmit`
Expected: 에러 없음(월간 화면 포함 전부 정합).

```bash
git add types/report-archive.ts types/monthly-report.ts lib/report-archive-api.ts lib/useApi.ts \
        app/unified/reports/monthly-report/page.tsx "app/unified/reports/monthly-report/[id]/page.tsx"
git commit -m "refactor(report-fe): 아카이브 API·타입 리포트타입 파라미터화 (R1 #38 FE)"
```

---

## Task 2: 배당 본문 타입

**Files:**
- Create: `frontend/allfolio_app/types/dividend-report.ts`

- [ ] **Step 1: 타입 작성**

```typescript
// types/dividend-report.ts
import type { ArchiveMeta, ArchiveDetail } from './report-archive'
export type { ArchiveMeta, ArchiveDetail }

export interface DividendSummary {
  grossTotal: number
  withholdingTax: number
  netTotal: number
  effectiveTaxRate: number   // 0~100 스케일
  receiptCount: number
  ttmYield: number | null    // 0~100 스케일
}

export interface DividendReceipt {
  payDate: string            // "YYYY-MM-DD"
  stockName: string
  symbol: string | null
  account: string
  gross: number
  tax: number
  net: number
}

export interface DividendMonthly {
  month: string              // "YYYY-MM"
  net: number
}

export interface DividendBySymbol {
  stockName: string
  symbol: string | null
  gross: number
  tax: number
  net: number
  weight: number             // 0~100 스케일
}

export interface DividendByCountry {
  country: string            // "국내" | "해외"
  gross: number
  tax: number
  net: number
  effectiveTaxRate: number   // 0~100 스케일
}

export interface DividendReportBody {
  summary: DividendSummary
  receipts: DividendReceipt[]
  monthly: DividendMonthly[]
  bySymbol: DividendBySymbol[]
  byCountry: DividendByCountry[]
}
```

- [ ] **Step 2: 타입체크 + 커밋**

Run: `npx tsc --noEmit` (Expected: 에러 없음)
```bash
git add types/dividend-report.ts
git commit -m "feat(dividend-fe): 배당·이자 보고서 본문 타입 (R1 #38 FE)"
```

---

## Task 3: 요약 카드 + 국가별 컴포넌트

**Files:**
- Create: `frontend/allfolio_app/components/dividend-report/DividendSummary.tsx`
- Create: `frontend/allfolio_app/components/dividend-report/ByCountryTable.tsx`

- [ ] **Step 1: DividendSummary 작성**

```tsx
// components/dividend-report/DividendSummary.tsx
import type { DividendSummary as Summary } from '@/types/dividend-report'
import { fmtKrw, fmtPctScaled } from '@/lib/report-format'

export function DividendSummary({ summary }: { summary: Summary }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">요약</h2>
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Card label="세전 총액" value={fmtKrw(summary.grossTotal)} />
        <Card label={`원천징수 (실효 ${fmtPctScaled(summary.effectiveTaxRate)})`} value={fmtKrw(summary.withholdingTax)} />
        <Card label="세후 실수령" value={fmtKrw(summary.netTotal)} />
        <Card label="TTM 배당수익률" value={fmtPctScaled(summary.ttmYield)} />
      </div>
      <p className="text-xs text-gray-500">수취 {summary.receiptCount}건 · 금액은 KRW 환산 기준</p>
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

- [ ] **Step 2: ByCountryTable 작성**

```tsx
// components/dividend-report/ByCountryTable.tsx
import type { DividendByCountry } from '@/types/dividend-report'
import { fmtKrw } from '@/lib/report-format'

export function ByCountryTable({ rows }: { rows: DividendByCountry[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">국가별 원천징수 요약</h2>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">국가</th><th className="p-3 text-right">세전</th>
              <th className="p-3 text-right">원천징수</th><th className="p-3 text-right">세후</th>
              <th className="p-3 text-right">실효세율</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.country} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3 font-medium text-gray-100">{r.country}</td>
                <td className="p-3 text-right tabular-nums">{fmtKrw(r.gross)}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{fmtKrw(r.tax)}</td>
                <td className="p-3 text-right tabular-nums">{fmtKrw(r.net)}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{r.effectiveTaxRate.toFixed(2)}%</td>
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

- [ ] **Step 3: 타입체크 + 커밋**

Run: `npx tsc --noEmit` (Expected: 에러 없음)
```bash
git add components/dividend-report/DividendSummary.tsx components/dividend-report/ByCountryTable.tsx
git commit -m "feat(dividend-fe): 요약 카드·국가별 원천징수 섹션 (R1 #38 FE)"
```

---

## Task 4: 수취 내역 + 종목별 테이블

**Files:**
- Create: `frontend/allfolio_app/components/dividend-report/ReceiptsTable.tsx`
- Create: `frontend/allfolio_app/components/dividend-report/BySymbolTable.tsx`

- [ ] **Step 1: ReceiptsTable 작성**

```tsx
// components/dividend-report/ReceiptsTable.tsx
import type { DividendReceipt } from '@/types/dividend-report'
import { fmtKrw } from '@/lib/report-format'

export function ReceiptsTable({ receipts }: { receipts: DividendReceipt[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">수취 내역</h2>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">지급일</th><th className="p-3">종목</th><th className="p-3">계좌</th>
              <th className="p-3 text-right">세전</th><th className="p-3 text-right">원천징수</th><th className="p-3 text-right">세후</th>
            </tr>
          </thead>
          <tbody>
            {receipts.map((r, i) => (
              <tr key={`${r.payDate}-${r.symbol}-${i}`} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3 text-gray-300">{r.payDate}</td>
                <td className="p-3">
                  <span className="font-medium text-gray-100">{r.stockName}</span>
                  {r.symbol && <span className="ml-2 text-xs text-gray-500">{r.symbol}</span>}
                </td>
                <td className="p-3 text-gray-400">{r.account}</td>
                <td className="p-3 text-right tabular-nums">{fmtKrw(r.gross)}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{fmtKrw(r.tax)}</td>
                <td className="p-3 text-right tabular-nums">{fmtKrw(r.net)}</td>
              </tr>
            ))}
            {receipts.length === 0 && (
              <tr><td colSpan={6} className="p-4 text-center text-gray-500">수취 내역이 없습니다.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}
```

- [ ] **Step 2: BySymbolTable 작성**

```tsx
// components/dividend-report/BySymbolTable.tsx
import type { DividendBySymbol } from '@/types/dividend-report'
import { fmtKrw } from '@/lib/report-format'

export function BySymbolTable({ rows }: { rows: DividendBySymbol[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">종목별 집계</h2>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">종목</th><th className="p-3 text-right">세전</th>
              <th className="p-3 text-right">원천징수</th><th className="p-3 text-right">세후</th>
              <th className="p-3 text-right">비중</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={`${r.symbol}-${r.stockName}-${i}`} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3">
                  <span className="font-medium text-gray-100">{r.stockName}</span>
                  {r.symbol && <span className="ml-2 text-xs text-gray-500">{r.symbol}</span>}
                </td>
                <td className="p-3 text-right tabular-nums">{fmtKrw(r.gross)}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{fmtKrw(r.tax)}</td>
                <td className="p-3 text-right tabular-nums">{fmtKrw(r.net)}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{r.weight.toFixed(2)}%</td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={5} className="p-4 text-center text-gray-500">종목이 없습니다.</td></tr>
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
git add components/dividend-report/ReceiptsTable.tsx components/dividend-report/BySymbolTable.tsx
git commit -m "feat(dividend-fe): 수취 내역·종목별 집계 테이블 (R1 #38 FE)"
```

---

## Task 5: 월별 추이 차트

**Files:**
- Create: `frontend/allfolio_app/components/dividend-report/MonthlyNetTrend.tsx`

- [ ] **Step 1: 컴포넌트 작성**

```tsx
// components/dividend-report/MonthlyNetTrend.tsx
'use client'

import {
  Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'
import type { DividendMonthly } from '@/types/dividend-report'
import { fmtKrw } from '@/lib/report-format'

export function MonthlyNetTrend({ rows }: { rows: DividendMonthly[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">월별 세후 수취 추이</h2>
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
                formatter={(v: number) => [fmtKrw(v), '세후']}
                contentStyle={{ background: '#111827', border: '1px solid #374151' }}
              />
              <Bar dataKey="net" fill="#10b981" radius={[4, 4, 0, 0]} />
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
git add components/dividend-report/MonthlyNetTrend.tsx
git commit -m "feat(dividend-fe): 월별 세후 수취 추이 차트 (R1 #38 FE)"
```

---

## Task 6: 상세 페이지 (조립 + 인쇄)

**Files:**
- Create: `frontend/allfolio_app/app/unified/reports/dividend-report/[id]/page.tsx`

- [ ] **Step 1: 상세 페이지 작성**

```tsx
// app/unified/reports/dividend-report/[id]/page.tsx
'use client'

import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useParams } from 'next/navigation'
import { useReportArchiveApi } from '@/lib/useApi'
import { parseReportBody } from '@/lib/report-archive-api'
import type { DividendReportBody } from '@/types/dividend-report'
import { DividendSummary } from '@/components/dividend-report/DividendSummary'
import { ReceiptsTable } from '@/components/dividend-report/ReceiptsTable'
import { MonthlyNetTrend } from '@/components/dividend-report/MonthlyNetTrend'
import { BySymbolTable } from '@/components/dividend-report/BySymbolTable'
import { ByCountryTable } from '@/components/dividend-report/ByCountryTable'

export default function DividendReportDetailPage() {
  const { id } = useParams<{ id: string }>()
  const api = useReportArchiveApi('DIVIDEND_INTEREST')
  const { data, isLoading, isError } = useQuery({
    queryKey: ['dividend-report', id],
    queryFn: async () => {
      const detail = await api!.detail(id)
      return { meta: detail.meta, body: parseReportBody<DividendReportBody>(detail.body) }
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
        <Link href="/unified/reports/dividend-report" className="text-sm text-gray-400 hover:text-gray-200">← 목록</Link>
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
          <Link href="/unified/reports/dividend-report" className="text-sm text-gray-500 hover:text-gray-300">← 목록</Link>
          <h1 className="text-2xl font-bold">{y}년 {Number(m)}월 배당·이자 보고서</h1>
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

      <DividendSummary summary={body.summary} />
      <ReceiptsTable receipts={body.receipts} />
      <MonthlyNetTrend rows={body.monthly} />
      <BySymbolTable rows={body.bySymbol} />
      <ByCountryTable rows={body.byCountry} />
    </div>
  )
}
```

- [ ] **Step 2: 타입체크 + 커밋**

Run: `npx tsc --noEmit` (Expected: 에러 없음)
```bash
git add "app/unified/reports/dividend-report/[id]/page.tsx"
git commit -m "feat(dividend-fe): 배당·이자 보고서 상세 화면 + 인쇄 (R1 #38 FE)"
```

---

## Task 7: 목록/생성 페이지

**Files:**
- Create: `frontend/allfolio_app/app/unified/reports/dividend-report/page.tsx`

- [ ] **Step 1: 목록/생성 페이지 작성**

```tsx
// app/unified/reports/dividend-report/page.tsx
'use client'

import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { useReportArchiveApi } from '@/lib/useApi'
import type { ArchiveMeta } from '@/types/report-archive'

const NOW = new Date()
const YEARS = Array.from({ length: 6 }, (_, i) => NOW.getFullYear() - i)
const MONTHS = Array.from({ length: 12 }, (_, i) => i + 1)

export default function DividendReportListPage() {
  const api = useReportArchiveApi('DIVIDEND_INTEREST')
  const router = useRouter()
  const qc = useQueryClient()
  const [year, setYear] = useState(NOW.getMonth() === 0 ? NOW.getFullYear() - 1 : NOW.getFullYear())
  const [month, setMonth] = useState(NOW.getMonth() === 0 ? 12 : NOW.getMonth())
  const [error, setError] = useState<string | null>(null)

  const { data: list, isLoading } = useQuery({
    queryKey: ['dividend-report', 'list'],
    queryFn: () => api!.list(),
    enabled: !!api,
    retry: false,
  })

  const gen = useMutation({
    mutationFn: () => api!.generate(year, month),
    onSuccess: (meta) => {
      qc.invalidateQueries({ queryKey: ['dividend-report', 'list'] })
      router.push(`/unified/reports/dividend-report/${meta.id}`)
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
        <h1 className="text-2xl font-bold">배당·이자 보고서</h1>
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
            아직 생성된 배당·이자 보고서가 없습니다. 위에서 연·월을 골라 생성하세요.
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
                    onClick={() => router.push(`/unified/reports/dividend-report/${r.id}`)}
                    onKeyDown={(e) => { if (e.key === 'Enter') router.push(`/unified/reports/dividend-report/${r.id}`) }}
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
git add app/unified/reports/dividend-report/page.tsx
git commit -m "feat(dividend-fe): 배당·이자 보고서 목록·생성 화면 (R1 #38 FE)"
```

---

## Task 8: 허브 카드 + 빌드 검증

**Files:**
- Modify: `frontend/allfolio_app/app/unified/reports/page.tsx`

- [ ] **Step 1: REPORTS 배열에 카드 추가**

`app/unified/reports/page.tsx`의 `REPORTS` 배열에서 `href: '/unified/reports/monthly-report'` 카드 객체 **바로 뒤**에 추가:
```tsx
  {
    href:  '/unified/reports/dividend-report',
    title: '배당·이자 보고서',
    desc:  'R-03 기관급 배당 리포트 — 세전·원천징수·세후, 종목·국가별 집계, PDF 인쇄',
    color: 'border-amber-700 hover:border-amber-500',
    badge: '💰',
  },
```

- [ ] **Step 2: 프로덕션 빌드 검증**

Run: `npx next build`
Expected: 성공. 라우트 목록에 `/unified/reports/dividend-report`(정적)와 `/unified/reports/dividend-report/[id]`(동적)가 나타나고, 기존 `/unified/reports/monthly-report` 계열도 그대로 컴파일.

- [ ] **Step 3: 커밋**

```bash
git add app/unified/reports/page.tsx
git commit -m "feat(dividend-fe): 보고서 허브에 배당·이자 카드 (R1 #38 FE)"
```

---

## Task 9: 브라우저 검증 + 스모크

**Files:** (없음 — 검증 전용)

- [ ] **Step 1: dev 기동** — preview_start(포트 3000). Bash로 서버 직접 기동 금지.
- [ ] **Step 2: 목록/생성** — `/unified/reports/dividend-report` → 연·월 선택 → 생성. #38 BE 미머지 시 400 배너, 머지 시 상세 이동. console 에러 없음 확인.
- [ ] **Step 3: 상세 렌더** — 6섹션(요약카드·수취내역·월별추이·종목별·국가별) + WARNING 배너, `ttmYield` null→"—". read_page + 스크린샷.
- [ ] **Step 4: 인쇄 미리보기** — 배경 반전·네비 숨김·손익색 보존(#37 CSS 공용).
- [ ] **Step 5: 최종 커밋(있으면)**

---

## 완료 기준

- `/unified/reports/dividend-report` 목록·생성, `/[id]` 상세·인쇄 동작, 6섹션 정상 + null 폴백
- 공유 아카이브 API/타입 일반화로 월간 화면(#37) 회귀 없음(`npx tsc --noEmit`·`next build` 통과)
- 레거시 `/reports/dividend` 영향 없음, 허브 카드 노출
- 세율마스터·이자·캘린더는 후속
