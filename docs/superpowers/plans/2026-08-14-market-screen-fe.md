# 시장 화면 FE Implementation Plan (AF-104 · PR 2/2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /api/market`이 주는 지수·환율·금리를 네 탭 화면으로 보여준다.

**Architecture:** 한 번의 쿼리로 네 탭 데이터를 받아 탭별 컴포넌트가 나눠 쓴다. 코드→한글 라벨 매핑과 국가 구분은 프런트가 표 하나로 소유한다(백엔드는 코드만 싣는다). 탭 상태는 URL 쿼리에 둔다.

**Tech Stack:** Next.js(App Router) · React Query · Tailwind · 기존 `components/ui` 프리미티브

**설계 문서:** `docs/superpowers/specs/2026-08-14-market-screen-design.md`
**백엔드:** PR #163(조회 API) + #164(미국 금리) 머지·배포 완료

---

## 사전 필독 (모든 태스크 공통)

### 이 저장소에는 프런트엔드 테스트가 없다

`package.json`에 test 스크립트가 없고 jest·vitest·testing-library 어느 것도 의존성에 없다.
보고서 페이지가 스무 개 넘는데 테스트 파일이 하나도 없다 — **확립된 관행이므로 따른다.**

그래서 이 계획의 검증은 **타입 체크 + 브라우저**다. 태스크마다:

```bash
cd frontend/allfolio_app && npx tsc --noEmit
```

그리고 개발 서버를 띄워 실제 페이지를 열고 DOM·콘솔·네트워크를 확인한다.
**"화면이 뜬다"에서 멈추지 말 것** — 숫자가 맞는지, 빈 상태가 제대로 나오는지까지 본다.

### 응답 모양은 실물로 확인된 것이다

2026-08-14 운영에서 받은 실제 응답:

```
flags: {"indicesEnabled": true}
domestic: 5종, overseas: 9종
fx: 2026-08-14 326회차, 58통화
rates: 10종 (한국 6 + 미국 4)
```

스케일: **원본 값은 소수 4자리**(`"2.7500"`, `"385.4700"`), **파생값은 2자리**(`changeBp: "-1.20"`,
`changeRate: "0.02"`). Jackson이 `BigDecimal` 스케일을 보존하므로 그대로 믿고 포맷하면 된다.

