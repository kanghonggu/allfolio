# 공시 피드 화면 (S12) — 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 보유종목 공시와 임원·주요주주 소유변동을 한 화면(`/unified/disclosures`)에서 보여준다.

**Architecture:** 백엔드는 이미 있는 `GET /api/disclosures`를 두 군데만 고친다 — 정정공시 묶기 키와 `heldCount` 추가. 프런트는 시장 화면(AF-104) 선례를 따라 페이지를 얇게 두고 렌더링을 패널 둘로 나눈다.

**Tech Stack:** Kotlin/Spring(백엔드) · Next.js App Router · TypeScript · react-query · Tailwind · axios

**선행 스펙:** `docs/superpowers/specs/2026-08-19-disclosure-feed-screen-design.md`
**백엔드 스펙:** `docs/superpowers/specs/2026-08-18-dart-disclosure-design.md` (6·7절이 데이터 계약)

---

## 작업 전 반드시 읽을 것

**브랜치를 확인하고 전환하지 말 것.** 작업 시작 전과 커밋 직전에 `git branch --show-current`.

**`git commit --amend`를 쓰지 말 것. 항상 새 커밋.**

**이 레포는 주석이 무겁다.** 각 결정의 "왜"를 한국어로 남긴다. 아래 코드 블록의 주석은 최소한이니 실측 근거를 보태 보강할 것 — 단, **사실이 아닌 것을 쓰지 말 것.**

**프런트엔드에는 테스트 인프라가 없다**(테스트 파일 0, 러너 의존성 0, `package.json`에 `test` 스크립트 없음). **러너를 도입하지 말 것.** FE 검증은 Task 7의 수동 항목으로 한다.

### 명령

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.dart.query.*"
```

```bash
cd frontend/allfolio_app && npx tsc --noEmit && npm run build
```

---

## File Structure

| 파일 | 책임 |
|---|---|
| **백엔드 (수정)** | |
| `backend-app/.../dart/query/DisclosureFeedService.kt` | 묶기 키 교체 + `heldCount` + `flrNm` 노출 |
| `backend-app/.../dart/query/DisclosureFeedServiceTest.kt` | 위 셋의 회귀 테스트 |
| **프런트엔드 (신규)** | |
| `types/disclosure.ts` | 응답 타입 |
| `lib/disclosure-api.ts` | `createDisclosureApi(token)` |
| `lib/useApi.ts` (수정) | `useDisclosureApi()` 추가 |
| `components/disclosure/DisclosureFeedPanel.tsx` | ① 목록 · Tier 배지 · 정정 표기 · T5 접기 |
| `components/disclosure/InsiderTradePanel.tsx` | ② 섹션 · 종목별 그룹 · 부호 표기 |
| `app/unified/disclosures/page.tsx` | 페치 · 상태 분기 · 패널 배치 |
| `components/NavBar.tsx` (수정) | 항목 1개 |

---

## Task 1: 정정공시 묶기 키 교체

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dart/query/DisclosureFeedService.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/dart/query/DisclosureFeedServiceTest.kt`

현재 `(corpCode, reportNmNorm)`으로 묶어 최신 건만 노출한다. **같은 이름의 별개 사건이 함께 접힌다** — 실측 6영업일 `is_material` 3,507행 중 137행이 정정이 아닌데 숨겨진다. 최악은 Tier 4로, 임원 각자가 내는 보고서라 이름이 전부 같아 한미약품 23건이 1건으로 접힌다.

- [ ] **Step 1: 실패하는 테스트 2개 추가**

먼저 기존 `disclosure(...)` 헬퍼에 인자 둘을 더한다. 현재 시그니처는 이렇다:

```kotlin
    private fun disclosure(
        rceptNo: String, stockCode: String?, tier: Short?, rceptDt: LocalDate,
        reportNm: String = "유상증자결정", corpCode: String = "C1",
    ) = DartDisclosureEntity(
```

아래로 바꾼다. **둘 다 기본값이 있으므로 기존 호출부는 그대로 컴파일된다.**

```kotlin
    private fun disclosure(
        rceptNo: String, stockCode: String?, tier: Short?, rceptDt: LocalDate,
        reportNm: String = "유상증자결정", corpCode: String = "C1",
        /** Tier 4는 임원 이름이 온다. 기본값은 회사 자신 — 나머지 Tier의 실제 모양이다 */
        flrNm: String = "회사",
        /** `[기재정정]` 접두어 유무. 새 묶기 규칙이 이걸 본다 */
        isCorrection: Boolean = false,
    ) = DartDisclosureEntity(
```

`DartDisclosureEntity(...)` 생성 부분에서 `flrNm = flrNm`, `isCorrection = isCorrection`으로 넘기도록 고친다(지금은 상수로 박혀 있다).

