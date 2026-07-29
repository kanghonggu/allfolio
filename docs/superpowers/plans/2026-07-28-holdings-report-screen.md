# 월말 보유 명세서 화면 (R-05, SCR-RPT-08) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** #44 BE 엔진이 아카이브한 `HOLDINGS` 본문을 목록·상세 2라우트로 렌더링하고 브라우저 인쇄(PDF)를 제공한다.

**Architecture:** Next.js App Router. #38/#39 화면과 동형, main의 일반화된 아카이브 인프라 재사용. `report-archive-api.ts`에 `HOLDINGS` 상수만 추가하고, 보유 타입·섹션 컴포넌트·2페이지를 신규 작성. 프레젠테이션 컴포넌트는 typed props만 받는다.

**Tech Stack:** TypeScript · Next.js(App Router) · @tanstack/react-query · axios · Tailwind. **테스트 러너 없음** — `next build` + 브라우저 프리뷰 검증.

**Spec:** `docs/superpowers/specs/2026-07-28-holdings-report-screen-design.md`

**주의:** 브랜치 `feat/holdings-report-screen`는 main에서 분기(스택 없음). 명령은 `frontend/allfolio_app/`에서. 검증: `npx tsc --noEmit`, 최종 `npx next build`. `tsconfig.tsbuildinfo` 커밋 금지(명시 경로 add).

---

## File Structure

- Modify `lib/report-archive-api.ts` — `HOLDINGS` 상수 + `ReportType` 유니온 확장
- Create `types/holdings-report.ts`
- Create `components/holdings-report/{HoldingsSummary,HoldingsGrid,ByAccountTable,ByTypeTable,CashTable}.tsx`
- Create `app/unified/reports/holdings-report/page.tsx`, `.../[id]/page.tsx`
- Modify `app/unified/reports/page.tsx` — 허브 카드

---

## Task 1: HOLDINGS 상수 + 보유 타입

**Files:**
- Modify: `frontend/allfolio_app/lib/report-archive-api.ts`
- Create: `frontend/allfolio_app/types/holdings-report.ts`

- [ ] **Step 1: report-archive-api.ts에 HOLDINGS 추가**

`export const COST = 'COST'` 다음 줄에 추가:
```typescript
export const HOLDINGS = 'HOLDINGS'
```
`ReportType` 유니온 확장:
```typescript
export type ReportType = typeof MONTHLY_REPORT | typeof DIVIDEND_INTEREST | typeof COST | typeof HOLDINGS
```

- [ ] **Step 2: 보유 타입 작성**

`types/holdings-report.ts`:
```typescript
// types/holdings-report.ts
import type { ArchiveMeta, ArchiveDetail } from './report-archive'
export type { ArchiveMeta, ArchiveDetail }

export interface HoldingsSummary {
  totalValueKrw: number
  holdingCount: number
  accountCount: number
  cashWeight: number         // 0~100 스케일 (fmtPct 금지, fmtPctScaled/.toFixed 사용)
  unrealizedPnlKrw: number   // 부호 있는 KRW
}

export interface Holding {
  name: string
  symbol: string | null
  type: string
  account: string
  provider: string
  quantity: number
  avgPrice: number           // 원통화 평단
  currentValue: number       // 원통화 평가액
  valueKrw: number
  weight: number             // 0~100 스케일
  unrealizedPnl: number      // KRW, 부호
  returnRate: number         // 0~100 스케일
}

export interface HoldingByAccount {
  account: string
  provider: string
  valueKrw: number
  weight: number             // 0~100 스케일
  holdingCount: number
}

export interface HoldingByType {
  type: string
  valueKrw: number
  weight: number             // 0~100 스케일
  holdingCount: number
}

export interface HoldingCash {
  account: string
  currency: string
  valueKrw: number
}

export interface HoldingsReportBody {
  summary: HoldingsSummary
  holdings: Holding[]
  byAccount: HoldingByAccount[]
  byType: HoldingByType[]
  cash: HoldingCash[]
}
```

- [ ] **Step 3: 타입체크 + 커밋**

Run: `npx tsc --noEmit` (Expected: 에러 없음 — 월간·배당·비용 회귀 없음)
```bash
git add lib/report-archive-api.ts types/holdings-report.ts
git commit -m "feat(holdings-fe): HOLDINGS 리포트타입 + 보유 명세 본문 타입 (R2 #40 FE)"
```

---

## Task 2: 요약 카드 + 보유 명세 그리드

**Files:**
- Create: `frontend/allfolio_app/components/holdings-report/HoldingsSummary.tsx`
- Create: `frontend/allfolio_app/components/holdings-report/HoldingsGrid.tsx`

- [ ] **Step 1: HoldingsSummary 작성**

