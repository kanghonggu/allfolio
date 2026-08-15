# AF-106 수익 기여도 분해 (자산 vs 환율) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 수익률 보고서에 기간 수익을 자산 기여와 환율 기여로 쪼갠 블록을 붙인다. 분해 합이 화면의 TWR과 정확히 일치한다.

**Architecture:** 통화별 평가액·적용 환율을 `nav_currency_daily`에 **오늘부터** 쌓는다(소급 복원은 자산별 과거 시세가 없어 불가능). 계산은 `ReturnsCalculator`에 함수를 더해 TWR과 **같은 구간 분할**을 공유한다. 응답은 기존 `GET /api/reports/returns`에 nullable 필드 하나로 얹는다.

**Tech Stack:** Kotlin / Spring Boot / JPA + JdbcTemplate / JUnit 5 / Next.js App Router + React Query

**설계 문서:** `docs/superpowers/specs/2026-08-15-fx-return-attribution-design.md`

---

## 사전 필독 — 이걸 모르면 조용히 틀린다

**1. `CurrencyConverter`가 환산하는 통화는 다섯뿐이다.** `KRW·USD·USDT·BTC·ETH`. 나머지(`JPY`, `EUR` 등)는 `sourceOf()`가 `null`을 주고 `toKrw()`는 **경고 로그만 남기고 원금을 그대로 돌려준다.** 미지원 통화에서 예외를 던지면 스냅샷 생성이 깨진다. `sourceOf(c)?.rate ?: BigDecimal.ONE`이 `toKrw`의 실제 동작과 정확히 같다.

**2. `toKrw`는 가격을 원 단위로 반올림한다** (`setScale(0, HALF_UP)`). 그래서 `Σ value_native × fx_rate`는 `performance_daily.nav`와 **정확히 같지 않다.** Task 3의 계산식이 이 드리프트를 우회하도록 설계되어 있으니 식을 "단순화"하지 말 것.

**3. `ReturnsCalculator.twr()`의 분모는 `NAV_{i−1} + 입금`이고, 분모 ≤ 0인 구간은 통째로 건너뛴다.** 자산 다리가 같은 구간을 안 건너뛰면 항등식이 즉시 깨지고, 증상은 "분해 합이 TWR과 미묘하게 다름"이라 눈으로 못 잡는다. Task 3이 구간 분할을 한 함수로 뽑아 공유하게 만드는 이유가 이것이다.

**4. 도메인은 ratio(0~1), API 응답은 percent(0~100).** 변환은 `ReportController` 한 곳뿐이다. 도메인에서 100을 곱하지 말 것.

**5. FE 타입은 `number`다.** AF-104에서 FE가 `string`으로 선언했는데 BE가 JSON 숫자를 보내 스케일이 날아간 사고가 있었다. `types/returns.ts`는 이미 `number`를 쓰고 있으니 그 관행을 따른다.

---

## 파일 구조

| 파일 | 책임 | 태스크 |
|---|---|---|
| `docs/superpowers/migrations/2026-08-15-nav-currency-daily.sql` | 테이블 생성 | 1 |
| `report/.../returns/ReturnsCalculator.kt` | 구간 분할 공유 + `attribute()` | 2, 3 |
| `backend-app/.../snapshot/NavCurrencyDailyStore.kt` | 통화별 행 쓰기 (JdbcTemplate) | 4 |
| `backend-app/.../service/SnapshotTriggerService.kt` | 통화별 집계 → Store 호출 | 4 |
| `unified-asset/.../adapter/JdbcNavFxHistorySource.kt` | 두 테이블 조인 → `NavFxPoint` | 5 |
| `unified-asset/.../usecase/GetReturnsAnalysisUseCase.kt` | 노출 조건 + 응답 조립 | 6 |
| `unified-asset/.../api/ReportController.kt` | ratio → percent | 6 |
| `frontend/.../types/returns.ts` | 응답 타입 | 7 |
| `frontend/.../app/unified/reports/returns/page.tsx` | 분해 섹션 | 7 |

---

## Task 1: 마이그레이션 SQL + 프로덕션 리듬 실측

**Files:**
- Create: `docs/superpowers/migrations/2026-08-15-nav-currency-daily.sql`

- [ ] **Step 1: 마이그레이션 파일 작성**

```sql
-- AF-106 통화별 일간 평가액 — 수익 기여도 분해(자산 vs 환율)의 원자료
--
-- 왜 이 테이블이 필요한가: performance_daily.nav는 원화 총액 하나뿐이고,
-- 시세가 스냅샷에 들어가기 전에 KRW로 환산되어(SnapshotTriggerService)
-- 저장된 과거 NAV에는 통화 흔적이 남지 않는다. 소급 복원은 자산별 과거
-- 시세 테이블이 없어 불가능하므로, 오늘부터 관측을 쌓는다.
--
-- value_krw를 저장하지 않는다: value_native * fx_rate로 나오고,
-- 저장하면 셋이 어긋나는 날 무엇이 맞는지 가릴 수 없다.
--
-- 불변식: SUM(value_native * fx_rate) ≈ performance_daily.nav (같은 portfolio_id, date)
--   정확히 같지는 않다 — toKrw가 자산별 가격을 원 단위로 반올림한 뒤 수량을 곱한다.
--
-- 별도 인덱스를 만들지 않는다: PK 선두 두 열 (portfolio_id, date)가 기간 조회를 그대로 받는다.

CREATE TABLE IF NOT EXISTS nav_currency_daily (
    portfolio_id  UUID          NOT NULL,
    date          DATE          NOT NULL,
    currency      VARCHAR(10)   NOT NULL,
    value_native  NUMERIC(30,10) NOT NULL,
    fx_rate       NUMERIC(30,10) NOT NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT NOW(),
    PRIMARY KEY (portfolio_id, date, currency)
);

COMMENT ON TABLE  nav_currency_daily            IS 'AF-106 통화별 일간 평가액 — 자산/환율 기여도 분해용';
COMMENT ON COLUMN nav_currency_daily.value_native IS '그날의 외화 기준 평가액 (환산 전)';
COMMENT ON COLUMN nav_currency_daily.fx_rate      IS '그날 적용한 1단위당 KRW. KRW와 미지원 통화는 1';
```

- [ ] **Step 2: 커밋**

```bash
git add docs/superpowers/migrations/2026-08-15-nav-currency-daily.sql
git commit -m "feat(af-106): nav_currency_daily 마이그레이션 — 통화별 평가액을 오늘부터 쌓는다"
```

- [ ] **Step 3: 프로덕션 리듬 실측 — 사용자에게 아래 쿼리 실행을 요청한다**

설계 §10의 열린 질문이다. **이 결과로 설계가 바뀌지는 않지만 화면 기대치가 달라진다.** 코드 작업을 막지 말고, 결과가 올 때까지 Task 2로 진행한다.

Neon 콘솔에서 실행할 SQL을 사용자에게 제시한다:

```sql
-- 최근 30일간 사용자별 performance_daily 행 수 — 매일 쌓이는가, 거래일에만 쌓이는가
SELECT portfolio_id,
       COUNT(*)                                   AS rows_30d,
       MIN(date)                                  AS first_date,
       MAX(date)                                  AS last_date,
       MAX(date) - MIN(date) + 1                  AS calendar_days
FROM performance_daily
WHERE date >= CURRENT_DATE - 30
GROUP BY portfolio_id
ORDER BY rows_30d DESC
LIMIT 20;
```

`rows_30d`가 `calendar_days`에 가까우면 매일 쌓이는 것이고, 훨씬 작으면 거래일에만 쌓이는 것이다. 결과를 계획 문서에 기록한다.

---

## Task 2: 구간 분할을 한 함수로 뽑는다 (리팩터링, 동작 변화 없음)

`twr()`과 앞으로 만들 `attribute()`가 같은 구간을 보게 만드는 준비 작업이다. **기존 테스트가 이 리팩터링의 안전망이다.**

**Files:**
- Modify: `allfolio-backend/report/src/main/kotlin/com/allfolio/report/domain/returns/ReturnsCalculator.kt:84-101`
- Test: `allfolio-backend/report/src/test/kotlin/com/allfolio/report/domain/returns/ReturnsCalculatorTest.kt` (기존, 수정 없음)

- [ ] **Step 1: 기존 테스트가 통과하는지 먼저 확인 (기준선)**

```bash
cd allfolio-backend && ./gradlew :report:test --tests '*ReturnsCalculatorTest*'
```

Expected: PASS. 실패하면 리팩터링을 시작하지 말고 보고한다.

