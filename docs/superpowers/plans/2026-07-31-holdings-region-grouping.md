# R-05 지역 그룹핑 (통화 기반 지역 노출) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 보유명세 보고서(R-05)에 보유 자산의 통화 파생 지역 노출(byRegion) 집계를 추가한다.

**Architecture:** 순수 `CurrencyRegionMapper`(통화→지역 라벨) + `HoldingsReportGenerator`가 기존 `valued`/`totalKrw`/`pct`로 지역별 집계 후 body `byRegion` 직렬화. FE는 옵셔널 필드로 표 렌더.

**Tech Stack:** Kotlin/Spring(unified-asset), JUnit, Next.js/React/TypeScript.

Spec: `docs/superpowers/specs/2026-07-31-holdings-region-grouping-design.md`

---

## Task 1: CurrencyRegionMapper (순수 매퍼 + 테스트)

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/CurrencyRegionMapper.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/CurrencyRegionMapperTest.kt`

- [ ] **Step 1: 실패 테스트 작성** — `CurrencyRegionMapperTest.kt`:

```kotlin
package com.allfolio.unifiedasset.application.usecase

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CurrencyRegionMapperTest {
    @Test
    fun `통화코드는 지역으로 매핑된다`() {
        assertThat(CurrencyRegionMapper.regionOf("KRW")).isEqualTo("국내")
        assertThat(CurrencyRegionMapper.regionOf("USD")).isEqualTo("미국")
        assertThat(CurrencyRegionMapper.regionOf("JPY")).isEqualTo("일본")
        assertThat(CurrencyRegionMapper.regionOf("EUR")).isEqualTo("유럽")
    }

    @Test
    fun `소문자·공백은 정규화된다`() {
        assertThat(CurrencyRegionMapper.regionOf("usd")).isEqualTo("미국")
        assertThat(CurrencyRegionMapper.regionOf(" krw ")).isEqualTo("국내")
    }

    @Test
    fun `미등록 통화와 null-공백은 기타`() {
        assertThat(CurrencyRegionMapper.regionOf("AUD")).isEqualTo("기타")
        assertThat(CurrencyRegionMapper.regionOf(null)).isEqualTo("기타")
        assertThat(CurrencyRegionMapper.regionOf("")).isEqualTo("기타")
        assertThat(CurrencyRegionMapper.regionOf("   ")).isEqualTo("기타")
    }
}
```

- [ ] **Step 2: 테스트 실패 확인** — Run: `cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :unified-asset:test --tests "*CurrencyRegionMapperTest*"` → Expected: compile FAIL (CurrencyRegionMapper 미정의).

- [ ] **Step 3: 최소 구현** — `CurrencyRegionMapper.kt`:

```kotlin
package com.allfolio.unifiedasset.application.usecase

/** 통화 코드 → 지역 라벨(근사). 자산에 국가/거래소 필드가 없어 통화 기준 추정. */
object CurrencyRegionMapper {
    private val MAP = mapOf(
        "KRW" to "국내", "USD" to "미국", "JPY" to "일본", "EUR" to "유럽",
        "CNY" to "중국", "HKD" to "홍콩", "GBP" to "영국",
    )

    fun regionOf(currency: String?): String =
        currency?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let { MAP[it] ?: "기타" } ?: "기타"
}
```

- [ ] **Step 4: 테스트 통과 확인** — Run: `cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :unified-asset:test --tests "*CurrencyRegionMapperTest*"` → Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/CurrencyRegionMapper.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/CurrencyRegionMapperTest.kt
git commit -m "feat(holdings): add CurrencyRegionMapper (TDD)"
```

---

## Task 2: HoldingsReportGenerator 통합 + 테스트

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/HoldingsReportGenerator.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/HoldingsReportGeneratorTest.kt`

- [ ] **Step 1: 실패 테스트 확장** — 기존 `HoldingsReportGeneratorTest`에 케이스 추가. 먼저 파일을 읽어 자산 생성 픽스처(Asset.create 또는 헬퍼)와 generator 셋업·`mapper`·`fx` 사용법을 파악할 것. KRW 자산과 USD 자산을 각각 1건 이상 만들어 generate 후 body의 `byRegion` 검증:

```kotlin
    @Test
    fun `byRegion은 통화 파생 지역으로 평가액을 집계한다`() {
        // 기존 테스트와 동일 패턴으로 KRW 자산 1건, USD 자산 1건을 포함해 generate 호출
        // (기존 테스트의 자산 생성 헬퍼/픽스처를 재사용할 것)
        val report = generator.generate(userId, period)
        val body = mapper.readTree(report.bodyJson)
        val regions = body.get("byRegion")
        assertThat(regions).isNotNull
        val labels = regions.map { it.get("region").asText() }
        assertThat(labels).contains("국내", "미국")
        // 각 그룹은 valueKrw·weight·holdingCount 필드를 갖는다
        val first = regions.first()
        assertThat(first.has("valueKrw")).isTrue
        assertThat(first.has("weight")).isTrue
        assertThat(first.has("holdingCount")).isTrue
    }
