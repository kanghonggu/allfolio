# 현금흐름 보고서 화면 (R-06, SCR-RPT-09) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** #46 BE 엔진이 아카이브한 `CASHFLOW` 본문을 목록·상세 2라우트로 렌더링하고 브라우저 인쇄(PDF)를 제공한다.

**Architecture:** Next.js App Router. #39/#40 화면과 동형, main의 일반화된 아카이브 인프라 재사용. `report-archive-api.ts`에 `CASHFLOW` 상수만 추가하고, 현금흐름 타입·섹션 컴포넌트·2페이지를 신규 작성. 프레젠테이션 컴포넌트는 typed props만 받는다. 금액만 다루므로 퍼센트 스케일 이슈 없음.

**Tech Stack:** TypeScript · Next.js(App Router) · @tanstack/react-query · recharts · Tailwind. **테스트 러너 없음** — `next build` + 브라우저 프리뷰 검증.

**Spec:** `docs/superpowers/specs/2026-07-28-cashflow-report-screen-design.md`

**주의:** 브랜치 `feat/cashflow-report-screen`는 main에서 분기(스택 없음). 명령은 `frontend/allfolio_app/`에서. 검증: `npx tsc --noEmit`, 최종 `npx next build`. `tsconfig.tsbuildinfo` 커밋 금지(명시 경로 add).

---

## File Structure

- Modify `lib/report-archive-api.ts` — `CASHFLOW` 상수 + `ReportType` 유니온 확장
- Create `types/cashflow-report.ts`
- Create `components/cashflow-report/{CashflowSummary,CashflowByType,MonthlyCashflowChart,CashflowDetails}.tsx`
- Create `app/unified/reports/cashflow-report/page.tsx`, `.../[id]/page.tsx`
- Modify `app/unified/reports/page.tsx` — 허브 카드

---

## Task 1: CASHFLOW 상수 + 현금흐름 타입

**Files:**
- Modify: `frontend/allfolio_app/lib/report-archive-api.ts`
- Create: `frontend/allfolio_app/types/cashflow-report.ts`

- [ ] **Step 1: report-archive-api.ts에 CASHFLOW 추가**

`export const HOLDINGS = 'HOLDINGS'` 다음 줄에 추가:
```typescript
export const CASHFLOW = 'CASHFLOW'
```
`ReportType` 유니온 확장(기존 유니온 끝에 `| typeof CASHFLOW` 추가):
```typescript
export type ReportType = typeof MONTHLY_REPORT | typeof DIVIDEND_INTEREST | typeof COST | typeof HOLDINGS | typeof CASHFLOW
```

- [ ] **Step 2: 현금흐름 타입 작성**

`types/cashflow-report.ts`:
```typescript
// types/cashflow-report.ts
import type { ArchiveMeta, ArchiveDetail } from './report-archive'
export type { ArchiveMeta, ArchiveDetail }

export interface CashflowSummary {
  totalInflow: number
  totalOutflow: number
  netFlow: number            // 부호 (유출 초과 시 음수)
}

export interface CashflowByTypeRow {
  type: string
  amount: number             // 부호 (유출 음수)
  direction: 'IN' | 'OUT'
}

export interface CashflowMonthly {
  month: string              // "YYYY-MM"
  inflow: number
  outflow: number
  net: number
}

export interface CashflowDetail {
  date: string               // "YYYY-MM-DD"
  account: string
  type: string
  description: string
  amount: number             // 부호
}

export interface CashflowReportBody {
  summary: CashflowSummary
  byType: CashflowByTypeRow[]
  monthly: CashflowMonthly[]
  details: CashflowDetail[]
}
```

- [ ] **Step 3: 타입체크 + 커밋**

Run: `npx tsc --noEmit` (Expected: 에러 없음 — 기존 4화면 회귀 없음)
```bash
git add lib/report-archive-api.ts types/cashflow-report.ts
git commit -m "feat(cashflow-fe): CASHFLOW 리포트타입 + 현금흐름 본문 타입 (R2 #41 FE)"
```

---

## Task 2: 요약 카드 + 유형별 현금흐름

**Files:**
- Create: `frontend/allfolio_app/components/cashflow-report/CashflowSummary.tsx`
- Create: `frontend/allfolio_app/components/cashflow-report/CashflowByType.tsx`

- [ ] **Step 1: CashflowSummary 작성**