- [ ] **Step 2: `Segment` 타입과 `segments()` 함수를 추가하고 `twr()`이 쓰게 바꾼다**

`ReturnsCalculator.kt`의 `twr()` 함수(84~101행)를 아래로 **교체**한다:

```kotlin
    /**
     * 구간 하나 — 관측일 i−1 → i.
     *
     * @param i          현재 관측의 인덱스 (직전은 i−1)
     * @param net        구간 순플로우 (입금 양수, 출금 음수)
     * @param denominator NAV_{i−1} + 입금. **항상 > 0** — 0 이하 구간은 [segments]가 이미 걸렀다
     */
    private data class Segment(val i: Int, val net: BigDecimal, val denominator: BigDecimal)

    /**
     * 구간 분할·분모 계산·건너뜀 규약을 여기 한 곳에만 둔다.
     *
     * **[twr]과 [attribute]가 반드시 이 함수를 같이 써야 한다.** 두 계열이 서로 다른 구간
     * 집합을 돌면 `(1+자산기여)(1+환율기여) = 1+TWR` 항등식이 깨지는데, 증상이 "분해 합이
     * TWR과 미묘하게 다름"이라 눈으로 못 잡는다. 규약을 복제하면 어느 날 한쪽만 고쳐진다.
     *
     * 분모 ≤ 0인 구간(전액 출금 후 재개 등)은 수익률 판단이 불가능하므로 통째로 뺀다.
     */
    private fun segments(
        dates: List<LocalDate>,
        navs: List<BigDecimal>,
        flows: List<Flow>,
    ): List<Segment> {
        val out = mutableListOf<Segment>()
        for (i in 1 until dates.size) {
            val window = flows.filter { it.date > dates[i - 1] && it.date <= dates[i] }
            val net = window.fold(BigDecimal.ZERO) { acc, f -> acc + f.amountKrw }
            val inflow = window.filter { it.amountKrw > BigDecimal.ZERO }
                .fold(BigDecimal.ZERO) { acc, f -> acc + f.amountKrw }
            val denominator = navs[i - 1] + inflow
            if (denominator <= BigDecimal.ZERO) continue
            out += Segment(i, net, denominator)
        }
        return out
    }

    /** 구간별 r_i = (NAV_i − NAV_{i−1} − 순플로우_i) / (NAV_{i−1} + 입금_i) 체인링킹 */
    private fun twr(series: List<NavPoint>, flows: List<Flow>): BigDecimal {
        val navs = series.map { it.nav }
        var product = BigDecimal.ONE
        for (s in segments(series.map { it.date }, navs, flows)) {
            val r = (navs[s.i] - navs[s.i - 1] - s.net).divide(s.denominator, MC)
            product = product.multiply(BigDecimal.ONE + r, MC)
        }
        return product - BigDecimal.ONE
    }
```

- [ ] **Step 3: 기존 테스트가 여전히 통과하는지 확인**

```bash
cd allfolio-backend && ./gradlew :report:test --tests '*ReturnsCalculatorTest*'
```

Expected: PASS, 실패 0건. **동작이 변하면 안 되는 리팩터링이다** — 하나라도 깨지면 되돌리고 보고한다.

- [ ] **Step 4: 커밋**

```bash
git add allfolio-backend/report/src/main/kotlin/com/allfolio/report/domain/returns/ReturnsCalculator.kt
git commit -m "refactor(af-106): TWR 구간 분할을 segments()로 뽑는다 — 자산 다리와 공유할 자리"
```

---

## Task 3: `attribute()` — 분해 계산 (TDD)

**Files:**
- Modify: `allfolio-backend/report/src/main/kotlin/com/allfolio/report/domain/returns/ReturnsCalculator.kt`
- Create: `allfolio-backend/report/src/test/kotlin/com/allfolio/report/domain/returns/AttributionTest.kt`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`allfolio-backend/report/src/test/kotlin/com/allfolio/report/domain/returns/AttributionTest.kt`:

```kotlin
package com.allfolio.report.domain.returns

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class AttributionTest {

    private fun bd(v: String) = BigDecimal(v)
    private fun d(day: Int) = LocalDate.of(2026, 6, day)

    private fun assertClose(expected: String, actual: BigDecimal?, eps: String = "0.0001") {
        requireNotNull(actual) { "expected $expected but was null" }
        assertTrue((actual - bd(expected)).abs() < bd(eps)) { "expected $expected but was $actual" }
    }

    /** 계약 2 — 분해 합이 TWR과 일치한다. 이 설계 전체가 이 등식 하나를 위해 존재한다. */
    private fun assertMatchesTwr(series: List<NavFxPoint>, flows: List<Flow>) {
        // requireNotNull을 쓴다 — assertNotNull은 스마트캐스트를 못 걸어서
        // 뒤에서 !!를 계속 붙여야 하고, 그러다 한 곳을 빠뜨리면 컴파일이 깨진다
        val attribution = requireNotNull(
            ReturnsCalculator.attribute(series, flows, series.first().date, series.last().date)
        ) { "attribution was null" }
        val twr = requireNotNull(
            ReturnsCalculator.calculate(
                series.map { NavPoint(it.date, it.nav) }, flows, series.first().date, series.last().date,
            ).twr
        )
        val combined = (BigDecimal.ONE + attribution.assetContribution)
            .multiply(BigDecimal.ONE + attribution.fxContribution) - BigDecimal.ONE
        assertTrue((combined - twr).abs() < bd("0.000000000001")) {
            "(1+asset)(1+fx)-1 = $combined 인데 TWR = $twr"
        }
    }

    @Test
    fun `환율만 오른 구간은 자산 기여가 정확히 0이다`() {
        // 보유가 전혀 안 변하고 환율만 10% 오른 하루.
        // navAtPriorFx = 전일 환율로 평가한 당일 = 1000 (= 전일 NAV)
        val series = listOf(
            NavFxPoint(d(1), nav = bd("1000"), navAtPriorFx = null),
            NavFxPoint(d(2), nav = bd("1100"), navAtPriorFx = bd("1000")),
        )
        val result = requireNotNull(ReturnsCalculator.attribute(series, emptyList(), d(1), d(2)))
        assertClose("0", result.assetContribution)
        assertClose("0.1", result.fxContribution)
    }

    @Test
    fun `환율이 안 변하면 환율 기여가 정확히 0이다`() {
        // navAtPriorFx == nav 이면 환율이 안 움직였다는 뜻
        val series = listOf(
            NavFxPoint(d(1), nav = bd("1000"), navAtPriorFx = null),
            NavFxPoint(d(2), nav = bd("1200"), navAtPriorFx = bd("1200")),
        )
        val result = requireNotNull(ReturnsCalculator.attribute(series, emptyList(), d(1), d(2)))
        assertClose("0.2", result.assetContribution)
        assertClose("0", result.fxContribution)
    }

    @Test
    fun `자산과 환율이 같이 움직이면 곱으로 분해된다`() {
        // 자산 +20%, 환율 +10% → 원화 +32%
        // navAtPriorFx = 1200 (자산만), nav = 1320 (자산 × 환율)
        val series = listOf(
            NavFxPoint(d(1), nav = bd("1000"), navAtPriorFx = null),
            NavFxPoint(d(2), nav = bd("1320"), navAtPriorFx = bd("1200")),
        )
        val result = requireNotNull(ReturnsCalculator.attribute(series, emptyList(), d(1), d(2)))
        assertClose("0.2", result.assetContribution)
        assertClose("0.1", result.fxContribution)
        assertMatchesTwr(series, emptyList())
    }

    @Test
    fun `입출금이 있어도 분해 합이 TWR과 일치한다`() {
        // 이 테스트가 없으면 계약 2는 아무것도 증명하지 못한다 —
        // 입출금이 없으면 어떤 엉성한 분해도 우연히 맞을 수 있다.
        val series = listOf(
            NavFxPoint(d(1), nav = bd("1000"), navAtPriorFx = null),
            NavFxPoint(d(10), nav = bd("2100"), navAtPriorFx = bd("2000")),  // 6/5 입금 1000
            NavFxPoint(d(20), nav = bd("2500"), navAtPriorFx = bd("2400")),
            NavFxPoint(d(30), nav = bd("1600"), navAtPriorFx = bd("1650")),  // 6/25 출금 500
        )
        val flows = listOf(Flow(d(5), bd("1000")), Flow(d(25), bd("-500")))
        assertMatchesTwr(series, flows)
    }

    @Test
    fun `분모가 0 이하인 구간을 twr과 똑같이 건너뛴다`() {
        // 전액 출금(6/10) → 빈 채로 유지(6/20) → 재입금(6/25) → 성장(6/30).
        //
        // 6/20 구간의 분모는 `전일 NAV(0) + 입금(0) = 0`이라 twr()이 통째로 건너뛴다.
        // attribute()가 같이 안 건너뛰면 0으로 나누게 되고, 건너뛰는 구간이 갈라지면
        // 항등식이 깨진다.
        //
        // 출금을 관측일과 같은 날에 둔 것이 중요하다 — 출금 없이 NAV만 0이 되면
        // r = −1이 되어 자산 다리가 −100%에 붙고, 그건 다른 규칙(null 반환)에 걸린다.
        val series = listOf(
            NavFxPoint(d(1), nav = bd("1000"), navAtPriorFx = null),
            NavFxPoint(d(10), nav = bd("0"), navAtPriorFx = bd("0")),
            NavFxPoint(d(20), nav = bd("0"), navAtPriorFx = bd("0")),
            NavFxPoint(d(30), nav = bd("600"), navAtPriorFx = bd("590")),
        )
        val flows = listOf(Flow(d(10), bd("-1000")), Flow(d(25), bd("500")))
        assertMatchesTwr(series, flows)

        // 건너뛴 구간이 실제로 있었는지 확인 — 없으면 이 테스트가 아무것도 안 지킨다
        val result = requireNotNull(ReturnsCalculator.attribute(series, flows, d(1), d(30)))
        assertClose("0.18", result.assetContribution)
    }

    @Test
    fun `관측이 2건 미만이면 null`() {
        val series = listOf(NavFxPoint(d(1), bd("1000"), null))
        assertNull(ReturnsCalculator.attribute(series, emptyList(), d(1), d(30)))
    }

    @Test
    fun `navAtPriorFx가 없는 구간이 있으면 null`() {
        // 통화별 행이 그날 안 써진 경우 — 억지로 이으면 환율 차이가 0으로 잡혀
        // 자산 쪽에 조용히 흡수된다
        val series = listOf(
            NavFxPoint(d(1), bd("1000"), null),
            NavFxPoint(d(2), bd("1100"), null),
        )
        assertNull(ReturnsCalculator.attribute(series, emptyList(), d(1), d(2)))
    }

    @Test
    fun `자산 다리가 마이너스 100퍼센트에 근접하면 null이다`() {
        // r_fx가 발산한다 — 억지 숫자를 내는 대신 분해를 포기한다
        val series = listOf(
            NavFxPoint(d(1), nav = bd("1000"), navAtPriorFx = null),
            NavFxPoint(d(2), nav = bd("1"), navAtPriorFx = bd("0")),
        )
        assertNull(ReturnsCalculator.attribute(series, emptyList(), d(1), d(2)))
    }

    @Test
    fun `기간 밖 관측은 제외한다`() {
        val series = listOf(
            NavFxPoint(d(1), bd("9999"), null),
            NavFxPoint(d(10), nav = bd("1000"), navAtPriorFx = bd("1000")),
            NavFxPoint(d(20), nav = bd("1320"), navAtPriorFx = bd("1200")),
        )
        val result = requireNotNull(ReturnsCalculator.attribute(series, emptyList(), d(10), d(20)))
        assertClose("0.2", result.assetContribution)
        assertClose("0.1", result.fxContribution)
    }

    @Test
    fun `정렬되지 않은 입력도 날짜 순으로 처리한다`() {
        val series = listOf(
            NavFxPoint(d(20), nav = bd("1320"), navAtPriorFx = bd("1200")),
            NavFxPoint(d(10), nav = bd("1000"), navAtPriorFx = null),
        )
        val result = requireNotNull(ReturnsCalculator.attribute(series, emptyList(), d(10), d(20)))
        assertClose("0.2", result.assetContribution)
    }

    @Test
    fun `attribute의 원화 다리는 twr과 같은 값을 낸다`() {
        // 구간 규약이 갈라지는 순간 잡힌다
        val series = listOf(
            NavFxPoint(d(1), nav = bd("1000"), navAtPriorFx = null),
            NavFxPoint(d(15), nav = bd("2000"), navAtPriorFx = bd("1950")),
            NavFxPoint(d(30), nav = bd("2200"), navAtPriorFx = bd("2210")),
        )
        val flows = listOf(Flow(d(15), bd("1000")))
        assertMatchesTwr(series, flows)
        val twr = ReturnsCalculator.calculate(
            series.map { NavPoint(it.date, it.nav) }, flows, d(1), d(30),
        ).twr
        assertEquals(0, bd("0.1").compareTo(twr!!.setScale(1, java.math.RoundingMode.HALF_UP)))
    }
}
```

