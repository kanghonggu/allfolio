# R-06 환전/이체 Phase 3 — FE 기록 폼 Design Spec

- **Date**: 2026-07-31
- **Status**: Approved (design), pending implementation
- **Scope**: 환전(FX)·계좌간이체(TRANSFER)를 사용자가 입력·기록하는 **FE 화면**. Phase 1의 `POST /api/cashflows/transfer`·`/fx` 호출. 신규 페이지 `/unified/cashflow` + NavBar 링크 + API 클라이언트 확장.
- **Depends on**: Phase 1(#62 머지)·Phase 2(#63 머지) — 엔드포인트·리포트 섹션 존재. `main`(cca9c64+)에서 분기.
- **Out of scope**: 입금/출금(DEPOSIT/WITHDRAWAL) 일반 기록 폼(기존 `/record` — 현재도 UI 없음, 이번 범위 아님), 수정, 스프레드 계산 도우미, 계좌간 환전.

## 1. Background

Phase 1/2로 BE는 환전/이체 기록·표시가 가능하지만 **FE에 현금흐름 기록 UI가 전무**해 사용자는 API 직접 호출로만 입력 가능. Phase 3는 이체/환전 입력 폼을 제공한다. (일반 입출금 기록 UI 부재는 별개 이슈로 이번 범위 밖.)

## 2. Decisions (확정)

| 항목 | 결정 |
|---|---|
| 위치 | 신규 페이지 `/unified/cashflow`, NavBar에 "현금흐름" 링크 추가 |
| 구성 | 두 폼: **계좌간이체**·**환전**(탭 또는 상하 섹션) + 최근 기록 리스트(내부이동) |
| 계좌 선택 | `useUnifiedApi().accounts.list()` 드롭다운(accountName + provider) |
| 이체 입력 | fromAccount·toAccount(≠)·flowDate·amount·currency(기본=from 계좌 통화)·memo |
| 환전 입력 | account·flowDate·fromAmount·fromCurrency·toAmount·toCurrency(≠)·memo |
| 인증 | 기존 패턴(Bearer → 백엔드 JwtUserIdFilter가 X-User-Id 주입) |
| 성공 처리 | react-query mutation → 성공 시 폼 리셋 + 리스트 invalidate |
| 검증 | 클라이언트: 필수값·from≠to·fromCcy≠toCcy·양수. 서버 400도 표시 |

## 3. Frontend Design

### 3.1 타입 — `types/returns.ts`
- `FlowType` 확장: `'DEPOSIT' | 'WITHDRAWAL' | 'TRANSFER_IN' | 'TRANSFER_OUT' | 'FX_IN' | 'FX_OUT'`.
- `CashFlowItem`에 `linkId?: string | null` 추가.
- 신규 요청 타입:
  ```ts
  export interface TransferRequest { fromAccountId: string; toAccountId: string; flowDate: string; amount: number; currency: string; memo?: string | null }
  export interface FxRequest { accountId?: string | null; flowDate: string; fromAmount: number; fromCurrency: string; toAmount: number; toCurrency: string; memo?: string | null }
  ```

### 3.2 API 클라이언트 — `lib/cashflow-api.ts`
- `transfer(req: TransferRequest): Promise<CashFlowItem[]>` → `POST /transfer`.
- `fx(req: FxRequest): Promise<CashFlowItem[]>` → `POST /fx`.
- (기존 list/record/remove 유지.)

### 3.3 페이지 — `app/unified/cashflow/page.tsx` ('use client')
- `useCashFlowApi()`·`useUnifiedApi()`(accounts)·react-query.
- 계좌 목록 쿼리, 내부이동 최근 리스트(=`cashflow.list()` 후 `linkId != null` 필터 또는 flowType 내부 필터로 표시; 기간 파라미터 없이 전체 조회).
- **이체 폼**: 계좌 셀렉트 2개(from/to), 날짜(default 오늘), 금액, 통화(from 계좌 통화 자동), 메모. 제출 → `transfer` mutation.
- **환전 폼**: 계좌 셀렉트, 날짜, fromAmount·fromCurrency, toAmount·toCurrency, 메모. 제출 → `fx` mutation.
- 성공 시 폼 리셋 + `['cashflow']` invalidate. 오류 메시지 표시.
- 다크 테마·기존 페이지(accounts/new 등) 룩앤필 준수.

### 3.4 NavBar — `components/NavBar.tsx`
- `/unified/reports` 근처에 `<Link href="/unified/cashflow">현금흐름</Link>` 추가(기존 링크 스타일 동일).

## 4. Tests
- **FE 타입체크**: `npx tsc --noEmit` clean (신규 타입·페이지·api).
- 단위 테스트 프레임워크가 FE에 구성돼 있지 않으면 타입체크 + 수동 확인으로 갈음(기존 FE 관례 따름). 폼 로직의 순수 검증 함수(from≠to·fromCcy≠toCcy·양수)는 페이지 내 헬퍼로 두되, 별도 테스트 러너가 없으면 타입체크로 갈음.

## 5. Rollout
- **스키마·BE 변경 없음**(Phase 1/2 재사용) → 마이그레이션 불필요. main 병합 시 FE 배포.
- 검증: `/unified/cashflow`에서 이체/환전 기록 → CASHFLOW 리포트 "환전·계좌간이체" 섹션(Phase 2)에 표기 확인.

## 6. Affected Files
**FE**: (수정) `types/returns.ts`, `lib/cashflow-api.ts`, `components/NavBar.tsx`; (신규) `app/unified/cashflow/page.tsx`.

## 7. Out of Scope (후속)
일반 입출금 기록 UI, 기록 수정/삭제 UX 고도화, 환율 자동조회 도우미, 계좌간 환전, 대량 CSV 반입.
