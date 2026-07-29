# ESG 스크리닝 화면 (R-07, SCR-RPT-10) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** #47 BE 엔진이 아카이브한 `ESG_SCREENING` 본문을 목록·상세 2라우트로 렌더링하고 브라우저 인쇄(PDF)를 제공한다.

**Architecture:** Next.js App Router. 다른 리포트 화면과 동형, main의 일반화된 아카이브 인프라 재사용. `report-archive-api.ts`에 `ESG_SCREENING` 상수만 추가하고, ESG 타입·섹션 컴포넌트·2페이지를 신규 작성. 프레젠테이션 컴포넌트는 typed props만. ESG 점수는 0~100 "점", weight는 0~100 "%".

**Tech Stack:** TypeScript · Next.js(App Router) · @tanstack/react-query · Tailwind (차트 없음 — 점수는 CSS 게이지 바). **테스트 러너 없음** — `next build` + 브라우저 프리뷰 검증.

**Spec:** `docs/superpowers/specs/2026-07-28-esg-screening-screen-design.md`

**주의:** 브랜치 `feat/esg-screening-screen`는 main 분기(스택 없음). 명령은 `frontend/allfolio_app/`에서. 검증: `npx tsc --noEmit`, 최종 `npx next build`. `tsconfig.tsbuildinfo` 커밋 금지.

---

## File Structure

- Modify `lib/report-archive-api.ts` — `ESG_SCREENING` 상수 + `ReportType` 유니온 확장
- Create `types/esg-screening.ts`
- Create `components/esg-screening/{EsgSummary,EsgScoreBars,EsgBreakdownTable,ViolationsTable}.tsx`
- Create `app/unified/reports/esg-screening/page.tsx`, `.../[id]/page.tsx`
- Modify `app/unified/reports/page.tsx` — 허브 카드

---

## Task 1: ESG_SCREENING 상수 + 타입

**Files:**
- Modify: `frontend/allfolio_app/lib/report-archive-api.ts`
- Create: `frontend/allfolio_app/types/esg-screening.ts`

- [ ] **Step 1: report-archive-api.ts에 ESG_SCREENING 추가**

기존 마지막 상수(`COST` 계열) 다음 줄에 추가:
```typescript
export const ESG_SCREENING = 'ESG_SCREENING'
```
`ReportType` 유니온 끝에 `| typeof ESG_SCREENING` 추가(파일의 현재 유니온 항목을 그대로 두고 맨 뒤에만 추가):
```typescript
// 예: export type ReportType = ... | typeof COST | typeof ESG_SCREENING
```

- [ ] **Step 2: ESG 타입 작성**

`types/esg-screening.ts`:
```typescript
// types/esg-screening.ts
import type { ArchiveMeta, ArchiveDetail } from './report-archive'
export type { ArchiveMeta, ArchiveDetail }

export interface EsgScores {
  rating: string
  totalScore: number          // 0~100 점
  environmental: number       // 0~100 점
  social: number              // 0~100 점
  governance: number          // 0~100 점
}

export interface EsgBreakdownRow {
  name: string
  type: string
  weight: number              // 0~100 스케일
  e: number
  s: number
  g: number
  total: number               // 0~100 점
  rating: string
}

export interface EsgScreeningSummary {
  violationCount: number
  violationValueKrw: number
  violationWeight: number     // 0~100 스케일
}

export interface EsgViolation {
  name: string
  symbol: string | null
  listName: string
  reason: string
  valueKrw: number
  weight: number              // 0~100 스케일
}

export interface EsgScreeningReportBody {
  esg: EsgScores
  esgBreakdown: EsgBreakdownRow[]
  screening: EsgScreeningSummary
  violations: EsgViolation[]
}
```

- [ ] **Step 3: 타입체크 + 커밋**

Run: `npx tsc --noEmit` (Expected: 에러 없음 — 기존 화면 회귀 없음)
```bash
git add lib/report-archive-api.ts types/esg-screening.ts
git commit -m "feat(esg-fe): ESG_SCREENING 리포트타입 + 본문 타입 (R2 #42 FE)"
```

---

## Task 2: 요약 카드 + E/S/G 점수 바

**Files:**
- Create: `frontend/allfolio_app/components/esg-screening/EsgSummary.tsx`
- Create: `frontend/allfolio_app/components/esg-screening/EsgScoreBars.tsx`

- [ ] **Step 1: EsgSummary 작성**