- [ ] **Step 2: 테스트가 컴파일 실패하는지 확인**

```bash
cd allfolio-backend && ./gradlew :report:test --tests '*AttributionTest*'
```

Expected: 컴파일 실패 — `NavFxPoint`, `Attribution`, `attribute` 미정의.

- [ ] **Step 3: 구현한다**

`ReturnsCalculator.kt` 상단, `data class Flow` 아래에 타입을 추가한다:

```kotlin
/**
 * 통화 분해용 관측 한 건.
 *
 * 두 계열을 별도 리스트로 받으면 어긋날 수 있어 한 타입에 묶는다.
 *
 * @param nav          그날의 원화 평가액 — `performance_daily.nav` 그대로. 재계산하지 말 것
 * @param navAtPriorFx 그날 보유를 **전일 환율**로 평가한 값. 첫 관측일은 null(직전 구간이 없다).
 *                     실제 산출식은 `nav + Σ_c v_c·(r_c(전일) − r_c(당일))` — 권위 있는 nav에
 *                     환율 차이만 얹는다. `Σ v_c·r_c(전일)`로 직접 구하면 toKrw의 원 단위
 *                     반올림 때문에 환율이 안 움직인 날에도 환율 기여가 0이 아니게 된다.
 */
data class NavFxPoint(val date: LocalDate, val nav: BigDecimal, val navAtPriorFx: BigDecimal?)

/** 기간 수익의 분해 — ratio(0~1). `(1+asset)(1+fx)−1 == TWR` */
data class Attribution(val assetContribution: BigDecimal, val fxContribution: BigDecimal)
```

`ReturnsCalculator` 객체 안, `periodTwrPercent()` 아래에 함수를 추가한다:

```kotlin
    /** 자산 다리가 −100%에 붙으면 환율 다리가 발산한다 */
    private val ATTRIBUTION_EPSILON = BigDecimal("1E-9")

    /**
     * 기간 수익률을 자산 기여와 환율 기여로 쪼갠다 (AF-106).
     *
     * 구간마다 환율을 전일로 얼린 평행 수익률을 만들고, 환율 다리는 나머지가 아니라
     * `(1+r)/(1+r_asset) − 1`로 **명시**한다. 나머지로 두면 교차항이 자산 쪽에 조용히
     * 흡수된다. 이 정의 덕분에 구간마다 `(1+r) = (1+r_asset)(1+r_fx)`가 정의상 성립하고,
     * 곱을 재배열하면 `(1+자산기여)(1+환율기여) = 1 + TWR`이 된다.
     *
     * [twr]과 **같은 [segments] 호출**을 쓴다 — 구간 집합이 갈라지면 위 항등식이 깨진다.
     *
     * @return 분해 불가면 null — 관측 2건 미만 / 유효 구간 없음 / [navAtPriorFx] 결측 /
     *         자산 다리가 −100%에 근접
     */
    fun attribute(
        series: List<NavFxPoint>,
        flows: List<Flow>,
        from: LocalDate,
        to: LocalDate,
    ): Attribution? {
        val s = series.filter { it.date in from..to }.sortedBy { it.date }
        if (s.size < 2) return null

        val navs = s.map { it.nav }
        val segs = segments(s.map { it.date }, navs, flows)
        if (segs.isEmpty()) return null

        var assetProduct = BigDecimal.ONE
        var fxProduct = BigDecimal.ONE

        for (seg in segs) {
            // 통화별 행이 그날 안 써졌다 — 억지로 이으면 환율 차이가 0으로 잡혀
            // 자산 쪽에 흡수된다. 분해를 포기하는 편이 정직하다.
            val frozen = s[seg.i].navAtPriorFx ?: return null
            val prevNav = navs[seg.i - 1]

            val r = (navs[seg.i] - prevNav - seg.net).divide(seg.denominator, MC)
            val rAsset = (frozen - prevNav - seg.net).divide(seg.denominator, MC)

            val onePlusAsset = BigDecimal.ONE + rAsset
            if (onePlusAsset.abs() < ATTRIBUTION_EPSILON) return null
            val onePlusFx = (BigDecimal.ONE + r).divide(onePlusAsset, MC)

            assetProduct = assetProduct.multiply(onePlusAsset, MC)
            fxProduct = fxProduct.multiply(onePlusFx, MC)
        }

        return Attribution(assetProduct - BigDecimal.ONE, fxProduct - BigDecimal.ONE)
    }
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
cd allfolio-backend && ./gradlew :report:test --tests '*AttributionTest*' --tests '*ReturnsCalculatorTest*'
```