그다음 `DisclosureFeedServiceTest`에 아래 두 테스트를 넣는다.

```kotlin
    @Test
    fun `같은 이름이라도 보고자가 다르면 접히지 않는다`() {
        // 임원·주요주주 보고서는 임원 각자가 낸다. 실측: 한미약품 한 회사에서 23건이
        // 같은 이름으로 왔고, 이전 구현은 22건을 supersededCount로 숨겼다.
        val store = FakeStore(
            holdings = listOf("128940"),
            disclosures = listOf(
                disclosure("R1", "128940", 4, LocalDate.of(2026, 8, 18),
                    reportNm = "임원·주요주주특정증권등소유상황보고서", corpCode = "C1", flrNm = "황상연"),
                disclosure("R2", "128940", 4, LocalDate.of(2026, 8, 18),
                    reportNm = "임원·주요주주특정증권등소유상황보고서", corpCode = "C1", flrNm = "백가람"),
            ),
        )

        val feed = DisclosureFeedService(store).feedFor(userId, from)

        assertThat(feed.items).hasSize(2)
        assertThat(feed.items.map { it.supersededCount }).containsExactly(0, 0)
    }

    @Test
    fun `정정이 없으면 같은 이름이어도 접히지 않는다`() {
        // 실측: 두산퓨얼셀의 단일판매·공급계약체결 4건은 별개 계약 4건이다.
        // 이전 구현은 3건을 '정정으로 대체됨'으로 숨겼다.
        val store = FakeStore(
            holdings = listOf("336260"),
            disclosures = listOf(
                disclosure("R1", "336260", 1, LocalDate.of(2026, 8, 11),
                    reportNm = "단일판매·공급계약체결", corpCode = "C1"),
                disclosure("R2", "336260", 1, LocalDate.of(2026, 8, 18),
                    reportNm = "단일판매·공급계약체결", corpCode = "C1"),
            ),
        )

        val feed = DisclosureFeedService(store).feedFor(userId, from)

        assertThat(feed.items).hasSize(2)
    }
```

**기존 테스트 하나를 고쳐야 한다.** `같은 회사 같은 보고서는 최신 건만 낸다`는 두 행이 모두 `isCorrection=false`라 새 규칙에서는 접히지 않는다. 정정 관계임을 명시하도록 바꾼다:

```kotlin
    @Test
    fun `같은 회사 같은 보고서는 최신 건만 낸다`() {
        // [기재정정]은 새 rcept_no를 받는다. 정규화가 접두어를 떼므로 원본과 정정본이
        // 같은 그룹에 들어간다 — 실측 875건이 이 형태다.
        val store = FakeStore(
            holdings = listOf("005930"),
            disclosures = listOf(
                disclosure("R_ORIG", "005930", 5, LocalDate.of(2026, 8, 11), "반기보고서 (2026.06)"),
                disclosure("R_FIX", "005930", 5, LocalDate.of(2026, 8, 18), "반기보고서 (2026.06)",
                    isCorrection = true),
            ),
        )

        val feed = DisclosureFeedService(store).feedFor(userId, from)

        assertThat(feed.items.map { it.rceptNo }).containsExactly("R_FIX")
        assertThat(feed.items.single().supersededCount).isEqualTo(1)
    }
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.dart.query.DisclosureFeedServiceTest"
```

Expected: 새 테스트 2개 FAIL (`hasSize(2)` 기대인데 1이 나옴)

- [ ] **Step 3: 묶기 규칙 교체**

`feedFor`의 `groupBy` 블록을 아래로 바꾼다.

```kotlin
        val items = store.findMaterial(held, from)
            // **묶기 키에 flr_nm이 들어간다.** 임원·주요주주 보고서(Tier 4)는 임원 각자가
            // 내는데 report_nm이 전부 같다 — 실측 한미약품 23건·한미사이언스 16건.
            // flr_nm 없이 묶으면 그 회사 임원 전원의 보고서가 한 건으로 접힌다.
            // 나머지 Tier는 flr_nm이 회사 자신이라 키에 넣어도 결과가 안 바뀐다.
            .groupBy { Triple(it.corpCode, it.reportNmNorm, it.flrNm) }
            .flatMap { (_, group) ->
                // **정정이 있을 때만 접는다.** 정규화가 접두어를 떼므로 같은 이름의 별개
                // 사건도 한 그룹에 들어온다 — 실측 두산퓨얼셀의 단일판매·공급계약체결 4건은
                // 서로 다른 계약 4건이다. 접두어가 하나도 없으면 정정 관계가 아니므로 편다.
                if (group.none { it.isCorrection }) group.map { toItem(it, supersededCount = 0) }
                else listOf(toItem(group.maxWith(LATEST), supersededCount = group.size - 1))
            }
            .sortedWith(
                compareBy<DisclosureItem> { it.materialTier ?: Short.MAX_VALUE }
                    .thenByDescending { it.rceptDt }
                    .thenByDescending { it.rceptNo },
            )
```