```tsx
// components/esg-screening/EsgSummary.tsx
import type { EsgScores, EsgScreeningSummary } from '@/types/esg-screening'
import { fmtKrw } from '@/lib/report-format'

export function EsgSummary({ esg, screening }: { esg: EsgScores; screening: EsgScreeningSummary }) {
  const clean = screening.violationCount === 0
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">요약</h2>
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Card label="ESG 등급" value={esg.rating} color="text-emerald-400" />
        <Card label="종합점수" value={`${esg.totalScore.toFixed(1)}점`} color="text-gray-100" />
        <Card
          label="배제 위반"
          value={clean ? '없음 ✓' : `${screening.violationCount}종목`}
          color={clean ? 'text-emerald-400' : 'text-red-400'}
        />
        <Card label="위반 비중" value={`${screening.violationWeight.toFixed(2)}%`} color={clean ? 'text-gray-100' : 'text-red-400'} sub={fmtKrw(screening.violationValueKrw)} />
      </div>
    </section>
  )
}

function Card({ label, value, color, sub }: { label: string; value: string; color: string; sub?: string }) {
  return (
    <div className="rounded-xl border border-gray-700 bg-gray-900 p-5">
      <p className="text-xs text-gray-500">{label}</p>
      <p className={`mt-2 text-xl font-bold tabular-nums ${color}`}>{value}</p>
      {sub && <p className="mt-1 text-xs text-gray-500 tabular-nums">{sub}</p>}
    </div>
  )
}
```

- [ ] **Step 2: EsgScoreBars 작성 (0~100 게이지)**

```tsx
// components/esg-screening/EsgScoreBars.tsx
import type { EsgScores } from '@/types/esg-screening'

export function EsgScoreBars({ esg }: { esg: EsgScores }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">E·S·G 점수</h2>
      <div className="space-y-4 rounded-xl border border-gray-700 bg-gray-900 p-5">
        <ScoreBar label="환경 (E)" score={esg.environmental} color="bg-emerald-500" />
        <ScoreBar label="사회 (S)" score={esg.social} color="bg-sky-500" />
        <ScoreBar label="지배구조 (G)" score={esg.governance} color="bg-violet-500" />
        <div className="border-t border-gray-800 pt-3">
          <ScoreBar label="종합" score={esg.totalScore} color="bg-amber-500" />
        </div>
      </div>
    </section>
  )
}

function ScoreBar({ label, score, color }: { label: string; score: number; color: string }) {
  const pct = Math.min(100, Math.max(0, score))
  return (
    <div>
      <div className="mb-1 flex justify-between text-sm">
        <span className="text-gray-300">{label}</span>
        <span className="tabular-nums font-medium text-gray-100">{score.toFixed(1)}점</span>
      </div>
      <div className="h-2 overflow-hidden rounded-full bg-gray-800">
        <div className={`h-2 rounded-full ${color}`} style={{ width: `${pct}%` }} />
      </div>
    </div>
  )
}
```

- [ ] **Step 3: 타입체크 + 커밋**

Run: `npx tsc --noEmit` (Expected: 에러 없음)
```bash
git add components/esg-screening/EsgSummary.tsx components/esg-screening/EsgScoreBars.tsx
git commit -m "feat(esg-fe): 요약 카드·E/S/G 점수 게이지 (R2 #42 FE)"
```

---

## Task 3: ESG 종목별 + 위반 내역

**Files:**
- Create: `frontend/allfolio_app/components/esg-screening/EsgBreakdownTable.tsx`
- Create: `frontend/allfolio_app/components/esg-screening/ViolationsTable.tsx`

- [ ] **Step 1: EsgBreakdownTable 작성**

```tsx
// components/esg-screening/EsgBreakdownTable.tsx
import type { EsgBreakdownRow } from '@/types/esg-screening'

export function EsgBreakdownTable({ rows }: { rows: EsgBreakdownRow[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">종목별 ESG</h2>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">종목</th><th className="p-3">유형</th><th className="p-3 text-right">비중</th>
              <th className="p-3 text-right">E</th><th className="p-3 text-right">S</th><th className="p-3 text-right">G</th>
              <th className="p-3 text-right">종합</th><th className="p-3">등급</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={`${r.name}-${i}`} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3 font-medium text-gray-100">{r.name}</td>
                <td className="p-3 text-gray-400">{r.type}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{r.weight.toFixed(2)}%</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{r.e}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{r.s}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{r.g}</td>
                <td className="p-3 text-right tabular-nums font-medium text-gray-100">{r.total.toFixed(1)}</td>
                <td className="p-3 text-gray-300">{r.rating}</td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={8} className="p-4 text-center text-gray-500">데이터가 없습니다.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}
```

- [ ] **Step 2: ViolationsTable 작성 (0이면 녹색 카드)**