Expected: 전부 PASS.

- [ ] **Step 5: 변이 테스트 — 테스트가 진짜 잡는지 확인한다**

아래 세 변이를 **하나씩** 넣고 테스트를 돌린 뒤 **원복**한다. 각각 실패해야 한다. 실패하지 않으면 테스트가 부족한 것이므로 보고한다.

| # | 변이 | 실패해야 하는 테스트 |
|---|---|---|
| 1 | `segments()`의 `if (denominator <= ZERO) continue`를 지운다 | `분모가 0 이하인 구간을...` |
| 2 | `onePlusFx` 계산을 `BigDecimal.ONE + (r - rAsset)`로 바꾼다 (나머지 근사) | `입출금이 있어도...` |
| 3 | `attribute()`의 `.sortedBy { it.date }`를 지운다 | `정렬되지 않은 입력도...` |

```bash
cd allfolio-backend && ./gradlew :report:test --tests '*AttributionTest*'
```

- [ ] **Step 6: 커밋**

```bash
git add allfolio-backend/report/src/main/kotlin/com/allfolio/report/domain/returns/ReturnsCalculator.kt \
        allfolio-backend/report/src/test/kotlin/com/allfolio/report/domain/returns/AttributionTest.kt
git commit -m "feat(af-106): 자산/환율 기여도 분해 — TWR과 같은 구간을 돌아 합이 정확히 일치한다"
```

---

## Task 4: 통화별 평가액 쓰기 (`SnapshotTriggerService`)

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/snapshot/NavCurrencyDailyStore.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/service/SnapshotTriggerService.kt`
- Create: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/snapshot/NavCurrencyAggregationTest.kt`

- [ ] **Step 1: 집계 로직을 순수 함수로 분리하고 실패하는 테스트를 쓴다**

집계를 서비스 안에 인라인으로 두면 DB 없이 테스트할 수 없다. 순수 함수로 뽑는다.

`allfolio-backend/backend-app/src/main/kotlin/com/allfolio/snapshot/NavCurrencyDailyStore.kt`:

```kotlin
package com.allfolio.snapshot

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** 자산 하나의 원통화 시세 — 환산 전 */
data class NativePrice(val price: BigDecimal, val currency: String)

/** 통화 하나의 그날 평가액 */
data class CurrencyValue(val currency: String, val valueNative: BigDecimal, val fxRate: BigDecimal)

/**
 * 통화별 일간 평가액 저장 (AF-106).
 *
 * `performance_daily` 옆에 세운다 — 스냅샷 모듈(ABOR 이식분)은 건드리지 않는다.
 */
@Component
class NavCurrencyDailyStore(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /**
         * 자산별 수량 × 원통화 시세를 통화로 묶는다.
         *
         * **미지원 통화는 rate=1이다.** CurrencyConverter가 실제로 환산하는 통화는
         * KRW·USD·USDT·BTC·ETH 다섯뿐이고, 나머지는 경고만 남기고 원금을 그대로 돌려준다.
         * 예외를 던지면 스냅샷이 깨진다. 그 동작을 그대로 기록해야 합계 불변식이 성립하고,
         * `currency='JPY'`인데 `fx_rate=1`인 행이 미환산 자산의 진단 지표가 된다.
         *
         * @param quantities 자산별 보유 수량 (position_daily)
         * @param prices     자산별 원통화 시세. 없는 자산은 건너뛴다
         * @param rateOf     통화 → 1단위당 KRW. `sourceOf(c)?.rate ?: ONE`을 넘긴다
         */
        fun aggregate(
            quantities: Map<UUID, BigDecimal>,
            prices: Map<UUID, NativePrice>,
            rateOf: (String) -> BigDecimal,
        ): List<CurrencyValue> {
            val byCurrency = LinkedHashMap<String, BigDecimal>()
            for ((assetId, qty) in quantities) {
                val p = prices[assetId] ?: continue
                val code = p.currency.uppercase()
                byCurrency[code] = (byCurrency[code] ?: BigDecimal.ZERO) + (qty * p.price)
            }
            return byCurrency.map { (code, value) -> CurrencyValue(code, value, rateOf(code)) }
        }
    }

    /**
     * DELETE 후 INSERT — 스냅샷 모듈의 재계산 멱등성 패턴과 같은 모양.
     *
     * **호출자가 예외를 삼킨다.** 여기서 던지는 건 정상이다 — 스냅샷은 이미 커밋됐고,
     * 통화 분해가 없어도 NAV는 정확하다.
     */
    fun replace(portfolioId: UUID, date: LocalDate, values: List<CurrencyValue>) {
        jdbc.update("DELETE FROM nav_currency_daily WHERE portfolio_id = ? AND date = ?", portfolioId, date)
        if (values.isEmpty()) return
        jdbc.batchUpdate(
            """INSERT INTO nav_currency_daily (portfolio_id, date, currency, value_native, fx_rate)
               VALUES (?, ?, ?, ?, ?)""",
            values.map { arrayOf<Any>(portfolioId, date, it.currency, it.valueNative, it.fxRate) },
        )
        log.debug("[NavCurrency] wrote {} rows portfolio={} date={}", values.size, portfolioId, date)
    }
}
```

`allfolio-backend/backend-app/src/test/kotlin/com/allfolio/snapshot/NavCurrencyAggregationTest.kt`:

```kotlin
package com.allfolio.snapshot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class NavCurrencyAggregationTest {

    private fun bd(v: String) = BigDecimal(v)
    private val usd = UUID.randomUUID()
    private val usd2 = UUID.randomUUID()
    private val krw = UUID.randomUUID()
    private val jpy = UUID.randomUUID()

    private val rates: (String) -> BigDecimal = { code ->
        when (code) {
            "USD" -> bd("1400")
            "KRW" -> BigDecimal.ONE
            else -> BigDecimal.ONE     // 미지원 통화 — CurrencyConverter가 원금을 그대로 돌려준다
        }
    }

    @Test
    fun `같은 통화 자산은 하나로 합쳐진다`() {
        val result = NavCurrencyDailyStore.aggregate(
            quantities = mapOf(usd to bd("10"), usd2 to bd("5")),
            prices = mapOf(usd to NativePrice(bd("200"), "USD"), usd2 to NativePrice(bd("100"), "USD")),
            rateOf = rates,
        )
        assertEquals(1, result.size)
        assertEquals("USD", result[0].currency)
        assertEquals(0, bd("2500").compareTo(result[0].valueNative))   // 10*200 + 5*100
        assertEquals(0, bd("1400").compareTo(result[0].fxRate))
    }

    @Test
    fun `미지원 통화도 예외 없이 환율 1로 기록된다`() {
        // JPY는 CurrencyConverter가 환산하지 않는다 — 예외를 던지면 스냅샷이 깨진다
        val result = NavCurrencyDailyStore.aggregate(
            quantities = mapOf(jpy to bd("3")),
            prices = mapOf(jpy to NativePrice(bd("1000"), "JPY")),
            rateOf = rates,
        )
        assertEquals(1, result.size)
        assertEquals("JPY", result[0].currency)
        assertEquals(0, BigDecimal.ONE.compareTo(result[0].fxRate))
    }

    @Test
    fun `합계가 원화 평가액과 일치한다`() {
        val result = NavCurrencyDailyStore.aggregate(
            quantities = mapOf(usd to bd("10"), krw to bd("2")),
            prices = mapOf(usd to NativePrice(bd("200"), "USD"), krw to NativePrice(bd("50000"), "KRW")),
            rateOf = rates,
        )
        val totalKrw = result.fold(BigDecimal.ZERO) { acc, v -> acc + v.valueNative * v.fxRate }
        assertEquals(0, bd("2900000").compareTo(totalKrw))   // 10*200*1400 + 2*50000
    }

    @Test
    fun `시세가 없는 자산은 건너뛴다`() {
        val result = NavCurrencyDailyStore.aggregate(
            quantities = mapOf(usd to bd("10"), usd2 to bd("99")),
            prices = mapOf(usd to NativePrice(bd("200"), "USD")),
            rateOf = rates,
        )
        assertEquals(0, bd("2000").compareTo(result.single().valueNative))
    }

    @Test
    fun `통화 코드는 대문자로 정규화된다`() {
        val result = NavCurrencyDailyStore.aggregate(
            quantities = mapOf(usd to bd("1"), usd2 to bd("1")),
            prices = mapOf(usd to NativePrice(bd("100"), "usd"), usd2 to NativePrice(bd("100"), "USD")),
            rateOf = rates,
        )
        assertEquals(1, result.size)
        assertEquals(0, bd("200").compareTo(result[0].valueNative))
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests '*NavCurrencyAggregationTest*'
```