클래스 안에 헬퍼와 비교자를 둔다.

```kotlin
    private companion object {
        val LATEST: Comparator<DartDisclosureEntity> =
            compareBy({ it.rceptDt }, { it.rceptNo })
    }

    private fun toItem(e: DartDisclosureEntity, supersededCount: Int) = DisclosureItem(
        rceptNo = e.rceptNo,
        corpName = e.corpName,
        stockCode = e.stockCode,
        reportNm = e.reportNm,
        flrNm = e.flrNm,
        rceptDt = e.rceptDt,
        materialTier = e.materialTier,
        isCorrection = e.isCorrection,
        sourceUrl = SOURCE_URL_PREFIX + e.rceptNo,
        supersededCount = supersededCount,
    )
```

`DisclosureItem`에 `flrNm`을 추가한다 — 화면이 Tier 4 행에서 임원 이름을 보여줘야 한다.

```kotlin
data class DisclosureItem(
    val rceptNo: String,
    val corpName: String,
    val stockCode: String?,
    val reportNm: String,
    /** 제출인. Tier 4는 임원 이름(실측 `황상연`), 나머지는 회사 자신이다 */
    val flrNm: String?,
    val rceptDt: LocalDate,
    val materialTier: Short?,
    val isCorrection: Boolean,
    val sourceUrl: String,
    /** 정정으로 접힌 이전 건 수. 접히지 않았으면 0 */
    val supersededCount: Int,
)
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.dart.query.DisclosureFeedServiceTest"
```

Expected: PASS (기존 6개 + 신규 2개 = 8개)

- [ ] **Step 5: 변이 테스트**

`flrNm`을 키에서 빼 보고 `같은 이름이라도 보고자가 다르면 접히지 않는다`가 빨개지는지, `group.none { it.isCorrection }` 분기를 지워 보고 `정정이 없으면…`이 빨개지는지 확인한다. 각각 `git checkout --`로 복구하고 `git status`가 빈 것을 확인할 것.

- [ ] **Step 6: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dart/query/DisclosureFeedService.kt \
        allfolio-backend/backend-app/src/test/kotlin/com/allfolio/dart/query/DisclosureFeedServiceTest.kt
git commit -m "fix(s12): 정정공시 묶기가 별개 사건을 숨기지 않게 한다 — 실측 137행"
```

---

## Task 2: `heldCount` 추가

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dart/query/DisclosureFeedService.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/dart/query/DisclosureFeedServiceTest.kt`

현재 응답이 `{ items, insiderTrades }`뿐이라 FE가 **"보유종목이 없다"와 "공시가 없다"를 구분할 수 없다.** 계좌를 연결하지 않은 사용자가 "공시가 없구나"로 오해한다. 서비스가 이미 `findHeldStockCodes`로 아는 값이라 추가 쿼리가 없다.

- [ ] **Step 1: 실패하는 테스트 추가**

```kotlin
    @Test
    fun `보유종목 수를 함께 낸다 — 보유 0과 공시 0은 다른 상태다`() {
        val store = FakeStore(holdings = listOf("005930", "000660"), disclosures = emptyList())

        val feed = DisclosureFeedService(store).feedFor(userId, from)

        assertThat(feed.heldCount).isEqualTo(2)
        assertThat(feed.items).isEmpty()
    }

    @Test
    fun `보유종목이 없으면 heldCount가 0이다`() {
        val store = FakeStore(holdings = emptyList(), disclosures = emptyList())

        assertThat(DisclosureFeedService(store).feedFor(userId, from).heldCount).isZero()
    }
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.dart.query.DisclosureFeedServiceTest"
```

Expected: 컴파일 실패 — `Unresolved reference: heldCount`

- [ ] **Step 3: 구현**

```kotlin
data class DisclosureFeed(
    val items: List<DisclosureItem>,
    val insiderTrades: List<InsiderTradeItem>,
    /**
     * 보유 종목 수. **화면이 "보유가 없다"와 "공시가 없다"를 갈라야 해서 낸다** —
     * 전자는 계좌 연결로 유도할 상태고 후자는 정상 상태라 문구가 달라야 한다.
     * `findHeldStockCodes`가 이미 구한 값이라 추가 쿼리가 없다.
     */
    val heldCount: Int,
)
```

`feedFor`의 두 반환 지점을 고친다.

```kotlin
        val held = store.findHeldStockCodes(userId)
        if (held.isEmpty()) return DisclosureFeed(emptyList(), emptyList(), heldCount = 0)
```