```tsx
// components/cashflow-report/CashflowSummary.tsx
import type { CashflowSummary as Summary } from '@/types/cashflow-report'
import { fmtKrw, pctColor } from '@/lib/report-format'

export function CashflowSummary({ summary }: { summary: Summary }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">요약</h2>
      <div className="grid gap-4 sm:grid-cols-3">
        <Card label="총유입" value={fmtKrw(summary.totalInflow)} color="text-emerald-400" />
        <Card label="총유출" value={fmtKrw(summary.totalOutflow)} color="text-red-400" />
        <Card label="순현금흐름" value={fmtKrw(summary.netFlow)} color={pctColor(summary.netFlow)} />
      </div>
    </section>
  )
}

function Card({ label, value, color }: { label: string; value: string; color: string }) {
  return (
    <div className="rounded-xl border border-gray-700 bg-gray-900 p-5">
      <p className="text-xs text-gray-500">{label}</p>
      <p className={`mt-2 text-xl font-bold tabular-nums ${color}`}>{value}</p>
    </div>
  )
}
```

- [ ] **Step 2: CashflowByType 작성 (부호 바차트 + 테이블)**

```tsx
// components/cashflow-report/CashflowByType.tsx
'use client'

import { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { CashflowByTypeRow } from '@/types/cashflow-report'
import { fmtKrw, pctColor } from '@/lib/report-format'

export function CashflowByType({ rows }: { rows: CashflowByTypeRow[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">유형별 현금흐름</h2>
      <div className="rounded-xl border border-gray-700 bg-gray-900 p-4">
        {rows.length === 0 ? (
          <div className="flex h-[240px] items-center justify-center text-sm text-gray-500">데이터 없음</div>
        ) : (
          <ResponsiveContainer width="100%" height={240}>
            <BarChart data={rows}>
              <CartesianGrid strokeDasharray="3 3" stroke="#374151" />
              <XAxis dataKey="type" tick={{ fill: '#9ca3af', fontSize: 11 }} />
              <YAxis tickFormatter={(v) => fmtKrw(v)} tick={{ fill: '#9ca3af', fontSize: 11 }} width={80} />
              <Tooltip
                formatter={(v: number) => [fmtKrw(v), '금액']}
                contentStyle={{ background: '#111827', border: '1px solid #374151' }}
              />
              <Bar dataKey="amount" radius={[4, 4, 0, 0]}>
                {rows.map((r) => (
                  <Cell key={r.type} fill={r.direction === 'IN' ? '#34d399' : '#f87171'} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        )}
      </div>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">유형</th><th className="p-3 text-right">금액</th><th className="p-3">방향</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.type} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3 font-medium text-gray-100">{r.type}</td>
                <td className={`p-3 text-right tabular-nums ${pctColor(r.amount)}`}>{fmtKrw(r.amount)}</td>
                <td className="p-3 text-gray-400">{r.direction === 'IN' ? '유입' : '유출'}</td>
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
git add components/cashflow-report/CashflowSummary.tsx components/cashflow-report/CashflowByType.tsx
git commit -m "feat(cashflow-fe): 요약 카드·유형별 현금흐름 (R2 #41 FE)"
```

---

## Task 3: 월별 추이 + 상세 내역

**Files:**
- Create: `frontend/allfolio_app/components/cashflow-report/MonthlyCashflowChart.tsx`
- Create: `frontend/allfolio_app/components/cashflow-report/CashflowDetails.tsx`

- [ ] **Step 1: MonthlyCashflowChart 작성 (그룹 바 + 순흐름 라인)**

```tsx
// components/cashflow-report/MonthlyCashflowChart.tsx
'use client'

import {
  Bar, CartesianGrid, ComposedChart, Legend, Line, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'
import type { CashflowMonthly } from '@/types/cashflow-report'
import { fmtKrw } from '@/lib/report-format'

export function MonthlyCashflowChart({ rows }: { rows: CashflowMonthly[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">월별 추이</h2>
      <div className="rounded-xl border border-gray-700 bg-gray-900 p-4">
        {rows.length === 0 ? (
          <div className="flex h-[260px] items-center justify-center text-sm text-gray-500">데이터 없음</div>
        ) : (
          <ResponsiveContainer width="100%" height={260}>
            <ComposedChart data={rows}>
              <CartesianGrid strokeDasharray="3 3" stroke="#374151" />
              <XAxis dataKey="month" tick={{ fill: '#9ca3af', fontSize: 12 }} />
              <YAxis tickFormatter={(v) => fmtKrw(v)} tick={{ fill: '#9ca3af', fontSize: 11 }} width={80} />
              <Tooltip
                formatter={(v: number, name: string) => [fmtKrw(v), name]}
                contentStyle={{ background: '#111827', border: '1px solid #374151' }}
              />
              <Legend formatter={(v) => <span className="text-xs text-gray-300">{v}</span>} />
              <Bar dataKey="inflow" fill="#34d399" name="유입" radius={[4, 4, 0, 0]} />
              <Bar dataKey="outflow" fill="#f87171" name="유출" radius={[4, 4, 0, 0]} />
              <Line dataKey="net" stroke="#60a5fa" name="순흐름" strokeWidth={2} dot={{ r: 3 }} />
            </ComposedChart>
          </ResponsiveContainer>
        )}
      </div>
    </section>
  )
}
```