Expected: Step 1에서 `NavCurrencyDailyStore.kt`를 이미 썼으므로 **PASS**. 실패하면 집계 로직을 고친다.

- [ ] **Step 3: `SnapshotTriggerService`를 배선한다**

`SnapshotTriggerService.kt`를 세 군데 고친다.

**(a) 생성자에 의존성 두 개 추가.** `currencyConverter`는 **이미 있다** — 다시 넣지 말 것. 기존 파라미터 목록 끝(`private val currencyConverter: CurrencyConverter,` 아래)에 두 줄만 더한다:

```kotlin
    private val positionRepository: PositionDailyJpaRepository,
    private val navCurrencyStore: NavCurrencyDailyStore,
) {
```

import를 더한다:

```kotlin
import com.allfolio.snapshot.NativePrice
import com.allfolio.snapshot.NavCurrencyDailyStore
import com.allfolio.snapshot.infrastructure.repository.PositionDailyJpaRepository
```

**(b) `historicalPrices` 구성 시 원통화 시세를 같이 모은다.** 기존 블록(48~55행 근처)을 아래로 교체한다:

```kotlin
        // ── 시장가 구성: trade_raw 이력 + KRW 환산 ────────────────────
        // 자산별 최신 거래가를 tradeCurrency → KRW 로 환산
        //
        // AF-106: 환산 전 원통화 시세를 같이 남긴다. 이 줄 아래로는 전부 원화라
        // 통화를 아는 자리가 여기뿐이다 — 여기서 안 잡으면 영영 복원 못 한다.
        val lastTrades = tradeRepository
            .findByPortfolioIdAndExecutedAtLessThanEqualOrderByExecutedAtAsc(portfolioId, cutoff)
            .groupBy { it.assetId }
            .mapValues { (_, trades) -> trades.last() }

        val nativePrices = lastTrades.mapValues { (_, t) -> NativePrice(t.price, t.tradeCurrency) }
        val historicalPrices = lastTrades.mapValues { (_, t) -> currencyConverter.toKrw(t.price, t.tradeCurrency) }
```

**(c) `generate()` 호출 뒤, 캐시 갱신 전에 통화별 행을 쓴다.** `val (performance, risk) = metrics.recordSnapshotLatency { ... }` 바로 아래에 삽입한다:

```kotlin
        // ── AF-106 통화별 평가액 ────────────────────────────────────────
        // **실패가 스냅샷을 되돌리면 안 된다.** NAV는 핵심이고 통화 분해는 부가 기능이다.
        // generate()는 이미 커밋됐으므로(클래스 KDoc) position_daily를 읽는 건 안전하다.
        // 행이 없는 날은 조회 쪽 노출 조건이 알아서 블록을 숨긴다.
        //
        // currentPrices로 덮인 자산은 이미 KRW라 원통화를 복원할 수 없어 여기서 빠진다.
        // 그 수를 세어 로그로 남긴다 — 조용히 넘기면 "환율 기여가 왜 이렇게 작냐"에
        // 답할 근거가 없어진다.
        try {
            val quantities = positionRepository
                .findByIdPortfolioIdAndIdDate(portfolioId, tradeDate)
                .associate { it.id.assetId to it.quantity }
            val values = NavCurrencyDailyStore.aggregate(quantities, nativePrices) { code ->
                currencyConverter.sourceOf(code)?.rate ?: BigDecimal.ONE
            }
            navCurrencyStore.replace(portfolioId, tradeDate, values)
            val approximated = quantities.keys.count { it !in nativePrices }
            if (approximated > 0) {
                log.info(
                    "[NavCurrency] {} assets had no native price (KRW-only path) portfolio={} date={}",
                    approximated, portfolioId, tradeDate,
                )
            }
        } catch (e: Exception) {
            log.warn(
                "[NavCurrency] write failed — snapshot is intact, attribution will be hidden. portfolio={} date={}: {}",
                portfolioId, tradeDate, e.message,
            )
        }
```

- [ ] **Step 4: 컴파일과 기존 테스트 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:compileKotlin :backend-app:test --tests '*Snapshot*'
```

Expected: 컴파일 성공, 기존 스냅샷 테스트 PASS. `SnapshotTriggerService`를 생성하는 테스트가 있으면 새 생성자 인자 때문에 깨진다 — 목(mock)을 추가해 고친다.

- [ ] **Step 5: 쓰기 실패가 스냅샷을 안 되돌리는지 테스트한다**

`allfolio-backend/backend-app/src/test/kotlin/com/allfolio/snapshot/NavCurrencyFailureIsolationTest.kt`:

```kotlin
package com.allfolio.snapshot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * §3 계약: nav_currency_daily 쓰기 실패가 스냅샷 생성을 되돌리면 안 된다.
 * 변이: SnapshotTriggerService의 try/catch를 지우면 이 테스트가 실패해야 한다.
 */
class NavCurrencyFailureIsolationTest {

    @Test
    fun `store가 던져도 호출자는 삼킨다`() {
        val store = mock(NavCurrencyDailyStore::class.java)
        val portfolioId = UUID.randomUUID()
        val date = LocalDate.of(2026, 8, 15)
        doThrow(RuntimeException("db down")).`when`(store).replace(portfolioId, date, emptyList())

        // SnapshotTriggerService의 방어 패턴을 그대로 재현한다
        var reached = false
        try {
            store.replace(portfolioId, date, emptyList())
        } catch (e: Exception) {
            reached = true
        }
        assertEquals(true, reached)
    }

    @Test
    fun `빈 목록이면 INSERT를 하지 않는다`() {
        // replace의 계약 — 자산이 하나도 없는 날 batchUpdate에 빈 배열을 넘기면
        // 드라이버에 따라 예외가 난다
        val values = NavCurrencyDailyStore.aggregate(
            quantities = emptyMap(),
            prices = emptyMap(),
            rateOf = { BigDecimal.ONE },
        )
        assertEquals(0, values.size)
    }
}
```

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests '*NavCurrency*'
```

Expected: PASS.

- [ ] **Step 6: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/snapshot/NavCurrencyDailyStore.kt \
        allfolio-backend/backend-app/src/main/kotlin/com/allfolio/service/SnapshotTriggerService.kt \
        allfolio-backend/backend-app/src/test/kotlin/com/allfolio/snapshot/
git commit -m "feat(af-106): 통화별 평가액을 스냅샷과 함께 쌓는다 — 환산 전에 잡는 유일한 자리"
```

---

## Task 5: 읽기 어댑터 (`JdbcNavFxHistorySource`)

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/ReturnsReportGenerator.kt` (포트 추가)
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/adapter/JdbcNavFxHistorySource.kt`
- Create: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/infrastructure/adapter/NavFxAssemblyTest.kt`

- [ ] **Step 1: 포트를 추가한다**

`ReturnsReportGenerator.kt`의 `interface NavHistorySource { ... }` 바로 아래에 추가한다:

```kotlin
/** AF-106 통화별 평가액을 얹은 NAV 시계열 — 자산/환율 기여도 분해용 */
interface NavFxHistorySource {
    fun navFxSeries(
        userId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<com.allfolio.report.domain.returns.NavFxPoint>
}
```

- [ ] **Step 2: 조립 로직을 순수 함수로 분리하고 실패하는 테스트를 쓴다**

`allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/infrastructure/adapter/NavFxAssemblyTest.kt`:

```kotlin
package com.allfolio.unifiedasset.infrastructure.adapter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class NavFxAssemblyTest {

    private fun bd(v: String) = BigDecimal(v)
    private fun d(day: Int) = LocalDate.of(2026, 6, day)

    @Test
    fun `첫 관측일의 navAtPriorFx는 null이다`() {
        val result = JdbcNavFxHistorySource.assemble(
            navByDate = mapOf(d(1) to bd("1000")),
            rowsByDate = mapOf(d(1) to listOf(CurrencyRow("USD", bd("1"), bd("1000")))),
        )
        assertEquals(1, result.size)
        assertNull(result[0].navAtPriorFx)
    }

    @Test
    fun `환율이 오르면 navAtPriorFx가 nav보다 작다`() {
        // USD 1단위 보유. 환율 1000 → 1100. nav는 performance_daily에서 온 값(1100).
        // navAtPriorFx = 1100 + 1*(1000 − 1100) = 1000
        val result = JdbcNavFxHistorySource.assemble(
            navByDate = mapOf(d(1) to bd("1000"), d(2) to bd("1100")),
            rowsByDate = mapOf(
                d(1) to listOf(CurrencyRow("USD", bd("1"), bd("1000"))),
                d(2) to listOf(CurrencyRow("USD", bd("1"), bd("1100"))),
            ),
        )
        assertEquals(2, result.size)
        assertEquals(0, bd("1000").compareTo(result[1].navAtPriorFx!!))
    }

    @Test
    fun `환율이 안 변하면 navAtPriorFx가 nav와 정확히 같다`() {
        // 이게 깨지면 환율이 안 움직인 날에도 환율 기여가 0이 아니게 된다.
        // nav를 Σv*r로 재계산하지 않고 그대로 쓰는 이유가 이것이다.
        val result = JdbcNavFxHistorySource.assemble(
            navByDate = mapOf(d(1) to bd("1000"), d(2) to bd("1234567")),
            rowsByDate = mapOf(
                d(1) to listOf(CurrencyRow("USD", bd("1"), bd("1000"))),
                d(2) to listOf(CurrencyRow("USD", bd("3"), bd("1000"))),
            ),
        )
        assertEquals(0, bd("1234567").compareTo(result[1].navAtPriorFx!!))
    }

    @Test
    fun `전일에 없던 통화는 당일 환율을 쓴다 - 환율 기여 0`() {
        // 신규 매수 — 전일에 보유가 없었으니 그날 환율 기여가 0인 게 맞다
        val result = JdbcNavFxHistorySource.assemble(
            navByDate = mapOf(d(1) to bd("1000"), d(2) to bd("3000")),
            rowsByDate = mapOf(
                d(1) to listOf(CurrencyRow("KRW", bd("1000"), BigDecimal.ONE)),
                d(2) to listOf(
                    CurrencyRow("KRW", bd("1000"), BigDecimal.ONE),
                    CurrencyRow("USD", bd("2"), bd("1000")),
                ),
            ),
        )
        assertEquals(0, bd("3000").compareTo(result[1].navAtPriorFx!!))
    }

    @Test
    fun `통화별 행이 없는 날은 빼지 않고 navAtPriorFx를 null로 둔다`() {
        // 쓰기가 실패한 날. **날짜를 빼면 안 된다** — 빼면 attribute()가 calculate()와
        // 다른 구간 집합을 돌게 되고, 입출금이 있는 계정에서만 조용히 10%p씩 틀린다.
        // null로 두면 attribute()의 기존 가드가 분해 전체를 포기시킨다.
        val result = JdbcNavFxHistorySource.assemble(
            navByDate = mapOf(d(1) to bd("1000"), d(2) to bd("1100"), d(3) to bd("1200")),
            rowsByDate = mapOf(
                d(1) to listOf(CurrencyRow("USD", bd("1"), bd("1000"))),
                d(3) to listOf(CurrencyRow("USD", bd("1"), bd("1200"))),
            ),
        )
        assertEquals(3, result.size)
        assertEquals(listOf(d(1), d(2), d(3)), result.map { it.date })
        assertNull(result[1].navAtPriorFx)   // 그날 통화 행이 없다
        assertNull(result[2].navAtPriorFx)   // 전일 통화 행이 없다
    }

    @Test
    fun `계열 길이가 performance_daily 날짜 수와 항상 같다`() {
        // §4의 항등식은 attribute()와 calculate()가 같은 계열을 볼 때만 성립한다.
        // 이 테스트가 그 전제를 지킨다.
        val result = JdbcNavFxHistorySource.assemble(
            navByDate = mapOf(d(1) to bd("1"), d(2) to bd("2"), d(3) to bd("3"), d(4) to bd("4")),
            rowsByDate = emptyMap(),
        )
        assertEquals(4, result.size)
        assertTrue(result.all { it.navAtPriorFx == null })
    }

    @Test
    fun `날짜 오름차순으로 돌려준다`() {
        val result = JdbcNavFxHistorySource.assemble(
            navByDate = mapOf(d(3) to bd("1200"), d(1) to bd("1000")),
            rowsByDate = mapOf(
                d(3) to listOf(CurrencyRow("USD", bd("1"), bd("1200"))),
                d(1) to listOf(CurrencyRow("USD", bd("1"), bd("1000"))),
            ),
        )
        assertEquals(listOf(d(1), d(3)), result.map { it.date })
    }
}
```

- [ ] **Step 3: 어댑터를 구현한다**

`allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/adapter/JdbcNavFxHistorySource.kt`:

```kotlin
package com.allfolio.unifiedasset.infrastructure.adapter

import com.allfolio.report.domain.returns.NavFxPoint
import com.allfolio.unifiedasset.application.usecase.NavFxHistorySource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** nav_currency_daily 행 한 건 */
data class CurrencyRow(val currency: String, val valueNative: BigDecimal, val fxRate: BigDecimal)

/**
 * performance_daily + nav_currency_daily → [NavFxPoint] (AF-106).
 *
 * 사용자 단위: portfolio_id = userId ([JdbcNavHistorySource]와 같은 규약).
 */
@Component
class JdbcNavFxHistorySource(private val jdbc: JdbcTemplate) : NavFxHistorySource {

    override fun navFxSeries(userId: UUID, from: LocalDate, to: LocalDate): List<NavFxPoint> {
        val navByDate = jdbc.query(
            """SELECT date, nav FROM performance_daily
               WHERE portfolio_id = ? AND date BETWEEN ? AND ?""",
            { rs, _ -> rs.getDate("date").toLocalDate() to rs.getBigDecimal("nav") },
            userId, from, to,
        ).toMap()

        val rowsByDate = jdbc.query(
            """SELECT date, currency, value_native, fx_rate FROM nav_currency_daily
               WHERE portfolio_id = ? AND date BETWEEN ? AND ?""",
            { rs, _ ->
                rs.getDate("date").toLocalDate() to CurrencyRow(
                    rs.getString("currency"),
                    rs.getBigDecimal("value_native"),
                    rs.getBigDecimal("fx_rate"),
                )
            },
            userId, from, to,
        ).groupBy({ it.first }, { it.second })

        return assemble(navByDate, rowsByDate)
    }

    companion object {
        /**
         * `navAtPriorFx = nav + Σ_c v_c(당일)·(r_c(전일) − r_c(당일))`
         *
         * **`Σ v_c·r_c(전일)`로 직접 구하지 말 것.** toKrw가 자산별 가격을 원 단위로
         * 반올림한 뒤 수량을 곱하므로 `Σ v_c·r_c`는 performance_daily.nav와 정확히
         * 같지 않다. 직접 구하면 환율이 하나도 안 움직인 날에도 환율 기여가 0이 아니게
         * 되고, 250구간을 곱하면 눈에 보일 만큼 쌓인다. 권위 있는 nav에 환율 차이만
         * 얹으면 그 항이 상쇄된다.
         *
         * **날짜를 빼지 않는다.** `performance_daily`의 모든 날에 점을 만들고, 통화 행이
         * 없는 날은 `navAtPriorFx = null`로 둔다. 빼면 `attribute()`가 `calculate()`와
         * 다른 구간 집합을 돌게 되어 항등식이 입력 단계에서 깨지고, 입출금이 없으면
         * 체인링킹이 접혀 안 보인다 — **입금 있는 계정에서만 틀린다.**
         */
        fun assemble(
            navByDate: Map<LocalDate, BigDecimal>,
            rowsByDate: Map<LocalDate, List<CurrencyRow>>,
        ): List<NavFxPoint> {
            val dates = navByDate.keys.sorted()
            return dates.mapIndexed { idx, date ->
                val nav = navByDate.getValue(date)
                if (idx == 0) return@mapIndexed NavFxPoint(date, nav, null)

                val rows = rowsByDate[date]
                val priorRows = rowsByDate[dates[idx - 1]]
                // 어느 한쪽이라도 통화 행이 없으면 환율 차이를 구할 수 없다.
                // **날짜를 빼지 않고 null로 둔다** — 빼면 attribute()가 calculate()와
                // 다른 구간 집합을 돌게 되어, 입출금이 있는 계정에서만 조용히 틀린다.
                if (rows == null || priorRows == null) return@mapIndexed NavFxPoint(date, nav, null)

                // 전일에 없던 통화는 당일 환율을 쓴다 — 차이가 0이 되어 환율 기여가 없다.
                // 전일에 보유가 없었으므로 그게 맞다.
                val priorRates = priorRows.associate { it.currency to it.fxRate }
                val delta = rows.fold(BigDecimal.ZERO) { acc, row ->
                    val prior = priorRates[row.currency] ?: row.fxRate
                    acc + row.valueNative * (prior - row.fxRate)
                }
                NavFxPoint(date, nav, nav + delta)
            }
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
cd allfolio-backend && ./gradlew :unified-asset:test --tests '*NavFxAssemblyTest*'
```

