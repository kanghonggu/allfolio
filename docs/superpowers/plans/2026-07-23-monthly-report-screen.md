# 월간 운용보고서 화면 + PDF (R-01) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** #36 엔진이 아카이브한 R-01 월간 운용보고서 본문(JSON)을 목록·상세 2라우트로 렌더링하고 브라우저 인쇄(PDF)를 제공한다.

**Architecture:** Next.js App Router. `/api/reports/archive` (Bearer 인증, `JwtUserIdFilter`가 X-User-Id 서버측 추출) 위에 axios 클라이언트를 얹고, 상세 페이지는 프레젠테이션 섹션 컴포넌트로 분리해 조립한다. PDF는 신규 의존성 없이 `@media print` CSS + `window.print()`.

**Tech Stack:** TypeScript · Next.js(App Router) · @tanstack/react-query · axios · recharts · Tailwind (dark 테마). **테스트 러너 없음** — `next build` 타입체크 + 브라우저 프리뷰 + 스모크로 검증(기존 FE 패턴).

**Spec:** `docs/superpowers/specs/2026-07-23-monthly-report-screen-design.md`

---

## File Structure

- Create `frontend/allfolio_app/types/monthly-report.ts` — 아카이브 메타 + body JSON 타입
- Create `frontend/allfolio_app/lib/report-archive-api.ts` — `createReportArchiveApi(accessToken)`
- Modify `frontend/allfolio_app/lib/useApi.ts` — `useReportArchiveApi()` 훅 추가
- Create `frontend/allfolio_app/lib/report-format.ts` — 공유 포맷 헬퍼(fmtPct/fmtKrw/pctColor)
- Create `frontend/allfolio_app/components/monthly-report/PerformanceSummary.tsx`
- Create `frontend/allfolio_app/components/monthly-report/FlowWaterfall.tsx`
- Create `frontend/allfolio_app/components/monthly-report/TopHoldingsTable.tsx`
- Create `frontend/allfolio_app/components/monthly-report/ExposureCharts.tsx`
- Create `frontend/allfolio_app/components/monthly-report/AccountsTable.tsx`
- Create `frontend/allfolio_app/app/unified/reports/monthly-report/page.tsx` — 목록/생성
- Create `frontend/allfolio_app/app/unified/reports/monthly-report/[id]/page.tsx` — 상세/인쇄
- Modify `frontend/allfolio_app/app/globals.css` — `@media print` + `.no-print`
- Modify `frontend/allfolio_app/app/unified/reports/page.tsx` — 허브 카드 추가

모든 명령은 `frontend/allfolio_app/`에서 실행. 검증 기동은 `npm run dev`.

---

## Task 1: 타입 정의

**Files:**
- Create: `frontend/allfolio_app/types/monthly-report.ts`

- [ ] **Step 1: 타입 파일 작성**

`ReportArchiveController`의 `ArchiveMetaResponse`/`ArchiveDetailResponse`와 #36 엔진 body JSON을 그대로 미러링한다.

```typescript
// types/monthly-report.ts
export type ReportStatus = 'FINAL' | 'WARNING'   // 백엔드 enum ReportStatus { FINAL, WARNING }

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

export interface BenchmarkBlock {
  indexType: string
  label: string
  periodReturn: number | null
  excessReturn: number | null
}

export interface MonthPerformance {
  twr: number | null
  mwr: number | null
  startNav: number | null
  endNav: number | null
  netFlow: number
  investmentPnl: number | null
  benchmark: BenchmarkBlock | null
}

export interface StandardPeriod { twr: number | null }

export interface Performance {
  month: MonthPerformance
  standard: Partial<Record<'3M' | 'YTD' | '1Y' | 'SI', StandardPeriod>>
  volatility: number | null
}

export interface Holding {
  name: string
  symbol: string
  type: string
  quantity: number
  valueKrw: number
  weight: number
  returnRate: number | null
}

export interface ExposureRow { valueKrw: number; weight: number; type?: string; currency?: string }

export interface Exposure {
  byType: (ExposureRow & { type: string })[]
  byCurrency: (ExposureRow & { currency: string })[]
}

export interface AccountRow {
  accountName: string
  provider: string
  valueKrw: number
  weight: number
  assetCount: number
}

export interface FlowDecomposition {
  startNav: number
  netFlow: number
  investmentPnl: number
  endNav: number
}

export interface MonthlyReportBody {
  performance: Performance
  topHoldings: Holding[]
  exposure: Exposure
  accounts: AccountRow[]
  flowDecomposition: FlowDecomposition
  note: string
}

export interface ArchiveDetail {
  meta: ArchiveMeta
  body: string   // JSON 문자열 — parseMonthlyReportBody로 파싱
}
```

