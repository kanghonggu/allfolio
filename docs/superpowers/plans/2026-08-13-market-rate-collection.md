# 금리 수집(한국 ECOS) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 한국 금리 6종(기준금리·콜금리·CD 91일·국고채 3년·국고채 10년·회사채 AA- 3년)을 ECOS에서 수집해 `market_rate`에 쌓는다.

**Architecture:** AF-101 지수 수집과 같은 골격 — 설정이 대상을 정하고(`market-rate.series`), 수집 서비스가 종목별로 조회·upsert하고 요약을 돌려주며, 어드민·스케줄러 두 경로로 트리거된다. ECOS 호출은 AF-100의 `EcosApiClient`를 재사용하되 환율 전용 가정(0 이하 거부·`rateKrw` 명명·주기 하드코딩)을 걷어내 금리도 통과하게 일반화한다.

**Tech Stack:** Kotlin · Spring Boot · JPA(Hibernate, `ddl-auto: none`) · JUnit5 + AssertJ + Mockito · GitHub Actions

**설계 문서:** `docs/superpowers/specs/2026-08-13-market-rate-collection-design.md`

---

## 사전 필독 (모든 태스크 공통)

### 이 계획은 두 번에 나눠 머지된다

탐색 엔드포인트로 ECOS 코드를 확인하려면 **배포된 서버**가 필요하고(로컬에 ECOS 키가 없다),
확인 전에는 수집 대상 설정을 채울 수 없다. 그래서:

- **Task 1~8 = PR 1.** 코드 일체 + `market-rate.series`는 **빈 채로** 머지한다.
  `collect-rate.yml`에 **cron을 넣지 않는다**(수동 실행만) — 대상 0건이면 500이라 매일 빨간 잡이 된다.
- **Task 9 = PR 2.** 배포 후 탐색으로 코드를 확인해 설정을 채우고, 그때 cron을 켠다.

AF-101도 같은 순서였다(`MarketIndexAdminController.raw`가 그 흔적이다).

### 건드리지 않는 것

- `FxRateAdminController` · `CashFlowRecomputeService` · `CashFlowJpaRepository` —
  **다른 브랜치가 작업 중이다**(현금흐름 KRW 소급 재계산). 이 계획은 이 셋을 건드리지 않는다.
  diff에 나타나면 범위가 샌 것이다.
- `HistoricalRateSource.DailyRate.rateKrw` · `HistoricalFxRateEntity.rateKrw` —
  환율 도메인 타입이라 이름이 맞다. Task 1의 이름 변경 대상이 아니다.

### 검증 명령

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests '<패턴>' --no-daemon
```

전 모듈 검증은 Task 8에서 한 번:

```bash
cd allfolio-backend && ./gradlew test --rerun-tasks --no-daemon
```

## 파일 구조

| 파일 | 책임 |
|---|---|
| `fx/EcosValuePolicy.kt` (신규) | 값 검증 정책. 환율은 양수만, 금리는 단위 오인만 거른다 |
| `fx/EcosResponseParser.kt` (수정) | 타입 중립화 + 정책 위임 |
| `fx/EcosApiClient.kt` (수정) | 조회 좌표를 `EcosQuery`로 묶고 주기를 파라미터로 뺀다 |
| `fx/EcosHistoricalRateSource.kt` (수정) | 환율 호출부 — 정책 `POSITIVE`, 주기 `D` |
| `fx/EcosStatListClient.kt` (신규) | 통계표·항목 목록 조회 (코드 탐색용) |
| `market/rate/MarketRateProperties.kt` (신규) | 수집 대상 설정 |
| `market/rate/RateCollectService.kt` (신규) | 종목별 조회 → upsert → 요약 |
| `unified-asset/.../entity/MarketRateEntity.kt` (신규) | 저장 |
| `unified-asset/.../jpa/MarketRateJpaRepository.kt` (신규) | 구간 조회 |
| `api/admin/MarketRateAdminController.kt` (신규) | 수집 트리거 + 탐색 |
| `api/scheduler/SchedulerTriggerController.kt` (수정) | 스케줄러 트리거 한 줄 |
| `.github/workflows/collect-rate.yml` (신규) | 평일 1회 (cron은 Task 9에서) |
| `docs/superpowers/migrations/2026-08-13-market-rate.sql` (신규) | 수동 DDL |

---

### Task 1: 파서 일반화 — 값 검증을 호출자가 정한다

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/EcosValuePolicy.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/EcosResponseParser.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/EcosResponseParserTest.kt`

**왜 하는가:** 현재 파서는 `rate <= 0`인 행을 건너뛴다. 0원짜리 환율은 없으니 환율에는 맞지만,
금리에 그대로 쓰면 **0.00%로 공표된 날이 예외도 없이 사라지고 경고 로그만 남는다.**
반환 타입 이름 `EcosRate(baseDate, rateKrw)`도 금리를 담으면 거짓말이 된다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`EcosResponseParserTest.kt`의 기존 테스트 `값이 비었거나 0 이하인 행은 건너뛰고 센다`를
아래 두 개로 **교체**하고, 나머지 테스트의 `parser.parse(json)` 호출을
`parser.parse(json, EcosValuePolicy.POSITIVE)`로 바꾼다. 26번째 줄의
`result.rates[0].rateKrw`는 `result.rates[0].value`가 된다.

```kotlin
    @Test
    fun `환율 정책은 값이 비었거나 0 이하인 행을 건너뛰고 센다`() {
        val json = """
            {"StatisticSearch":{"list_total_count":4,"row":[
              {"TIME":"20250808","DATA_VALUE":"1385.5"},
              {"TIME":"20250809","DATA_VALUE":""},
              {"TIME":"20250810","DATA_VALUE":"0"},
              {"TIME":"20250811","DATA_VALUE":"-1"}
            ]}}
        """.trimIndent()

        val result = parser.parse(json, EcosValuePolicy.POSITIVE)

        assertThat(result.rates).hasSize(1)
        assertThat(result.skipped).isEqualTo(3)
    }

    /**
     * 금리는 0.00%로 공표될 수 있고 마이너스 금리도 실재한다.
     * 환율 정책을 그대로 쓰면 그 날이 통째로 사라지는데, 예외도 안 나고 경고 로그만 남는다.
     */
    @Test
    fun `금리 정책은 0과 마이너스를 살리고 단위 오인만 거른다`() {
        val json = """
            {"StatisticSearch":{"list_total_count":5,"row":[
              {"TIME":"20250808","DATA_VALUE":"3.5"},
              {"TIME":"20250809","DATA_VALUE":"0"},
              {"TIME":"20250810","DATA_VALUE":"-0.25"},
              {"TIME":"20250811","DATA_VALUE":"100.0001"},
              {"TIME":"20250812","DATA_VALUE":""}
            ]}}
        """.trimIndent()

        val result = parser.parse(json, EcosValuePolicy.PERCENT)

        assertThat(result.rates.map { it.value })
            .usingElementComparator(java.math.BigDecimal::compareTo)
            .containsExactly(
                java.math.BigDecimal("3.5"),
                java.math.BigDecimal("0"),
                java.math.BigDecimal("-0.25"),
            )
        assertThat(result.skipped).isEqualTo(2)
    }
```

- [ ] **Step 2: 컴파일 실패를 확인한다**

Run: `cd allfolio-backend && ./gradlew :backend-app:compileTestKotlin --no-daemon`
Expected: FAIL — `Unresolved reference: EcosValuePolicy`

- [ ] **Step 3: 정책을 만든다**

`EcosValuePolicy.kt`:

```kotlin
package com.allfolio.fx

import java.math.BigDecimal

/**
 * ECOS가 준 값을 받아들일지 판정한다.
 *
 * 파서가 판정을 들고 있으면 첫 호출자(환율)의 가정이 모든 호출자에게 강요된다 —
 * 실제로 `rate <= 0` 가드가 그랬고, 금리에 그대로 쓰면 0.00% 공표일이 조용히 사라진다.
 * 그래서 무엇이 말이 되는 값인지는 도메인을 아는 호출자가 정한다.
 */
enum class EcosValuePolicy {
    /** 환율 — 0원짜리 환율은 없다. 0 이하는 파싱 사고이지 값이 아니다 */
    POSITIVE {
        override fun accepts(value: BigDecimal): Boolean = value > BigDecimal.ZERO
    },

    /**
     * 금리(연 %) — **부호로 거르지 않는다.** 0.00% 공표도, 마이너스 금리도 실재한다.
     * 대신 단위 오인(연 3.5%를 350으로 주는 계열)과 파싱 사고만 잡는다.
     * 한계는 ±100%: 한국 금리 시계열이 이 범위를 벗어나면 값이 아니라 형식이 바뀐 것이다.
     */
    PERCENT {
        override fun accepts(value: BigDecimal): Boolean = value.abs() <= BigDecimal("100")
    },
    ;

    abstract fun accepts(value: BigDecimal): Boolean
}
```

- [ ] **Step 4: 파서를 고친다**

`EcosResponseParser.kt`에서 세 곳을 바꾼다.

10~14번째 줄의 타입 선언:

```kotlin
/** ECOS 통계 한 행 — 기준일과 그 날의 값. 단위는 계열마다 다르다(환율은 원, 금리는 연 %) */
data class EcosObservation(val baseDate: LocalDate, val value: BigDecimal)

/** @param skipped 값·날짜가 이상해 버린 행 수. 조용히 삼키지 않고 호출자에게 보고한다. */
data class EcosParseResult(val rates: List<EcosObservation>, val skipped: Int)
```

37번째 줄 시그니처와 58~63번째 줄 판정:

```kotlin
    fun parse(json: String, valuePolicy: EcosValuePolicy): EcosParseResult {
```

```kotlin
            if (date == null || rate == null || !valuePolicy.accepts(rate)) {
                skipped++
                log.warn("[ECOS] 행 건너뜀 TIME={} DATA_VALUE={} policy={}", time, value, valuePolicy)
                null
            } else {
                EcosObservation(date, rate)
            }
```

- [ ] **Step 5: 호출부를 따라 고친다**

컴파일러가 가리키는 곳은 셋이다.

`EcosApiClient.kt`의 `parser.parse(body)` → `parser.parse(body, query.valuePolicy)`는 **Task 2에서**
`EcosQuery`를 만든 뒤 한다. 이 태스크에서는 임시로 `parser.parse(body, EcosValuePolicy.POSITIVE)`로 두고
Task 2가 갈아끼운다.