```tsx
// components/esg-screening/ViolationsTable.tsx
import type { EsgViolation } from '@/types/esg-screening'
import { fmtKrw } from '@/lib/report-format'

export function ViolationsTable({ rows }: { rows: EsgViolation[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">배제 위반 내역</h2>
      {rows.length === 0 ? (
        <div className="rounded-xl border border-emerald-700 bg-emerald-950/40 p-6 text-center text-sm text-emerald-300">
          배제 위반 없음 ✓
        </div>
      ) : (
        <div className="overflow-x-auto rounded-xl border border-red-800 bg-gray-900">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
                <th className="p-3">종목</th><th className="p-3">배제 리스트</th><th className="p-3">사유</th>
                <th className="p-3 text-right">평가액</th><th className="p-3 text-right">비중</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={`${r.symbol}-${r.name}-${i}`} className="border-b border-gray-800 last:border-b-0">
                  <td className="p-3">
                    <span className="font-medium text-gray-100">{r.name}</span>
                    {r.symbol && <span className="ml-2 text-xs text-gray-500">{r.symbol}</span>}
                  </td>
                  <td className="p-3 text-gray-400">{r.listName}</td>
                  <td className="p-3"><span className="rounded bg-red-950 px-2 py-0.5 text-xs text-red-300">{r.reason}</span></td>
                  <td className="p-3 text-right tabular-nums text-red-300">{fmtKrw(r.valueKrw)}</td>
                  <td className="p-3 text-right tabular-nums text-gray-300">{r.weight.toFixed(2)}%</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
```

- [ ] **Step 3: 타입체크 + 커밋**

Run: `npx tsc --noEmit` (Expected: 에러 없음)
```bash
git add components/esg-screening/EsgBreakdownTable.tsx components/esg-screening/ViolationsTable.tsx
git commit -m "feat(esg-fe): 종목별 ESG·배제 위반 내역 (R2 #42 FE)"
```

---

## Task 4: 상세 페이지 (조립 + 인쇄)

**Files:**
- Create: `frontend/allfolio_app/app/unified/reports/esg-screening/[id]/page.tsx`

- [ ] **Step 1: 상세 페이지 작성**

```tsx
// app/unified/reports/esg-screening/[id]/page.tsx
'use client'

import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useParams } from 'next/navigation'
import { useReportArchiveApi } from '@/lib/useApi'
import { parseReportBody, ESG_SCREENING } from '@/lib/report-archive-api'
import type { EsgScreeningReportBody } from '@/types/esg-screening'
import { EsgSummary } from '@/components/esg-screening/EsgSummary'
import { EsgScoreBars } from '@/components/esg-screening/EsgScoreBars'
import { EsgBreakdownTable } from '@/components/esg-screening/EsgBreakdownTable'
import { ViolationsTable } from '@/components/esg-screening/ViolationsTable'

export default function EsgScreeningDetailPage() {
  const { id } = useParams<{ id: string }>()
  const api = useReportArchiveApi(ESG_SCREENING)
  const { data, isLoading, isError } = useQuery({
    queryKey: ['esg-screening', id],
    queryFn: async () => {
      const detail = await api!.detail(id)
      return { meta: detail.meta, body: parseReportBody<EsgScreeningReportBody>(detail.body) }
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
        <Link href="/unified/reports/esg-screening" className="text-sm text-gray-400 hover:text-gray-200">← 목록</Link>
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
          <Link href="/unified/reports/esg-screening" className="text-sm text-gray-500 hover:text-gray-300">← 목록</Link>
          <h1 className="text-2xl font-bold">{y}년 {Number(m)}월 ESG 스크리닝</h1>
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

      <EsgSummary esg={body.esg} screening={body.screening} />
      <EsgScoreBars esg={body.esg} />
      <EsgBreakdownTable rows={body.esgBreakdown} />
      <ViolationsTable rows={body.violations} />
    </div>
  )
}
```

- [ ] **Step 2: 타입체크 + 커밋**

Run: `npx tsc --noEmit` (Expected: 에러 없음)
```bash
git add "app/unified/reports/esg-screening/[id]/page.tsx"
git commit -m "feat(esg-fe): ESG 스크리닝 상세 화면 + 인쇄 (R2 #42 FE)"
```

---

## Task 5: 목록/생성 페이지

**Files:**
- Create: `frontend/allfolio_app/app/unified/reports/esg-screening/page.tsx`

- [ ] **Step 1: 목록/생성 페이지 작성**