- [ ] **Step 2: 타입체크 통과 확인**

Run: `npx tsc --noEmit`
Expected: 새 파일로 인한 에러 없음(기존 에러가 있다면 무관한 것만).

- [ ] **Step 3: 커밋**

```bash
git add types/monthly-report.ts
git commit -m "feat(monthly-fe): 월간 운용보고서 아카이브·본문 타입 (R1 #37)"
```

---

## Task 2: API 클라이언트 + 훅

**Files:**
- Create: `frontend/allfolio_app/lib/report-archive-api.ts`
- Modify: `frontend/allfolio_app/lib/useApi.ts`

- [ ] **Step 1: 아카이브 API 클라이언트 작성**

기존 `report-api.ts` 패턴(Bearer, axios) 그대로. base는 `/api/reports/archive`.

```typescript
// lib/report-archive-api.ts
import axios from 'axios'
import type { ArchiveMeta, ArchiveDetail, MonthlyReportBody } from '@/types/monthly-report'

const BASE_URL = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'}/api/reports/archive`

export const MONTHLY_REPORT = 'MONTHLY_REPORT'

export function createReportArchiveApi(accessToken: string) {
  const api = axios.create({
    baseURL: BASE_URL,
    timeout: 30_000,
    headers: { Authorization: `Bearer ${accessToken}` },
  })

  return {
    generate: async (year: number, month: number): Promise<ArchiveMeta> =>
      (await api.post<ArchiveMeta>('/generate', { type: MONTHLY_REPORT, year, month })).data,

    list: async (): Promise<ArchiveMeta[]> =>
      (await api.get<ArchiveMeta[]>('', { params: { type: MONTHLY_REPORT } })).data,

    detail: async (id: string): Promise<ArchiveDetail> =>
      (await api.get<ArchiveDetail>(`/${id}`)).data,
  }
}

export function parseMonthlyReportBody(body: string): MonthlyReportBody {
  return JSON.parse(body) as MonthlyReportBody
}
```

- [ ] **Step 2: useApi.ts에 훅 추가**

`lib/useApi.ts` 상단 import 블록에 추가:

```typescript
import { createReportArchiveApi } from './report-archive-api'
```

파일 끝(마지막 훅 뒤)에 추가:

```typescript
export function useReportArchiveApi() {
  const { accessToken } = useAuth()
  return useMemo(
    () => (accessToken ? createReportArchiveApi(accessToken) : null),
    [accessToken],
  )
}
```

- [ ] **Step 3: 타입체크 통과 확인**

Run: `npx tsc --noEmit`
Expected: 새 파일/훅 관련 에러 없음.

- [ ] **Step 4: 커밋**

```bash
git add lib/report-archive-api.ts lib/useApi.ts
git commit -m "feat(monthly-fe): 아카이브 API 클라이언트 + useReportArchiveApi 훅 (R1 #37)"
```

---

## Task 3: 공유 포맷 헬퍼

**Files:**
- Create: `frontend/allfolio_app/lib/report-format.ts`

- [ ] **Step 1: 헬퍼 작성**

`returns/page.tsx`의 인라인 헬퍼를 공유 모듈로 승격(동일 동작). 상세 화면과 섹션 컴포넌트가 공유한다.

```typescript
// lib/report-format.ts
export function fmtPct(n: number | null | undefined): string {
  if (n === null || n === undefined) return '—'
  const pct = n * 100
  return `${pct >= 0 ? '+' : ''}${pct.toFixed(2)}%`
}