`EcosHistoricalRateSource.kt:56`:

```kotlin
            DailyRate(it.baseDate, it.value.divide(series.unitDivisor, SCALE, RoundingMode.HALF_UP))
```

`FxRateBackfillServiceTest.kt`의 `EcosRate(` 전부를 `EcosObservation(`으로 바꾼다(13곳).
`repo.saved.single().rateKrw` 같은 단언은 **바꾸지 않는다** — 그건 저장 엔티티의 필드이지 파서 타입이 아니다.

`EcosStatisticSearchClientTest.kt:96`: `result.rates[0].rateKrw` → `result.rates[0].value`

- [ ] **Step 6: 테스트가 통과하는지 확인한다**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*Ecos*' --tests '*FxRateBackfill*' --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx
git commit -m "refactor(af-102): ECOS 파서의 값 검증을 호출자가 정하게 한다"
```

---

### Task 2: 조회 좌표를 묶고 주기를 파라미터로 뺀다

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/EcosApiClient.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/EcosHistoricalRateSource.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/EcosStatisticSearchClientTest.kt`

**왜 하는가:** 현재 클라이언트는 경로에 주기 `D`를 박아 넣는다(`.../$statCode/D/...`).
금리 계열의 주기가 종목마다 다를 수 있어 설정값으로 받아야 한다. 파라미터가 여섯 개가 되므로
좌표를 `EcosQuery`로 묶는다.

**이번엔 `D`만 받는다.** 주기가 바뀌면 요청 날짜 형식도 함께 바뀌고(월별은 `yyyyMM`)
파서의 `TIME` 해석도 바뀐다. 확인되지 않은 주기를 미리 구현하면 검증할 수 없는 코드가 남으므로,
**`D`가 아니면 명시적으로 거부한다** — 조용히 0건이 되는 것보다 낫다. 실제로 다른 주기가
필요하다고 Task 9에서 확인되면 그때 형식 매핑과 함께 넓힌다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`EcosStatisticSearchClientTest.kt`의 70번째 줄 `call` 헬퍼를 아래로 바꾸고,
`fetchDailyRates(...)` 호출 4곳(196·217·284·291)을 `fetch(query, ...)` 형태로 바꾼다.

```kotlin
    private fun query(cycle: String = "D") = EcosQuery(
        statCode = "TEST-STAT-CODE",
        itemCode = "TEST-ITEM-CODE",
        cycle = cycle,
        valuePolicy = EcosValuePolicy.POSITIVE,
    )

    private fun call(port: Int) = client(port).fetch(query(), LocalDate.now(), LocalDate.now())
```

그리고 주기 거부 테스트를 추가한다:

```kotlin
    @Test
    fun `D가 아닌 주기는 호출 전에 거부한다`() {
        assertThatThrownBy { client(9999).fetch(query("M"), LocalDate.now(), LocalDate.now()) }
            .isInstanceOf(EcosApiException::class.java)
            .hasMessageContaining("주기")
    }
```

- [ ] **Step 2: 컴파일 실패를 확인한다**

Run: `cd allfolio-backend && ./gradlew :backend-app:compileTestKotlin --no-daemon`
Expected: FAIL — `Unresolved reference: EcosQuery`

- [ ] **Step 3: 인터페이스를 바꾼다**

`EcosApiClient.kt`의 13~20번째 줄을 아래로 교체한다:

```kotlin
/**
 * ECOS 시계열 하나를 가리키는 좌표.
 *
 * @param cycle ECOS 주기 코드. 현재 지원은 `D`뿐이다 — 다른 주기는 요청 날짜 형식과
 *              응답 `TIME` 형식이 함께 바뀌므로, 확인되지 않은 채 넓히면 조용히 0건이 된다.
 * @param valuePolicy 어떤 값을 받아들일지. 환율과 금리가 다르다 — [EcosValuePolicy] 참조
 */
data class EcosQuery(
    val statCode: String,
    val itemCode: String,
    val cycle: String,
    val valuePolicy: EcosValuePolicy,
)

interface EcosApiClient {
    /** 지정 기간의 통계를 가져온다. 실패하면 예외를 던진다 — 호출자가 기존 값을 지키도록. */
    fun fetch(query: EcosQuery, from: LocalDate, to: LocalDate): EcosParseResult
}
```

- [ ] **Step 4: 구현을 바꾼다**

`EcosApiClient.kt`의 `override fun fetchDailyRates(...)`(95~100번째 줄)를 아래로 바꾸고,
본문에서 `statCode` → `query.statCode`, `itemCode` → `query.itemCode`로 고친다.
경로 조립과 로그, 마지막 `parser.parse` 호출이 바뀐다:

```kotlin
    override fun fetch(query: EcosQuery, from: LocalDate, to: LocalDate): EcosParseResult {
        // 설정 누락은 서버 문제다. IllegalArgumentException으로 던지면 GlobalExceptionHandler가
        // 400 Bad Request로 내보내 클라이언트 잘못처럼 보인다.
        if (properties.apiKey.isBlank()) {
            throw EcosApiException("NO_KEY", "ECOS 인증키가 설정되지 않았습니다 (ECOS_API_KEY)")
        }
        if (query.statCode.isBlank() || query.itemCode.isBlank()) {
            throw EcosApiException("NO_SERIES", "ECOS 통계표·항목 코드가 설정되지 않았습니다")
        }
        // 아래 DATE_FORMAT(yyyyMMdd)과 파서의 TIME 해석이 둘 다 일별 전제다.
        // 다른 주기를 통과시키면 ECOS가 0건을 돌려주고, 그건 "코드가 틀렸다"와 구분되지 않는다.
        if (query.cycle != DAILY_CYCLE) {
            throw EcosApiException("CYCLE", "지원하지 않는 주기입니다: ${query.cycle} (현재 D만 지원)")
        }

        val path = "/api/StatisticSearch/${properties.apiKey}/json/kr/1/$MAX_ROWS/" +
            "${query.statCode}/${query.cycle}/${from.format(DATE_FORMAT)}/${to.format(DATE_FORMAT)}/${query.itemCode}"

        log.info("[ECOS] 조회 statCode={} itemCode={} {}~{}", query.statCode, query.itemCode, from, to)
```

`companion object`에 상수를 더한다:

```kotlin
        private const val DAILY_CYCLE = "D"
```

나머지 본문의 `statCode=` 로그 인자를 `query.statCode`로 바꾸고,
마지막 `parser.parse(body, EcosValuePolicy.POSITIVE)`를 `parser.parse(body, query.valuePolicy)`로 바꾼다
(Task 1에서 임시로 둔 자리다).

- [ ] **Step 5: 환율 호출부를 고친다**

`EcosHistoricalRateSource.kt:41`:

```kotlin
            client.fetch(
                EcosQuery(
                    statCode = series.statCode,
                    itemCode = series.itemCode,
                    cycle = "D",
                    // 0원짜리 환율은 없다. 금리와 달리 부호로 거르는 게 맞다
                    valuePolicy = EcosValuePolicy.POSITIVE,
                ),
                from,
                to,
            )
```

`FxRateBackfillServiceTest.kt`의 `FakeClient`(378~401번째 줄)도 새 시그니처로 바꾼다:

```kotlin
    private open class FakeClient(private val result: EcosParseResult) : EcosApiClient {
        var calls = 0

        override fun fetch(query: EcosQuery, from: LocalDate, to: LocalDate): EcosParseResult {
            calls++
            return result
        }
    }
```

(기존 `FakeClient`가 호출 인자를 기록하고 있으면 그 필드는 그대로 두고 `query.statCode` 등으로 옮겨 담는다.
예외를 던지는 하위 클래스도 같은 시그니처로 바꾼다.)

- [ ] **Step 6: 테스트가 통과하는지 확인한다**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*Ecos*' --tests '*FxRateBackfill*' --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 커밋**

```bash
git add allfolio-backend/backend-app/src
git commit -m "refactor(af-102): ECOS 조회 좌표를 EcosQuery로 묶고 주기를 설정값으로 뺀다"
```

---

### Task 3: `market_rate` 테이블

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/entity/MarketRateEntity.kt`
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/jpa/MarketRateJpaRepository.kt`
- Create: `docs/superpowers/migrations/2026-08-13-market-rate.sql`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/infrastructure/jpa/MarketRateJpaRepositoryTest.kt`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```kotlin
package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.MarketRateEntity
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.test.context.ContextConfiguration
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 금리는 (지표코드, 기준일)로 한 건이다.
 * 지수와 달리 슬롯이 없다 — 공표 기관이 확정한 하루 한 값이고, 응답이 기준일을 직접 준다.
 */
@DataJpaTest
@ContextConfiguration(classes = [MarketRateJpaRepositoryTest.TestConfig::class])
class MarketRateJpaRepositoryTest {

    @Autowired private lateinit var repository: MarketRateJpaRepository

    @Autowired private lateinit var entityManager: EntityManager