**설계 문서가 한 곳 낡았다.** 거기엔 "이번 금리 탭은 한국 6종만"이라고 적혀 있는데, 그 뒤 FRED가
머지돼(#164) 미국 4종이 같은 `rates` 배열에 실린다. **이 계획은 한·미 2단으로 간다** — 설계 문서의
원래 그림(한국/미국 2단 + 한·미 기준금리차)이 이제 실제로 가능해진 것이라, 문서의 최종 의도에
오히려 더 가깝다.

### 세 가지 "데이터 없음" 규약이 다르다

`MarketSnapshot`의 KDoc이 명시한 계약이다. **셋을 같게 다루면 안 된다:**

| 필드 | `null` | `[]` |
|---|---|---|
| `domestic`·`overseas` | 플래그 off — 탭 자체를 지운다 | 켜져 있고 데이터 없음 — "데이터 없음" |
| `fx` | 데이터 없음 | (해당 없음) |
| `rates` | (해당 없음) | 데이터 없음 |

**`domestic ?? []` 같은 코드를 쓰지 말 것.** 플래그 off를 "데이터 없음"으로 바꿔 버리고,
그러면 지수 탭이 빈 채로 남아 재배포 킬 스위치가 화면상 무력해진다.

### 라우트는 `/unified/market`이다 — 설계 문서의 `/market`이 아니다

설계 문서는 예시로 `/market?tab=fx`라고 적었지만 `app/unified/layout.tsx`가 **인증 가드**다
(`authenticated`가 아니면 `/login`으로 보낸다). 최상위 라우트로 두면 로그아웃 상태에서도 렌더되고,
그건 재배포 관점에서 정확히 피하려던 것이다. 백엔드도 `/api/market`을 `authenticated`로 막는다.

## 파일 구조

| 파일 | 책임 |
|---|---|
| `types/market.ts` (신규) | 응답 타입 |
| `lib/market-api.ts` (신규) | axios 클라이언트 |
| `lib/useApi.ts` (수정) | `useMarketApi()` 추가 |
| `lib/market-labels.ts` (신규) | 코드→한글 라벨 + 국가 구분. **매핑을 소유하는 유일한 파일** |
| `components/NavBar.tsx` (수정) | `시장` 항목 |
| `app/unified/market/page.tsx` (신규) | 탭 셸 + URL 상태 + 쿼리 |
| `components/market/IndexCards.tsx` (신규) | 지수 카드 목록 |
| `components/market/FxPanel.tsx` (신규) | 내 통화 카드 + 58통화 표 + 검색 |
| `components/market/RatePanel.tsx` (신규) | 금리 한·미 2단 |

---

### Task 1: 타입 + API 클라이언트

**Files:**
- Create: `frontend/allfolio_app/types/market.ts`
- Create: `frontend/allfolio_app/lib/market-api.ts`
- Modify: `frontend/allfolio_app/lib/useApi.ts`

- [ ] **Step 1: 타입을 쓴다**

`types/market.ts`:

```ts
/**
 * GET /api/market 응답 (AF-104).
 *
 * **숫자가 전부 string이다.** 백엔드가 BigDecimal을 스케일 보존해서 보내므로
 * (`"2.7500"`, `"-1.20"`) number로 받으면 자릿수가 사라진다. 표시용으로는 그대로 쓰고,
 * 계산이 필요할 때만 Number()로 바꾼다.
 */
export interface MarketSnapshot {
  /** null = 플래그 off(서버가 안 실었다), [] = 켜져 있고 데이터 없음 */
  domestic: IndexQuoteView[] | null
  overseas: IndexQuoteView[] | null
  /** null = 데이터 없음 */
  fx: FxSnapshot | null
  rates: RateView[]
  flags: MarketFlags
}

export interface IndexQuoteView {
  code: string
  price: string
  change: string
  changeRate: string
  /** 장중 | 장마감 | 개장전 */
  marketStatus: string
  tradeDate: string
  /** OPEN | MID | CLOSE */
  slot: string
}

export interface FxSnapshot {
  baseDate: string
  roundNo: number
  /** **UTC다.** new Date()로 읽으면 로컬로 해석돼 KST 사용자에게 9시간 이르게 보인다 */
  collectedAt: string
  quotes: FxQuoteView[]
}

export interface FxQuoteView {
  currency: string
  baseRate: string
  /** 은행이 그 통화를 현찰·송금으로 취급 안 하면 null이다 — 0이 아니라 `-`로 그린다 */
  cashBuy: string | null
  cashSell: string | null
  remitSend: string | null
  remitReceive: string | null
  /** null = 직전 기준일에 그 통화가 없었다 */
  change: string | null
  changeRate: string | null
}

export interface RateView {
  code: string
  value: string
  /** **항목마다 다르다.** 기준금리 공표가 시장금리보다 이틀 늦다 */
  quoteDate: string
  /** bp다(%p 아님). null = 비교할 직전 값 없음 */
  changeBp: string | null
}

export interface MarketFlags {
  indicesEnabled: boolean
}
```

- [ ] **Step 2: 클라이언트를 만든다**

`lib/market-api.ts` — `lib/report-api.ts`의 형태를 그대로 따른다:

```ts
import axios from 'axios'
import type { MarketSnapshot } from '@/types/market'

const BASE_URL = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'}/api/market`

export function createMarketApi(accessToken: string) {
  const api = axios.create({
    baseURL: BASE_URL,
    timeout: 30_000,
    headers: { Authorization: `Bearer ${accessToken}` },
  })

  return {
    snapshot: async (): Promise<MarketSnapshot> => (await api.get<MarketSnapshot>('')).data,
  }
}
```

- [ ] **Step 3: 훅을 더한다**

`lib/useApi.ts`에 임포트와 훅을 추가한다(파일의 기존 훅들과 같은 모양):

```ts
import { createMarketApi } from './market-api'

export function useMarketApi() {
  const { accessToken } = useAuth()
  return useMemo(
    () => (accessToken ? createMarketApi(accessToken) : null),
    [accessToken],
  )
}
```

- [ ] **Step 4: 타입 체크**

Run: `cd frontend/allfolio_app && npx tsc --noEmit`
Expected: 오류 없음

- [ ] **Step 5: 커밋**

```bash
git add frontend/allfolio_app/types/market.ts frontend/allfolio_app/lib/market-api.ts frontend/allfolio_app/lib/useApi.ts
git commit -m "feat(af-104): 시장 조회 API 클라이언트 + 타입"
```

---

### Task 2: 코드→라벨 매핑

**Files:**
- Create: `frontend/allfolio_app/lib/market-labels.ts`

**왜 프런트가 갖나:** 백엔드는 코드만 싣는다(`KOSPI`, `KTB_3Y`). 설정의 `nameContains`는 KIS 응답
검증용 부분 문자열(`"다우존스 산업"`)이지 표시명이 아니라 그걸 쓰면 안 된다. 한글 라벨이 어차피
프런트 몫이므로 국가 구분도 같은 표에서 한다.

- [ ] **Step 1: 매핑을 쓴다**

```ts
/**
 * 시장 데이터 코드 → 한글 라벨 (AF-104).
 *
 * **백엔드는 코드만 싣는다.** 표시명이 프런트 몫인 이유: 설정의 `nameContains`는 KIS 응답을
 * 검증하는 부분 문자열이지 이름이 아니고("다우존스 산업"), 지수를 새로 추가해도 화면 문구는
 * 어차피 사람이 정해야 한다.
 *
 * **모르는 코드는 코드 그대로 보여준다.** 백엔드에 종목이 추가됐는데 여기를 안 고치면
 * 라벨이 없는 채로라도 값이 보여야 한다 — 빈칸이면 종목이 사라진 것처럼 보인다.
 */
const INDEX_LABELS: Record<string, string> = {
  KOSPI: '코스피',
  KOSDAQ: '코스닥',
  KOSPI200: '코스피 200',
  KOSDAQ150: '코스닥 150',
  KRX300: 'KRX 300',
  SPX: 'S&P 500',
  NASDAQ: '나스닥 종합',
  DOW: '다우존스 산업',
  NASDAQ100: '나스닥 100',
  VIX: 'VIX 변동성',
  STOXX50: '유로 STOXX 50',
  NIKKEI225: '니케이 225',
  HANGSENG: '항셍',
  SHANGHAI: '상해종합',
}

const RATE_LABELS: Record<string, string> = {
  BASE_RATE: '한국은행 기준금리',
  CALL_ON: '콜금리(익일물)',
  CD_91D: 'CD 91일',
  KTB_3Y: '국고채 3년',
  KTB_10Y: '국고채 10년',
  CORP_AA3Y: '회사채 AA- 3년',
  US_FFR: '연방기금금리',
  UST_2Y: '미국채 2년',
  UST_10Y: '미국채 10년',
  UST_30Y: '미국채 30년',
}

/** 금리 탭의 2단 구성. 여기 없는 코드는 '기타'로 모인다 */
const KR_RATES = ['BASE_RATE', 'CALL_ON', 'CD_91D', 'KTB_3Y', 'KTB_10Y', 'CORP_AA3Y']
const US_RATES = ['US_FFR', 'UST_2Y', 'UST_10Y', 'UST_30Y']

export function indexLabel(code: string): string {
  return INDEX_LABELS[code] ?? code
}

export function rateLabel(code: string): string {
  return RATE_LABELS[code] ?? code
}

export type RateCountry = 'KR' | 'US' | 'ETC'

export function rateCountry(code: string): RateCountry {
  if (KR_RATES.includes(code)) return 'KR'
  if (US_RATES.includes(code)) return 'US'
  return 'ETC'
}

/**
 * 통화 코드 → 한글 국가·통화명. 58통화 전부를 적지 않는다 —
 * 자주 보는 것만 두고 나머지는 코드로 남긴다. 표에 코드 열이 따로 있어 정보가 없어지지 않는다.
 */
const CURRENCY_LABELS: Record<string, string> = {
  USD: '미국 달러', EUR: '유로', JPY: '일본 엔', CNY: '중국 위안',
  GBP: '영국 파운드', AUD: '호주 달러', CAD: '캐나다 달러', HKD: '홍콩 달러',
  CHF: '스위스 프랑', SGD: '싱가포르 달러', THB: '태국 바트', VND: '베트남 동',
}

export function currencyLabel(code: string): string {
  return CURRENCY_LABELS[code] ?? code
}
```

> 위 14개는 `application.yml`의 `market-index.domestic`(5) + `overseas`(9)와 대조해 적은 것이다.
> 특히 마지막 둘은 `HSI`·`SHCOMP`가 아니라 **`HANGSENG`·`SHANGHAI`**다 — 계획을 쓰다 한 번
> 틀렸던 자리이므로, 구현 전에 yml과 한 번 더 대조할 것. 틀려도 코드가 그대로 보일 뿐 안 죽는다.

- [ ] **Step 2: 타입 체크 + 커밋**

Run: `cd frontend/allfolio_app && npx tsc --noEmit`

```bash
git add frontend/allfolio_app/lib/market-labels.ts
git commit -m "feat(af-104): 시장 코드 한글 라벨 매핑"
```

---

### Task 3: 화면 뼈대 — 라우트·탭·URL 상태·내비게이션

**Files:**
- Create: `frontend/allfolio_app/app/unified/market/page.tsx`
- Modify: `frontend/allfolio_app/components/NavBar.tsx`

- [ ] **Step 1: 페이지를 만든다**

```tsx
'use client'

import { useQuery } from '@tanstack/react-query'
import { useRouter, useSearchParams } from 'next/navigation'
import { useMarketApi } from '@/lib/useApi'
import PageHeader from '@/components/ui/PageHeader'
import Label from '@/components/ui/Label'
import { ErrorState, LoadingState } from '@/components/ui/states'
import { cx } from '@/lib/cx'

type TabKey = 'domestic' | 'overseas' | 'fx' | 'rates'

const TABS: Array<{ key: TabKey; label: string }> = [
  { key: 'domestic', label: '국내' },
  { key: 'overseas', label: '해외' },
  { key: 'fx', label: '환율' },
  { key: 'rates', label: '금리' },
]

export default function MarketPage() {
  const api = useMarketApi()
  const router = useRouter()
  const params = useSearchParams()

  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['market', 'snapshot'],
    queryFn: () => api!.snapshot(),
    enabled: !!api,
    retry: false,
  })

  // **지수 플래그가 off면 탭 자체를 지운다.** "준비 중"을 띄우지 않는다 —
  // 없는 기능을 광고하는 셈이고, 눌러도 빈 화면이면 다음부터 아무도 안 누른다.
  // `data`가 아직 없을 때 탭을 다 보여주면 로딩 후 사라지며 깜빡이므로, 로딩 중에도 숨긴다.
  const indicesOn = data?.flags.indicesEnabled ?? false
  const visibleTabs = TABS.filter((t) => indicesOn || (t.key !== 'domestic' && t.key !== 'overseas'))

  // 탭 상태를 URL에 둔다 — 안 그러면 뒤로가기가 화면을 통째로 벗어난다
  const requested = params.get('tab') as TabKey | null
  const tab: TabKey = visibleTabs.some((t) => t.key === requested)
    ? (requested as TabKey)
    : visibleTabs[0]?.key ?? 'fx'

  const selectTab = (key: TabKey) => {
    router.replace(`/unified/market?tab=${key}`, { scroll: false })
  }

  return (
    <div className="border border-line-card bg-surface">
      <PageHeader className="px-5 pt-4 sm:px-7" title="시장" meta="지수 · 환율 · 금리" />

      <nav className="flex gap-1 border-b border-line-card px-5 sm:px-7" aria-label="시장 탭">
        {visibleTabs.map((t) => (
          <button
            key={t.key}
            type="button"
            onClick={() => selectTab(t.key)}
            aria-current={tab === t.key ? 'page' : undefined}
            className={cx(
              'px-3 py-2 font-mono text-[11px] tracking-label transition-colors',
              tab === t.key ? 'border-b-2 border-ink text-ink' : 'text-fg-faint hover:text-ink',
            )}
          >
            {t.label}
          </button>
        ))}
      </nav>

      <div className="px-5 py-5 sm:px-7">
        {isLoading && <LoadingState />}
        {isError && <ErrorState message="시장 데이터를 불러오지 못했습니다." onRetry={() => refetch()} />}
        {data && (
          <>
            {tab === 'domestic' && <Label size="sm" tone="faint">국내 지수</Label>}
            {tab === 'overseas' && <Label size="sm" tone="faint">해외 지수</Label>}
            {tab === 'fx' && <Label size="sm" tone="faint">환율</Label>}
            {tab === 'rates' && <Label size="sm" tone="faint">금리</Label>}
          </>
        )}
      </div>
    </div>
  )
}
```

> 탭 본문은 Task 4~6이 채운다. 이 태스크는 **셸이 도는 것**까지다.

- [ ] **Step 2: 내비게이션에 항목을 더한다**

`components/NavBar.tsx`의 항목 배열 **맨 끝**에 넣는다:

```tsx
  { href: '/unified/market', label: '시장' },