```kotlin
        return DisclosureFeed(items, insiders, heldCount = held.size)
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.dart.query.*"
```

Expected: PASS, 10개

- [ ] **Step 5: 전체 스위트 회귀 확인**

```bash
cd allfolio-backend && ./gradlew test
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dart/query/DisclosureFeedService.kt \
        allfolio-backend/backend-app/src/test/kotlin/com/allfolio/dart/query/DisclosureFeedServiceTest.kt
git commit -m "feat(s12): 응답에 heldCount를 싣는다 — 보유 0과 공시 0은 다른 상태다"
```

---

## Task 3: 프런트 타입과 API 클라이언트

**Files:**
- Create: `frontend/allfolio_app/types/disclosure.ts`
- Create: `frontend/allfolio_app/lib/disclosure-api.ts`
- Modify: `frontend/allfolio_app/lib/useApi.ts`

`lib/market-api.ts`와 같은 모양으로 만든다 — axios 인스턴스에 Bearer를 물리고 `create*Api(token)`을 내보낸다.

- [ ] **Step 1: 타입 작성**

`types/disclosure.ts`:

```typescript
/**
 * GET /api/disclosures 응답 (D1 / S12).
 *
 * **`ownedRate`·`changeRate`는 JSON number다.** 백엔드가 `NUMERIC(7,2)`로 스케일을
 * 정해 보내지만 `JSON.parse`가 뒤 0을 버린다(`0.05` → `0.05`, `46.00` → `46`).
 * 표시할 때 자릿수를 다시 고정할 것 — `types/market.ts`가 같은 함정을 적어 두고 있다.
 *
 * **`0.00`은 "변동 없음"이 아니다.** 지분율 0.005% 미만이 반올림된 것이다(실측 6행).
 * 수량 필드가 진실을 들고 있다.
 */
export interface DisclosureFeed {
  items: DisclosureItem[]
  insiderTrades: InsiderTradeItem[]
  /** 보유 종목 수. 0이면 "계좌를 연결하세요", 0이 아닌데 items가 비면 "공시가 없습니다" */
  heldCount: number
}

export interface DisclosureItem {
  rceptNo: string
  corpName: string
  stockCode: string | null
  /** 원문 그대로 — 정규화 전. 화면은 이것을 보여준다 */
  reportNm: string
  /** 제출인. Tier 4는 임원 이름, 나머지는 회사 자신 */
  flrNm: string | null
  /** ISO date (`2026-08-18`) */
  rceptDt: string
  /** 1~5. null이면 화이트리스트 미해당인데, 피드에는 is_material만 오므로 실제로는 안 온다 */
  materialTier: number | null
  isCorrection: boolean
  sourceUrl: string
  /** 정정으로 접힌 이전 건 수. 0이면 접히지 않음 */
  supersededCount: number
}

/**
 * **매수·매도를 말하지 않는다.** `elestock`에 변동사유 필드가 없어(30개사 3,922행 전건 확인)
 * 무상증자·스톡옵션 행사·상속과 장내매수를 구분할 수 없다. 이 타입에 `changeType` 같은
 * 필드를 추가하지 말 것 — 채울 소스가 없다.
 */
export interface InsiderTradeItem {
  rceptNo: string
  stockCode: string | null
  /** 보고자. OpenDART의 실제 필드명이 `repror`다 — 오타가 아니다 */
  repror: string
  officerPosition: string | null
  /** 등기임원 true / 비등기임원 false / 결측 null. 3-값이다 */
  isRegistered: boolean | null
  /** `10%이상주주` · `사실상지배주주` · null */
  majorHolderType: string | null
  reportDate: string
  ownedQty: number | null
  /** 음수 가능. 실측 범위 -58,500,000 ~ 36,000,000 */
  changeQty: number | null
  ownedRate: number | null
  changeRate: number | null
  sourceUrl: string
}
```

- [ ] **Step 2: API 클라이언트 작성**

`lib/disclosure-api.ts`:

```typescript
import axios from 'axios'
import type { DisclosureFeed } from '@/types/disclosure'

const BASE_URL = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'}/api/disclosures`