    // UNIQUE 제약이 엔티티에 선언돼 있지 않으면 H2에 제약이 아예 안 생겨
    // 중복 삽입이 조용히 커밋된다 — AF-100에서 실제로 물린 함정이다.
    @Test
    fun `같은 지표 같은 날은 두 번 못 들어간다`() {
        save(rate("KTB_3Y", LocalDate.of(2026, 8, 12), "3.15"))

        assertThatThrownBy { save(rate("KTB_3Y", LocalDate.of(2026, 8, 12), "3.20")) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `지표가 다르면 같은 날에도 들어간다`() {
        save(rate("KTB_3Y", LocalDate.of(2026, 8, 12), "3.15"))
        save(rate("KTB_10Y", LocalDate.of(2026, 8, 12), "3.40"))

        assertThat(repository.findAll()).hasSize(2)
    }

    @Test
    fun `구간 조회는 경계를 포함한다`() {
        save(rate("KTB_3Y", LocalDate.of(2026, 8, 10), "3.10"))
        save(rate("KTB_3Y", LocalDate.of(2026, 8, 11), "3.12"))
        save(rate("KTB_3Y", LocalDate.of(2026, 8, 12), "3.15"))
        save(rate("KTB_10Y", LocalDate.of(2026, 8, 11), "3.40"))

        val found = repository.findByRateCodeAndQuoteDateBetween(
            "KTB_3Y", LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 12),
        )

        assertThat(found).hasSize(3)
        assertThat(found.map { it.rateCode }).containsOnly("KTB_3Y")
    }

    @Test
    fun `마이너스 금리도 저장된다`() {
        save(rate("CALL_ON", LocalDate.of(2026, 8, 12), "-0.25"))

        assertThat(repository.findAll().single().rateValue).isEqualByComparingTo("-0.25")
    }

    private fun save(entity: MarketRateEntity) {
        repository.saveAndFlush(entity)
        entityManager.clear()
    }

    private fun rate(code: String, date: LocalDate, value: String) = MarketRateEntity(
        id = UUID.randomUUID(),
        rateCode = code,
        quoteDate = date,
        rateValue = BigDecimal(value),
        source = "ECOS",
        collectedAt = LocalDateTime.of(2026, 8, 12, 18, 10),
    )

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = [MarketRateEntity::class])
    @EnableJpaRepositories(basePackageClasses = [MarketRateJpaRepository::class])
    class TestConfig
}
```

- [ ] **Step 2: 컴파일 실패를 확인한다**

Run: `cd allfolio-backend && ./gradlew :unified-asset:compileTestKotlin --no-daemon`
Expected: FAIL — `Unresolved reference: MarketRateEntity`

- [ ] **Step 3: 엔티티를 만든다**

```kotlin
package com.allfolio.unifiedasset.infrastructure.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 금리 한 건 (AF-102).
 *
 * **키가 `(지표코드, 기준일)`인 이유**: 지수는 장중에 값이 변해 스케줄 지점(슬롯)이 키였지만,
 * 금리는 공표 기관이 확정한 하루 한 값이고 ECOS 응답이 기준일(`TIME`)을 직접 준다.
 * 슬롯을 넣으면 같은 값이 슬롯 수만큼 복제된다.
 *
 * **전일대비(bp)와 스프레드는 담지 않는다.** 파생값이라 원본이 정정될 때 같이 안 고쳐져
 * 화석이 된다 — 그리고 ECOS는 정정한다. 조회 시 직전 행과 비교해 계산한다.
 *
 * 컬럼 이름이 `value`가 아니라 `rate_value`인 이유: `VALUE`는 SQL 예약어 계열이라
 * DB·드라이버마다 인용부호 요구가 갈린다. 값어치 없는 위험이다.
 *
 * `rateValue`·`collectedAt`이 var인 이유: 같은 날을 다시 수집하면 값만 덮기 때문.
 */
@Entity
@Table(
    name = "market_rate",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_market_rate", columnNames = ["rate_code", "quote_date"]),
    ],
)
class MarketRateEntity(
    @Id val id: UUID,
    @Column(name = "rate_code", nullable = false, length = 20) val rateCode: String,
    @Column(name = "quote_date", nullable = false) val quoteDate: LocalDate,
    /** 연 %. 마이너스 금리가 실재하므로 부호를 제한하지 않는다 */
    @Column(name = "rate_value", nullable = false, precision = 9, scale = 4) var rateValue: BigDecimal,
    @Column(name = "source", nullable = false, length = 20) val source: String,
    @Column(name = "collected_at", nullable = false) var collectedAt: LocalDateTime,
)
```

- [ ] **Step 4: 레포지토리를 만든다**

```kotlin
package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.MarketRateEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

interface MarketRateJpaRepository : JpaRepository<MarketRateEntity, UUID> {

    /**
     * 그 지표의 구간 내 기존 행. 수집은 구간을 통째로 받아 덮으므로 한 번에 읽는다 —
     * 행마다 조회하면 2주 x 6종목이 84번의 왕복이 된다(Neon은 원격이다).
     */
    fun findByRateCodeAndQuoteDateBetween(
        rateCode: String,
        from: LocalDate,
        to: LocalDate,
    ): List<MarketRateEntity>
}
```

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests '*MarketRate*' --no-daemon`
Expected: BUILD SUCCESSFUL (4 tests)

- [ ] **Step 6: 마이그레이션 SQL을 쓴다**

`docs/superpowers/migrations/2026-08-13-market-rate.sql`:

```sql
-- AF-102 금리 수집 (한국 ECOS)
-- ddl-auto: none 이므로 Neon에 직접 적용한다. 재실행 가능하게 IF NOT EXISTS로 쓴다.

CREATE TABLE IF NOT EXISTS market_rate (
    id           uuid         NOT NULL,
    rate_code    varchar(20)  NOT NULL,
    quote_date   date         NOT NULL,
    rate_value   numeric(9,4) NOT NULL,
    source       varchar(20)  NOT NULL,
    collected_at timestamp    NOT NULL,

    CONSTRAINT pk_market_rate PRIMARY KEY (id),
    CONSTRAINT uk_market_rate UNIQUE (rate_code, quote_date)
);

-- 최신값 조회와 구간 조회 양쪽에 듣는다
CREATE INDEX IF NOT EXISTS idx_market_rate_lookup
    ON market_rate (rate_code, quote_date DESC);

-- 적용 확인
SELECT COUNT(*) AS rows FROM market_rate;
```

- [ ] **Step 7: 커밋**

```bash
git add allfolio-backend/unified-asset docs/superpowers/migrations/2026-08-13-market-rate.sql
git commit -m "feat(af-102): market_rate 테이블 — (지표코드, 기준일) 한 건"
```

---

### Task 4: 수집 대상 설정

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/rate/MarketRateProperties.kt`
- Modify: `allfolio-backend/backend-app/src/main/resources/application.yml`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/rate/MarketRatePropertiesTest.kt`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```kotlin
package com.allfolio.market.rate

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * 설정 키가 어긋나면 목록이 조용히 비고, 수집은 "대상 0건"으로 끝난다.
 * 그 실패는 로그에만 남아서, 바인딩 자체를 테스트로 못 박는다.
 */
class MarketRatePropertiesTest {

    private val runner = ApplicationContextRunner()
        .withUserConfiguration(TestConfig::class.java)

    @Test
    fun `series 목록을 바인딩한다`() {
        runner.withPropertyValues(
            "market-rate.series[0].code=KTB_3Y",
            "market-rate.series[0].stat-code=721Y001",
            "market-rate.series[0].item-code=5030000",
            "market-rate.series[0].cycle=D",
        ).run { context ->
            val series = context.getBean(MarketRateProperties::class.java).series
            assertThat(series).hasSize(1)
            assertThat(series[0].code).isEqualTo("KTB_3Y")
            assertThat(series[0].statCode).isEqualTo("721Y001")
            assertThat(series[0].itemCode).isEqualTo("5030000")
            assertThat(series[0].cycle).isEqualTo("D")
        }
    }

    @Test
    fun `설정이 없으면 빈 목록이다`() {
        runner.run { context ->
            assertThat(context.getBean(MarketRateProperties::class.java).series).isEmpty()
        }
    }

    /**
     * 오타난 설정은 기동을 실패시킨다. 런타임에 종목별 실패로 흘리면 매일 실패 한 줄이
     * 쌓일 뿐이고, 그 사이 그 종목은 비어 있다. `EcosProperties.Series`가 unit-divisor에
     * 같은 판단을 한다 — 바인딩 시점에 막는다.
     */
    @Test
    fun `코드가 비어 있으면 기동에 실패한다`() {
        runner.withPropertyValues(
            "market-rate.series[0].code=KTB_3Y",
            "market-rate.series[0].stat-code=",
            "market-rate.series[0].item-code=5030000",
        ).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasMessageContaining("KTB_3Y")
        }
    }

    @Test
    fun `지원하지 않는 주기는 기동에 실패한다`() {
        runner.withPropertyValues(
            "market-rate.series[0].code=BASE_RATE",
            "market-rate.series[0].stat-code=722Y001",
            "market-rate.series[0].item-code=0101000",
            "market-rate.series[0].cycle=M",
        ).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasMessageContaining("주기")
        }
    }

    @EnableConfigurationProperties(MarketRateProperties::class)
    class TestConfig
}
```

> `721Y001`·`5030000`·`722Y001`·`0101000`은 **바인딩이 되는지만 보는 더미 문자열이다.**
> 실제 코드는 Task 9에서 탐색 엔드포인트로 확인해 넣는다 — 이 값을 설정에 옮겨 적지 말 것.

- [ ] **Step 2: 컴파일 실패를 확인한다**

Run: `cd allfolio-backend && ./gradlew :backend-app:compileTestKotlin --no-daemon`
Expected: FAIL — `Unresolved reference: MarketRateProperties`

- [ ] **Step 3: 설정 클래스를 만든다**

```kotlin
package com.allfolio.market.rate

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * 금리 수집 대상 (AF-102).
 *
 * **맵이 아니라 리스트인 이유**: `EcosProperties.series`는 통화별 맵이라 대문자 키를
 * 환경변수로 표현할 수 없는 문제를 안고 있다(relaxed binding이 소문자화한다).
 * 여기서는 코드가 값이므로 그 문제가 아예 생기지 않는다.
 *
 * **미확인 종목은 빈 코드로 두지 말고 목록에서 뺀다.** 빈 코드를 넣으면 대상 수에는 잡히고
 * 매일 실패로 남지만, 빼면 대상 수 자체가 줄어 "아직 안 넣었다"는 사실이 그대로 드러난다.
 */
@Component
@ConfigurationProperties(prefix = "market-rate")
class MarketRateProperties {
    var series: List<RateSeries> = emptyList()

    class RateSeries {
        /** 우리가 정한 canonical 코드. DB의 rate_code가 된다 */
        var code: String = ""
        /** ECOS 통계표 코드 */
        var statCode: String = ""
        /** ECOS 항목 코드 */
        var itemCode: String = ""
        /** ECOS 주기 코드. 현재 지원은 D뿐이다 */
        var cycle: String = "D"
    }

    /**
     * 오타난 설정으로는 기동하지 않는다.
     *
     * 런타임 실패로 흘리면 매일 실패 한 줄이 쌓일 뿐이고 그 종목은 계속 비어 있다.
     * `EcosProperties.Series`가 `unit-divisor`에 같은 판단을 한다 — 바인딩 시점에 막는다.
     *
     * **`init` 블록으로는 안 된다.** 이 클래스는 setter 바인딩(`var`)이라 생성자가
     * 빈 값으로 먼저 돌고 나서 프로퍼티가 채워진다 — `EcosProperties.Series`가 쓰는
     * `require`가 여기서는 항상 빈 값에 대해 돈다. 바인딩이 끝난 뒤인 `@PostConstruct`여야 한다.
     */
    @PostConstruct
    fun validate() {
        val problems = series.flatMap { s ->
            val label = s.code.ifBlank { "(code 없음)" }
            buildList {
                if (s.code.isBlank()) add("code가 비어 있습니다")
                if (s.statCode.isBlank()) add("$label: stat-code가 비어 있습니다")
                if (s.itemCode.isBlank()) add("$label: item-code가 비어 있습니다")
                // 클라이언트도 같은 검사를 하지만 그건 호출 시점이라 종목별 실패로 흩어진다.
                // 여기서 막으면 배포가 실패해 사람이 즉시 본다
                if (s.cycle != "D") add("$label: 지원하지 않는 주기입니다: ${s.cycle} (현재 D만 지원)")
            }
        }
        require(problems.isEmpty()) { "market-rate.series 설정이 올바르지 않습니다 — " + problems.joinToString("; ") }
    }
}
```