```

**오른쪽 끝인 게 설계다** — 부차적 화면이라 대시보드보다 먼저 눈에 들어오면 안 된다.

- [ ] **Step 3: 타입 체크**

Run: `cd frontend/allfolio_app && npx tsc --noEmit`
Expected: 오류 없음

- [ ] **Step 4: 브라우저로 확인한다**

개발 서버를 띄우고 `/unified/market`을 연다. 확인할 것:

- 탭 넷이 보이고, 누르면 URL이 `?tab=rates`로 바뀐다
- **주소창에 `?tab=rates`를 직접 넣고 새로고침하면 그 탭이 선택된 채로 뜬다**
- **뒤로가기가 화면을 벗어나지 않는다** (탭 전환은 `replace`라 히스토리를 안 쌓는다 — 의도한 동작이다)
- 네트워크 탭에 `/api/market` 요청이 **한 번만** 나간다 (탭을 바꿔도 다시 안 부른다)
- 콘솔에 오류가 없다

- [ ] **Step 5: 커밋**

```bash
git add frontend/allfolio_app/app/unified/market/page.tsx frontend/allfolio_app/components/NavBar.tsx
git commit -m "feat(af-104): 시장 화면 셸 — 4탭 + URL 상태"
```

---

### Task 4: 환율 탭

**Files:**
- Create: `frontend/allfolio_app/components/market/FxPanel.tsx`
- Modify: `frontend/allfolio_app/app/unified/market/page.tsx`

설계에서 이 탭이 지는 몫이 있다: **"내 숫자와의 연결"**. 지수 카드의 "내 수익률 한 줄"을
데이터가 없어 뺐으므로(AF-106에 묶임), 이 화면이 순수 시세 나열이 되지 않게 하는 건
상단 "내 통화" 카드뿐이다. **빼면 안 된다.**

- [ ] **Step 1: 컴포넌트를 만든다**

```tsx
'use client'

