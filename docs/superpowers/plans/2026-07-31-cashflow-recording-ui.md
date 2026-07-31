# R-06 환전/이체 Phase 3 — FE 기록 폼 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. FE 위주(타입체크로 검증).

**Goal:** 사용자가 계좌간이체·환전을 입력·기록하는 FE 화면(`/unified/cashflow`)을 추가하고, Phase 1 엔드포인트(`/transfer`·`/fx`)를 호출한다.

**Architecture:** `cashflow-api.ts`에 transfer/fx 추가 → 신규 `/unified/cashflow` 페이지가 계좌 드롭다운 + 두 폼(이체·환전) + 최근 내부이동 리스트를 react-query로 처리 → NavBar 링크 추가.

**Tech Stack:** Next.js App Router, React, TypeScript, @tanstack/react-query, axios.

Spec: `docs/superpowers/specs/2026-07-31-cashflow-recording-ui-design.md`
Base: `main`(cca9c64+). FE 명령은 `cd /Users/hong9/IdeaProjects/allfolio/frontend/allfolio_app`.

---

## Task 1: 타입 + API 클라이언트

**Files:**
- Modify: `frontend/allfolio_app/types/returns.ts`
- Modify: `frontend/allfolio_app/lib/cashflow-api.ts`

- [ ] **Step 1: 타입 확장** — `types/returns.ts`:
  - `FlowType`을 `'DEPOSIT' | 'WITHDRAWAL' | 'TRANSFER_IN' | 'TRANSFER_OUT' | 'FX_IN' | 'FX_OUT'`로 변경.
  - `CashFlowItem`에 `linkId?: string | null` 추가.
  - 추가:
    ```ts
    export interface TransferRequest {
      fromAccountId: string
      toAccountId: string
      flowDate: string
      amount: number
      currency: string
      memo?: string | null
    }
    export interface FxRequest {
      accountId?: string | null
      flowDate: string
      fromAmount: number
      fromCurrency: string
      toAmount: number
      toCurrency: string
      memo?: string | null
    }
    ```

- [ ] **Step 2: API 메서드** — `lib/cashflow-api.ts`에 import에 `TransferRequest, FxRequest` 추가, return 객체에:
    ```ts
    transfer: async (req: TransferRequest): Promise<CashFlowItem[]> =>
      (await api.post<CashFlowItem[]>('/transfer', req)).data,

    fx: async (req: FxRequest): Promise<CashFlowItem[]> =>
      (await api.post<CashFlowItem[]>('/fx', req)).data,
    ```

- [ ] **Step 3: 타입체크** — Run: `cd /Users/hong9/IdeaProjects/allfolio/frontend/allfolio_app && npx tsc --noEmit` → no errors.

- [ ] **Step 4: 커밋**
```bash
git add frontend/allfolio_app/types/returns.ts frontend/allfolio_app/lib/cashflow-api.ts
git commit -m "feat(cashflow-fe): extend types + transfer/fx api client methods"
```

---

## Task 2: 기록 페이지 `/unified/cashflow`

**Files:**
- Create: `frontend/allfolio_app/app/unified/cashflow/page.tsx`