임포트: `jakarta.annotation.PostConstruct`

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*MarketRateProperties*' --no-daemon`
Expected: BUILD SUCCESSFUL (4 tests)

- [ ] **Step 5: `application.yml`에 빈 블록을 둔다**

`market-index:` 블록(245번째 줄) 바로 뒤에 넣는다:

```yaml
# 금리 수집 대상 (AF-102)
#
# **비어 있는 것은 미완이지 실수가 아니다.** ECOS 통계표·항목 코드는 사이트에서 확인한 값만
# 넣는다 — 추정한 코드는 오류가 아니라 0건을 돌려주고, 그러면 "코드가 틀렸는지 기간이 빈 건지"
# 구분할 방법이 없다. 확인은 GET /api/admin/rate/ecos/tables 로 한다.
#
# 대상이 0건이면 수집 엔드포인트가 500을 낸다. 그래서 이 목록을 채우기 전에는
# collect-rate.yml에 cron을 넣지 않는다.
market-rate:
  series: []
```

- [ ] **Step 6: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/rate allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/rate allfolio-backend/backend-app/src/main/resources/application.yml
git commit -m "feat(af-102): 금리 수집 대상 설정 — 확인한 코드만 넣는다"
```

---

### Task 5: 수집 서비스

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/rate/RateCollectService.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/rate/RateCollectServiceTest.kt`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```kotlin
package com.allfolio.market.rate

import com.allfolio.fx.EcosApiException
import com.allfolio.fx.EcosObservation
import com.allfolio.fx.EcosApiClient
import com.allfolio.fx.EcosParseResult
import com.allfolio.fx.EcosQuery
import com.allfolio.unifiedasset.infrastructure.entity.MarketRateEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

class RateCollectServiceTest {

    private val from = LocalDate.of(2026, 8, 10)
    private val to = LocalDate.of(2026, 8, 12)
    private val now = LocalDateTime.of(2026, 8, 12, 9, 10)

    @Test
    fun `종목별로 조회해 저장하고 건수를 보고한다`() {
        val repo = FakeRepo()
        val client = FakeClient(
            mapOf(
                "S1" to listOf(obs("2026-08-11", "3.10"), obs("2026-08-12", "3.15")),
                "S2" to listOf(obs("2026-08-12", "3.40")),
            ),
        )

        val summary = service(client, repo, series("KTB_3Y", "S1"), series("KTB_10Y", "S2")).collect(from, to, now)

        assertThat(summary.requested).isEqualTo(2)
        assertThat(summary.inserted).isEqualTo(3)
        assertThat(summary.updated).isZero()
        assertThat(summary.failed).isZero()
        assertThat(repo.saved).hasSize(3)
    }

    /**
     * 종목 하나가 터져도 나머지를 저장한다. 예외로 끝내면 살아 있던 값까지 같이 잃는다.
     */
    @Test
    fun `한 종목이 실패해도 나머지는 저장한다`() {
        val repo = FakeRepo()
        val client = FakeClient(
            mapOf("S2" to listOf(obs("2026-08-12", "3.40"))),
            failing = mapOf("S1" to EcosApiException("HTTP-500", "ECOS가 HTTP 500 을 반환했습니다")),
        )

        val summary = service(client, repo, series("KTB_3Y", "S1"), series("KTB_10Y", "S2")).collect(from, to, now)

        assertThat(summary.requested).isEqualTo(2)
        assertThat(summary.collected).isEqualTo(1)
        assertThat(summary.failed).isEqualTo(1)
        assertThat(summary.failures.single()).contains("KTB_3Y").contains("HTTP 500")
        assertThat(repo.saved.single().rateCode).isEqualTo("KTB_10Y")
    }

    /**
     * 같은 구간을 다시 수집하면 행이 늘지 않고 값만 덮인다.
     * 수집 창이 매번 2주를 재조회하므로 이게 깨지면 매일 행이 불어난다.
     */
    @Test
    fun `같은 구간을 다시 수집하면 덮어쓴다`() {
        val repo = FakeRepo()
        repo.saved += entity("KTB_3Y", LocalDate.of(2026, 8, 12), "3.10")
        val client = FakeClient(mapOf("S1" to listOf(obs("2026-08-12", "3.15"))))

        val summary = service(client, repo, series("KTB_3Y", "S1")).collect(from, to, now)

        assertThat(summary.inserted).isZero()
        assertThat(summary.updated).isEqualTo(1)
        assertThat(repo.saved).hasSize(1)
        assertThat(repo.saved.single().rateValue).isEqualByComparingTo("3.15")
        assertThat(repo.saved.single().collectedAt).isEqualTo(now)
    }

    @Test
    fun `버려진 행 수를 보고한다`() {
        val repo = FakeRepo()
        val client = FakeClient(
            mapOf("S1" to listOf(obs("2026-08-12", "3.15"))),
            skipped = mapOf("S1" to 2),
        )

        val summary = service(client, repo, series("KTB_3Y", "S1")).collect(from, to, now)

        assertThat(summary.skippedRows).isEqualTo(2)
    }

    /**
     * 소스가 구간 밖 날짜를 섞어 주면 걷어낸다. 안 걷어내면 그 행이 새 UUID로 INSERT되어
     * 유니크 제약이 배치 전체를 죽인다 — 재실행해도 똑같이 죽는다.
     */
    @Test
    fun `요청 구간 밖 날짜는 걷어내고 센다`() {
        val repo = FakeRepo()
        val client = FakeClient(
            mapOf(
                "S1" to listOf(
                    obs("2026-08-11", "3.10"),
                    obs("2026-08-20", "3.30"), // to(8/12) 이후
                    obs("2026-08-01", "3.05"), // from(8/10) 이전
                ),
            ),
        )

        val summary = service(client, repo, series("KTB_3Y", "S1")).collect(from, to, now)

        assertThat(summary.outOfRange).isEqualTo(2)
        assertThat(summary.inserted).isEqualTo(1)
        assertThat(repo.saved.single().quoteDate).isEqualTo(LocalDate.of(2026, 8, 11))
    }

    /**
     * 0건은 실패가 아니다 — 기준금리처럼 변경 시에만 공표되는 계열은 2주 창이 빌 수 있다.
     * 다만 코드가 죽어도 똑같이 0건이라, 이름을 남겨 사람이 보게 한다.
     */
    @Test
    fun `0건으로 돌아온 종목은 실패가 아니라 이름으로 남는다`() {
        val repo = FakeRepo()
        val client = FakeClient(mapOf("S1" to emptyList(), "S2" to listOf(obs("2026-08-12", "3.40"))))

        val summary = service(client, repo, series("BASE_RATE", "S1"), series("KTB_10Y", "S2")).collect(from, to, now)

        assertThat(summary.emptySeries).containsExactly("BASE_RATE")
        assertThat(summary.failed).isZero()
        assertThat(summary.collected).isEqualTo(1)
    }

    @Test
    fun `대상이 없으면 요청 0건으로 끝난다`() {
        val summary = service(FakeClient(emptyMap()), FakeRepo()).collect(from, to, now)

        assertThat(summary.requested).isZero()
        assertThat(summary.collected).isZero()
    }

    @Test
    fun `금리 정책으로 조회한다`() {
        val client = FakeClient(mapOf("S1" to emptyList()))

        service(client, FakeRepo(), series("KTB_3Y", "S1")).collect(from, to, now)

        // 환율 정책으로 부르면 0.00% 공표일이 조용히 사라진다
        assertThat(client.queries.single().valuePolicy).isEqualTo(com.allfolio.fx.EcosValuePolicy.PERCENT)
        assertThat(client.queries.single().cycle).isEqualTo("D")
    }

    private fun service(
        client: EcosApiClient,
        repo: FakeRepo,
        vararg series: MarketRateProperties.RateSeries,
    ): RateCollectService {
        val properties = MarketRateProperties().apply { this.series = series.toList() }
        return RateCollectService(client, repo, properties)
    }

    private fun series(code: String, statCode: String) = MarketRateProperties.RateSeries().apply {
        this.code = code
        this.statCode = statCode
        this.itemCode = "ITEM"
        this.cycle = "D"
    }

    private fun obs(date: String, value: String) = EcosObservation(LocalDate.parse(date), BigDecimal(value))

    private fun entity(code: String, date: LocalDate, value: String) = MarketRateEntity(
        id = java.util.UUID.randomUUID(),
        rateCode = code,
        quoteDate = date,
        rateValue = BigDecimal(value),
        source = "ECOS",
        collectedAt = LocalDateTime.of(2026, 8, 11, 18, 10),
    )

    /** statCode로 응답을 가른다 — 종목마다 다른 결과를 주려면 그 축이 필요하다 */
    private class FakeClient(
        private val rows: Map<String, List<EcosObservation>>,
        private val failing: Map<String, RuntimeException> = emptyMap(),
        private val skipped: Map<String, Int> = emptyMap(),
    ) : EcosApiClient {
        val queries = mutableListOf<EcosQuery>()

        override fun fetch(query: EcosQuery, from: LocalDate, to: LocalDate): EcosParseResult {
            queries += query
            failing[query.statCode]?.let { throw it }
            return EcosParseResult(rows[query.statCode] ?: emptyList(), skipped[query.statCode] ?: 0)
        }
    }

    /**
     * 인메모리 레포. JPA 레포 인터페이스 전체를 구현하지 않으려고 서비스가 쓰는 두 메서드만
     * 가진 좁은 포트를 둔다 — 그 포트가 RateCollectService.Store 다.
     */
    private class FakeRepo : RateCollectService.Store {
        val saved = mutableListOf<MarketRateEntity>()

        override fun findRange(rateCode: String, from: LocalDate, to: LocalDate): List<MarketRateEntity> =
            saved.filter { it.rateCode == rateCode && it.quoteDate >= from && it.quoteDate <= to }

        override fun saveAll(entities: List<MarketRateEntity>) {
            entities.forEach { entity ->
                if (saved.none { it.rateCode == entity.rateCode && it.quoteDate == entity.quoteDate }) {
                    saved += entity
                }
            }
        }
    }
}
```