export function createDisclosureApi(accessToken: string) {
  const api = axios.create({
    baseURL: BASE_URL,
    timeout: 30_000,
    headers: { Authorization: `Bearer ${accessToken}` },
  })

  return {
    // from은 백엔드가 KST 기준 30일 전으로 기본값을 잡는다 — 여기서 안 보낸다.
    // 기간 선택 컨트롤을 만들지 않기로 했으므로 파라미터를 노출할 이유가 없다(설계 5절)
    feed: async (): Promise<DisclosureFeed> => (await api.get<DisclosureFeed>('')).data,
  }
}
```

- [ ] **Step 3: 훅 추가**

`lib/useApi.ts`에 import와 훅을 더한다. 파일 안의 기존 훅들과 같은 모양이다.

```typescript
import { createDisclosureApi } from './disclosure-api'
```

```typescript
export function useDisclosureApi() {
  const { accessToken } = useAuth()
  return useMemo(
    () => (accessToken ? createDisclosureApi(accessToken) : null),
    [accessToken],
  )
}
```

- [ ] **Step 4: 타입 검사**

```bash
cd frontend/allfolio_app && npx tsc --noEmit
```

Expected: 오류 없음

- [ ] **Step 5: 커밋**

```bash
git add frontend/allfolio_app/types/disclosure.ts \
        frontend/allfolio_app/lib/disclosure-api.ts \
        frontend/allfolio_app/lib/useApi.ts
git commit -m "feat(s12): 공시 피드 타입·API 클라이언트"
```

---

## Task 4: 공시 피드 패널

**Files:**
- Create: `frontend/allfolio_app/components/disclosure/DisclosureFeedPanel.tsx`

- [ ] **Step 1: 작성**

```tsx
// components/disclosure/DisclosureFeedPanel.tsx
'use client'

import { useState } from 'react'
import type { DisclosureItem } from '@/types/disclosure'
import Label from '@/components/ui/Label'
import { EmptyState } from '@/components/ui/states'

/**
 * Tier 5는 정기보고서다 — 실측 상장사 5,394건 중 2,846건(53%)이다.
 * 반기보고서 마감 시즌엔 보유 10종목이면 T5만 10건이고 T1~T4는 0~2건일 수 있어,
 * 평평한 목록이면 화면이 반기보고서 더미로 보인다. **버리지 않고 접는다** —
 * 백엔드가 is_material=true로 저장하고 정렬로만 뒤로 민 것과 같은 판단이다.
 */
const TIER_PERIODIC = 5

/**
 * **Tier에 분류명을 붙이지 않는다.** 백엔드 스펙 8절이 기록한 기지 오분류 때문이다 —
 * `매매거래정지`(T3)가 부정형까지 잡아 27건 중 `주권매매거래정지해제`(= 정지가 풀린 것)가
 * 12건이다. 백엔드에선 정렬 순위 문제였지만 화면에 "위험" 배지를 붙이는 순간 주장이 된다.
 * `report_nm` 원문이 이미 사실을 말하므로 분류명을 덧붙일 값이 없다.
 * 숫자와 색만 쓴다. S13에서 키워드를 고친 뒤 재검토할 것.
 */
const TIER_TONE: Record<number, string> = {
  1: 'text-danger',
  2: 'text-ink',
  3: 'text-warn',
  4: 'text-fg-2',
  5: 'text-fg-faint',
}

function shortDate(iso: string): string {
  // `2026-08-18` → `08-18`. 30일 창이라 연도가 바뀌는 경계는 드물고, 바뀌어도
  // 정렬이 최신순이라 순서로 읽힌다
  return iso.slice(5)
}