- [ ] **Step 1: 페이지 작성** — 기존 페이지(예: `app/unified/accounts/page.tsx`, `app/unified/admin/tax-rates/page.tsx`)의 react-query·다크테마·mutation 패턴을 참고해 작성. 요구사항:
  - `'use client'`.
  - `const api = useCashFlowApi()`, `const unified = useUnifiedApi()`, `const qc = useQueryClient()`.
  - 계좌 쿼리: `useQuery({ queryKey:['unified','accounts'], queryFn:()=>unified!.accounts.list(), enabled:!!unified })`.
  - 내부이동 리스트 쿼리: `useQuery({ queryKey:['cashflow','internal'], queryFn:()=>api!.list(), enabled:!!api, select:(rows)=>rows.filter(r=>r.linkId) })` (linkId 있는 레그만; 없으면 flowType이 TRANSFER_*/FX_* 인 것으로 필터).
  - **이체 폼 상태**: fromAccountId, toAccountId, flowDate(기본 오늘 `new Date().toISOString().slice(0,10)`), amount(string), currency, memo. from 계좌 선택 시 currency를 해당 계좌 통화로 자동 채움(수정 가능).
  - **환전 폼 상태**: accountId, flowDate, fromAmount, fromCurrency, toAmount, toCurrency, memo.
  - 순수 검증 헬퍼(페이지 내):
    ```ts
    function transferError(f: {fromAccountId:string; toAccountId:string; amount:string; currency:string}): string | null {
      if (!f.fromAccountId || !f.toAccountId) return '계좌를 선택하세요'
      if (f.fromAccountId === f.toAccountId) return '출발·도착 계좌가 같을 수 없습니다'
      if (!(Number(f.amount) > 0)) return '금액은 0보다 커야 합니다'
      if (!f.currency) return '통화를 입력하세요'
      return null
    }
    function fxError(f: {accountId:string; fromAmount:string; fromCurrency:string; toAmount:string; toCurrency:string}): string | null {
      if (!f.accountId) return '계좌를 선택하세요'
      if (!(Number(f.fromAmount) > 0) || !(Number(f.toAmount) > 0)) return '금액은 0보다 커야 합니다'
      if (!f.fromCurrency || !f.toCurrency) return '통화를 입력하세요'
      if (f.fromCurrency.toUpperCase() === f.toCurrency.toUpperCase()) return '환전 통화가 같을 수 없습니다'
      return null
    }
    ```
  - **이체 mutation**: `useMutation({ mutationFn:(req:TransferRequest)=>api!.transfer(req), onSuccess:()=>{ qc.invalidateQueries({queryKey:['cashflow']}); /*폼 리셋*/ } })`. 제출 핸들러에서 검증 통과 시 `mutate({ fromAccountId, toAccountId, flowDate, amount:Number(amount), currency, memo })`. 검증 실패 시 에러 상태로 표시. `mutation.error`(서버 400)도 메시지 표시.
  - **환전 mutation**: 동일 패턴, `api!.fx({ accountId, flowDate, fromAmount:Number(...), fromCurrency, toAmount:Number(...), toCurrency, memo })`.
  - **최근 내부이동 리스트**: 표(날짜·flowType·계좌/통화·amount·amountKrw). Phase 2의 표시와 유사하되 여기선 레그 원본(list) 기준 간단 표기.
  - 계좌 셀렉트 옵션: `accounts.map(a => <option value={a.id}>{a.accountName} ({a.provider})</option>)`.
  - 로딩/빈 상태 처리. 스타일: 기존 페이지의 `rounded-xl border border-gray-800 bg-gray-900 p-*`, `text-gray-*`, 버튼 `bg-emerald-*` 등 관례 준수.

- [ ] **Step 2: 타입체크** — Run: `cd /Users/hong9/IdeaProjects/allfolio/frontend/allfolio_app && npx tsc --noEmit` → no errors. (react-query v5 객체형 API 사용. Map/Set 스프레드 금지.)

- [ ] **Step 3: 커밋**
```bash
git add frontend/allfolio_app/app/unified/cashflow/page.tsx
git commit -m "feat(cashflow-fe): add /unified/cashflow transfer & fx recording page"
```

---

## Task 3: NavBar 링크

**Files:**
- Modify: `frontend/allfolio_app/components/NavBar.tsx`

- [ ] **Step 1: 링크 추가** — `/unified/reports` Link 근처에 동일 스타일로:
```tsx
<Link href="/unified/cashflow" className="text-sm text-gray-400 hover:text-white transition-colors">
  현금흐름
</Link>
```
(기존 링크들의 className과 정확히 일치시킬 것.)

- [ ] **Step 2: 타입체크** — Run: `cd /Users/hong9/IdeaProjects/allfolio/frontend/allfolio_app && npx tsc --noEmit` → no errors.

- [ ] **Step 3: 커밋**
```bash
git add frontend/allfolio_app/components/NavBar.tsx
git commit -m "feat(cashflow-fe): add 현금흐름 nav link"
```

---

## Task 4: 브라우저 검증(선택, 가능 시)
- [ ] dev 서버 기동 후 `/unified/cashflow` 렌더·폼 상호작용·콘솔 에러 확인. (환경상 불가하면 타입체크로 갈음.)

---

## Self-Review 체크
- [ ] FlowType 확장·CashFlowItem.linkId·요청 타입 일치(Task1↔페이지).
- [ ] transfer/fx api 경로(`/transfer`,`/fx`)·응답 `CashFlowItem[]`.
- [ ] 클라이언트 검증(from≠to·fromCcy≠toCcy·양수·필수) + 서버 오류 표시.
- [ ] NavBar 링크 스타일 일치.
- [ ] 다크테마·react-query v5 패턴 준수.

## Rollout
- 스키마·BE 무변경 → 마이그레이션 불필요. main 병합 시 FE 배포.