```tsx
// app/unified/reports/esg-screening/page.tsx
'use client'

import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { useReportArchiveApi } from '@/lib/useApi'
import { ESG_SCREENING } from '@/lib/report-archive-api'
import type { ArchiveMeta } from '@/types/report-archive'

const NOW = new Date()
const YEARS = Array.from({ length: 6 }, (_, i) => NOW.getFullYear() - i)
const MONTHS = Array.from({ length: 12 }, (_, i) => i + 1)

export default function EsgScreeningListPage() {
  const api = useReportArchiveApi(ESG_SCREENING)
  const router = useRouter()
  const qc = useQueryClient()
  const [year, setYear] = useState(NOW.getMonth() === 0 ? NOW.getFullYear() - 1 : NOW.getFullYear())
  const [month, setMonth] = useState(NOW.getMonth() === 0 ? 12 : NOW.getMonth())
  const [error, setError] = useState<string | null>(null)

  const { data: list, isLoading } = useQuery({
    queryKey: ['esg-screening', 'list'],
    queryFn: () => api!.list(),
    enabled: !!api,
    retry: false,
  })

  const gen = useMutation({
    mutationFn: () => api!.generate(year, month),
    onSuccess: (meta) => {
      qc.invalidateQueries({ queryKey: ['esg-screening', 'list'] })
      router.push(`/unified/reports/esg-screening/${meta.id}`)
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
        <h1 className="text-2xl font-bold">ESG 스크리닝</h1>
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
            아직 생성된 ESG 스크리닝 보고서가 없습니다. 위에서 연·월을 골라 생성하세요.
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
                    onClick={() => router.push(`/unified/reports/esg-screening/${r.id}`)}
                    onKeyDown={(e) => { if (e.key === 'Enter') router.push(`/unified/reports/esg-screening/${r.id}`) }}
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
git add app/unified/reports/esg-screening/page.tsx
git commit -m "feat(esg-fe): ESG 스크리닝 목록·생성 화면 (R2 #42 FE)"
```

---

## Task 6: 허브 카드 + 빌드 검증

**Files:**
- Modify: `frontend/allfolio_app/app/unified/reports/page.tsx`

- [ ] **Step 1: REPORTS 배열에 카드 추가**

`app/unified/reports/page.tsx`의 `REPORTS` 배열에서 `href: '/unified/reports/cost-report'` 카드 객체 **바로 뒤**에 추가. IMPORTANT: grep로 배지 `🌿`·색 `border-lime-`가 다른 카드와 중복되지 않는지 확인, 충돌 시 미사용 이모지/색으로 교체(기존 ESG 점수 카드 `🌱`와 구분되게):
```tsx
  {
    href:  '/unified/reports/esg-screening',
    title: 'ESG 스크리닝',
    desc:  'R-07 투자배제·ESG — E/S/G 점수, 종목별 등급, 배제리스트 위반 스크리닝, PDF 인쇄',
    color: 'border-lime-700 hover:border-lime-500',
    badge: '🌿',
  },
```

- [ ] **Step 2: 프로덕션 빌드 검증**

Run: `npx next build`
Expected: 성공. 라우트 목록에 `/unified/reports/esg-screening`(정적)·`/unified/reports/esg-screening/[id]`(동적)가 나타나고, 기존 `monthly-report`·`dividend-report`·`cost-report` 계열도 그대로 컴파일.

- [ ] **Step 3: 커밋**

```bash
git add app/unified/reports/page.tsx
git commit -m "feat(esg-fe): 보고서 허브에 ESG 스크리닝 카드 (R2 #42 FE)"
```

---

## Task 7: 브라우저 검증 + 스모크

**Files:** (없음 — 검증 전용)

- [ ] **Step 1: dev 기동** — preview_start(포트 3000). Bash 서버 기동 금지.
- [ ] **Step 2: 목록/생성** — `/unified/reports/esg-screening` → 연·월 → 생성. #47 BE 미머지 시 400 배너, 머지 시 상세 이동. console 에러 없음.
- [ ] **Step 3: 상세 렌더** — 요약카드(등급·종합점수·위반수 색·위반비중)·E/S/G 게이지·종목별 ESG 테이블·위반 내역(0이면 녹색 ✓). read_page + 스크린샷.
- [ ] **Step 4: 인쇄 미리보기** — 배경 반전·네비 숨김(#38 CSS 공용).
- [ ] **Step 5: 최종 커밋(있으면)**

---

## 완료 기준

- `/unified/reports/esg-screening` 목록·생성, `/[id]` 상세·인쇄 동작, 요약+E/S/G+종목별+위반 정상, 위반 0/N 분기
- `npx tsc --noEmit`·`npx next build` 통과 — 기존 화면 회귀 없음
- 허브 카드 노출(고유 배지·색, 기존 ESG 점수 🌱와 구분), 리스트 관리·이력은 후속