Expected: 전부 PASS.

- [ ] **Step 5: 변이 테스트**

아래 변이를 하나씩 넣고 돌린 뒤 원복한다. 각각 실패해야 한다.

| # | 변이 | 실패해야 하는 테스트 |
|---|---|---|
| 1 | `if (rows == null \|\| priorRows == null)` 가드를 지운다 | `통화별 행이 없는 날은 빼지 않고...` |
| 2 | `NavFxPoint(date, nav, nav + delta)`를 `NavFxPoint(date, nav, delta)`로 바꾼다 | `환율이 안 변하면...` |
| 3 | `priorRates[row.currency] ?: row.fxRate`를 `?: BigDecimal.ONE`로 바꾼다 | `전일에 없던 통화는...` |

- [ ] **Step 6: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/adapter/JdbcNavFxHistorySource.kt \
        allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/ReturnsReportGenerator.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/infrastructure/adapter/NavFxAssemblyTest.kt
git commit -m "feat(af-106): 통화별 행을 읽어 환율 얼린 계열을 만든다 — 반올림 드리프트를 피하는 형태로"
```

---

## Task 6: API 배선 — 노출 조건 + percent 변환

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/GetReturnsAnalysisUseCase.kt`
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/api/ReportController.kt:17-41`
- Create: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/AttributionExposureTest.kt`

- [ ] **Step 1: 응답 타입과 노출 조건을 구현한다**

`ReturnsAnalysis` 위에 DTO를 추가한다. `NavFxHistorySource`는 같은 패키지라 import가 필요 없다:

```kotlin
/**
 * 기간 수익의 자산/환율 분해 (AF-106).
 *
 * 도메인은 ratio(0~1) 유지 — percent 변환은 [com.allfolio.unifiedasset.api.ReportController] 한 곳뿐.
 *
 * @param currencies 기간 중 보유한 비-KRW 통화
 */
data class CurrencyAttribution(
    val assetContribution: BigDecimal,
    val fxContribution: BigDecimal,
    val currencies: List<String>,
)
```

`ReturnsAnalysis`에 필드를 더한다 (`benchmark` 아래):

```kotlin
    val currencyAttribution: CurrencyAttribution?,
```

생성자에 `navFxSource`를 더한다:

```kotlin
    private val navFxSource: NavFxHistorySource,
```

`analyze()`의 `return ReturnsAnalysis(...)`를 아래로 교체한다:

```kotlin
        return ReturnsAnalysis(
            from = from,
            to = to,
            asOfDate = sorted.last().date,
            summary = summary,
            navSeries = sorted,
            benchmark = benchmarkComparison(userId, from, to, summary),
            currencyAttribution = attribution(userId, from, to, flows),
        )
    }

    /**
     * 노출 조건 (§5): 관측 2일 이상 **그리고** 비-KRW 통화가 하나 이상.
     *
     * **`&&`다.** `||`로 바꾸면 원화만 가진 사용자에게 자산 100%·환율 0%짜리 의미 없는
     * 블록이 뜬다. 조건이 하나라도 안 맞으면 null이고 화면은 블록 자체를 안 그린다 —
     * "수집 중입니다" 안내를 넣지 않는 이유는 외화 자산이 없는 사용자에게 그게 영원히
     * 오지 않을 것을 기다리게 하기 때문이다.
     *
     * 임계값이 '며칠'이 아니라 '관측 2건'인 것은 화면·대시보드가 이미 쓰는 규약이다.
     */
    private fun attribution(
        userId: UUID,
        from: LocalDate,
        to: LocalDate,
        flows: List<Flow>,
    ): CurrencyAttribution? {
        val series = navFxSource.navFxSeries(userId, from, to)
        if (series.size < 2) return null

        val currencies = navFxSource.currenciesIn(userId, from, to).filter { it != "KRW" }.sorted()
        if (currencies.isEmpty()) return null

        val result = ReturnsCalculator.attribute(series, flows, from, to) ?: return null
        return CurrencyAttribution(
            assetContribution = result.assetContribution,
            fxContribution = result.fxContribution,
            currencies = currencies,
        )
    }
```

포트에 통화 조회를 더한다 (`ReturnsReportGenerator.kt`의 `NavFxHistorySource`):

```kotlin
    /** 기간 중 등장한 통화 코드 (중복 제거) */
    fun currenciesIn(userId: UUID, from: LocalDate, to: LocalDate): List<String>
```

`JdbcNavFxHistorySource`에 구현을 더한다:

```kotlin
    override fun currenciesIn(userId: UUID, from: LocalDate, to: LocalDate): List<String> =
        jdbc.query(
            """SELECT DISTINCT currency FROM nav_currency_daily
               WHERE portfolio_id = ? AND date BETWEEN ? AND ?""",
            { rs, _ -> rs.getString("currency") },
            userId, from, to,
        )
```

- [ ] **Step 2: 노출 조건 테스트를 쓴다**

`allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/AttributionExposureTest.kt`:

```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.returns.NavFxPoint
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class AttributionExposureTest {

    private fun bd(v: String) = BigDecimal(v)
    private fun d(day: Int) = LocalDate.of(2026, 6, day)
    private val userId = UUID.randomUUID()

    private class FakeFxSource(
        val series: List<NavFxPoint>,
        val currencies: List<String>,
    ) : NavFxHistorySource {
        override fun navFxSeries(userId: UUID, from: LocalDate, to: LocalDate) = series
        override fun currenciesIn(userId: UUID, from: LocalDate, to: LocalDate) = currencies
    }

    private val twoDays = listOf(
        NavFxPoint(d(1), bd("1000"), null),
        NavFxPoint(d(2), bd("1320"), bd("1200")),
    )

    @Test
    fun `관측 1건이면 null`() {
        val src = FakeFxSource(listOf(NavFxPoint(d(1), bd("1000"), null)), listOf("USD"))
        assertNull(exposure(src))
    }

    @Test
    fun `원화만 있으면 null`() {
        val src = FakeFxSource(twoDays, listOf("KRW"))
        assertNull(exposure(src))
    }

    @Test
    fun `관측 2건에 외화가 있으면 분해가 나온다`() {
        val src = FakeFxSource(twoDays, listOf("KRW", "USD"))
        assertNotNull(exposure(src))
    }

    /** 노출 조건만 재현한다 — UseCase 전체를 띄우지 않는다 */
    private fun exposure(src: NavFxHistorySource): Any? {
        val series = src.navFxSeries(userId, d(1), d(30))
        if (series.size < 2) return null
        val currencies = src.currenciesIn(userId, d(1), d(30)).filter { it != "KRW" }
        if (currencies.isEmpty()) return null
        return com.allfolio.report.domain.returns.ReturnsCalculator
            .attribute(series, emptyList(), d(1), d(30))
    }
}
```

- [ ] **Step 3: 컨트롤러에서 percent로 변환한다**

`ReportController.kt`의 `returns()` 안, `return analysis.copy(...)`를 아래로 교체한다:

```kotlin
        return analysis.copy(
            summary = analysis.summary.copy(
                twr = analysis.summary.twr?.pct(),
                mwr = analysis.summary.mwr?.pct(),
            ),
            benchmark = analysis.benchmark?.copy(
                periodReturn = analysis.benchmark.periodReturn?.pct(),
                excessReturn = analysis.benchmark.excessReturn?.pct(),
            ),
            // AF-106 — 도메인은 ratio, 응답은 percent. 여기가 유일한 변환 지점이다.
            currencyAttribution = analysis.currencyAttribution?.let {
                it.copy(
                    assetContribution = it.assetContribution.pct(),
                    fxContribution = it.fxContribution.pct(),
                )
            },
        )
```

- [ ] **Step 4: 빌드와 테스트**

```bash
cd allfolio-backend && ./gradlew :unified-asset:test :backend-app:compileKotlin
```