```
주의: 기존 테스트가 assertj를 쓰는지 JUnit5 assertions를 쓰는지 파일을 확인해 **그 파일의 기존 스타일에 맞출 것**(혼용 금지). JsonNode 배열 순회는 `for (n in regions)` 또는 `regions.map { ... }` 사용.

- [ ] **Step 2: 테스트 실패 확인** — Run: `cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :unified-asset:test --tests "*HoldingsReportGeneratorTest*"` → Expected: FAIL (byRegion 노드 없음).

- [ ] **Step 3: 생성기 통합** — `HoldingsReportGenerator.generate`의 `byType` 산출 직후, `body` 맵 생성 전에 추가:

```kotlin
        val byRegion = valued.groupBy { CurrencyRegionMapper.regionOf(it.first.currency) }
            .map { (region, g) ->
                val sum = g.fold(BigDecimal.ZERO) { acc, (_, v) -> acc + v }
                mapOf("region" to region, "valueKrw" to sum, "weight" to pct(sum, totalKrw), "holdingCount" to g.size)
            }.sortedByDescending { it["valueKrw"] as BigDecimal }
```
그리고 `body = mapOf(...)`에 `"byType" to byType,` 다음 줄에 추가:
```kotlin
            "byRegion" to byRegion,
```
KDoc의 "v1 제외: 월간 변동 diff, 지역별 그룹핑." → "v1 제외 해소: 지역 노출(통화 파생) 포함. 후속: 국가/거래소 필드 기반 정밀 분류." (월간 변동은 이미 #56에서 포함되었으므로 문구에서 함께 정리).

- [ ] **Step 4: 테스트 통과 확인** — Run: `cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :unified-asset:test --tests "*HoldingsReportGeneratorTest*" --tests "*CurrencyRegionMapperTest*"` → Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/HoldingsReportGenerator.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/HoldingsReportGeneratorTest.kt
git commit -m "feat(holdings): add currency-derived region exposure to body"
```

---

## Task 3: 백엔드 회귀

- [ ] **Step 1: unified-asset 전체 테스트** — Run: `cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :unified-asset:test` → Expected: BUILD SUCCESSFUL.
- [ ] **Step 2: (실패 시) 수정 후 재실행.**

---

## Task 4: Frontend — 타입 + 컴포넌트 + 페이지

**Files:**
- Modify: `frontend/allfolio_app/types/holdings-report.ts`
- Create: `frontend/allfolio_app/components/holdings-report/RegionExposure.tsx`
- Modify: `frontend/allfolio_app/app/unified/reports/holdings-report/[id]/page.tsx`

- [ ] **Step 1: 타입 추가** — `types/holdings-report.ts`를 읽고 추가:

```ts
export interface HoldingsByRegion {
  region: string
  valueKrw: number
  weight: number        // 0~100 스케일
  holdingCount: number
}
```
그리고 Holdings 리포트 BODY 인터페이스(summary/holdings/byAccount/byType/cash 필드를 가진 것)에 `byRegion?: HoldingsByRegion[]` (옵셔널) 추가.

- [ ] **Step 2: 컴포넌트 생성** — `components/holdings-report/RegionExposure.tsx`. 같은 폴더의 byType/byAccount 표 컴포넌트를 읽고 동일 룩앤필로 작성. 참고 골격:

```tsx
import { fmtKrw } from '@/lib/report-format'
import type { HoldingsByRegion } from '@/types/holdings-report'

export function RegionExposure({ rows }: { rows: HoldingsByRegion[] }) {
  if (!rows || rows.length === 0) return null
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">지역 노출</h2>
      <p className="text-xs text-gray-500">보유 통화 기준 추정 지역입니다(자산 국가·거래소 데이터 부재).</p>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">지역</th>
              <th className="p-3 text-right">평가액</th>
              <th className="p-3 text-right">비중</th>
              <th className="p-3 text-right">종목수</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={`${r.region}-${i}`} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3 text-gray-200">{r.region}</td>
                <td className="p-3 text-right tabular-nums text-gray-100">{fmtKrw(r.valueKrw)}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{r.weight.toFixed(2)}%</td>
                <td className="p-3 text-right tabular-nums text-gray-400">{r.holdingCount}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}
```
(단, `fmtKrw` import 경로·스타일 클래스는 같은 폴더 기존 컴포넌트와 실제로 일치시킬 것.)

- [ ] **Step 3: 페이지 렌더** — `app/unified/reports/holdings-report/[id]/page.tsx`를 읽고 import 추가 후 byType 섹션 근처에:

```tsx
{body.byRegion && body.byRegion.length > 0 && <RegionExposure rows={body.byRegion} />}
```
(페이지의 실제 파싱된 body 변수명 확인 후 맞춤.)

- [ ] **Step 4: 타입체크** — Run: `cd /Users/hong9/IdeaProjects/allfolio/frontend/allfolio_app && npx tsc --noEmit` → Expected: no errors. (tsconfig에 downlevelIteration 없음 → Map/Set 스프레드 금지, 필요 시 Array.from.)

- [ ] **Step 5: 커밋**

```bash
git add frontend/allfolio_app/types/holdings-report.ts \
        frontend/allfolio_app/components/holdings-report/RegionExposure.tsx \
        "frontend/allfolio_app/app/unified/reports/holdings-report/[id]/page.tsx"
git commit -m "feat(holdings-fe): render currency-derived region exposure"
```

---

## Self-Review 체크
- [ ] Spec 항목(통화→지역 매핑·null/미등록 기타·byRegion 집계·정렬·FE 추정 문구) 모두 커버.
- [ ] `regionOf` 시그니처·`byRegion` 필드명 Task1↔Task2↔FE 일치.
- [ ] FE `byRegion?` 옵셔널 → 구 아카이브 호환.
- [ ] 근사(통화 기준) 한계 명시(리포트 KDoc·FE 문구).

## Rollout
- 스키마 변경 없음 → 마이그레이션 불필요. main 병합 시 배포.