import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useUnifiedApi } from '@/lib/useApi'
import type { FxSnapshot, FxQuoteView } from '@/types/market'
import { currencyLabel } from '@/lib/market-labels'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import { EmptyState } from '@/components/ui/states'
import { dirTone } from '@/lib/format'

/** 값이 없으면 0이 아니라 대시다 — 0은 "그 값이 0원"이라는 뜻이 된다 */
function orDash(v: string | null): string {
  return v ?? '-'
}

export default function FxPanel({ fx }: { fx: FxSnapshot | null }) {
  const unified = useUnifiedApi()
  const [q, setQ] = useState('')

  // 내 보유 통화 — 계좌 목록에서 뽑는다. 시장 데이터와 섞지 않는 이유는
  // /api/market이 사용자별 데이터를 안 싣기 때문이고, 그건 캐시 가능하게 두려는 판단이다
  const { data: accounts } = useQuery({
    queryKey: ['accounts', 'list'],
    queryFn: () => unified!.accounts.list(),
    enabled: !!unified,
    retry: false,
  })

  const myCurrencies = useMemo(() => {
    const set = new Set((accounts ?? []).map((a) => a.currency).filter(Boolean))
    set.delete('KRW') // 원화는 환율이 없다
    return [...set]
  }, [accounts])

  if (!fx) return <EmptyState message="환율 데이터가 아직 없습니다." />

  const byCode = new Map(fx.quotes.map((x) => [x.currency, x]))
  const mine = myCurrencies.map((c) => byCode.get(c)).filter((x): x is FxQuoteView => !!x)

  const filtered = q.trim()
    ? fx.quotes.filter((x) =>
        x.currency.toLowerCase().includes(q.toLowerCase()) ||
        currencyLabel(x.currency).includes(q),
      )
    : fx.quotes

  return (
    <div className="space-y-6">
      {/* 고시 도장 — 사용자가 은행 화면과 직접 대조하는 신뢰 장치다.
          **회차와 기준일에만 해당한다** — 값은 우리가 1단위로 정규화해서(JPY 100엔 고시 등)
          은행 화면 숫자와 다를 수 있다. 그걸 "고치지" 말 것. */}
      <div className="flex items-baseline justify-between">
        <Label size="sm" tone="faint">내 통화</Label>
        <span className="font-mono text-[10px] tracking-label text-fg-faint">
          하나은행 고시 · {fx.roundNo}회차 · {fx.baseDate}
        </span>
      </div>

      {mine.length === 0 ? (
        <p className="text-[13px] text-fg-2">외화 계좌가 없습니다.</p>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2">
          {mine.map((x) => (
            <div key={x.currency} className="border border-line-card p-4">
              <div className="flex items-baseline justify-between">
                <span className="font-mono text-[12px]">{x.currency}</span>
                <span className="text-[11px] text-fg-faint">{currencyLabel(x.currency)}</span>
              </div>
              <div className="mt-2 flex items-baseline gap-2">
                <Num className="text-[18px]">{x.baseRate}</Num>
                {x.change && (
                  <Num tone={dirTone(Number(x.change))} className="text-[12px]">
                    {x.change} ({x.changeRate}%)
                  </Num>
                )}
              </div>
              <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-1 text-[11px] text-fg-2">
                <dt>현찰 살 때</dt><dd className="text-right"><Num>{orDash(x.cashBuy)}</Num></dd>
                <dt>현찰 팔 때</dt><dd className="text-right"><Num>{orDash(x.cashSell)}</Num></dd>
                <dt>송금 보낼 때</dt><dd className="text-right"><Num>{orDash(x.remitSend)}</Num></dd>
                <dt>송금 받을 때</dt><dd className="text-right"><Num>{orDash(x.remitReceive)}</Num></dd>
              </dl>
              {x.currency === 'JPY' && (
                <p className="mt-2 text-[10px] text-fg-faint">1엔 기준입니다 (은행 화면은 100엔 기준)</p>
              )}
            </div>
          ))}
        </div>
      )}

      <div className="flex items-baseline justify-between pt-2">
        <Label size="sm" tone="faint">전체 통화 {fx.quotes.length}</Label>
        {/* `Field` 프리미티브를 안 쓴다 — `id`가 필수이고 자식이 단일 엘리먼트여야 하는
            폼 필드용이라, 라벨 없는 검색 입력에는 안 맞는다 */}
        <input
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder="통화 검색"
          aria-label="통화 검색"
          className="border border-line-card bg-transparent px-2 py-1 font-mono text-[11px]"
        />
      </div>

      <table className="w-full text-[12px]">
        <thead>
          <tr className="border-b border-line-card text-left text-fg-faint">
            <th className="py-1 font-normal">통화</th>
            <th className="py-1 font-normal">이름</th>
            <th className="py-1 text-right font-normal">매매기준율</th>
            <th className="py-1 text-right font-normal">전일대비</th>
          </tr>
        </thead>
        <tbody>
          {filtered.map((x) => (
            <tr key={x.currency} className="border-b border-line-card/50">
              <td className="py-1 font-mono">{x.currency}</td>
              <td className="py-1 text-fg-2">{currencyLabel(x.currency)}</td>
              <td className="py-1 text-right"><Num>{x.baseRate}</Num></td>
              <td className="py-1 text-right">
                {x.change ? (
                  <Num tone={dirTone(Number(x.change))}>{x.change} ({x.changeRate}%)</Num>
                ) : (
                  <span className="text-fg-faint">-</span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {filtered.length === 0 && <p className="py-6 text-center text-[12px] text-fg-faint">검색 결과가 없습니다.</p>}
    </div>
  )
}
```

- [ ] **Step 2: 페이지에 연결한다**

`app/unified/market/page.tsx`에서 `{tab === 'fx' && <Label …>}`을 `{tab === 'fx' && <FxPanel fx={data.fx} />}`로 바꾸고 임포트를 더한다.

- [ ] **Step 3: 타입 체크**

Run: `cd frontend/allfolio_app && npx tsc --noEmit`

- [ ] **Step 4: 브라우저로 확인한다**

`/unified/market?tab=fx`를 연다. 확인할 것:

- **58행이 다 나온다** (헤더의 "전체 통화 58"과 표 행 수가 같은지)
- **USD가 상단 "내 통화" 카드에 있다** — 표에서는 알파벳 순이라 50번째쯤이라 스크롤해야 나온다.
  이 카드가 없으면 화면이 못 쓰게 되는 이유가 그것이다
- 검색창에 `usd`를 넣으면 한 줄만 남고, `달러`를 넣으면 미국·호주·캐나다·싱가포르·홍콩이 남는다
- 현찰·송금 값이 없는 통화(대부분의 소수 통화)가 **`0`이 아니라 `-`**로 보인다
- 도장에 `326회차 · 2026-08-14` 형태가 보인다
- 상승은 빨강, 하락은 파랑

- [ ] **Step 5: 커밋**

```bash
git add frontend/allfolio_app/components/market/FxPanel.tsx frontend/allfolio_app/app/unified/market/page.tsx
git commit -m "feat(af-104): 환율 탭 — 내 통화 카드 + 58통화 검색"
```

---

### Task 5: 금리 탭 — 한·미 2단

**Files:**
- Create: `frontend/allfolio_app/components/market/RatePanel.tsx`
- Modify: `frontend/allfolio_app/app/unified/market/page.tsx`

- [ ] **Step 1: 컴포넌트를 만든다**

```tsx
'use client'

import type { RateView } from '@/types/market'
import { rateLabel, rateCountry } from '@/lib/market-labels'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import { EmptyState } from '@/components/ui/states'
import { dirTone } from '@/lib/format'

function Section({ title, rows }: { title: string; rows: RateView[] }) {
  if (rows.length === 0) return null
  return (
    <div>
      <Label size="sm" tone="faint">{title}</Label>
      <table className="mt-2 w-full text-[12px]">
        <tbody>
          {rows.map((r) => (
            <tr key={r.code} className="border-b border-line-card/50">
              <td className="py-1.5">{rateLabel(r.code)}</td>
              <td className="py-1.5 text-right"><Num className="text-[13px]">{r.value}</Num></td>
              <td className="py-1.5 text-right">
                {r.changeBp ? (
                  <Num tone={dirTone(Number(r.changeBp))}>{r.changeBp}bp</Num>
                ) : (
                  <span className="text-fg-faint">-</span>
                )}
              </td>
              {/* **기준일을 항목마다 단다.** 기준금리 공표가 시장금리보다 이틀 늦은 게
                  실측으로 확인됐다 — 공통 헤더에 시각 하나를 두면 화면이 거짓말을 한다 */}
              <td className="py-1.5 text-right font-mono text-[10px] text-fg-faint">{r.quoteDate}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export default function RatePanel({ rates }: { rates: RateView[] }) {
  if (rates.length === 0) return <EmptyState message="금리 데이터가 아직 없습니다." />

  const kr = rates.filter((r) => rateCountry(r.code) === 'KR')
  const us = rates.filter((r) => rateCountry(r.code) === 'US')
  const etc = rates.filter((r) => rateCountry(r.code) === 'ETC')

  // 한·미 기준금리차 — 저장하지 않고 여기서 만든다. 원본이 정정되면 파생값은
  // 같이 안 고쳐져 화석이 되기 때문이다(AF-102 판단)
  const krBase = kr.find((r) => r.code === 'BASE_RATE')
  const usBase = us.find((r) => r.code === 'US_FFR')
  const gap = krBase && usBase ? (Number(krBase.value) - Number(usBase.value)).toFixed(2) : null

  return (
    <div className="space-y-6">
      {gap && (
        <div className="border border-line-card p-4">
          <Label size="sm" tone="faint">한·미 기준금리차</Label>
          <div className="mt-1 flex items-baseline gap-2">
            <Num tone={dirTone(Number(gap))} className="text-[18px]">{gap}%p</Num>
            <span className="text-[11px] text-fg-2">
              한국 {krBase.value} · 미국 {usBase.value}
            </span>
          </div>
          <p className="mt-1 text-[10px] text-fg-faint">
            기준일이 다를 수 있습니다 — 한국 {krBase.quoteDate} · 미국 {usBase.quoteDate}
          </p>
        </div>
      )}
      <Section title="한국 (한국은행)" rows={kr} />
      <Section title="미국 (FRED)" rows={us} />
      <Section title="기타" rows={etc} />
    </div>
  )
}
```

- [ ] **Step 2: 페이지에 연결한다**

`{tab === 'rates' && <RatePanel rates={data.rates} />}`로 바꾸고 임포트를 더한다.

- [ ] **Step 3: 타입 체크 + 브라우저 확인**

Run: `cd frontend/allfolio_app && npx tsc --noEmit`

`/unified/market?tab=rates`에서 확인할 것:

- **한국 6종, 미국 4종**이 각 단에 나온다. `기타` 단은 안 보인다(코드가 다 매핑돼 있으면)
- **기준일이 항목마다 다르다** — 기준금리가 다른 것들보다 이틀쯤 이르다. 그게 보여야 정상이다
- `changeBp`가 `-1.20bp` 형태다. **`-0.01` 같은 값이 보이면 단위가 100배 틀린 것이니 멈출 것**
- 한·미 기준금리차 카드가 뜨고, 두 기준일이 함께 적힌다

- [ ] **Step 4: 커밋**

```bash
git add frontend/allfolio_app/components/market/RatePanel.tsx frontend/allfolio_app/app/unified/market/page.tsx
git commit -m "feat(af-104): 금리 탭 — 한·미 2단 + 기준금리차"
```

---

### Task 6: 지수 탭 (국내·해외)

**Files:**
- Create: `frontend/allfolio_app/components/market/IndexCards.tsx`
- Modify: `frontend/allfolio_app/app/unified/market/page.tsx`

- [ ] **Step 1: 컴포넌트를 만든다**

```tsx
'use client'

import type { IndexQuoteView } from '@/types/market'
import { indexLabel } from '@/lib/market-labels'
import Num from '@/components/ui/Num'
import Badge from '@/components/ui/Badge'
import { EmptyState } from '@/components/ui/states'
import { dirTone } from '@/lib/format'

export default function IndexCards({ quotes }: { quotes: IndexQuoteView[] }) {
  if (quotes.length === 0) return <EmptyState message="지수 데이터가 아직 없습니다." />

  return (
    <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
      {quotes.map((q) => (
        <div key={q.code} className="border border-line-card p-4">
          <div className="flex items-baseline justify-between">
            <span className="text-[13px]">{indexLabel(q.code)}</span>
            {/* 장 상태가 없으면 한국 낮에 미국 지수가 안 움직이는 걸 보고 고장으로 오해한다 */}
            <Badge>{q.marketStatus}</Badge>
          </div>
          <div className="mt-2">
            <Num className="text-[20px]">{q.price}</Num>
          </div>
          <div className="mt-1">
            <Num tone={dirTone(Number(q.change))} className="text-[12px]">
              {q.change} ({q.changeRate}%)
            </Num>
          </div>
          {/* 기준 시각 — 장마감이면 언제 종가인지까지 말한다 */}
          <p className="mt-2 font-mono text-[10px] text-fg-faint">
            {q.tradeDate} · {q.slot}
          </p>
        </div>
      ))}
    </div>
  )
}
```

- [ ] **Step 2: 페이지에 연결한다**

```tsx
{tab === 'domestic' && data.domestic && <IndexCards quotes={data.domestic} />}
{tab === 'overseas' && data.overseas && <IndexCards quotes={data.overseas} />}
```

**`data.domestic ?? []`를 쓰지 말 것.** `null`은 플래그 off이고 그때는 탭 자체가 없으므로
이 분기에 도달하지 않는다. `?? []`로 바꾸면 플래그 off가 "데이터 없음"으로 둔갑한다.

- [ ] **Step 3: 타입 체크 + 브라우저 확인**

`/unified/market?tab=domestic`과 `?tab=overseas`에서:

- 국내 5장, 해외 9장
- 장 상태 배지가 보인다(`장중`·`장마감`·`개장전`)
- 라벨이 한글이다. **코드가 그대로 보이는 카드가 있으면** `market-labels.ts`에 그 코드를 더한다
- 등락 색이 빨강/파랑

- [ ] **Step 4: 플래그 off 동작을 확인한다**

**이건 이 화면에서 가장 중요한 확인이다.** 개발 서버가 보는 백엔드에서
`MARKET_INDICES_ENABLED=false`로 띄우고 다시 연다:

- **탭이 둘만 보인다** (환율·금리)
- `?tab=domestic`을 주소창에 직접 넣어도 환율 탭으로 떨어진다 (없는 탭 요청은 첫 탭으로)
- 네트워크 응답에 `domestic`·`overseas`가 `null`이다

로컬 백엔드를 띄우기 어려우면 이 단계를 **PR 본문에 미확인으로 남기고** 배포 후 확인한다 —
조용히 건너뛰지 말 것.

- [ ] **Step 5: 커밋**

```bash
git add frontend/allfolio_app/components/market/IndexCards.tsx frontend/allfolio_app/app/unified/market/page.tsx
git commit -m "feat(af-104): 지수 탭 — 국내·해외 카드"
```

---

### Task 7: 전체 확인 + PR

- [ ] **Step 1: 빌드**

```bash
cd frontend/allfolio_app && npm run build
```
Expected: 성공. 타입 오류·미사용 임포트가 여기서 걸린다.

- [ ] **Step 2: 네 탭을 모두 눈으로 확인하고 스크린샷을 남긴다**

각 탭의 스크린샷을 PR에 붙인다. 화면 작업이라 글로만 설명하면 리뷰가 안 된다.

- [ ] **Step 3: 반응형을 확인한다**

브라우저 폭을 375px(모바일)로 줄이고 네 탭을 다시 본다:

- 58행 표가 가로로 삐져나가지 않는다(넘치면 표를 `overflow-x-auto`로 감싼다)
- 카드 그리드가 1열로 접힌다
- 탭 넷이 한 줄에 들어간다

- [ ] **Step 4: 푸시하고 PR**

```bash
git push -u origin feat/af-104-market-screen-fe
```

PR 본문에 담을 것:
- 탭별 스크린샷
- **지수 카드에 "내 수익률 한 줄"이 없는 이유** (데이터가 없다 — `byRegion`은 비중만 담고
  지역이 통화 파생이라 현금이 섞인다. AF-106에 묶임)
- 플래그 off 확인 여부 (Task 6 Step 4)
- 라우트가 설계 문서의 `/market`이 아니라 `/unified/market`인 이유(인증 가드)

- [ ] **Step 5: CI 확인**

```bash
gh pr checks --watch
```

---

## 완료 후 보고할 것

- Task 7 Step 2 스크린샷 네 장
- 플래그 off 동작 확인 결과 (또는 미확인이면 그렇게)
- 라벨이 없어 코드 그대로 나온 종목이 있었는지
- 모바일 폭에서 깨진 곳이 있었는지