export function fmtKrw(n: number | null | undefined): string {
  if (n === null || n === undefined) return '—'
  return `${n >= 0 ? '' : '-'}₩${Math.abs(Math.round(n)).toLocaleString()}`
}

export function pctColor(n: number | null | undefined): string {
  if (n === null || n === undefined) return 'text-gray-400'
  return n >= 0 ? 'text-emerald-400' : 'text-red-400'
}
```

- [ ] **Step 2: 타입체크 + 커밋**

Run: `npx tsc --noEmit` (Expected: 에러 없음)

```bash
git add lib/report-format.ts
git commit -m "feat(monthly-fe): 공유 리포트 포맷 헬퍼 (R1 #37)"
```

---

## Task 4: 성과 요약 + 성과 상세 컴포넌트

**Files:**
- Create: `frontend/allfolio_app/components/monthly-report/PerformanceSummary.tsx`

- [ ] **Step 1: 컴포넌트 작성**

월간 KPI + 벤치마크 + 표준기간 TWR 테이블 + 변동성. `performance` prop만 받는 순수 프레젠테이션.

```tsx
// components/monthly-report/PerformanceSummary.tsx
import type { Performance } from '@/types/monthly-report'
import { fmtPct, fmtKrw, pctColor } from '@/lib/report-format'

const STANDARD_KEYS: Array<'3M' | 'YTD' | '1Y' | 'SI'> = ['3M', 'YTD', '1Y', 'SI']