function Row({ item }: { item: DisclosureItem }) {
  return (
    <li className="border-b border-line-card/50 px-4 py-2.5 last:border-b-0">
      <div className="flex items-baseline gap-2">
        <span
          className={`shrink-0 font-mono text-[11px] ${TIER_TONE[item.materialTier ?? 5] ?? 'text-fg-faint'}`}
          aria-label={`분류 ${item.materialTier ?? '-'}`}
        >
          [{item.materialTier ?? '-'}]
        </span>
        <a
          href={item.sourceUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="min-w-0 flex-1 text-[13px] leading-snug text-fg-1 underline-offset-2 hover:underline"
        >
          {item.reportNm}
        </a>
        {item.supersededCount > 0 && (
          <span className="shrink-0 text-[11px] text-fg-faint">정정 {item.supersededCount}회</span>
        )}
        <span className="shrink-0 font-mono text-[11px] text-fg-faint">{shortDate(item.rceptDt)}</span>
      </div>
      <div className="mt-0.5 pl-6 text-[11.5px] text-fg-faint">
        {item.corpName}
        {item.stockCode && <span className="ml-1.5 font-mono">{item.stockCode}</span>}
        {/* 제출인은 회사 자신일 때 반복이라 안 보여준다. Tier 4에서만 임원 이름이 온다 */}
        {item.flrNm && item.flrNm !== item.corpName && <span className="ml-1.5">· {item.flrNm}</span>}
      </div>
    </li>
  )
}

export default function DisclosureFeedPanel({ items }: { items: DisclosureItem[] }) {
  const [periodicOpen, setPeriodicOpen] = useState(false)

  if (items.length === 0) {
    return <EmptyState title="최근 30일간 공시가 없습니다" description="보유 종목에 접수된 주요 공시가 없습니다." />
  }

  // 백엔드가 Tier 오름차순으로 이미 정렬해 보내므로 순서를 다시 잡지 않는다
  const main = items.filter((i) => i.materialTier !== TIER_PERIODIC)
  const periodic = items.filter((i) => i.materialTier === TIER_PERIODIC)

  return (
    <div>
      <ul className="m-0 list-none p-0">
        {main.map((i) => <Row key={i.rceptNo} item={i} />)}
      </ul>

      {periodic.length > 0 && (
        <div className="border-t border-line-card">
          <button
            type="button"
            onClick={() => setPeriodicOpen((v) => !v)}
            aria-expanded={periodicOpen}
            className="flex w-full items-center justify-between px-4 py-2.5 text-left"
          >
            <Label size="sm" tone="faint">정기보고서 {periodic.length}건</Label>
            <span className="text-[11px] text-fg-faint">{periodicOpen ? '▴' : '▾'}</span>
          </button>
          {periodicOpen && (
            <ul className="m-0 list-none border-t border-line-card/50 p-0">
              {periodic.map((i) => <Row key={i.rceptNo} item={i} />)}
            </ul>
          )}
        </div>
      )}
    </div>
  )
}
```

- [ ] **Step 2: 타입 검사**

```bash
cd frontend/allfolio_app && npx tsc --noEmit
```

Expected: 오류 없음

- [ ] **Step 3: 커밋**

```bash
git add frontend/allfolio_app/components/disclosure/DisclosureFeedPanel.tsx
git commit -m "feat(s12): 공시 피드 패널 — Tier 배지는 중립 표기, 정기보고서는 접는다"
```

---

## Task 5: 소유변동 패널

**Files:**
- Create: `frontend/allfolio_app/components/disclosure/InsiderTradePanel.tsx`

- [ ] **Step 1: 작성**

```tsx
// components/disclosure/InsiderTradePanel.tsx
'use client'

import type { InsiderTradeItem } from '@/types/disclosure'
import Label from '@/components/ui/Label'
import { EmptyState } from '@/components/ui/states'

/**
 * **"매수"·"매도"·"장내매수"를 쓰지 않는다.** `elestock`에 변동사유 필드가 없어
 * (30개사 3,922행 전건 확인) 무상증자·스톡옵션 행사·상속과 장내매수를 구분할 수 없다.
 * 무상증자를 매수로 오표기하는 것은 금융 서비스에서 회복 불가능한 신뢰 손상이다
 * (백엔드 설계 원칙 3). 부호와 수량만 낸다.
 *
 * "주목 종목"·"매수 신호" 류 큐레이션 표현도 금지다 — 유사투자자문 소지가 있다(9절).
 */
const QTY = new Intl.NumberFormat('ko-KR')

/** 지분율은 NUMERIC(7,2)라 소수 2자리다. JSON.parse가 뒤 0을 버리므로 다시 고정한다 */
function pct(v: number | null): string {
  return v === null ? '—' : `${v.toFixed(2)}%`
}

function signed(v: number | null): string {
  if (v === null) return '—'
  // 0은 "변동 없음"이고 null은 "값 없음"이다. 둘을 같게 그리지 않는다
  return v > 0 ? `+${QTY.format(v)}` : QTY.format(v)
}

function who(t: InsiderTradeItem): string {
  // 등기 여부는 3-값이다 — 결측(null)을 "비등기"로 접으면 실측 125건이 거짓이 된다
  const parts: string[] = []
  if (t.isRegistered !== null) parts.push(t.isRegistered ? '등기임원' : '비등기임원')
  if (t.officerPosition) parts.push(t.officerPosition)
  if (t.majorHolderType) parts.push(t.majorHolderType)
  return parts.join(' ')
}

export default function InsiderTradePanel({ trades }: { trades: InsiderTradeItem[] }) {
  if (trades.length === 0) {
    return <EmptyState title="최근 30일간 소유수량 변동이 없습니다" />
  }

  // 종목별로 묶는다 — 백엔드가 접수일 내림차순으로 주므로 그룹 안 순서는 그대로 둔다
  const byStock = new Map<string, InsiderTradeItem[]>()
  for (const t of trades) {
    const key = t.stockCode ?? '—'
    const list = byStock.get(key)
    if (list) list.push(t)
    else byStock.set(key, [t])
  }

  return (
    <div>
      {[...byStock.entries()].map(([stockCode, rows]) => (
        <div key={stockCode} className="border-b border-line-card last:border-b-0">
          <div className="px-4 pt-3">
            <Label size="sm" tone="faint">
              <span className="font-mono">{stockCode}</span>
            </Label>
          </div>
          <ul className="m-0 list-none p-0">
            {rows.map((t) => (
              <li key={t.rceptNo} className="px-4 py-2 text-[12px]">
                <div className="flex items-baseline justify-between gap-3">
                  <a
                    href={t.sourceUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="min-w-0 truncate text-fg-1 underline-offset-2 hover:underline"
                  >
                    {t.repror}
                    {who(t) && <span className="ml-1.5 text-[11px] text-fg-faint">{who(t)}</span>}
                  </a>
                  <span className="shrink-0 font-mono tabular-nums text-fg-faint">
                    {t.ownedQty === null ? '—' : QTY.format(t.ownedQty)}주
                    <span className="ml-2 text-fg-2">{signed(t.changeQty)}</span>
                    <span className="ml-2">{pct(t.ownedRate)}</span>
                  </span>
                </div>
              </li>
            ))}
          </ul>
        </div>
      ))}
    </div>
  )
}
```

- [ ] **Step 2: 타입 검사**

```bash
cd frontend/allfolio_app && npx tsc --noEmit
```

Expected: 오류 없음

- [ ] **Step 3: 커밋**

```bash
git add frontend/allfolio_app/components/disclosure/InsiderTradePanel.tsx
git commit -m "feat(s12): 소유변동 패널 — 매수·매도를 말하지 않는다"
```

---

## Task 6: 페이지와 네비게이션

**Files:**
- Create: `frontend/allfolio_app/app/unified/disclosures/page.tsx`
- Modify: `frontend/allfolio_app/components/NavBar.tsx`

- [ ] **Step 1: 페이지 작성**

```tsx
// app/unified/disclosures/page.tsx
'use client'

import Link from 'next/link'
import { useQuery } from '@tanstack/react-query'
import { useDisclosureApi } from '@/lib/useApi'
import PageHeader from '@/components/ui/PageHeader'
import Panel from '@/components/ui/Panel'
import SectionHeader from '@/components/ui/SectionHeader'
import Button from '@/components/ui/Button'
import { EmptyState, ErrorState, LoadingState } from '@/components/ui/states'
import DisclosureFeedPanel from '@/components/disclosure/DisclosureFeedPanel'
import InsiderTradePanel from '@/components/disclosure/InsiderTradePanel'

export default function DisclosuresPage() {
  const api = useDisclosureApi()

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['disclosures', 'feed'],
    queryFn: () => api!.feed(),
    enabled: !!api,
    retry: false,
  })

  return (
    <div className="mx-auto max-w-[1400px] px-4 py-6">
      {/* 조회 구간을 헤더에 명시한다 — 기간 선택 컨트롤을 만들지 않기로 했으므로
          무엇을 보고 있는지는 글로 밝혀야 한다(설계 5절) */}
      <PageHeader title="공시" meta="최근 30일" />

      {isLoading && <LoadingState />}

      {isError && (
        <ErrorState
          message={error instanceof Error ? error.message : '공시를 불러오지 못했습니다.'}
          onRetry={() => refetch()}
        />
      )}

      {data && data.heldCount === 0 && (
        /* **"보유가 없다"와 "공시가 없다"를 갈라야 한다.** 전자는 행동이 필요한 상태고
           후자는 정상 상태다. 같은 문구로 처리하면 계좌를 안 연결한 사용자가
           "공시가 없구나"로 오해한다 */
        <EmptyState
          title="보유 종목이 없습니다"
          description="계좌를 연결하면 보유 종목의 공시를 모아서 보여드립니다."
          action={
            <Link href="/unified/accounts">
              <Button variant="outline" size="sm">계좌 연결</Button>
            </Link>
          }
        />
      )}

      {data && data.heldCount > 0 && (
        <div className="mt-6 space-y-6">
          <Panel>
            <SectionHeader label="보유종목 공시" />
            <DisclosureFeedPanel items={data.items} />
          </Panel>

          <Panel>
            <SectionHeader
              label="임원·주요주주 소유수량 변동"
              note="취득·처분 사유는 공시 원문에서 확인하세요"
            />
            <InsiderTradePanel trades={data.insiderTrades} />
          </Panel>
        </div>
      )}
    </div>
  )
}
```

> `SectionHeader`의 `note`가 카피 제약을 지키는 장치다. "매수/매도"를 말하지 않는 대신 **왜 안 말하는지를 대체하는 안내**를 둔다 — 사용자가 사유를 알고 싶으면 원문으로 간다.

- [ ] **Step 2: 네비게이션 항목 추가**

`components/NavBar.tsx`의 `NAV_ITEMS`에 한 줄을 넣는다. **시장 앞이다** — 공시는 내 보유종목 기반이라 시장(내 보유와 무관한 정보)보다 앞이 맞다.

```typescript
const NAV_ITEMS = [
  { href: '/unified', label: '통합 자산', exact: true },
  { href: '/unified/accounts', label: '계좌' },
  { href: '/unified/reports', label: '보고서' },
  { href: '/unified/cashflow', label: '현금흐름' },
  { href: '/unified/recon', label: '대사·검증' },
  // 공시는 내 보유종목 기반이라 시장(보유와 무관한 정보)보다 앞이다
  { href: '/unified/disclosures', label: '공시' },
  // 맨 끝인 게 설계다 — 부차적 화면이라 대시보드보다 먼저 눈에 들어오면 안 된다
  { href: '/unified/market', label: '시장' },
]
```

- [ ] **Step 3: 타입 검사와 빌드**

```bash
cd frontend/allfolio_app && npx tsc --noEmit && npm run build
```

Expected: 둘 다 오류 없음

- [ ] **Step 4: 커밋**

```bash
git add frontend/allfolio_app/app/unified/disclosures/page.tsx \
        frontend/allfolio_app/components/NavBar.tsx