- [ ] **Step 2: 컴파일 실패를 확인한다**

Run: `cd allfolio-backend && ./gradlew :backend-app:compileTestKotlin --no-daemon`
Expected: FAIL — `Unresolved reference: RateCollectService`

- [ ] **Step 3: 서비스를 만든다**

```kotlin
package com.allfolio.market.rate

import com.allfolio.fx.EcosApiClient
import com.allfolio.fx.EcosQuery
import com.allfolio.fx.EcosValuePolicy
import com.allfolio.unifiedasset.infrastructure.entity.MarketRateEntity
import com.allfolio.unifiedasset.infrastructure.jpa.MarketRateJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 금리 수집 한 번의 결과.
 *
 * @param requested 설정에 있는 종목 수. **0이면 설정이 빈 것이지 ECOS 문제가 아니다**
 * @param skippedRows 값·날짜가 이상해 파서가 버린 행 수. 0이 아니면 형식이 바뀐 신호다
 * @param outOfRange 요청 구간 밖 날짜라 걷어낸 행 수. 아래 필터 주석 참조
 * @param emptySeries 0건으로 돌아온 종목. **실패가 아니다** — 기준금리처럼 변경 시에만
 *                    공표되는 계열은 2주 창에 값이 없는 게 정상이다. 다만 코드가 죽어도
 *                    똑같이 0건이라, 어느 쪽인지는 사람이 봐야 한다. 그래서 세지 말고 이름을 남긴다
 * @param failures "KTB_3Y: <사유>" 형태. 어느 종목이 왜 빠졌는지 한 번에 보여야 한다
 */
data class RateCollectSummary(
    val from: LocalDate,
    val to: LocalDate,
    val requested: Int,
    val collected: Int,
    val inserted: Int,
    val updated: Int,
    val skippedRows: Int,
    val outOfRange: Int,
    val emptySeries: List<String>,
    val failed: Int,
    val failures: List<String>,
)

/**
 * 금리 수집 (AF-102).
 *
 * 일일 수집과 백필이 같은 경로를 쓴다 — 둘 다 "이 구간을 ECOS가 준 값으로 맞춘다"이고 멱등하다.
 * 스케줄 실행이 매번 최근 2주를 다시 조회하는 이유는 셋이다:
 * 공표가 밀리는 계열이 있고, ECOS는 값을 정정하며, 잡이 하루 실패해도 다음 날이 메운다.
 *
 * `@Transactional`을 붙이지 않는다 — 종목마다 HTTP 호출이 하나씩 있어서 트랜잭션에 넣으면
 * 루프가 끝날 때까지 Neon 커넥션을 쥐고 앉아 있게 된다. AF-101 지수 수집과 같은 이유다.
 */
@Service
class RateCollectService(
    private val client: EcosApiClient,
    private val store: Store,
    private val properties: MarketRateProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 저장에 필요한 것만 추린 좁은 포트.
     *
     * 서비스가 `JpaRepository`를 통째로 받으면 테스트가 스무 개 넘는 메서드를 구현하거나
     * 목으로 덮어야 한다. 실제로 쓰는 건 둘뿐이다.
     */
    interface Store {
        fun findRange(rateCode: String, from: LocalDate, to: LocalDate): List<MarketRateEntity>
        fun saveAll(entities: List<MarketRateEntity>)
    }

    companion object {
        private const val SOURCE = "ECOS"
    }

    fun collect(from: LocalDate, to: LocalDate, now: LocalDateTime): RateCollectSummary {
        require(!from.isAfter(to)) { "from이 to보다 늦습니다: $from ~ $to" }

        var inserted = 0
        var updated = 0
        var skippedRows = 0
        var outOfRange = 0
        val emptySeries = mutableListOf<String>()
        val failures = mutableListOf<String>()

        for (series in properties.series) {
            try {
                val result = client.fetch(
                    EcosQuery(
                        statCode = series.statCode,
                        itemCode = series.itemCode,
                        cycle = series.cycle,
                        // 금리는 0.00%도 마이너스도 실재한다 — 환율 정책으로 부르면 그 날이 사라진다
                        valuePolicy = EcosValuePolicy.PERCENT,
                    ),
                    from,
                    to,
                )
                skippedRows += result.skipped

                // 요청 구간 밖 날짜를 먼저 걷어낸다. 파서는 날짜만 파싱되면 통과시키므로
                // 소스가 구간 밖 날짜를 섞어 줄 수 있는데, 아래 existing 조회는 from..to로 한정된다 —
                // 그 날짜 행이 이미 테이블에 있으면(2주 창이 매일 겹치므로 반드시 있다) existing에서
                // 안 잡혀 새 UUID로 INSERT가 나가고 uk_market_rate가 배치 전체를 죽인다.
                // 재실행해도 똑같이 실패하고 운영자에게는 불투명한 제약 위반만 남는다.
                // AF-100의 FxRateBackfillService가 같은 방어를 한다 — 겪고 나서 생긴 것이다.
                val inRange = result.rates.filter { it.baseDate in from..to }
                outOfRange += result.rates.size - inRange.size

                // **0건을 실패로 만들지 않는다.** 기준금리처럼 변경 시에만 공표되는 계열은
                // 2주 창에 값이 없는 게 정상이다. 다만 통계표 코드가 죽어도 똑같이 0건이라
                // 자동으로는 못 가른다 — 이름을 남겨 사람이 보게 한다.
                if (inRange.isEmpty()) emptySeries += series.code

                val existing = store.findRange(series.code, from, to).associateBy { it.quoteDate }
                val toInsert = mutableListOf<MarketRateEntity>()

                for (row in inRange) {
                    val prior = existing[row.baseDate]
                    if (prior == null) {
                        toInsert += MarketRateEntity(
                            id = UUID.randomUUID(),
                            rateCode = series.code,
                            quoteDate = row.baseDate,
                            rateValue = row.value,
                            source = SOURCE,
                            collectedAt = now,
                        )
                    } else {
                        // 값이 같아도 collectedAt은 갱신한다 — "언제 확인한 값인가"가 화면에 나간다.
                        // source도 다시 쓴다: 같은 지표를 다른 소스에서 재수집하는 날
                        // (FRED가 후속으로 붙는다) 첫 수집 소스가 그대로 굳으면,
                        // 정정된 값을 설명하려고 들여다볼 바로 그 필드가 거짓말을 한다
                        prior.rateValue = row.value
                        prior.source = SOURCE
                        prior.collectedAt = now
                        updated++
                    }
                }

                // 같은 응답에 같은 날짜가 두 번 오면 유니크 제약에 걸린다. 마지막 값을 남긴다 —
                // ECOS 정정본이 뒤에 오는 형태이기 때문이다
                val deduped = toInsert.associateBy { it.quoteDate }.values.toList()
                store.saveAll(deduped + existing.values.filter { it.collectedAt == now })
                inserted += deduped.size
            } catch (e: Exception) {
                // 한 종목의 실패가 나머지를 끌고 가지 않는다
                failures += "${series.code}: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        val summary = RateCollectSummary(
            from = from,
            to = to,
            requested = properties.series.size,
            collected = inserted + updated,
            inserted = inserted,
            updated = updated,
            skippedRows = skippedRows,
            outOfRange = outOfRange,
            emptySeries = emptySeries,
            failed = failures.size,
            failures = failures,
        )

        when {
            properties.series.isEmpty() ->
                log.warn("[금리] 설정된 수집 대상이 없습니다 — market-rate.series 확인")
            failures.isEmpty() -> log.info("[금리] 수집 완료 {}", summary)
            else -> log.warn("[금리] 일부 실패 {}", summary)
        }
        return summary
    }
}

/**
 * JPA 레포를 [RateCollectService.Store]에 맞춘다.
 *
 * 서비스가 JPA 인터페이스를 직접 받지 않게 하는 얇은 층이다 — 테스트가 스무 개 넘는
 * 상속 메서드를 흉내 내지 않아도 되게 하는 것이 목적이고, 다른 의도는 없다.
 */
@Component
class JpaRateStore(private val repository: MarketRateJpaRepository) : RateCollectService.Store {
    override fun findRange(rateCode: String, from: LocalDate, to: LocalDate) =
        repository.findByRateCodeAndQuoteDateBetween(rateCode, from, to)

    override fun saveAll(entities: List<MarketRateEntity>) {
        repository.saveAll(entities)
    }
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*RateCollectService*' --no-daemon`
Expected: BUILD SUCCESSFUL (8 tests)

수정한 행이 실제로 저장되는지는 `existing.values.filter { it.collectedAt == now }`가 책임진다.
테스트 `같은 구간을 다시 수집하면 덮어쓴다`가 이걸 잡는다 — 실패하면 갱신분이 저장되지 않은 것이다.

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/rate allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/rate
git commit -m "feat(af-102): 금리 수집 서비스 — 부분 실패를 살리고 구간을 멱등하게 덮는다"
```

---

### Task 6: 탐색 클라이언트 + 어드민 엔드포인트

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/EcosStatListClient.kt`
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/api/admin/MarketRateAdminController.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/api/admin/MarketRateAdminControllerTest.kt`

**경로 확인:** ECOS 목록 API 경로(`StatisticTableList` / `StatisticItemList`)는
**개발가이드에서 확인한 뒤 상수로 박는다.** 경로가 틀리면 ECOS가 `RESULT.CODE`로 오류를 돌려주는데,
이 엔드포인트는 응답을 그대로 흘려보내므로 첫 호출에서 바로 드러난다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```kotlin
package com.allfolio.api.admin

import com.allfolio.fx.EcosApiException
import com.allfolio.fx.EcosStatListClient
import com.allfolio.market.rate.RateCollectService
import com.allfolio.market.rate.RateCollectSummary
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.ArgumentMatchers.any
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.LocalDateTime