export function PerformanceSummary({ perf }: { perf: Performance }) {
  const m = perf.month
  return (
    <section className="space-y-4 break-inside-avoid">
      <h2 className="text-lg font-semibold">성과 요약</h2>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Kpi label="TWR (시간가중)" value={fmtPct(m.twr)} color={pctColor(m.twr)} />
        <Kpi label="MWR (금액가중)" value={fmtPct(m.mwr)} color={pctColor(m.mwr)} />
        <Kpi label="기말 NAV" value={fmtKrw(m.endNav)} />
        <Kpi label="순증(입출금)" value={fmtKrw(m.netFlow)} color={pctColor(m.netFlow)} />
      </div>

      {m.benchmark && (
        <div className="rounded-xl border border-gray-700 bg-gray-900 p-4 text-sm">
          <p className="text-gray-400">
            벤치마크 <span className="text-gray-200">{m.benchmark.label}</span> 대비
          </p>
          <div className="mt-2 flex gap-6">
            <span>기간수익률 <b className={pctColor(m.benchmark.periodReturn)}>{fmtPct(m.benchmark.periodReturn)}</b></span>
            <span>초과수익 <b className={pctColor(m.benchmark.excessReturn)}>{fmtPct(m.benchmark.excessReturn)}</b></span>
          </div>
        </div>
      )}

      <div className="grid gap-4 sm:grid-cols-2">
        <div className="rounded-xl border border-gray-700 bg-gray-900 p-4">
          <p className="mb-2 text-xs text-gray-500">표준기간 TWR</p>
          <table className="w-full text-sm">
            <tbody>
              {STANDARD_KEYS.map((k) => (
                <tr key={k} className="border-t border-gray-800 first:border-t-0">
                  <td className="py-1.5 text-gray-400">{k}</td>
                  <td className={`py-1.5 text-right tabular-nums ${pctColor(perf.standard[k]?.twr)}`}>
                    {fmtPct(perf.standard[k]?.twr)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <Kpi label="연환산 변동성" value={fmtPct(perf.volatility)} />
      </div>
    </section>
  )
}

function Kpi({ label, value, color = 'text-gray-100' }: { label: string; value: string; color?: string }) {
  return (
    <div className="rounded-xl border border-gray-700 bg-gray-900 p-5">
      <p className="text-xs text-gray-500">{label}</p>
      <p className={`mt-2 text-xl font-bold tabular-nums ${color}`}>{value}</p>
    </div>
  )
}
```

- [ ] **Step 2: 타입체크 + 커밋**

Run: `npx tsc --noEmit` (Expected: 에러 없음)

```bash
git add components/monthly-report/PerformanceSummary.tsx
git commit -m "feat(monthly-fe): 성과 요약·상세 섹션 컴포넌트 (R1 #37)"
```

---

## Task 5: 입출금 효과 분해 워터폴

**Files:**
- Create: `frontend/allfolio_app/components/monthly-report/FlowWaterfall.tsx`

- [ ] **Step 1: 컴포넌트 작성**

#34 워터폴 패턴을 `flowDecomposition`(startNav→netFlow→investmentPnl→endNav)에 맞춰 재구성. netFlow는 단일 순유입 스텝(부호에 따라 색상).

```tsx
// components/monthly-report/FlowWaterfall.tsx
'use client'

import {
  Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'
import type { FlowDecomposition } from '@/types/monthly-report'
import { fmtKrw } from '@/lib/report-format'

export function FlowWaterfall({ flow }: { flow: FlowDecomposition }) {
  let running = flow.startNav
  const steps: { name: string; base: number; value: number; color: string }[] = [
    { name: '기초 NAV', base: 0, value: flow.startNav, color: '#6b7280' },
  ]
  steps.push({
    name: '순유입',
    base: flow.netFlow >= 0 ? running : running + flow.netFlow,
    value: Math.abs(flow.netFlow),
    color: flow.netFlow >= 0 ? '#10b981' : '#ef4444',
  })
  running += flow.netFlow
  steps.push({
    name: '투자손익',
    base: flow.investmentPnl >= 0 ? running : running + flow.investmentPnl,
    value: Math.abs(flow.investmentPnl),
    color: flow.investmentPnl >= 0 ? '#34d399' : '#f87171',
  })
  steps.push({ name: '기말 NAV', base: 0, value: flow.endNav, color: '#3b82f6' })

  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">입출금 효과 분해</h2>
      <div className="rounded-xl border border-gray-700 bg-gray-900 p-4">
        <ResponsiveContainer width="100%" height={280}>
          <BarChart data={steps}>
            <CartesianGrid strokeDasharray="3 3" stroke="#374151" />
            <XAxis dataKey="name" tick={{ fill: '#9ca3af', fontSize: 12 }} />
            <YAxis tickFormatter={(v) => fmtKrw(v)} tick={{ fill: '#9ca3af', fontSize: 11 }} width={80} />
            <Tooltip
              formatter={(v: number) => fmtKrw(v)}
              contentStyle={{ background: '#111827', border: '1px solid #374151' }}
            />
            <Bar dataKey="base" stackId="wf" fill="transparent" />
            <Bar dataKey="value" stackId="wf" radius={[4, 4, 0, 0]}>
              {steps.map((s) => <Cell key={s.name} fill={s.color} />)}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>
    </section>
  )
}
```

- [ ] **Step 2: 타입체크 + 커밋**

Run: `npx tsc --noEmit` (Expected: 에러 없음)

```bash
git add components/monthly-report/FlowWaterfall.tsx
git commit -m "feat(monthly-fe): 입출금 효과 분해 워터폴 (R1 #37)"
```

---

## Task 6: Top10 보유 + 계좌별 테이블

**Files:**
- Create: `frontend/allfolio_app/components/monthly-report/TopHoldingsTable.tsx`
- Create: `frontend/allfolio_app/components/monthly-report/AccountsTable.tsx`

- [ ] **Step 1: TopHoldingsTable 작성**

```tsx
// components/monthly-report/TopHoldingsTable.tsx
import type { Holding } from '@/types/monthly-report'
import { fmtKrw, fmtPct, pctColor } from '@/lib/report-format'

export function TopHoldingsTable({ holdings }: { holdings: Holding[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">상위 보유 종목 (Top 10)</h2>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">종목</th><th className="p-3">유형</th>
              <th className="p-3 text-right">수량</th><th className="p-3 text-right">평가액</th>
              <th className="p-3 text-right">비중</th><th className="p-3 text-right">수익률</th>
            </tr>
          </thead>
          <tbody>
            {holdings.map((h) => (
              <tr key={`${h.symbol}-${h.name}`} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3">
                  <span className="font-medium text-gray-100">{h.name}</span>
                  <span className="ml-2 text-xs text-gray-500">{h.symbol}</span>
                </td>
                <td className="p-3 text-gray-400">{h.type}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{h.quantity.toLocaleString()}</td>
                <td className="p-3 text-right tabular-nums">{fmtKrw(h.valueKrw)}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{h.weight.toFixed(2)}%</td>
                <td className={`p-3 text-right tabular-nums ${pctColor(h.returnRate)}`}>{fmtPct(h.returnRate)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}
```

- [ ] **Step 2: AccountsTable 작성**

```tsx
// components/monthly-report/AccountsTable.tsx
import type { AccountRow } from '@/types/monthly-report'
import { fmtKrw } from '@/lib/report-format'

export function AccountsTable({ accounts }: { accounts: AccountRow[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">계좌별 현황</h2>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">계좌</th><th className="p-3">증권사</th>
              <th className="p-3 text-right">평가액</th><th className="p-3 text-right">비중</th>
              <th className="p-3 text-right">자산수</th>
            </tr>
          </thead>
          <tbody>
            {accounts.map((a) => (
              <tr key={`${a.provider}-${a.accountName}`} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3 font-medium text-gray-100">{a.accountName}</td>
                <td className="p-3 text-gray-400">{a.provider}</td>
                <td className="p-3 text-right tabular-nums">{fmtKrw(a.valueKrw)}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{a.weight.toFixed(2)}%</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{a.assetCount}</td>
              </tr>
            ))}
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
git add components/monthly-report/TopHoldingsTable.tsx components/monthly-report/AccountsTable.tsx
git commit -m "feat(monthly-fe): Top10 보유·계좌별 테이블 (R1 #37)"
```

---

## Task 7: 익스포저 차트

**Files:**
- Create: `frontend/allfolio_app/components/monthly-report/ExposureCharts.tsx`

- [ ] **Step 1: 컴포넌트 작성**

유형별·통화별 도넛 2개 나란히. 항목이 8개를 넘으면 상위 7 + "기타"로 병합.

```tsx
// components/monthly-report/ExposureCharts.tsx
'use client'

import { Cell, Legend, Pie, PieChart, ResponsiveContainer, Tooltip } from 'recharts'
import type { Exposure } from '@/types/monthly-report'
import { fmtKrw } from '@/lib/report-format'

const COLORS = ['#3b82f6', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#06b6d4', '#ec4899', '#6b7280']

function collapse(rows: { label: string; valueKrw: number }[]) {
  if (rows.length <= 8) return rows
  const sorted = [...rows].sort((a, b) => b.valueKrw - a.valueKrw)
  const head = sorted.slice(0, 7)
  const rest = sorted.slice(7).reduce((a, r) => a + r.valueKrw, 0)
  return [...head, { label: '기타', valueKrw: rest }]
}

function Donut({ title, data }: { title: string; data: { label: string; valueKrw: number }[] }) {
  const rows = collapse(data)
  return (
    <div className="rounded-xl border border-gray-700 bg-gray-900 p-4">
      <p className="mb-2 text-xs text-gray-500">{title}</p>
      <ResponsiveContainer width="100%" height={240}>
        <PieChart>
          <Pie data={rows} dataKey="valueKrw" nameKey="label" innerRadius={50} outerRadius={80} paddingAngle={2}>
            {rows.map((_, i) => <Cell key={i} fill={COLORS[i % COLORS.length]} />)}
          </Pie>
          <Tooltip formatter={(v: number) => fmtKrw(v)} contentStyle={{ background: '#111827', border: '1px solid #374151' }} />
          <Legend wrapperStyle={{ fontSize: 12 }} />
        </PieChart>
      </ResponsiveContainer>
    </div>
  )
}

export function ExposureCharts({ exposure }: { exposure: Exposure }) {
  const byType = exposure.byType.map((r) => ({ label: r.type, valueKrw: r.valueKrw }))
  const byCurrency = exposure.byCurrency.map((r) => ({ label: r.currency, valueKrw: r.valueKrw }))
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">익스포저</h2>
      <div className="grid gap-4 sm:grid-cols-2">
        <Donut title="자산유형별" data={byType} />
        <Donut title="통화별" data={byCurrency} />
      </div>
    </section>
  )
}
```

- [ ] **Step 2: 타입체크 + 커밋**

Run: `npx tsc --noEmit` (Expected: 에러 없음)

```bash
git add components/monthly-report/ExposureCharts.tsx
git commit -m "feat(monthly-fe): 유형·통화별 익스포저 도넛 (R1 #37)"
```

---

## Task 8: 인쇄 CSS

**Files:**
- Modify: `frontend/allfolio_app/app/globals.css`

- [ ] **Step 1: globals.css 끝에 인쇄 규칙 추가**

화면 전용 요소는 `.no-print`, 인쇄 시 배경 흰색 반전, 섹션 페이지 나눔.

```css
/* === 월간 운용보고서 인쇄 (#37) === */
@media print {
  .no-print { display: none !important; }
  body { background: #ffffff !important; color: #111111 !important; }
  .print-invert, .print-invert * {
    background: #ffffff !important;
    color: #111111 !important;
    border-color: #d1d5db !important;
  }
  section { break-inside: avoid; }
  @page { margin: 14mm; }
}
```

- [ ] **Step 2: 타입체크(빌드 영향 없음) + 커밋**

```bash
git add app/globals.css
git commit -m "feat(monthly-fe): @media print 인쇄 레이아웃 규칙 (R1 #37)"
```

---

## Task 9: 상세 페이지 (조립 + 인쇄)

**Files:**
- Create: `frontend/allfolio_app/app/unified/reports/monthly-report/[id]/page.tsx`

- [ ] **Step 1: 상세 페이지 작성**

`detail(id)` 조회 → body parse → 섹션 조립. 헤더/버튼은 `.no-print`, 본문은 `.print-invert`.

```tsx
// app/unified/reports/monthly-report/[id]/page.tsx
'use client'

import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useParams } from 'next/navigation'
import { useReportArchiveApi, parseMonthlyReportBody } from '@/lib/report-archive-api'
import { PerformanceSummary } from '@/components/monthly-report/PerformanceSummary'
import { FlowWaterfall } from '@/components/monthly-report/FlowWaterfall'
import { TopHoldingsTable } from '@/components/monthly-report/TopHoldingsTable'
import { ExposureCharts } from '@/components/monthly-report/ExposureCharts'
import { AccountsTable } from '@/components/monthly-report/AccountsTable'

export default function MonthlyReportDetailPage() {
  const { id } = useParams<{ id: string }>()
  const api = useReportArchiveApi()
  const { data, isLoading, isError } = useQuery({
    queryKey: ['monthly-report', id],
    queryFn: () => api!.detail(id),
    enabled: !!api && !!id,
  })

  if (isLoading) return <div className="h-96 animate-pulse rounded-xl bg-gray-800" />
  if (isError || !data) {
    return (
      <div className="space-y-4">
        <div className="rounded-xl border border-red-800 bg-red-950 p-6 text-sm text-red-400">
          보고서를 찾을 수 없습니다.
        </div>
        <Link href="/unified/reports/monthly-report" className="text-sm text-gray-400 hover:text-gray-200">← 목록</Link>
      </div>
    )
  }

  const { meta } = data
  const body = parseMonthlyReportBody(data.body)
  const [y, m] = [meta.periodStart.slice(0, 4), meta.periodStart.slice(5, 7)]

  return (
    <div className="space-y-8 print-invert">
      <div className="flex items-center justify-between gap-3 no-print">
        <div className="flex items-center gap-3">
          <Link href="/unified/reports/monthly-report" className="text-sm text-gray-500 hover:text-gray-300">← 목록</Link>
          <h1 className="text-2xl font-bold">{y}년 {Number(m)}월 운용보고서</h1>
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

      <PerformanceSummary perf={body.performance} />
      <FlowWaterfall flow={body.flowDecomposition} />
      <TopHoldingsTable holdings={body.topHoldings} />
      <ExposureCharts exposure={body.exposure} />
      <AccountsTable accounts={body.accounts} />

      <p className="text-xs text-gray-500">{body.note}</p>
    </div>
  )
}
```

- [ ] **Step 2: 타입체크 통과 확인**

Run: `npx tsc --noEmit`
Expected: 에러 없음.

- [ ] **Step 3: 커밋**

```bash
git add app/unified/reports/monthly-report/\[id\]/page.tsx
git commit -m "feat(monthly-fe): 월간 운용보고서 상세 화면 + 인쇄 (R1 #37)"
```

---

## Task 10: 목록/생성 페이지

**Files:**
- Create: `frontend/allfolio_app/app/unified/reports/monthly-report/page.tsx`

- [ ] **Step 1: 목록/생성 페이지 작성**

연·월 선택 + 생성(성공 시 상세 이동) + 과거 아카이브 목록. 생성 400은 인라인 배너.

```tsx
// app/unified/reports/monthly-report/page.tsx
'use client'

import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { useReportArchiveApi } from '@/lib/report-archive-api'
import type { ArchiveMeta } from '@/types/monthly-report'

const NOW = new Date()
const YEARS = Array.from({ length: 6 }, (_, i) => NOW.getFullYear() - i)
const MONTHS = Array.from({ length: 12 }, (_, i) => i + 1)

export default function MonthlyReportListPage() {
  const api = useReportArchiveApi()
  const router = useRouter()
  const qc = useQueryClient()
  const [year, setYear] = useState(NOW.getFullYear())
  const [month, setMonth] = useState(NOW.getMonth() === 0 ? 12 : NOW.getMonth())
  const [error, setError] = useState<string | null>(null)

  const { data: list, isLoading } = useQuery({
    queryKey: ['monthly-report', 'list'],
    queryFn: () => api!.list(),
    enabled: !!api,
  })

  const gen = useMutation({
    mutationFn: () => api!.generate(year, month),
    onSuccess: (meta) => {
      qc.invalidateQueries({ queryKey: ['monthly-report', 'list'] })
      router.push(`/unified/reports/monthly-report/${meta.id}`)
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
        <h1 className="text-2xl font-bold">월간 운용보고서</h1>
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
        {isLoading ? (
          <div className="h-32 animate-pulse rounded-xl bg-gray-800" />
        ) : !list || list.length === 0 ? (
          <p className="rounded-xl border border-gray-800 bg-gray-900 p-6 text-center text-sm text-gray-500">
            아직 생성된 월간 운용보고서가 없습니다. 위에서 연·월을 골라 생성하세요.
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
                    onClick={() => router.push(`/unified/reports/monthly-report/${r.id}`)}
                    className="cursor-pointer border-b border-gray-800 last:border-b-0 hover:bg-gray-800/50"
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

- [ ] **Step 2: 타입체크 통과 확인**

Run: `npx tsc --noEmit`
Expected: 에러 없음.

- [ ] **Step 3: 커밋**

```bash
git add app/unified/reports/monthly-report/page.tsx
git commit -m "feat(monthly-fe): 월간 운용보고서 목록·생성 화면 (R1 #37)"
```

---

## Task 11: 보고서 허브 카드

**Files:**
- Modify: `frontend/allfolio_app/app/unified/reports/page.tsx`

- [ ] **Step 1: REPORTS 배열에 카드 추가**

`app/unified/reports/page.tsx`의 `REPORTS` 배열에서 `returns` 카드 객체 바로 뒤에 아래 항목을 추가한다.

```tsx
  {
    href:  '/unified/reports/monthly-report',
    title: '월간 운용보고서',
    desc:  'R-01 기관급 월간 리포트 — 성과·익스포저·계좌·입출금 분해, PDF 인쇄',
    color: 'border-indigo-700 hover:border-indigo-500',
    badge: '📄',
  },
```

- [ ] **Step 2: 타입체크 통과 확인**

Run: `npx tsc --noEmit`
Expected: 에러 없음.

- [ ] **Step 3: 커밋**

```bash
git add app/unified/reports/page.tsx
git commit -m "feat(monthly-fe): 보고서 허브에 월간 운용보고서 카드 (R1 #37)"
```

---

## Task 12: 브라우저 검증 + 스모크

**Files:** (없음 — 검증 전용)

- [ ] **Step 1: dev 서버 기동**

`.claude/launch.json`에 프론트 dev 설정이 없으면 추가 후 preview_start로 기동(포트는 next 기본 3000). Bash로 서버를 직접 띄우지 말 것.

- [ ] **Step 2: 목록/생성 흐름 확인**

`/unified/reports/monthly-report` 접속 → 연·월 선택 → "보고서 생성" 클릭.
확인: 데이터가 있으면 상세로 이동, 부족하면 빨간 에러 배너. read_console_messages로 에러 없음 확인.

- [ ] **Step 3: 상세 렌더 확인**

상세 페이지에서 8개 섹션 존재 확인 — 성과 요약(KPI·벤치마크·표준기간·변동성), 워터폴 4단, Top10 테이블, 익스포저 도넛 2개, 계좌별 테이블, note 각주. benchmark/volatility가 null인 케이스는 "—" 표기.
read_page로 구조 확인 + 스크린샷 1장.

- [ ] **Step 4: 인쇄 미리보기 확인**

상세에서 인쇄 버튼 → 브라우저 프린트 미리보기. 배경 흰색 반전, 네비/버튼 숨김(`.no-print`), 섹션 페이지 나눔 확인. (프린트 미리보기는 스크린샷 또는 수동 확인.)

- [ ] **Step 5: 빈/에러 상태 확인**

이력이 없는 계정에서 빈 목록 안내 문구, 없는 id로 상세 접속 시 "보고서를 찾을 수 없습니다" 확인.

- [ ] **Step 6: 최종 커밋(있으면) + 완료**

검증 중 수정이 있었으면 커밋. 없으면 이 태스크는 커밋 없이 완료.

---

## 완료 기준

- `/unified/reports/monthly-report` 목록·생성, `/[id]` 상세·인쇄 동작
- 8개 섹션 정상 렌더 + null 폴백("—") + WARNING 배너
- 브라우저 인쇄로 PDF 저장 가능(배경 반전·버튼 숨김·페이지 나눔)
- 레거시 `/reports/monthly`(월별 손익 정산) 영향 없음
- `npx tsc --noEmit` 통과, 보고서 허브에 카드 노출