```tsx
// components/holdings-report/HoldingsSummary.tsx
import type { HoldingsSummary as Summary } from '@/types/holdings-report'
import { fmtKrw, fmtPctScaled, pctColor } from '@/lib/report-format'

export function HoldingsSummary({ summary }: { summary: Summary }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">요약</h2>
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Card label="총평가액" value={fmtKrw(summary.totalValueKrw)} />
        <Card label="보유 종목 / 계좌" value={`${summary.holdingCount}종목 / ${summary.accountCount}계좌`} />
        <Card label="현금 비중" value={fmtPctScaled(summary.cashWeight)} />
        <Card label="평가손익 합계" value={fmtKrw(summary.unrealizedPnlKrw)} color={pctColor(summary.unrealizedPnlKrw)} />
      </div>
    </section>
  )
}

function Card({ label, value, color = 'text-gray-100' }: { label: string; value: string; color?: string }) {
  return (
    <div className="rounded-xl border border-gray-700 bg-gray-900 p-5">
      <p className="text-xs text-gray-500">{label}</p>
      <p className={`mt-2 text-xl font-bold tabular-nums ${color}`}>{value}</p>
    </div>
  )
}
```

- [ ] **Step 2: HoldingsGrid 작성**

```tsx
// components/holdings-report/HoldingsGrid.tsx
import type { Holding } from '@/types/holdings-report'
import { fmtKrw, fmtPctScaled, pctColor } from '@/lib/report-format'
// 주의: weight·returnRate는 백엔드에서 이미 0~100 스케일 → fmtPct(×100) 금지

function num(n: number) {
  return n.toLocaleString('ko-KR', { maximumFractionDigits: 8 })
}

export function HoldingsGrid({ holdings }: { holdings: Holding[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">보유 명세</h2>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">종목</th><th className="p-3">자산군</th><th className="p-3">계좌</th>
              <th className="p-3 text-right">수량</th><th className="p-3 text-right">평단</th>
              <th className="p-3 text-right">평가액</th><th className="p-3 text-right">비중</th>
              <th className="p-3 text-right">평가손익</th><th className="p-3 text-right">수익률</th>
            </tr>
          </thead>
          <tbody>
            {holdings.map((h, i) => (
              <tr key={`${h.symbol}-${h.name}-${i}`} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3">
                  <span className="font-medium text-gray-100">{h.name}</span>
                  {h.symbol && <span className="ml-2 text-xs text-gray-500">{h.symbol}</span>}
                </td>
                <td className="p-3 text-gray-400">{h.type}</td>
                <td className="p-3 text-gray-400">{h.account}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{num(h.quantity)}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{num(h.avgPrice)}</td>
                <td className="p-3 text-right tabular-nums">
                  {fmtKrw(h.valueKrw)}
                  <span className="ml-1 text-xs text-gray-500">({num(h.currentValue)})</span>
                </td>
                <td className="p-3 text-right tabular-nums text-gray-300">{h.weight.toFixed(2)}%</td>
                <td className={`p-3 text-right tabular-nums ${pctColor(h.unrealizedPnl)}`}>{fmtKrw(h.unrealizedPnl)}</td>
                <td className={`p-3 text-right tabular-nums ${pctColor(h.returnRate)}`}>{fmtPctScaled(h.returnRate)}</td>
              </tr>
            ))}
            {holdings.length === 0 && (
              <tr><td colSpan={9} className="p-4 text-center text-gray-500">보유 종목이 없습니다.</td></tr>
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
git add components/holdings-report/HoldingsSummary.tsx components/holdings-report/HoldingsGrid.tsx
git commit -m "feat(holdings-fe): 요약 카드·보유 명세 그리드 (R2 #40 FE)"
```

---

## Task 3: 계좌별·자산군별 소계 + 현금

**Files:**
- Create: `frontend/allfolio_app/components/holdings-report/ByAccountTable.tsx`
- Create: `frontend/allfolio_app/components/holdings-report/ByTypeTable.tsx`
- Create: `frontend/allfolio_app/components/holdings-report/CashTable.tsx`

- [ ] **Step 1: ByAccountTable 작성**

```tsx
// components/holdings-report/ByAccountTable.tsx
import type { HoldingByAccount } from '@/types/holdings-report'
import { fmtKrw } from '@/lib/report-format'

export function ByAccountTable({ rows }: { rows: HoldingByAccount[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">계좌별 소계</h2>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">계좌</th><th className="p-3">증권사</th>
              <th className="p-3 text-right">평가액</th><th className="p-3 text-right">비중</th><th className="p-3 text-right">종목수</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={`${r.provider}-${r.account}`} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3 font-medium text-gray-100">{r.account}</td>
                <td className="p-3 text-gray-400">{r.provider}</td>
                <td className="p-3 text-right tabular-nums">{fmtKrw(r.valueKrw)}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{r.weight.toFixed(2)}%</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{r.holdingCount}</td>
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

- [ ] **Step 2: ByTypeTable 작성**

```tsx
// components/holdings-report/ByTypeTable.tsx
import type { HoldingByType } from '@/types/holdings-report'
import { fmtKrw } from '@/lib/report-format'