class MarketRateAdminControllerTest {

    private val service: RateCollectService = mock(RateCollectService::class.java)
    private val statList: EcosStatListClient = mock(EcosStatListClient::class.java)
    private val controller = MarketRateAdminController(service, statList)

    @Test
    fun `수집 대상이 없으면 500이다`() {
        stub(summary(requested = 0, collected = 0))

        assertThatThrownBy { controller.collect(null, null) }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("market-rate.series")
    }

    /**
     * 요청은 있었는데 한 건도 못 건진 경우다. 200으로 내보내면 크론 잡이 초록으로 끝나고
     * 금리가 끊긴 걸 아무도 모른다. 502인 이유는 상류(ECOS) 문제이기 때문이다.
     */
    @Test
    fun `전멸은 502다`() {
        stub(summary(requested = 6, collected = 0, failed = 6, failures = listOf("KTB_3Y: HTTP 500")))

        assertThatThrownBy { controller.collect(null, null) }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("BAD_GATEWAY")
            .hasMessageContaining("한 건도")
    }

    /** 부분 실패까지 빨갛게 칠하면 매일 빨간 잡을 보게 되고, 그러면 진짜 전멸도 안 보인다 */
    @Test
    fun `부분 실패는 200이다`() {
        stub(summary(requested = 6, collected = 5, failed = 1, failures = listOf("CD_91D: HTTP 500")))

        val response = controller.collect(null, null)

        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.body?.failed).isEqualTo(1)
    }

    @Test
    fun `날짜를 안 주면 최근 2주를 조회한다`() {
        stub(summary(requested = 6, collected = 6))

        controller.collect(null, null)

        val captor = org.mockito.ArgumentCaptor.forClass(LocalDate::class.java)
        org.mockito.Mockito.verify(service).collect(
            captor.capture(),
            org.mockito.ArgumentMatchers.any(LocalDate::class.java) ?: LocalDate.EPOCH,
            org.mockito.ArgumentMatchers.any(LocalDateTime::class.java) ?: LocalDateTime.MIN,
        )
        // KST 오늘에서 14일 전. 정확한 날짜는 시계에 달렸으므로 간격만 본다
        assertThat(captor.value).isBefore(LocalDate.now())
    }

    @Test
    fun `ECOS 오류는 502로 나간다`() {
        `when`(statList.tables(any() ?: "")).thenThrow(EcosApiException("HTTP-500", "ECOS가 HTTP 500 을 반환했습니다"))

        assertThatThrownBy { controller.tables("국고채") }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("BAD_GATEWAY")
    }

    private fun stub(summary: RateCollectSummary) {
        `when`(
            service.collect(
                any(LocalDate::class.java) ?: LocalDate.EPOCH,
                any(LocalDate::class.java) ?: LocalDate.EPOCH,
                any(LocalDateTime::class.java) ?: LocalDateTime.MIN,
            ),
        ).thenReturn(summary)
    }

    private fun summary(
        requested: Int,
        collected: Int,
        failed: Int = 0,
        failures: List<String> = emptyList(),
    ) = RateCollectSummary(
        from = LocalDate.of(2026, 7, 30),
        to = LocalDate.of(2026, 8, 13),
        requested = requested,
        collected = collected,
        inserted = collected,
        updated = 0,
        skippedRows = 0,
        outOfRange = 0,
        emptySeries = emptyList(),
        failed = failed,
        failures = failures,
    )
}
```

- [ ] **Step 2: 컴파일 실패를 확인한다**

Run: `cd allfolio-backend && ./gradlew :backend-app:compileTestKotlin --no-daemon`
Expected: FAIL — `Unresolved reference: MarketRateAdminController`

- [ ] **Step 3: 탐색 클라이언트를 만든다**

```kotlin
package com.allfolio.fx

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration

/**
 * ECOS 통계표·항목 **목록** 조회 (AF-102).
 *
 * 수집이 아니라 **코드 확인용**이다. ECOS는 틀린 통계표·항목 코드에 오류가 아니라 0건을 준다 —
 * 그래서 코드를 추정해 넣으면 "코드가 틀렸는지 기간이 빈 건지" 영영 구분할 수 없다.
 * 로컬에는 인증키가 없고 Render에만 있으므로, 배포된 서버를 통하는 이 경로가
 * 사람이 사이트를 뒤지지 않는 유일한 확인 방법이다.
 *
 * **응답을 파싱하지 않고 그대로 돌려준다.** 파싱하면 우리가 기대한 모양만 보이는데,
 * 이 도구의 목적은 기대가 맞는지 확인하는 것이다. 오류 응답(RESULT)도 그대로 보여야
 * 경로가 틀렸다는 사실이 첫 호출에서 드러난다.
 *
 * [EcosStatisticSearchClient]와 합치지 않는 이유: 그쪽은 응답을 파서에 넘겨 도메인 타입으로
 * 바꾸는 것이 일이고, 이쪽은 바꾸지 않는 것이 일이다.
 */
@Component
class EcosStatListClient(
    private val properties: EcosProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(properties.baseUrl)
            .codecs { it.defaultCodecs().maxInMemorySize(8 * 1024 * 1024) }
            .build()
    }

    companion object {
        private val TIMEOUT = Duration.ofSeconds(30)
        private const val MAX_ROWS = 10_000
    }

    /** 통계표 목록. [statCode]를 주면 그 하위만 본다 */
    fun tables(statCode: String?): String =
        call("/api/StatisticTableList/${properties.apiKey}/json/kr/1/$MAX_ROWS" + (statCode?.let { "/$it" } ?: ""))

    /** 통계표 하나의 항목 목록 */
    fun items(statCode: String): String =
        call("/api/StatisticItemList/${properties.apiKey}/json/kr/1/$MAX_ROWS/$statCode")

    private fun call(path: String): String {
        if (properties.apiKey.isBlank()) {
            throw EcosApiException("NO_KEY", "ECOS 인증키가 설정되지 않았습니다 (ECOS_API_KEY)")
        }
        // 인증키가 경로 첫 세그먼트에 있다. 전체 URL을 로그에 찍지 않는다
        log.info("[ECOS] 목록 조회")
        return try {
            webClient.get().uri(path).retrieve().bodyToMono(String::class.java).block(TIMEOUT)
                ?: throw EcosApiException("EMPTY", "응답 본문이 비어 있습니다")
        } catch (e: EcosApiException) {
            throw e
        } catch (e: Throwable) {
            if (e is Error) throw e
            if (e is InterruptedException || e.cause is InterruptedException) Thread.currentThread().interrupt()
            // 예외 메시지·스택에 인증키가 박힌 URI가 들어 있다. 갈아끼우고 cause도 붙이지 않는다 —
            // EcosStatisticSearchClient가 같은 이유로 같은 방어를 한다
            log.warn("[ECOS] 목록 조회 실패 reason={}", e.javaClass.simpleName)
            throw EcosApiException("IO", "ECOS 목록 조회에 실패했습니다")
        }
    }
}
```

- [ ] **Step 4: 어드민 컨트롤러를 만든다**

```kotlin
package com.allfolio.api.admin

import com.allfolio.fx.EcosApiException
import com.allfolio.fx.EcosStatListClient
import com.allfolio.market.rate.RateCollectService
import com.allfolio.market.rate.RateCollectSummary
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

@RestController
@RequestMapping("/api/admin/rate")
class MarketRateAdminController(
    private val rateCollectService: RateCollectService,
    private val statListClient: EcosStatListClient,
) {
    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")

        /**
         * 기본 수집 창. 달력 14일이면 연휴가 끼어도 영업일이 5~6일은 들어온다.
         * 영업일을 세지 않는 이유는 공휴일 달력을 들일 값어치가 없어서다.
         */
        private const val WINDOW_DAYS = 14L
    }

    /**
     * GET /api/admin/rate/ecos/tables?stat=721Y001 — ECOS 통계표 목록 (AF-102).
     *
     * 수집 대상 코드를 확인하기 위한 것이다. **추정한 코드를 설정에 넣지 말 것** —
     * ECOS는 틀린 코드에 오류가 아니라 0건을 주므로, 잘못 넣으면 "기간이 비었다"와 구분되지 않는다.
     * 응답은 파싱하지 않고 그대로 나간다(오류 응답도 그대로 보여야 경로 실수가 드러난다).
     */
    @GetMapping("/ecos/tables", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun tables(@RequestParam(required = false) stat: String?): ResponseEntity<String> =
        try {
            ResponseEntity.ok(statListClient.tables(stat))
        } catch (e: EcosApiException) {
            // 요청은 멀쩡했고 상류 응답이 이상한 것이다. 전역 폴백의 500으로 뭉개지면
            // 운영자가 우리 버그를 찾으러 간다 — 백필·하나은행 엔드포인트와 같은 판단이다
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, e.message)
        }

    /** GET /api/admin/rate/ecos/items?stat=721Y001 — 통계표 하나의 항목 목록 */
    @GetMapping("/ecos/items", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun items(@RequestParam stat: String): ResponseEntity<String> =
        try {
            ResponseEntity.ok(statListClient.items(stat))
        } catch (e: EcosApiException) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, e.message)
        }

    /**
     * POST /api/admin/rate/collect — 금리 수집 (어드민 전용, AF-102).
     *
     * **날짜를 주지 않으면 KST 오늘 기준 최근 2주다.** 일일 수집과 백필이 같은 경로인 이유는
     * 둘이 같은 일이기 때문이다 — "이 구간을 ECOS가 준 값으로 맞춘다", 그리고 멱등하다.
     * 초기 백필은 `?from=2020-01-01&to=<오늘>`로 한 번 부른다.
     *
     * `LocalDate.now()`가 아니라 KST로 옮겨 오늘을 구한다 — Render 컨테이너는 UTC라
     * KST 새벽 실행이 하루 전으로 밀린다.
     *
     * **전멸은 502, 부분 실패는 200, 대상 0건은 500이다.** 판단 근거는
     * [MarketIndexAdminController.collect]의 KDoc에 길게 적혀 있고 여기서도 그대로다:
     * 조용한 수집 중단은 반드시 보여야 하고(502), 매일 빨간 잡은 아무도 안 보며(200),
     * 빈 설정은 우리 실수라 ECOS를 확인하러 보내면 안 된다(500).
     */
    @PostMapping("/collect")
    fun collect(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ResponseEntity<RateCollectSummary> {
        val end = to ?: LocalDate.now(KST)
        val start = from ?: end.minusDays(WINDOW_DAYS)

        val summary = rateCollectService.collect(start, end, LocalDateTime.now(ZoneOffset.UTC))

        if (summary.requested == 0) {
            // 우리 설정 실수다. ECOS를 확인하러 보내지 않도록 502가 아니라 500으로 낸다
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "수집 대상 금리가 설정에 없습니다 — application.yml의 market-rate.series 를 확인하세요",
            )
        }

        if (summary.collected == 0) {
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "금리를 한 건도 수집하지 못했습니다 (요청 ${summary.requested}건, $start~$end): " +
                    summary.failures.joinToString("; ").ifBlank { "사유 없음" },
            )
        }
        return ResponseEntity.ok(summary)
    }
}
```

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*MarketRateAdmin*' --no-daemon`
Expected: BUILD SUCCESSFUL (5 tests)