- [ ] **Step 2: CashflowDetails 작성**

```tsx
// components/cashflow-report/CashflowDetails.tsx
import type { CashflowDetail } from '@/types/cashflow-report'
import { fmtKrw, pctColor } from '@/lib/report-format'

export function CashflowDetails({ rows }: { rows: CashflowDetail[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">상세 내역</h2>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">일자</th><th className="p-3">계좌</th><th className="p-3">유형</th>
              <th className="p-3">설명</th><th className="p-3 text-right">금액</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={`${r.date}-${r.description}-${i}`} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3 text-gray-300">{r.date}</td>
                <td className="p-3 text-gray-400">{r.account}</td>
                <td className="p-3 text-gray-400">{r.type}</td>
                <td className="p-3 font-medium text-gray-100">{r.description}</td>
                <td className={`p-3 text-right tabular-nums ${pctColor(r.amount)}`}>{fmtKrw(r.amount)}</td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={5} className="p-4 text-center text-gray-500">내역이 없습니다.</td></tr>
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
git add components/cashflow-report/MonthlyCashflowChart.tsx components/cashflow-report/CashflowDetails.tsx
git commit -m "feat(cashflow-fe): 월별 추이 차트·상세 내역 (R2 #41 FE)"
```

---

## Task 4: 상세 페이지 (조립 + 인쇄)

**Files:**
- Create: `frontend/allfolio_app/app/unified/reports/cashflow-report/[id]/page.tsx`

- [ ] **Step 1: 상세 페이지 작성**

```tsx
// app/unified/reports/cashflow-report/[id]/page.tsx
'use client'

import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useParams } from 'next/navigation'
import { useReportArchiveApi } from '@/lib/useApi'
import { parseReportBody, CASHFLOW } from '@/lib/report-archive-api'
import type { CashflowReportBody } from '@/types/cashflow-report'
import { CashflowSummary } from '@/components/cashflow-report/CashflowSummary'
import { CashflowByType } from '@/components/cashflow-report/CashflowByType'
import { MonthlyCashflowChart } from '@/components/cashflow-report/MonthlyCashflowChart'
import { CashflowDetails } from '@/components/cashflow-report/CashflowDetails'

export default function CashflowReportDetailPage() {
  const { id } = useParams<{ id: string }>()
  const api = useReportArchiveApi(CASHFLOW)
  const { data, isLoading, isError } = useQuery({
    queryKey: ['cashflow-report', id],
    queryFn: async () => {
      const detail = await api!.detail(id)
      return { meta: detail.meta, body: parseReportBody<CashflowReportBody>(detail.body) }
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
        <Link href="/unified/reports/cashflow-report" className="text-sm text-gray-400 hover:text-gray-200">← 목록</Link>
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
          <Link href="/unified/reports/cashflow-report" className="text-sm text-gray-500 hover:text-gray-300">← 목록</Link>
          <h1 className="text-2xl font-bold">{y}년 {Number(m)}월 현금흐름 보고서</h1>
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

      <CashflowSummary summary={body.summary} />
      <CashflowByType rows={body.byType} />
      <MonthlyCashflowChart rows={body.monthly} />
      <CashflowDetails rows={body.details} />
    </div>
  )
}
```

- [ ] **Step 2: 타입체크 + 커밋**

Run: `npx tsc --noEmit` (Expected: 에러 없음)
```bash
git add "app/unified/reports/cashflow-report/[id]/page.tsx"
git commit -m "feat(cashflow-fe): 현금흐름 보고서 상세 화면 + 인쇄 (R2 #41 FE)"
```

---

## Task 5: 목록/생성 페이지

**Files:**
- Create: `frontend/allfolio_app/app/unified/reports/cashflow-report/page.tsx`

- [ ] **Step 1: 목록/생성 페이지 작성**