export function ByTypeTable({ rows }: { rows: HoldingByType[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">자산군별 소계</h2>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">자산군</th><th className="p-3 text-right">평가액</th>
              <th className="p-3 text-right">비중</th><th className="p-3 text-right">종목수</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.type} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3 font-medium text-gray-100">{r.type}</td>
                <td className="p-3 text-right tabular-nums">{fmtKrw(r.valueKrw)}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{r.weight.toFixed(2)}%</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{r.holdingCount}</td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={4} className="p-4 text-center text-gray-500">데이터가 없습니다.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}
```

- [ ] **Step 3: CashTable 작성**

```tsx
// components/holdings-report/CashTable.tsx
import type { HoldingCash } from '@/types/holdings-report'
import { fmtKrw } from '@/lib/report-format'

export function CashTable({ rows }: { rows: HoldingCash[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">현금 잔고</h2>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">계좌</th><th className="p-3">통화</th><th className="p-3 text-right">잔액</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={`${r.account}-${r.currency}-${i}`} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3 font-medium text-gray-100">{r.account}</td>
                <td className="p-3 text-gray-400">{r.currency}</td>
                <td className="p-3 text-right tabular-nums">{fmtKrw(r.valueKrw)}</td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={3} className="p-4 text-center text-gray-500">현금성 자산 없음</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}
```

- [ ] **Step 4: 타입체크 + 커밋**

Run: `npx tsc --noEmit` (Expected: 에러 없음)
```bash
git add components/holdings-report/ByAccountTable.tsx components/holdings-report/ByTypeTable.tsx components/holdings-report/CashTable.tsx
git commit -m "feat(holdings-fe): 계좌별·자산군별 소계 + 현금 잔고 (R2 #40 FE)"
```

---

## Task 4: 상세 페이지 (조립 + 인쇄)

**Files:**
- Create: `frontend/allfolio_app/app/unified/reports/holdings-report/[id]/page.tsx`

- [ ] **Step 1: 상세 페이지 작성**

```tsx
// app/unified/reports/holdings-report/[id]/page.tsx
'use client'

import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useParams } from 'next/navigation'
import { useReportArchiveApi } from '@/lib/useApi'
import { parseReportBody, HOLDINGS } from '@/lib/report-archive-api'
import type { HoldingsReportBody } from '@/types/holdings-report'
import { HoldingsSummary } from '@/components/holdings-report/HoldingsSummary'
import { HoldingsGrid } from '@/components/holdings-report/HoldingsGrid'
import { ByAccountTable } from '@/components/holdings-report/ByAccountTable'
import { ByTypeTable } from '@/components/holdings-report/ByTypeTable'
import { CashTable } from '@/components/holdings-report/CashTable'

export default function HoldingsReportDetailPage() {
  const { id } = useParams<{ id: string }>()
  const api = useReportArchiveApi(HOLDINGS)
  const { data, isLoading, isError } = useQuery({
    queryKey: ['holdings-report', id],
    queryFn: async () => {
      const detail = await api!.detail(id)
      return { meta: detail.meta, body: parseReportBody<HoldingsReportBody>(detail.body) }
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
        <Link href="/unified/reports/holdings-report" className="text-sm text-gray-400 hover:text-gray-200">← 목록</Link>
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
          <Link href="/unified/reports/holdings-report" className="text-sm text-gray-500 hover:text-gray-300">← 목록</Link>
          <h1 className="text-2xl font-bold">{y}년 {Number(m)}월 보유 명세서</h1>
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

      <HoldingsSummary summary={body.summary} />
      <HoldingsGrid holdings={body.holdings} />
      <div className="grid gap-4 lg:grid-cols-2">
        <ByAccountTable rows={body.byAccount} />
        <ByTypeTable rows={body.byType} />
      </div>
      <CashTable rows={body.cash} />
    </div>
  )
}
```

- [ ] **Step 2: 타입체크 + 커밋**

Run: `npx tsc --noEmit` (Expected: 에러 없음)
```bash
git add "app/unified/reports/holdings-report/[id]/page.tsx"
git commit -m "feat(holdings-fe): 월말 보유 명세서 상세 화면 + 인쇄 (R2 #40 FE)"
```

---

## Task 5: 목록/생성 페이지

**Files:**
- Create: `frontend/allfolio_app/app/unified/reports/holdings-report/page.tsx`

- [ ] **Step 1: 목록/생성 페이지 작성**

```tsx
// app/unified/reports/holdings-report/page.tsx
'use client'

import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { useReportArchiveApi } from '@/lib/useApi'
import { HOLDINGS } from '@/lib/report-archive-api'
import type { ArchiveMeta } from '@/types/report-archive'

const NOW = new Date()
const YEARS = Array.from({ length: 6 }, (_, i) => NOW.getFullYear() - i)
const MONTHS = Array.from({ length: 12 }, (_, i) => i + 1)

export default function HoldingsReportListPage() {
  const api = useReportArchiveApi(HOLDINGS)
  const router = useRouter()
  const qc = useQueryClient()
  const [year, setYear] = useState(NOW.getMonth() === 0 ? NOW.getFullYear() - 1 : NOW.getFullYear())
  const [month, setMonth] = useState(NOW.getMonth() === 0 ? 12 : NOW.getMonth())
  const [error, setError] = useState<string | null>(null)

  const { data: list, isLoading } = useQuery({
    queryKey: ['holdings-report', 'list'],
    queryFn: () => api!.list(),
    enabled: !!api,
    retry: false,
  })

  const gen = useMutation({
    mutationFn: () => api!.generate(year, month),
    onSuccess: (meta) => {
      qc.invalidateQueries({ queryKey: ['holdings-report', 'list'] })
      router.push(`/unified/reports/holdings-report/${meta.id}`)
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
        <h1 className="text-2xl font-bold">월말 보유 명세서</h1>
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
            아직 생성된 보유 명세서가 없습니다. 위에서 연·월을 골라 생성하세요.
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
                    onClick={() => router.push(`/unified/reports/holdings-report/${r.id}`)}
                    onKeyDown={(e) => { if (e.key === 'Enter') router.push(`/unified/reports/holdings-report/${r.id}`) }}
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
git add app/unified/reports/holdings-report/page.tsx
git commit -m "feat(holdings-fe): 월말 보유 명세서 목록·생성 화면 (R2 #40 FE)"
```

---

## Task 6: 허브 카드 + 빌드 검증

**Files:**
- Modify: `frontend/allfolio_app/app/unified/reports/page.tsx`

- [ ] **Step 1: REPORTS 배열에 카드 추가**

`app/unified/reports/page.tsx`의 `REPORTS` 배열에서 `href: '/unified/reports/cost-report'` 카드 객체 **바로 뒤**에 추가(배지·색은 허브 전체에서 고유해야 함 — 아래 값은 미사용 확인됨):
```tsx
  {
    href:  '/unified/reports/holdings-report',
    title: '월말 보유 명세서',
    desc:  'R-05 기관급 보유 명세 — 종목별 수량·평단·평가액·평가손익, 계좌·자산군별 소계, PDF 인쇄',
    color: 'border-sky-700 hover:border-sky-500',
    badge: '📑',
  },
```

- [ ] **Step 2: 프로덕션 빌드 검증**

Run: `npx next build`
Expected: 성공. 라우트 목록에 `/unified/reports/holdings-report`(정적)·`/unified/reports/holdings-report/[id]`(동적)가 나타나고, 기존 `monthly-report`·`dividend-report`·`cost-report` 계열도 그대로 컴파일.

- [ ] **Step 3: 커밋**

```bash
git add app/unified/reports/page.tsx
git commit -m "feat(holdings-fe): 보고서 허브에 월말 보유 명세서 카드 (R2 #40 FE)"
```

---

## Task 7: 브라우저 검증 + 스모크

**Files:** (없음 — 검증 전용)

- [ ] **Step 1: dev 기동** — preview_start(포트 3000). Bash 서버 기동 금지.
- [ ] **Step 2: 목록/생성** — `/unified/reports/holdings-report` → 연·월 → 생성. #44 BE 미머지 시 400 배너, 머지 시 상세 이동. console 에러 없음.
- [ ] **Step 3: 상세 렌더** — 요약카드(총평가액·종목/계좌·현금비중·평가손익)·보유 명세 그리드(원통화+KRW·수익률 0~100)·계좌별/자산군별 소계·현금·각주. read_page + 스크린샷.
- [ ] **Step 4: 인쇄 미리보기** — 배경 반전·네비 숨김·손익색 보존(#38 CSS 공용).
- [ ] **Step 5: 최종 커밋(있으면)**

---

## 완료 기준

- `/unified/reports/holdings-report` 목록·생성, `/[id]` 상세·인쇄 동작, 요약+명세그리드+소계2+현금 정상, null 폴백
- `npx tsc --noEmit`·`npx next build` 통과 — 월간·배당·비용 화면 회귀 없음
- 허브 카드 노출(고유 배지·색), 실현손익·월간변동·지역그룹핑은 후속