git commit -m "feat(s12): 공시 화면과 네비게이션 항목"
```

---

## Task 7: 브라우저 수동 검증

**Files:** 없음 (검증만)

FE에 테스트 러너가 없으므로(확인: 테스트 파일 0, 러너 의존성 0) **브라우저로 확인한다.** 러너 도입은 별건이다.

- [ ] **Step 1: 백엔드와 프런트 기동**

백엔드는 로컬 Postgres와 `DART_API_KEY`가 필요하다. 데이터가 없으면 Task 1의 마이그레이션이 적용된 로컬 DB에 직접 몇 행을 넣어 확인해도 된다.

```bash
docker start allfolio-postgres
```

프런트는 `.claude/launch.json`에 항목이 있으면 그것을 쓰고, 없으면 만든다. **`npm run dev`를 셸로 직접 띄우지 말 것** — 이 환경에서는 preview 도구를 쓴다.

- [ ] **Step 2: 확인 항목**

| 확인 | 기대 |
|---|---|
| 보유종목 없는 계정 | "보유 종목이 없습니다" + 계좌 연결 버튼 |
| 보유는 있고 공시 0 | "최근 30일간 공시가 없습니다" (계좌 연결 버튼 없음) |
| 정기보고서 접기·펼치기 | `정기보고서 N건 ▾` 클릭 시 목록이 나오고 `▴`로 바뀜 |
| 정정 표기 | `supersededCount > 0`인 행에 `정정 N회` |
| Tier 배지 | 숫자만 — **"위험"·"주가 직결" 같은 분류명이 없어야 한다** |
| 원문 링크 | 클릭 시 `dart.fss.or.kr` 문서로 새 탭 |
| ② 카피 | 화면 전체에 **"매수"·"매도"·"장내매수"·"주목"·"신호"가 없어야 한다** |
| 음수 증감 | `-500`이 그대로, `+1,800,000`은 부호 붙어서 |
| 지분율 | `46.07%`처럼 소수 2자리 고정 |
| 모바일 폭 | 375px에서 가로 스크롤이 안 생김 |
| 다크·라이트 | 기존 화면과 같은 기준 |

- [ ] **Step 3: 스크린샷으로 결과 공유**

확인한 화면을 스크린샷으로 남긴다. 특히 **Tier 배지에 분류명이 없는 것**과 **②에 매수·매도 표현이 없는 것**을 보여줄 것 — 이 둘이 이 화면의 핵심 제약이다.

- [ ] **Step 4: 발견한 문제가 있으면 고치고 커밋**

없으면 커밋할 것이 없다. 그대로 보고한다.

---

## 배포 후 확인

`GET /api/disclosures`가 실제 데이터를 돌려주려면 **D1 백엔드가 먼저 배포되고 수집이 한 번 돌아야 한다**(PR #182). 그전에는 `items`가 비어 정상적으로 "공시가 없습니다"가 뜬다 — 화면 버그가 아니다.

수집이 돈 뒤 확인할 것:

```sql
SELECT material_tier, count(*) FROM dart_disclosure
WHERE stock_code IS NOT NULL AND rcept_dt >= current_date - 30
GROUP BY material_tier ORDER BY material_tier;
```

Tier 5가 절반 이상이면 접기가 제 값을 한다. Tier 1이 0건이면 백엔드 정규화를 의심한다.

---

## 범위 밖

- **기간 선택 컨트롤** — 30일 고정(설계 5절). 필요가 확인되면 v2
- **종목별 필터** — 실사용 계정이 6종목이다. 수십 개가 되면 필요
- **읽음 표시** — 상태를 저장할 테이블이 없다. 별건
- **FE 테스트 러너 도입** — 별건. 이 태스크에서 결정하지 않는다
- **S13 화이트리스트 튜닝** — 별건. 그전까지 Tier 배지를 중립 표기로 두는 것이 이 계획의 대응이다