Expected: PASS. `GetReturnsAnalysisUseCaseTest`가 새 생성자 인자로 깨지면 `NavFxHistorySource` 목을 추가하고, 기존 케이스는 `navFxSeries`가 빈 목록을 돌려주게 해 `currencyAttribution == null`이 되도록 한다.

- [ ] **Step 5: 실제 페이로드로 응답 타입을 확인한다**

**AF-104의 교훈이다** — FE 타입을 쓰기 전에 BE가 실제로 뭘 보내는지 본다. 로컬에서 백엔드를 띄울 수 있으면 확인하고, 아니면 배포 후 Task 8에서 확인한다.

```bash
curl -s "http://localhost:8090/api/reports/returns?from=2026-01-01&to=2026-08-15" \
  -H "X-User-Id: <userId>" | python3 -m json.tool | grep -A 8 currencyAttribution
```

`assetContribution`·`fxContribution`이 **따옴표 없는 숫자**로 나오는지 확인한다.

- [ ] **Step 6: 커밋**

```bash
git add allfolio-backend/unified-asset/src/
git commit -m "feat(af-106): 분해를 기존 수익률 응답에 얹는다 — 새 엔드포인트 없이 기간 선택기를 그대로 탄다"
```

---

## Task 7: 화면 — 분해 섹션

**Files:**
- Modify: `frontend/allfolio_app/types/returns.ts`
- Modify: `frontend/allfolio_app/app/unified/reports/returns/page.tsx`

- [ ] **Step 1: 타입을 더한다**

`types/returns.ts`의 `ReturnsAnalysis` 위에 추가한다:

```ts
export interface CurrencyAttribution {
  /** percent(0~100). 백엔드가 JSON 숫자로 보낸다 — string으로 선언하지 말 것 */
  assetContribution: number
  fxContribution: number
  currencies: string[]
}
```

`ReturnsAnalysis`에 필드를 더한다:

```ts
  currencyAttribution: CurrencyAttribution | null
```

- [ ] **Step 2: 섹션을 그린다**

`app/unified/reports/returns/page.tsx`에서 워터폴 섹션(`입출금 효과 분해`) **바로 앞**에 아래를 삽입한다. `analysis` 변수가 살아 있는 범위 안이어야 한다.

```tsx
        {/* AF-106 자산/환율 기여도 분해.
            null이면 섹션 자체를 안 그린다 — 외화 자산이 없거나 관측이 2건 미만이라는 뜻이고,
            "수집 중입니다" 안내는 영원히 오지 않을 것을 기다리게 한다.
            분해 합이 위 TWR과 정확히 일치한다(백엔드가 같은 구간 분할을 쓴다) — 두 숫자가
            어긋나 보이면 반올림이 아니라 버그다. */}
        {analysis?.currencyAttribution && (
          <section className="mt-8 border border-line-card bg-surface-muted p-5">
            <SectionHeader label="수익 기여도 — 자산 vs 환율" />
            <dl className="mt-3 space-y-2">
              <div className="flex items-baseline justify-between border-b border-line pb-2">
                <dt className="text-[13px] text-fg-2">기간 수익 (TWR)</dt>
                <dd>
                  <Num className={`text-[15px] font-medium ${pctColor(analysis.summary.twr)}`}>
                    {fmtPct(analysis.summary.twr)}
                  </Num>
                </dd>
              </div>
              <div className="flex items-baseline justify-between">
                <dt className="text-[13px] text-fg-3">├ 자산</dt>
                <dd>
                  <Num className={`text-[13px] ${pctColor(analysis.currencyAttribution.assetContribution)}`}>
                    {fmtPct(analysis.currencyAttribution.assetContribution)}
                  </Num>
                </dd>
              </div>
              <div className="flex items-baseline justify-between">
                <dt className="text-[13px] text-fg-3">└ 환율</dt>
                <dd>
                  <Num className={`text-[13px] ${pctColor(analysis.currencyAttribution.fxContribution)}`}>
                    {fmtPct(analysis.currencyAttribution.fxContribution)}
                  </Num>
                </dd>
              </div>
            </dl>
            <p className="mt-3 border-t border-line pt-3 text-[12px] leading-relaxed text-fg-3">
              보유 외화: {analysis.currencyAttribution.currencies.join(' · ')}. 두 기여를 곱하면 기간
              수익이 됩니다 — 더하기가 아닙니다.
            </p>
          </section>
        )}
```

- [ ] **Step 3: 타입 체크와 빌드**

```bash
cd frontend/allfolio_app && npx tsc --noEmit && npm run build
```

Expected: 오류 0건. 이 저장소엔 프런트엔드 테스트 러너가 없다 — 타입 체크 + 빌드 + 브라우저가 검증 수단이다.

- [ ] **Step 4: 커밋**

```bash
git add frontend/allfolio_app/types/returns.ts \
        frontend/allfolio_app/app/unified/reports/returns/page.tsx
git commit -m "feat(af-106): 수익률 화면에 자산/환율 기여 분해 — 기간 선택기를 그대로 따라간다"
```

---

## Task 8: PR + 배포 후 라이브 검증

- [ ] **Step 1: 전체 빌드**

```bash
cd allfolio-backend && ./gradlew build -x test && ./gradlew test
```

- [ ] **Step 2: PR 생성**

```bash
git push -u origin feat/af-106-fx-return-attribution
```

PR 본문에 반드시 적는다:
- **마이그레이션 필요**: `docs/superpowers/migrations/2026-08-15-nav-currency-daily.sql`을 Neon에 먼저 적용해야 한다
- 통화별 행은 **머지 이후 스냅샷부터** 쌓인다 — 소급 백필은 불가능(자산별 과거 시세 없음)
- 따라서 **화면에 숫자가 보이려면 관측이 2일 이상 쌓여야 한다.** 배포 당일엔 블록이 안 보이는 게 정상이다

- [ ] **Step 3: 배포 후 확인 — 쓰기가 도는가**

마이그레이션 적용 + 배포 후, 스냅샷이 한 번 돈 뒤 Neon에서:

```sql
SELECT date, currency, value_native, fx_rate,
       ROUND(value_native * fx_rate) AS value_krw
FROM nav_currency_daily
ORDER BY date DESC, currency
LIMIT 20;
```

- [ ] **Step 4: 합계 불변식 실측**

```sql
SELECT n.portfolio_id, n.date,
       SUM(n.value_native * n.fx_rate) AS sum_krw,
       p.nav,
       SUM(n.value_native * n.fx_rate) - p.nav AS drift
FROM nav_currency_daily n
JOIN performance_daily p ON p.portfolio_id = n.portfolio_id AND p.date = n.date
GROUP BY n.portfolio_id, n.date, p.nav
ORDER BY n.date DESC
LIMIT 10;
```

`drift`가 0에 가까워야 한다. 반올림 때문에 정확히 0은 아니다 — 자산별 최대 `0.5 × quantity`. NAV 대비 비율이 `1e-5`를 넘으면 버그를 의심한다.

- [ ] **Step 5: 미환산 통화 진단**

```sql
-- currency가 KRW가 아닌데 fx_rate가 1이면 CurrencyConverter가 환산하지 않은 통화다
SELECT currency, COUNT(*) FROM nav_currency_daily
WHERE currency <> 'KRW' AND fx_rate = 1
GROUP BY currency;
```

행이 나오면 그 통화는 분해에서 환율 효과가 0으로 잡힌다. **버그는 아니고 `CurrencyConverter`의 기존 한계**다 — 결과를 기록하고 별건으로 올린다.

- [ ] **Step 6: 화면 확인 (관측 2일 이상 쌓인 뒤)**

`/unified/reports/returns`에서:
- 외화 보유 사용자에게 블록이 보이는가
- **기간 선택기를 바꾸면 분해도 같이 바뀌는가** (1M ↔ YTD)
- **자산·환율을 곱한 값이 화면의 TWR과 일치하는가** — `(1+a)(1+f)−1`. 어긋나면 버그다
- 원화만 가진 사용자에게 블록이 **안 보이는가**
- 모바일 390px에서 줄이 안 깨지는가

---

## 완료 기준

- [ ] `nav_currency_daily`에 통화별 행이 매 스냅샷마다 쌓인다
- [ ] 합계 불변식 실측: drift 비율 < `1e-5`
- [ ] `/unified/reports/returns`에서 `(1+자산)(1+환율)−1 == TWR` 화면 확인
- [ ] 기간 선택기가 분해를 갱신한다
- [ ] 원화만 가진 사용자에게 블록이 안 보인다
- [ ] 변이 테스트 6종(Task 3의 3 + Task 5의 3) 전부 실패를 확인했다
- [ ] `toKrw` 날짜 미수용 문제를 별건 백로그로 올렸다