```tsx
// app/unified/reports/cashflow-report/page.tsx
'use client'

import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { useReportArchiveApi } from '@/lib/useApi'
import { CASHFLOW } from '@/lib/report-archive-api'
import type { ArchiveMeta } from '@/types/report-archive'

const NOW = new Date()
const YEARS = Array.from({ length: 6 }, (_, i) => NOW.getFullYear() - i)
const MONTHS = Array.from({ length: 12 }, (_, i) => i + 1)

export default function CashflowReportListPage() {
  const api = useReportArchiveApi(CASHFLOW)
  const router = useRouter()
  const qc = useQueryClient()
  const [year, setYear] = useState(NOW.getMonth() === 0 ? NOW.getFullYear() - 1 : NOW.getFullYear())
  const [month, setMonth] = useState(NOW.getMonth() === 0 ? 12 : NOW.getMonth())
  const [error, setError] = useState<string | null>(null)

  const { data: list, isLoading } = useQuery({
    queryKey: ['cashflow-report', 'list'],
    queryFn: () => api!.list(),
    enabled: !!api,
    retry: false,
  })

  const gen = useMutation({
    mutationFn: () => api!.generate(year, month),
    onSuccess: (meta) => {
      qc.invalidateQueries({ queryKey: ['cashflow-report', 'list'] })
      router.push(`/unified/reports/cashflow-report/${meta.id}`)
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
        <h1 className="text-2xl font-bold">현금흐름 보고서</h1>
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
            아직 생성된 현금흐름 보고서가 없습니다. 위에서 연·월을 골라 생성하세요.
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
                    onClick={() => router.push(`/unified/reports/cashflow-report/${r.id}`)}
                    onKeyDown={(e) => { if (e.key === 'Enter') router.push(`/unified/reports/cashflow-report/${r.id}`) }}
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
git add app/unified/reports/cashflow-report/page.tsx
git commit -m "feat(cashflow-fe): 현금흐름 보고서 목록·생성 화면 (R2 #41 FE)"
```

---

## Task 6: 허브 카드 + 빌드 검증

**Files:**
- Modify: `frontend/allfolio_app/app/unified/reports/page.tsx`

- [ ] **Step 1: REPORTS 배열에 카드 추가**

`app/unified/reports/page.tsx`의 `REPORTS` 배열에서 `href: '/unified/reports/holdings-report'` 카드 객체 **바로 뒤**에 추가. IMPORTANT: 배지 `💧`·색 `border-cyan-`가 허브 다른 카드와 중복되지 않는지 grep 확인, 충돌 시 미사용 이모지/색으로 교체:
```tsx
  {
    href:  '/unified/reports/cashflow-report',
    title: '현금흐름 보고서',
    desc:  'R-06 기관급 현금흐름 — 입금·출금·매수·매도·배당·수수료, 순현금흐름, 월별 추이, PDF 인쇄',
    color: 'border-cyan-700 hover:border-cyan-500',
    badge: '💧',
  },
```

- [ ] **Step 2: 프로덕션 빌드 검증**

Run: `npx next build`
Expected: 성공. 라우트 목록에 `/unified/reports/cashflow-report`(정적)·`/unified/reports/cashflow-report/[id]`(동적)가 나타나고, 기존 `monthly-report`·`dividend-report`·`cost-report`·`holdings-report` 계열도 그대로 컴파일.

- [ ] **Step 3: 커밋**

```bash
git add app/unified/reports/page.tsx
git commit -m "feat(cashflow-fe): 보고서 허브에 현금흐름 카드 (R2 #41 FE)"
```

---

## Task 7: 브라우저 검증 + 스모크

**Files:** (없음 — 검증 전용)

- [ ] **Step 1: dev 기동** — preview_start(포트 3000). Bash 서버 기동 금지.
- [ ] **Step 2: 목록/생성** — `/unified/reports/cashflow-report` → 연·월 → 생성. #46 BE 미머지 시 400 배너, 머지 시 상세 이동. console 에러 없음.
- [ ] **Step 3: 상세 렌더** — 요약카드(총유입·총유출·순흐름 부호색)·유형별 부호 바+테이블·월별 그룹바+순흐름 라인·상세내역. read_page + 스크린샷.
- [ ] **Step 4: 인쇄 미리보기** — 배경 반전·네비 숨김·손익색 보존(#38 CSS 공용).
- [ ] **Step 5: 최종 커밋(있으면)**

---

## 완료 기준

- `/unified/reports/cashflow-report` 목록·생성, `/[id]` 상세·인쇄 동작, 요약+유형별+월별+상세 정상, null 폴백
- `npx tsc --noEmit`·`npx next build` 통과 — 월간·배당·비용·보유 화면 회귀 없음
- 허브 카드 노출(고유 배지·색), 기초/기말·환전·특이거래는 후속