`RateCollectService`는 open 클래스가 아니라 Mockito가 목으로 만들지 못할 수 있다.
`mockito-inline`(또는 `mock-maker-inline`)이 이미 설정돼 있는지 `FxRateAdminControllerTest`류를 확인하고,
없으면 `RateCollectService`를 `open class`로 바꾸는 대신 **테스트에서 얇은 가짜 서브클래스를 쓴다**
(프로덕션 코드를 테스트 때문에 열지 않는다).

- [ ] **Step 6: 커밋**

```bash
git add allfolio-backend/backend-app/src
git commit -m "feat(af-102): 금리 수집 어드민 엔드포인트 + ECOS 코드 탐색"
```

---

### Task 7: 스케줄러 트리거 + 워크플로

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/api/scheduler/SchedulerTriggerController.kt`
- Modify: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/api/scheduler/SchedulerTriggerControllerTest.kt`
- Create: `.github/workflows/collect-rate.yml`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`SchedulerTriggerControllerTest.kt`에 목을 하나 더하고(`private val rateAdmin: MarketRateAdminController = mock(...)`),
생성자 인자에 넣은 뒤 테스트 두 개를 더한다:

```kotlin
    @Test
    fun `토큰이 맞으면 금리 수집을 위임한다`() {
        controller.collectRate(TOKEN)

        verify(rateAdmin).collect(null, null)
    }

    @Test
    fun `토큰이 틀리면 금리 수집을 부르지 않는다`() {
        assertThatThrownBy { controller.collectRate("wrong") }
            .isInstanceOf(ResponseStatusException::class.java)

        verify(rateAdmin, never()).collect(null, null)
    }
```

(기존 테스트들이 `controller`를 만드는 곳이 여러 군데면 전부 새 인자를 받도록 고친다 —
컴파일러가 가리켜 준다.)

- [ ] **Step 2: 컴파일 실패를 확인한다**

Run: `cd allfolio-backend && ./gradlew :backend-app:compileTestKotlin --no-daemon`
Expected: FAIL — `No value passed for parameter 'rateAdmin'` 또는 `Unresolved reference: collectRate`

- [ ] **Step 3: 트리거를 더한다**

`SchedulerTriggerController` 생성자에 `private val rateAdmin: MarketRateAdminController,`를 더하고
메서드를 추가한다:

```kotlin
    /**
     * POST /api/internal/scheduler/rate — 금리 수집 트리거 (AF-102)
     *
     * **날짜를 노출하지 않는다.** [MarketRateAdminController.collect]가 null을 KST 오늘 기준
     * 최근 2주로 해석한다. 워크플로가 날짜를 계산해 실어 보내면 러너의 UTC 시계가 그대로
     * 데이터에 새겨지고, GitHub cron이 밀린 날 구간이 어긋난다.
     *
     * 백필 구간을 여기 노출하지 않는 이유: 초기 백필은 사람이 한 번 부르는 일회성 작업이고,
     * 스케줄러가 할 수 있어야 하는 일이 아니다. 어드민 엔드포인트에 있다.
     *
     * 어드민 컨트롤러에 위임하는 이유는 위 트리거들과 같다 — 502/500 매핑이 Actions 로그를
     * 읽는 사람에게 그대로 필요하다. **이 위임을 "정리"하지 말 것.**
     */
    @PostMapping("/rate")
    fun collectRate(
        @RequestHeader(name = TOKEN_HEADER, required = false) token: String?,
    ): ResponseEntity<RateCollectSummary> {
        authorize(token)
        return rateAdmin.collect(null, null)
    }
```

임포트 둘을 더한다: `com.allfolio.api.admin.MarketRateAdminController`, `com.allfolio.market.rate.RateCollectSummary`.

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*SchedulerTrigger*' --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 워크플로를 만든다**

`.github/workflows/collect-rate.yml`. `collect-index.yml`을 뼈대로 하되 슬롯 분기가 없어 단순하다.

```yaml
name: Collect Rate

# **cron이 아직 없다.** market-rate.series가 빈 동안은 수집이 500으로 끝나므로,
# 지금 켜면 매일 빨간 잡이 된다. AF-102 Task 9(코드 확인 후 설정 채우기)에서 아래를 켠다:
#
#   schedule:
#     - cron: "10 9 * * 1-5"   # KST 18:10 — ECOS 일별 계열은 마감 후 공표된다
#
# 18:10인 이유: 국내 지수 CLOSE 수집(15:50 KST)과 시간을 벌려 같은 인스턴스에 겹치지 않게 한다.
# GitHub cron이 5~30분 밀려도 수집 창이 최근 2주라 밀림이 데이터에 남지 않는다.
on:
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  # 콜드 스타트로 앞선 실행이 밀려 다음 지점과 겹쳐도 같은 구간을 두 번 쓰는 것뿐이라
  # 데이터는 안전하다(멱등). 그래도 ECOS 호출을 두 배로 쓸 이유는 없다.
  group: collect-rate
  cancel-in-progress: false

jobs:
  rate:
    name: 금리 수집
    runs-on: ubuntu-latest
    # 아래 curl 재시도 예산(최악 9분)보다 커야 한다. 잡이 먼저 잘리면 요약도 애너테이션도
    # 남지 않고 "cancelled"만 떠서 백엔드 문제가 러너 문제처럼 보인다.
    timeout-minutes: 12
    steps:
      - name: Trigger collection
        env:
          BACKEND_URL: ${{ secrets.BACKEND_URL }}
          SCHEDULER_TOKEN: ${{ secrets.SCHEDULER_TOKEN }}
        run: |
          set -euo pipefail

          if [ -z "${BACKEND_URL}" ] || [ -z "${SCHEDULER_TOKEN}" ]; then
            echo "::error::BACKEND_URL 또는 SCHEDULER_TOKEN 시크릿이 없습니다."
            exit 1
          fi

          # 끝의 슬래시를 지운다. 남겨두면 경로가 "//api/internal/..."이 되어 404가 난다.
          while [ "${BACKEND_URL}" != "${BACKEND_URL%/}" ]; do
            BACKEND_URL="${BACKEND_URL%/}"
          done

          # https만 허용한다. http면 Render가 301로 넘기는데 -L 없이는 POST가 전달되지 않는다.
          case "${BACKEND_URL}" in
            https://*) ;;
            *)
              echo "::error::BACKEND_URL 시크릿은 https://로 시작하고 끝에 슬래시가 없어야 합니다."
              exit 1
              ;;
          esac

          # -o/-w로 본문과 상태를 분리해 받는다. --fail을 쓰면 4xx/5xx에서 본문이 버려져
          # 왜 실패했는지 알 수 없게 된다. 재시도가 안전한 이유는 수집이 멱등하기 때문이다.
          # 전송 계층 실패에서는 curl이 0이 아닌 코드로 끝나므로 센티널을 세운다.
          HTTP_CODE=$(curl -sS -X POST \
            "${BACKEND_URL}/api/internal/scheduler/rate" \
            -H "X-Scheduler-Token: ${SCHEDULER_TOKEN}" \
            --max-time 120 \
            --retry 3 \
            --retry-delay 20 \
            --retry-all-errors \
            -o response.json \
            -w '%{http_code}') || HTTP_CODE="000"

          echo "HTTP ${HTTP_CODE}"

          # 이 저장소는 공개다. 토큰은 어디에도 찍지 않는다.
          {
            echo "### 금리 수집"
            echo ""
            echo "HTTP \`${HTTP_CODE}\`"
            echo ""
            echo '```json'
            cat response.json 2>/dev/null || echo '(본문 없음)'
            echo ""
            echo '```'
          } >> "$GITHUB_STEP_SUMMARY"

          if [ "${HTTP_CODE}" != "200" ]; then
            echo "::error::금리 수집 실패 (HTTP ${HTTP_CODE}). 500=수집 대상 설정 없음(market-rate.series) / 502=ECOS 응답 이상 또는 전량 실패 / 503=SCHEDULER_TOKEN 미설정 / 000=백엔드 응답 없음(콜드 스타트·네트워크)"
            exit 1
          fi

          # 200이어도 부분 실패일 수 있다. 잡을 빨갛게 만들지는 않고 애너테이션으로 남긴다.
          # **건수를 stdout에도 남긴다** — 잡 요약은 UI를 열어야만 보이고 API로는 못 읽는다.
          # 이게 없으면 "대상이 조용히 줄어든 경우"를 아무도 못 가른다(AF-111에서 실제로 겪었다).
          python3 - response.json <<'PY'
          import json, sys

          try:
              with open(sys.argv[1], encoding="utf-8") as f:
                  body = json.load(f)
          except (OSError, ValueError) as e:
              print(f"::warning::HTTP 200인데 응답 본문을 읽지 못했습니다({e}). 부분 실패 여부를 확인할 수 없습니다.")
              raise SystemExit(0)

          failed = body.get("failed") or 0
          failures = body.get("failures") or []
          skipped = body.get("skippedRows") or 0
          out_of_range = body.get("outOfRange") or 0
          empty = body.get("emptySeries") or []

          print(
              f"수집 결과: requested={body.get('requested')} collected={body.get('collected')} "
              f"inserted={body.get('inserted')} updated={body.get('updated')} "
              f"skippedRows={skipped} outOfRange={out_of_range} empty={len(empty)} "
              f"failed={failed} ({body.get('from')}~{body.get('to')})"
          )

          if not body.get("requested"):
              print("::warning::수집 대상이 0건입니다 — market-rate.series 설정을 확인하세요.")

          if skipped:
              # 파서가 버린 행이다. 꾸준히 늘면 ECOS 응답 형식이나 단위가 바뀐 신호다.
              print(f"::warning::값이 이상해 버린 행이 {skipped}건 있습니다.")

          if out_of_range:
              # 요청 구간 밖 날짜. 서비스가 걷어내 사고는 안 나지만, ECOS가 구간 해석을
              # 바꿨다는 신호라 계속 나오면 봐야 한다.
              print(f"::warning::요청 구간 밖 날짜 {out_of_range}건을 걷어냈습니다.")

          if empty:
              # **실패가 아니다.** 기준금리처럼 변경 시에만 공표되는 계열은 2주 창이 빌 수 있다.
              # 다만 통계표 코드가 죽어도 똑같이 0건이라, 같은 종목이 며칠씩 계속 여기 뜨면
              # 그건 정상이 아니다. 자동으로 못 가르는 구분이라 사람에게 넘긴다.
              print(f"::warning::0건으로 돌아온 종목: {', '.join(empty)} (변경 시에만 공표되는 계열이면 정상)")

          if failed or failures:
              reasons = " | ".join(str(x) for x in failures) or "(사유 없음)"
              print(f"::warning::금리 {failed}건 수집 실패 (수집 {body.get('collected')}/{body.get('requested')}): {reasons}")
          PY
```

- [ ] **Step 6: 커밋**

```bash
git add allfolio-backend/backend-app/src .github/workflows/collect-rate.yml
git commit -m "feat(af-102): 금리 수집 스케줄러 트리거 + 워크플로 (cron은 코드 확인 후)"
```

---

### Task 8: 전 모듈 검증 + PR

- [ ] **Step 1: 전 모듈 테스트**

```bash
cd allfolio-backend && ./gradlew test --rerun-tasks --no-daemon
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 변경 범위를 확인한다**

```bash
git diff --stat main...HEAD
```

**`FxRateAdminController`·`CashFlowRecomputeService`·`CashFlowJpaRepository`가 diff에 있으면 범위가 샌 것이다** —
다른 브랜치가 그 파일들을 작업 중이다.

`market-rate.series`가 `[]`인지, `collect-rate.yml`에 `schedule:`이 없는지도 확인한다.

- [ ] **Step 3: 푸시하고 PR**

```bash
git push -u origin feat/af-102-market-rate-collection
```

PR 본문에 반드시 담을 것:
- **머지해도 아직 아무것도 수집되지 않는다** — `market-rate.series`가 비어 있고 cron도 없다
- 배포 후 순서: 마이그레이션 적용 → 탐색으로 코드 확인 → 설정·cron PR → 백필 → 라이브 검증
- `EcosApiClient.fetch` 시그니처 변경이 환율 백필 경로를 건드리므로, 리뷰는 그쪽 테스트를 먼저 볼 것

- [ ] **Step 4: CI 확인**

```bash
gh pr checks --watch
```

**실패한 필수 체크를 `--admin`으로 우회하지 않는다.**

---

### Task 9: 배포 후 — 코드 확인 · 설정 · 백필 (PR 2)

> 이 태스크는 **PR 1이 머지·배포된 뒤에** 시작한다. 앞 태스크와 달리 운영 작업이 섞여 있다.

- [ ] **Step 1: 마이그레이션을 적용한다**

`docs/superpowers/migrations/2026-08-13-market-rate.sql`을 Neon 콘솔에서 실행한다.
마지막 `SELECT COUNT(*)`가 `0`을 돌려주면 테이블이 생긴 것이다.

- [ ] **Step 2: 통계표 코드를 확인한다**

어드민 JWT로 호출한다(경로가 `/api/admin/**`이라 ADMIN 롤이 필요하다).

```bash
curl -sS -H "Authorization: Bearer <어드민 JWT>" \
  "https://<서비스>.onrender.com/api/admin/rate/ecos/tables" | python3 -m json.tool | less
```

응답에서 아래 여섯을 찾는다. 이름으로 후보를 좁힌 뒤 각 통계표의 항목 코드를 확인한다:

```bash
curl -sS -H "Authorization: Bearer <어드민 JWT>" \
  "https://<서비스>.onrender.com/api/admin/rate/ecos/items?stat=<통계표코드>" | python3 -m json.tool | less
```

| 우리 코드 | 찾을 것 |
|---|---|
| `BASE_RATE` | 한국은행 기준금리 |
| `CALL_ON` | 콜금리(익일물) |
| `CD_91D` | CD 91일 |
| `KTB_3Y` | 국고채 3년 |
| `KTB_10Y` | 국고채 10년 |
| `CORP_AA3Y` | 회사채 AA- 3년 |

**주기가 `D`가 아닌 계열이 있으면 그 종목은 이번에 넣지 않는다.** 클라이언트가 `D`만 받으므로
설정에 넣으면 매일 실패로 남는다. 어떤 종목이 어떤 주기였는지 적어 두고 후속으로 넘긴다
(주기를 넓히려면 요청 날짜 형식과 파서의 `TIME` 해석을 함께 고쳐야 한다).

**회사채 AA- 3년이 목록에 없으면 그 종목만 빼고 진행한다.** 다섯 종목으로도 화면은 성립한다.

- [ ] **Step 3: 설정을 채운다**

`application.yml`의 `market-rate.series`를 확인한 코드로 채운다. 형식:

```yaml
market-rate:
  series:
    - code: BASE_RATE
      stat-code: "<확인한 값>"
      item-code: "<확인한 값>"
      cycle: D
    - code: KTB_3Y
      stat-code: "<확인한 값>"
      item-code: "<확인한 값>"
      cycle: D
```

- [ ] **Step 4: cron을 켠다**

`.github/workflows/collect-rate.yml`의 `on:` 블록을 아래로 바꾸고, 위의 안내 주석에서
"아직 없다" 문단을 지운다:

```yaml
on:
  schedule:
    # KST 18:10 (UTC 09:10) 평일 — ECOS 일별 계열은 마감 후 공표된다.
    # 국내 지수 CLOSE(15:50 KST)와 벌려 두어 같은 인스턴스에 겹치지 않게 한다.
    - cron: "10 9 * * 1-5"
  workflow_dispatch:
```

- [ ] **Step 5: 설정 PR을 올리고 머지·배포한다**

```bash
git add allfolio-backend/backend-app/src/main/resources/application.yml .github/workflows/collect-rate.yml
git commit -m "feat(af-102): 확인한 ECOS 코드로 금리 수집 대상을 채우고 cron을 켠다"
```

PR 본문에 **확인한 코드와 그 근거(통계표 이름)를 적는다** — 나중에 0건이 나올 때
"이 코드가 맞았던 적이 있는가"를 가르는 유일한 기록이다.

- [ ] **Step 6: 초기 백필을 돌린다 — 반드시 해를 끊어서**

**한 번에 2020~2026을 부르지 않는다.** `id`가 할당식이고 `@Version`이 없어서 Spring Data가
모든 행을 `em.merge`로 보내고, merge는 행마다 SELECT를 한 번씩 낸다 —
`batch_size`는 쓰기만 묶지 이 SELECT들은 안 묶는다. 6종목 x 7년이면 순차 왕복 9,000회 남짓이고,
수집 서비스는 (의도적으로) 트랜잭션이 없어 그 시간 내내 HTTP 요청 하나가 열려 있다.
AF-100이 한 통화 2,600행에서 이미 겪었고, `FxRateBackfillService`의 KDoc이 분할을
"편의가 아니라 필수"라고 못 박아 뒀다. 그래서 해마다 끊는다:

```bash
for y in 2020 2021 2022 2023 2024 2025 2026; do
  echo "=== $y ==="
  curl -sS -X POST -H "Authorization: Bearer <어드민 JWT>" \
    "https://<서비스>.onrender.com/api/admin/rate/collect?from=$y-01-01&to=$y-12-31" \
    | python3 -m json.tool
done
```

(마지막 해의 `to`가 미래여도 무해하다 — ECOS가 오늘까지만 준다.)

Expected: 해마다 `requested`가 설정한 종목 수와 같고, `inserted`가 종목당 240행 안팎,
`failed`가 0, `skippedRows`가 0, `outOfRange`가 0.

`emptySeries`에 기준금리처럼 변경 시에만 공표되는 계열이 뜨는 건 정상이다.
**다만 어떤 종목이 7년 내내 0건이면 그건 코드가 틀린 것이다** — 넘어가지 말고 Step 2로 돌아간다.

**`skippedRows`가 0이 아니면 멈추고 확인한다** — 값이 ±100을 벗어났다는 뜻이고,
그건 단위가 우리 가정과 다르다는 신호다(연 %가 아니라 bp로 오는 계열일 수 있다).

- [ ] **Step 7: 저장된 값을 대조한다**

Neon에서 최근 값을 뽑아 ECOS 사이트의 같은 날짜 값과 눈으로 대조한다.

```sql
SELECT rate_code, quote_date, rate_value
FROM market_rate
WHERE quote_date >= CURRENT_DATE - 7
ORDER BY rate_code, quote_date DESC;
```

**숫자를 대조하지 않으면 이 태스크는 안 끝난 것이다.** AF-101에서 수집은 되는데 값 대조를
미뤄 둔 상태가 지금도 남아 있다 — 같은 걸 반복하지 말 것.

- [ ] **Step 8: 워크플로를 수동 실행해 본다**

GitHub Actions에서 `Collect Rate`를 `Run workflow`로 한 번 돌린다.
Expected: 초록, 요약에 `requested=<종목 수> collected=<종목 수> inserted=0 updated=<종목 수>`
(백필이 이미 채웠으므로 삽입은 0이고 갱신만 나온다 — 이게 멱등성의 라이브 증거다).

- [ ] **Step 9: 노션을 갱신한다**

AF-102를 완료로 바꾸고 아래를 적는다: 확인한 통계표·항목 코드, 뺀 종목과 이유,
주기가 `D`가 아니어서 미룬 종목, FRED(미국)는 별도 태스크로 남았다는 것.

---

## 완료 후 보고할 것

- Task 8 전 모듈 테스트 결과
- Task 9 Step 2에서 확인한 코드 여섯 (또는 뺀 종목과 이유)
- Task 9 Step 6 백필 요약 숫자 (`inserted` / `skippedRows`)
- Task 9 Step 7 값 대조 결과 — **"수집됐다"가 아니라 "값이 맞다"까지**
