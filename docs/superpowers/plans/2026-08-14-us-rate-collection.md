# 미국 금리 수집 (FRED) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 연방기금금리와 미국채 2·10·30년을 FRED에서 수집해 기존 `market_rate`에 쌓는다.

**Architecture:** `RateCollectService`에서 ECOS에 묶인 한 줄만 `RateSource` 포트 뒤로 옮기고, 그 뒤 방어(구간 밖 필터·멱등 upsert·계수·실패 격리)는 공용으로 남긴다. 그다음 FRED 구현을 새 소스로 붙인다. 두 단계를 별도 커밋으로 나눠 회귀의 출처를 가릴 수 있게 한다.

**Tech Stack:** Kotlin · Spring Boot · WebClient · JUnit5 + AssertJ + Mockito

**설계 문서:** `docs/superpowers/specs/2026-08-14-us-rate-collection-design.md`

---

## 사전 필독 (모든 태스크 공통)

### 저장 계층은 이미 있다

`market_rate` 테이블, `MarketRateEntity`, `MarketRateJpaRepository`, `RateCollectService`,
어드민·스케줄러 트리거, `collect-rate.yml` 워크플로 — AF-102가 전부 만들어 가동 중이다.
**이 계획은 소스만 추가한다.** 마이그레이션도 새 테이블도 없다.

### 이사와 추가를 섞지 않는다

Task 2는 기존 ECOS 경로를 포트 뒤로 **옮기기만** 한다 — 동작이 바뀌면 안 되고, 기존 테스트가
그걸 지킨다. Task 3~4가 FRED를 **추가**한다. 한 커밋에 섞으면 회귀가 났을 때 이사 때문인지
새 소스 때문인지 가릴 수 없다.

### FRED 인증키는 쿼리 파라미터에 실린다

ECOS는 키를 URL **경로**에 넣지만 FRED는 **쿼리 파라미터**(`api_key=`)로 받는다.
위치는 달라도 노출 위험은 같다 — 전체 URL을 로그에 찍지 말고, 예외에 cause를 붙이지 말 것.
`EcosStatisticSearchClient`가 그 방어를 길게 설명해 두었으니 같은 규율을 따른다.

### 검증 명령

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests '*Rate*' --tests '*Fred*' --no-daemon
```

## 파일 구조

| 파일 | 책임 |
|---|---|
| `fx/EcosValuePolicy.kt` → `fx/RateValuePolicy.kt` | 이름만 바꾼다. 판정 로직 그대로 |
| `market/rate/RateSource.kt` (신규) | 포트 + `RateObservation` · `RateFetch` |
| `market/rate/EcosRateSource.kt` (신규) | 기존 ECOS 경로가 이사해 온다 |
| `market/rate/fred/FredProperties.kt` (신규) | 인증키·베이스 URL |
| `market/rate/fred/FredApiClient.kt` (신규) | HTTP 호출 + 키 유출 방어 |
| `market/rate/fred/FredObservationParser.kt` (신규) | `"."` 결측 처리 |
| `market/rate/fred/FredRateSource.kt` (신규) | 포트 구현 |
| `market/rate/MarketRateProperties.kt` (수정) | `series` → `ecos`, `fred` 추가 |
| `market/rate/RateCollectService.kt` (수정) | 소스 목록을 받는다 |
| `application.yml` (수정) | `market-rate.ecos`·`market-rate.fred`·`fred.api-key` |

---

### Task 1: `EcosValuePolicy` → `RateValuePolicy`

**Files:**
- Rename: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/EcosValuePolicy.kt` → `RateValuePolicy.kt`
- Modify: 호출부 전부 (컴파일러가 가리킨다)

**왜:** FRED도 같은 정책(0·마이너스 허용, ±100 초과 차단)을 쓴다. `Ecos*`라는 이름이 이제 거짓말이다.

**패키지는 옮기지 않는다.** `com.allfolio.fx`에 그대로 둔다 — 유일한 사용처인 `EcosResponseParser`가
거기 있고, `market/rate`로 옮기면 `fx → market.rate` 의존이 생겨 방향이 꼬인다.
세 번째 소스가 붙어 중립 패키지가 필요해지면 그때 옮긴다.

- [ ] **Step 1: 이름을 바꾼다**

```bash
cd /Users/hong9/IdeaProjects/allfolio
git mv allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/EcosValuePolicy.kt \
       allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/RateValuePolicy.kt
grep -rl "EcosValuePolicy" allfolio-backend --include="*.kt" | grep -v '/build/' \
  | xargs sed -i '' 's/EcosValuePolicy/RateValuePolicy/g'
```

- [ ] **Step 2: KDoc의 이름 근거를 고친다**

`RateValuePolicy.kt`의 클래스 KDoc 첫 줄을 아래로 바꾸고, 이름이 바뀐 이유를 한 줄 남긴다:

```kotlin
/**
 * 외부 소스가 준 값을 받아들일지 판정한다.
 *
 * 파서가 판정을 들고 있으면 첫 호출자(환율)의 가정이 모든 호출자에게 강요된다 —
 * 실제로 `rate <= 0` 가드가 그랬고, 금리에 그대로 쓰면 0.00% 공표일이 조용히 사라진다.
 * 그래서 무엇이 말이 되는 값인지는 도메인을 아는 호출자가 정한다.
 *
 * **이름에서 `Ecos`를 뺀 이유**: FRED도 [PERCENT]를 그대로 쓴다. 소스가 둘이 된 시점에
 * `EcosValuePolicy`는 거짓말이 됐다. 패키지가 아직 `fx`인 것은 유일한 사용처인
 * `EcosResponseParser`가 여기 있어서다 — 세 번째 소스가 붙으면 중립 패키지로 옮길 것.
 */
```

- [ ] **Step 3: 남은 참조가 없는지 확인한다**

```bash
grep -rn "EcosValuePolicy" allfolio-backend --include="*.kt" | grep -v '/build/'
```
Expected: 출력 없음

- [ ] **Step 4: 테스트**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*Ecos*' --tests '*Rate*' --no-daemon`
Expected: BUILD SUCCESSFUL — **한 건도 안 깨져야 한다.** 깨지면 이름 말고 다른 게 바뀐 것이다.

- [ ] **Step 5: 커밋**

```bash
git add -u allfolio-backend
git commit -m "refactor(fred): EcosValuePolicy를 RateValuePolicy로 — 소스가 둘이 되면 이름이 거짓말이다"
```

---

### Task 2: `RateSource` 포트 + ECOS 경로 이사 (동작 불변)

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/rate/RateSource.kt`
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/rate/EcosRateSource.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/rate/RateCollectService.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/rate/MarketRateProperties.kt`
- Modify: `allfolio-backend/backend-app/src/main/resources/application.yml`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/rate/RateCollectServiceTest.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/rate/MarketRatePropertiesTest.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/rate/MarketRatePropertiesYamlTest.kt`

**이 태스크의 성공 기준은 "아무것도 안 바뀌는 것"이다.** 기존 테스트가 통과하면 이사가 성공한 것이다.

- [ ] **Step 1: 포트를 만든다**

`RateSource.kt`:

```kotlin
package com.allfolio.market.rate

import java.math.BigDecimal
import java.time.LocalDate

/** 하루치 금리 한 건. 값은 연 %다 */
data class RateObservation(val quoteDate: LocalDate, val value: BigDecimal)

/** @param skipped 소스가 파싱 단계에서 버린 행 수 — 요약의 skippedRows로 그대로 나간다 */
data class RateFetch(val rows: List<RateObservation>, val skipped: Int)

/**
 * 금리 한 소스.
 *
 * **가져오기만 소스별이고 저장하기는 공용이다.** 구간 밖 날짜 제거·0건 처리·중복 접기·
 * inserted/updated/unchanged 계수·종목별 실패 격리는 [RateCollectService]가 한 벌만 갖는다 —
 * ECOS를 겪으며 생긴 방어지만 소스와 무관하게 옳다. AF-100의 `HistoricalRateSource`가 같은 판단이다.
 *
 * **환율 포트와 한 군데 다르다.** 환율은 호출자가 통화를 지목하므로 `supports(currency)`로 묻지만,
 * 금리는 수집 대상이 설정에서 열거되므로 소스가 자기 코드 목록을 내놓는다.
 */
interface RateSource {
    /** `market_rate.source`에 들어갈 값 */
    val sourceName: String

    /** 이 소스가 담당하는 canonical 코드. 설정에서 온다 */
    val codes: List<String>

    /**
     * `from..to`는 포함 범위이고, 범위 밖 날짜가 섞여 와도 된다 — 서비스가 걸러낸다.
     * 실패는 예외로 알린다 — 서비스가 종목별로 잡아 요약의 failures로 옮긴다.
     */
    fun fetch(code: String, from: LocalDate, to: LocalDate): RateFetch
}
```

- [ ] **Step 2: 설정을 소스별로 나눈다**

`MarketRateProperties.kt`에서 `var series: List<RateSeries>`를 `var ecos: List<EcosSeries>`로 바꾸고
(클래스 이름 `RateSeries` → `EcosSeries`), `var fred: List<FredSeries> = emptyList()`를 더한다:

```kotlin
    /** FRED 시계열 한 종. 좌표가 시리즈 ID 하나뿐이라 ECOS와 모양이 다르다 */
    class FredSeries {
        /** 우리가 정한 canonical 코드. DB의 rate_code가 된다 */
        var code: String = ""
        /** FRED series_id. 예: DGS10 */
        var seriesId: String = ""
    }
```

`validate()`는 두 목록을 각각 검사한다. **중복 코드 검사는 두 목록을 합쳐서 한다** —
`ecos`와 `fred`에 같은 코드가 있으면 한쪽이 다른 쪽을 덮어쓰는데, upsert 키가
`(rate_code, quote_date)`라 유니크 제약에도 안 걸리고 요약도 초록이다:

```kotlin
    @PostConstruct
    fun validate() {
        val problems = mutableListOf<String>()

        ecos.forEachIndexed { i, s ->
            val label = s.code.ifBlank { "ecos[$i]" }
            if (s.code.isBlank()) problems += "ecos[$i]: code가 비어 있습니다"
            if (s.statCode.isBlank()) problems += "$label: stat-code가 비어 있습니다"
            if (s.itemCode.isBlank()) problems += "$label: item-code가 비어 있습니다"
            if (s.cycle != EcosQuery.DAILY_CYCLE) {
                problems += "$label: 지원하지 않는 주기입니다: ${s.cycle} (현재 D만 지원)"
            }
        }
        fred.forEachIndexed { i, s ->
            val label = s.code.ifBlank { "fred[$i]" }
            if (s.code.isBlank()) problems += "fred[$i]: code가 비어 있습니다"
            if (s.seriesId.isBlank()) problems += "$label: series-id가 비어 있습니다"
        }

        // 두 목록을 합쳐서 본다. 같은 코드가 양쪽에 있으면 뒤에 도는 소스가 앞의 값을 덮는데,
        // upsert 키가 (rate_code, quote_date)라 유니크 제약에 안 걸리고 요약도 초록으로 끝난다.
        (ecos.map { it.code } + fred.map { it.code })
            .filter { it.isNotBlank() }
            .groupingBy { it }.eachCount()
            .filterValues { it > 1 }.keys
            .forEach { problems += "$it: code가 중복됩니다" }

        require(problems.isEmpty()) {
            "market-rate 설정이 올바르지 않습니다 — " + problems.joinToString("; ")
        }
    }
```

- [ ] **Step 3: `EcosRateSource`로 이사한다**

`EcosRateSource.kt` — `RateCollectService`에 인라인으로 있던 조립을 그대로 옮긴다:

```kotlin
package com.allfolio.market.rate

import com.allfolio.fx.EcosApiClient
import com.allfolio.fx.EcosQuery
import com.allfolio.fx.RateValuePolicy
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * ECOS(한국은행) 금리 소스 (AF-102).
 *
 * `RateCollectService`에 인라인으로 있던 조회를 [RateSource] 뒤로 옮긴 것이다 —
 * 동작은 그대로다. 옮긴 이유는 FRED가 두 번째 소스로 붙기 때문이고,
 * 옮기면서 아무것도 바뀌지 않았다는 것은 기존 테스트가 지킨다.
 */
@Component
class EcosRateSource(
    private val client: EcosApiClient,
    private val properties: MarketRateProperties,
) : RateSource {

    override val sourceName = "ECOS"

    override val codes: List<String>
        get() = properties.ecos.map { it.code }

    override fun fetch(code: String, from: LocalDate, to: LocalDate): RateFetch {
        val series = properties.ecos.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("ECOS 설정에 없는 금리 코드입니다: $code")

        val result = client.fetch(
            EcosQuery(
                statCode = series.statCode,
                itemCode = series.itemCode,
                cycle = series.cycle,
                // 금리는 0.00%도 마이너스도 실재한다 — 환율 정책으로 부르면 그 날이 사라진다
                valuePolicy = RateValuePolicy.PERCENT,
            ),
            from,
            to,
        )
        return RateFetch(
            rows = result.rates.map { RateObservation(it.baseDate, it.value) },
            skipped = result.skipped,
        )
    }
}
```

- [ ] **Step 4: 서비스가 소스 목록을 받게 한다**

`RateCollectService`의 생성자를 바꾸고:

```kotlin
class RateCollectService(
    private val sources: List<RateSource>,
    private val store: Store,
) {
```

(`client`와 `properties`는 더 이상 필요 없다 — 소스가 갖는다.)

수집 루프의 머리를 바꾼다. `for (series in properties.series)` → 소스와 코드의 쌍을 돈다:

```kotlin
        // 소스 x 코드로 편다. 어느 소스가 어느 코드를 갖는지는 소스가 안다 —
        // 서비스는 설정 모양을 알 필요가 없고, 그래서 소스가 늘어도 이 루프는 안 바뀐다
        val targets = sources.flatMap { source -> source.codes.map { source to it } }

        for ((source, code) in targets) {
            try {
                val result = source.fetch(code, from, to)
                skippedRows += result.skipped

                val inRange = result.rows.filter { it.quoteDate in from..to }
                outOfRange += result.rows.size - inRange.size

                if (inRange.isEmpty()) emptySeries += code
                ...
```

본문에서 `series.code` → `code`, `row.baseDate` → `row.quoteDate`,
`source = SOURCE` → `source = source.sourceName`으로 바꾼다.
`requested = properties.series.size` → `requested = targets.size`.
`SOURCE` 상수는 지운다 — 이제 소스가 자기 이름을 안다.

**`failures`의 형식은 바꾸지 않는다** (`"$code: <사유>"`). 워크플로가 그 문자열을 애너테이션으로 내보낸다.

- [ ] **Step 5: 기존 테스트를 새 시그니처에 맞춘다**

`RateCollectServiceTest`의 `FakeClient`를 `FakeSource`로 바꾼다. **단언은 하나도 바꾸지 않는다** —
바꿔야 한다면 동작이 바뀐 것이고, 그건 이 태스크의 실패다.

```kotlin
    /** 코드로 응답을 가른다 — 종목마다 다른 결과를 주려면 그 축이 필요하다 */
    private class FakeSource(
        override val sourceName: String = "ECOS",
        override val codes: List<String>,
        private val rows: Map<String, List<RateObservation>> = emptyMap(),
        private val failing: Map<String, RuntimeException> = emptyMap(),
        private val skipped: Map<String, Int> = emptyMap(),
    ) : RateSource {
        val requested = mutableListOf<Triple<String, LocalDate, LocalDate>>()

        override fun fetch(code: String, from: LocalDate, to: LocalDate): RateFetch {
            requested += Triple(code, from, to)
            failing[code]?.let { throw it }
            return RateFetch(rows[code] ?: emptyList(), skipped[code] ?: 0)
        }
    }
```

`MarketRatePropertiesTest`·`MarketRatePropertiesYamlTest`의 `series` 참조를 `ecos`로 바꾼다.

- [ ] **Step 6: `application.yml`의 키 이름을 바꾼다**

`market-rate:` 블록의 `series:`를 `ecos:`로 바꾼다. **항목은 한 글자도 건드리지 않는다.**

- [ ] **Step 7: 테스트**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*Rate*' --no-daemon`
Expected: BUILD SUCCESSFUL. **기존 단언이 하나도 안 바뀐 채로 통과해야 한다.**

- [ ] **Step 8: 커밋**

```bash
git add allfolio-backend/backend-app/src allfolio-backend/backend-app/src/main/resources/application.yml
git commit -m "refactor(fred): 금리 조회를 RateSource 포트 뒤로 옮긴다 — 동작 불변"
```

---

### Task 3: FRED 클라이언트 + 파서 — 결측 마침표를 거른다

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/rate/fred/FredProperties.kt`
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/rate/fred/FredObservationParser.kt`
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/rate/fred/FredApiClient.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/rate/fred/FredObservationParserTest.kt`

FRED 응답 형태:

```json
{"observations":[
  {"realtime_start":"2026-08-14","realtime_end":"2026-08-14","date":"2026-08-13","value":"4.25"},
  {"realtime_start":"2026-08-14","realtime_end":"2026-08-14","date":"2026-08-14","value":"."}
]}
```

- [ ] **Step 1: 실패하는 파서 테스트를 쓴다**

```kotlin
package com.allfolio.market.rate.fred

import com.allfolio.fx.RateValuePolicy
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class FredObservationParserTest {

    private val parser = FredObservationParser(ObjectMapper())

    @Test
    fun `날짜와 값을 뽑는다`() {
        val json = """
            {"observations":[
              {"date":"2026-08-12","value":"4.24"},
              {"date":"2026-08-13","value":"4.25"}
            ]}
        """.trimIndent()

        val result = parser.parse(json, RateValuePolicy.PERCENT)

        assertThat(result.rows).hasSize(2)
        assertThat(result.rows[1].quoteDate).isEqualTo(LocalDate.of(2026, 8, 13))
        assertThat(result.rows[1].value).isEqualByComparingTo("4.25")
        assertThat(result.skipped).isZero()
    }

    /**
     * **FRED는 관측이 없는 날 값으로 마침표를 준다.** 휴일·미공표일이 그렇다.
     *
     * 이걸 값 검증으로는 절대 못 거른다 — 0으로 변환되면 [RateValuePolicy.PERCENT]가
     * 통과시키기 때문이다(0.00% 공표일이 실재해서 일부러 통과시킨다). 그래서 파싱 단계에서
     * 걸러 센다. 안 그러면 화면에 "미국채 10년 0.00%"가 그럴듯하게 뜬다.
     */
    @Test
    fun `결측 마침표는 값이 아니라 파싱 단계에서 걸러 센다`() {
        val json = """
            {"observations":[
              {"date":"2026-08-13","value":"4.25"},
              {"date":"2026-08-14","value":"."},
              {"date":"2026-08-15","value":""}
            ]}
        """.trimIndent()

        val result = parser.parse(json, RateValuePolicy.PERCENT)

        assertThat(result.rows.map { it.value })
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactly(BigDecimal("4.25"))
        assertThat(result.skipped).isEqualTo(2)
    }

    /** 0%와 마이너스 금리는 실재한다 — 마침표와 달리 살려야 한다 */
    @Test
    fun `0과 마이너스는 살린다`() {
        val json = """
            {"observations":[
              {"date":"2026-08-12","value":"0"},
              {"date":"2026-08-13","value":"-0.25"}
            ]}
        """.trimIndent()

        val result = parser.parse(json, RateValuePolicy.PERCENT)

        assertThat(result.rows).hasSize(2)
        assertThat(result.skipped).isZero()
    }

    @Test
    fun `날짜 형식이 어긋난 행은 건너뛰고 센다`() {
        val json = """{"observations":[{"date":"2026-Q3","value":"4.25"}]}"""

        val result = parser.parse(json, RateValuePolicy.PERCENT)

        assertThat(result.rows).isEmpty()
        assertThat(result.skipped).isEqualTo(1)
    }

    @Test
    fun `observations가 없으면 예외다`() {
        assertThatThrownBy { parser.parse("""{"error_message":"Bad Request."}""", RateValuePolicy.PERCENT) }
            .isInstanceOf(FredApiException::class.java)
    }
}
```

- [ ] **Step 2: 컴파일 실패를 확인한다**

Run: `cd allfolio-backend && ./gradlew :backend-app:compileTestKotlin --no-daemon`
Expected: FAIL — `Unresolved reference: FredObservationParser`

- [ ] **Step 3: 파서를 만든다**

```kotlin
package com.allfolio.market.rate.fred

import com.allfolio.fx.RateValuePolicy
import com.allfolio.market.rate.RateFetch
import com.allfolio.market.rate.RateObservation
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate

class FredApiException(val code: String, val detail: String) :
    RuntimeException("FRED 오류 [$code] $detail")

/**
 * FRED `series/observations` 응답 파서.
 *
 * 정상: `{"observations":[{"date":"2026-08-13","value":"4.25"}, ...]}`
 *
 * **`value`가 마침표(`"."`)면 관측이 없는 날이다.** 휴일·미공표일에 그렇게 온다.
 * 이걸 숫자로 읽으려 하면 실패하고, 0으로 해석하면 더 나쁘다 —
 * [RateValuePolicy.PERCENT]는 0을 **일부러** 통과시키므로(0.00% 공표일이 실재한다)
 * 값 검증으로는 절대 못 잡는다. 그래서 여기서 걸러 [RateFetch.skipped]로 센다.
 */
@Component
class FredObservationParser(
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun parse(json: String, valuePolicy: RateValuePolicy): RateFetch {
        val root = mapper.readTree(json)

        val observations = root.path("observations")
        if (!observations.isArray) {
            // 오류 본문에는 error_message가 실린다. 그 문자열은 우리가 만든 게 아니라
            // 서버가 준 것이라 요청 URL이 되울려 올 수 있다 — 그래서 싣지 않는다
            throw FredApiException("PARSE", "응답에 observations가 없습니다")
        }

        var skipped = 0
        val rows = observations.mapNotNull { node ->
            val date = node.path("date").asText("")
            val raw = node.path("value").asText("")

            val quoteDate = runCatching { LocalDate.parse(date) }.getOrNull()
            // "."은 결측이다. BigDecimal(".")은 예외를 던지므로 runCatching이 잡지만,
            // 의도를 코드로 남긴다 — 다음 사람이 "왜 굳이"라고 지우지 않도록
            val value = if (raw == MISSING) null else runCatching { BigDecimal(raw) }.getOrNull()

            if (quoteDate == null || value == null || !valuePolicy.accepts(value)) {
                skipped++
                log.warn("[FRED] 행 건너뜀 date={} value={}", date, raw)
                null
            } else {
                RateObservation(quoteDate, value)
            }
        }
        return RateFetch(rows, skipped)
    }

    companion object {
        /** FRED가 관측 없음을 나타내는 값 */
        private const val MISSING = "."
    }
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*FredObservationParser*' --no-daemon`
Expected: BUILD SUCCESSFUL (5 tests)

- [ ] **Step 5: 설정과 클라이언트를 만든다**

`FredProperties.kt`:

```kotlin
package com.allfolio.market.rate.fred

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * FRED 접속 설정.
 *
 * **인증키가 쿼리 파라미터에 실린다.** ECOS는 경로 첫 세그먼트지만 FRED는 `api_key=`다.
 * 위치만 다를 뿐 노출 위험은 같다 — 전체 URL을 로그에 찍지 말 것.
 */
@Component
@ConfigurationProperties(prefix = "fred")
class FredProperties {
    var apiKey: String = ""
    var baseUrl: String = "https://api.stlouisfed.org"
}
```

`FredApiClient.kt`:

```kotlin
package com.allfolio.market.rate.fred

import com.allfolio.fx.RateValuePolicy
import com.allfolio.market.rate.RateFetch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Duration
import java.time.LocalDate

/**
 * FRED `series/observations` 호출.
 *
 * **인증키를 로그에 남기지 않는다.** 키가 쿼리 파라미터에 실려서, 전체 URL을 찍거나
 * 예외에 cause를 붙이면(Reactor의 checkpoint 프레임에 요청 URI가 통째로 들어 있다)
 * 그대로 샌다. `EcosStatisticSearchClient`가 같은 이유로 같은 방어를 한다.
 */
@Component
class FredApiClient(
    private val properties: FredProperties,
    private val parser: FredObservationParser,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(properties.baseUrl)
            .codecs { it.defaultCodecs().maxInMemorySize(8 * 1024 * 1024) }
            .build()
    }

    internal var timeout: Duration = DEFAULT_TIMEOUT

    fun fetch(seriesId: String, from: LocalDate, to: LocalDate): RateFetch {
        // 설정 누락은 서버 문제다 — NO_KEY로 던져 GlobalExceptionHandler가 500으로 내보내게 한다.
        // 502로 나가면 운영자가 멀쩡한 세인트루이스 연은을 확인하러 간다
        if (properties.apiKey.isBlank()) {
            throw FredApiException("NO_KEY", "FRED 인증키가 설정되지 않았습니다 (FRED_API_KEY)")
        }
        if (seriesId.isBlank()) {
            throw FredApiException("NO_SERIES", "FRED 시리즈 ID가 설정되지 않았습니다")
        }

        log.info("[FRED] 조회 seriesId={} {}~{}", seriesId, from, to)

        val body = try {
            webClient.get()
                .uri { b ->
                    b.path("/fred/series/observations")
                        .queryParam("series_id", seriesId)
                        .queryParam("api_key", properties.apiKey)
                        .queryParam("file_type", "json")
                        .queryParam("observation_start", from)
                        .queryParam("observation_end", to)
                        .build()
                }
                .retrieve()
                .bodyToMono(String::class.java)
                .block(timeout)
                ?: throw FredApiException("EMPTY", "응답 본문이 비어 있습니다")
        } catch (e: FredApiException) {
            throw e
        } catch (e: WebClientResponseException) {
            // 상태만 남긴다. 본문에는 우리 요청 URL이 되울려 올 수 있고 거기 키가 들어 있다.
            // cause도 붙이지 않는다 — Reactor checkpoint 프레임에 URI가 통째로 있다
            log.warn("[FRED] HTTP {} seriesId={}", e.statusCode.value(), seriesId)
            throw FredApiException("HTTP-${e.statusCode.value()}", "FRED가 HTTP ${e.statusCode.value()} 를 반환했습니다")
        } catch (e: Throwable) {
            if (e is Error) throw e
            if (e is InterruptedException || e.cause is InterruptedException) Thread.currentThread().interrupt()
            log.warn("[FRED] 호출 실패 seriesId={} reason={}", seriesId, e.javaClass.simpleName)
            throw FredApiException("IO", "FRED 호출에 실패했습니다")
        }

        return parser.parse(body, RateValuePolicy.PERCENT)
    }

    companion object {
        private val DEFAULT_TIMEOUT = Duration.ofSeconds(30)
    }
}
```

- [ ] **Step 6: `GlobalExceptionHandler`는 건드리지 않는다 — 확인만 한다**

설계 문서는 "FRED 키 미설정은 ECOS의 `NO_KEY`와 같이 500으로 나가야 한다"고 적었지만,
**`FredApiException`은 핸들러까지 가지 않는다.** `RateCollectService`가 대상마다
`catch (e: Exception)`으로 잡아 요약의 `failures`로 옮기기 때문이다 — 지금 `EcosApiException`도
같은 이유로 핸들러에 안 닿는다.

그러니 핸들러에 매핑을 더하지 않는다. 안 닿는 분기를 추가하면 테스트할 수 없는 코드가 된다.
나중에 FRED를 직접 부르는 경로(탐색 엔드포인트 같은)가 생기면 그때 더한다.

`RateCollectService`가 정말로 `FredApiException`을 삼키는지 눈으로 확인할 것 —
`catch` 절이 `Exception`이 아니라 특정 타입으로 좁혀져 있으면 이 판단이 틀린다.

- [ ] **Step 7: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/rate/fred allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/rate/fred
git commit -m "feat(fred): FRED 클라이언트 + 파서 — 결측 마침표는 파싱 단계에서 거른다"
```

---

### Task 4: `FredRateSource` + 설정

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/rate/fred/FredRateSource.kt`
- Modify: `allfolio-backend/backend-app/src/main/resources/application.yml`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/rate/fred/FredRateSourceTest.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/rate/MarketRatePropertiesYamlTest.kt`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```kotlin
package com.allfolio.market.rate.fred

import com.allfolio.market.rate.MarketRateProperties
import com.allfolio.market.rate.RateFetch
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

class FredRateSourceTest {

    private val from = LocalDate.of(2026, 8, 1)
    private val to = LocalDate.of(2026, 8, 14)

    @Test
    fun `설정의 코드를 담당한다`() {
        assertThat(source().codes).containsExactly("UST_10Y")
        assertThat(source().sourceName).isEqualTo("FRED")
    }

    @Test
    fun `설정의 시리즈 ID로 조회한다`() {
        val client = FakeClient()

        source(client).fetch("UST_10Y", from, to)

        assertThat(client.requested).containsExactly(Triple("DGS10", from, to))
    }

    /** 설정에 없는 코드가 오면 조용히 빈 결과를 주지 않는다 — 설정과 코드가 어긋난 것이다 */
    @Test
    fun `설정에 없는 코드는 예외다`() {
        assertThatThrownBy { source().fetch("UST_2Y", from, to) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("UST_2Y")
    }

    private fun source(client: FakeClient = FakeClient()): FredRateSource {
        val properties = MarketRateProperties().apply {
            fred = listOf(
                MarketRateProperties.FredSeries().apply { code = "UST_10Y"; seriesId = "DGS10" },
            )
        }
        return FredRateSource(client, properties)
    }

    private class FakeClient : FredApiClient(FredProperties(), FredObservationParser(com.fasterxml.jackson.databind.ObjectMapper())) {
        val requested = mutableListOf<Triple<String, LocalDate, LocalDate>>()

        override fun fetch(seriesId: String, from: LocalDate, to: LocalDate): RateFetch {
            requested += Triple(seriesId, from, to)
            return RateFetch(emptyList(), 0)
        }
    }
}
```

> `FredApiClient`가 `open`이 아니면 위 `FakeClient`가 컴파일되지 않는다.
> `kotlin("plugin.spring")`이 `@Component`를 all-open으로 열어 주므로 대개 그대로 된다 —
> 안 되면 **프로덕션 클래스를 열지 말고** 클라이언트를 인터페이스로 뽑을 것.

- [ ] **Step 2: 컴파일 실패를 확인한다**

Run: `cd allfolio-backend && ./gradlew :backend-app:compileTestKotlin --no-daemon`
Expected: FAIL — `Unresolved reference: FredRateSource`

- [ ] **Step 3: 소스를 만든다**

```kotlin
package com.allfolio.market.rate.fred

import com.allfolio.market.rate.MarketRateProperties
import com.allfolio.market.rate.RateFetch
import com.allfolio.market.rate.RateSource
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * FRED(세인트루이스 연은) 미국 금리 소스.
 *
 * 값 정책·구간 밖 필터·멱등 upsert는 전부 공용이다 — 이 클래스는 설정의 시리즈 ID로
 * 조회만 한다. [RateSource]의 KDoc 참조.
 */
@Component
class FredRateSource(
    private val client: FredApiClient,
    private val properties: MarketRateProperties,
) : RateSource {

    override val sourceName = "FRED"

    override val codes: List<String>
        get() = properties.fred.map { it.code }

    override fun fetch(code: String, from: LocalDate, to: LocalDate): RateFetch {
        val series = properties.fred.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("FRED 설정에 없는 금리 코드입니다: $code")
        return client.fetch(series.seriesId, from, to)
    }
}
```

- [ ] **Step 4: `application.yml`에 설정을 더한다**

`market-rate:` 블록에 `fred:` 목록을 더한다:

```yaml
  # 미국 금리 — FRED(세인트루이스 연은). 시리즈 ID는 FRED 문서 기준이며 배포 후 실호출로 확인한다.
  #
  # T10Y2Y(10년-2년 스프레드)는 **일부러 안 받는다** — UST_10Y - UST_2Y로 조회 시 계산한다.
  # 저장해 두면 어느 날 둘이 어긋났을 때 어느 쪽이 맞는지 가릴 방법이 없다.
  # 한·미 기준금리차(BASE_RATE - US_FFR)도 같은 이유로 저장하지 않는다.
  fred:
    - { code: US_FFR,  series-id: DFF }
    - { code: UST_2Y,  series-id: DGS2 }
    - { code: UST_10Y, series-id: DGS10 }
    - { code: UST_30Y, series-id: DGS30 }
```

그리고 `ecos:` 블록 근처에 FRED 접속 설정을 더한다:

```yaml
fred:
  api-key: ${FRED_API_KEY:}
  base-url: ${FRED_BASE_URL:https://api.stlouisfed.org}
```

- [ ] **Step 5: 실제 yml 단언을 갱신한다**

`MarketRatePropertiesYamlTest`에 미국 4종 단언을 더한다 — **이 테스트가 YAML 오타를 잡는 유일한 그물이다**:

```kotlin
    @Test
    fun `application yml에 미국 금리 4종이 들어 있다`() {
        assertThat(properties.fred.map { "${it.code}=${it.seriesId}" })
            .containsExactly(
                "US_FFR=DFF",      // 연방기금금리
                "UST_2Y=DGS2",     // 미국채 2년
                "UST_10Y=DGS10",   // 미국채 10년
                "UST_30Y=DGS30",   // 미국채 30년
            )
    }
```

- [ ] **Step 6: 조회 API가 미국 코드도 싣게 한다 — 이게 없으면 수집만 되고 화면에 안 나온다**

**계획이 원래 여기를 틀리게 적었다.** "조회 API는 `rates`를 그대로 싣고 있어서 아무것도 안 고쳐도
미국 금리가 실린다"고 썼는데, `MarketQueryService.rateViews()`는 **설정 목록에서 코드를 열거한다** —
`properties.ecos`만 읽으므로 FRED 코드는 조회 대상에 아예 안 들어간다. 수집은 되고 DB에도 쌓이는데
화면에는 안 나오는, 조용한 종류의 실패다.

`MarketRateProperties`에 계산 프로퍼티를 하나 둔다:

```kotlin
    /**
     * 수집·조회 양쪽이 쓰는 전체 코드 목록. **순서가 한국 → 미국이고, 그게 화면 순서가 된다.**
     *
     * 이 프로퍼티가 있는 이유: 코드 목록을 필요로 하는 곳이 둘(수집 서비스는 소스를 통해,
     * 조회 서비스는 여기를 통해)인데, 양쪽이 각자 `ecos + fred`를 더하면 소스가 셋이 되는 날
     * 한쪽만 고쳐진다. 그때 증상은 "수집은 되는데 화면에 없다"이고, 오류도 로그도 안 난다.
     */
    val allCodes: List<String>
        get() = ecos.map { it.code } + fred.map { it.code }
```

`MarketQueryService.rateViews()`가 `rateProperties.ecos` 대신 `rateProperties.allCodes`를 돌게 하고,
`validate()`의 중복 검사도 이 프로퍼티를 쓰게 정리한다.

**테스트**: 조회 서비스 테스트에 미국 코드가 섞인 설정을 주고 두 나라 코드가 다 실리는지 단언한다.
`MarketQueryServiceTest`의 기존 설정 픽스처가 `ecos`만 채우고 있을 것이므로 `fred`도 채운다.

- [ ] **Step 7: 죽은 설정 키를 가리키는 운영 문구를 고친다**

Task 2에서 `market-rate.series` → `market-rate.ecos`로 바꿨는데, **운영자에게 그 키를 알려주는
문구 네 곳이 아직 옛 이름을 말한다.** Task 2는 단언 수정이 금지돼 있어 남겨 뒀다.

- `MarketRateAdminController` — "수집 대상 금리가 설정에 없습니다 — application.yml의 `market-rate.series`를 확인하세요"
- `MarketRateAdminControllerTest` — 위 문구를 단언한다
- `.github/workflows/collect-rate.yml` — 오류 범례에서 세 곳

이제 `fred`가 실제로 yml에 들어왔으므로 **두 목록을 다 가리키게** 고친다
(예: "`market-rate.ecos`·`market-rate.fred`를 확인하세요"). 없는 키를 가리키는 안내는
운영자를 엉뚱한 곳으로 보낸다 — 특히 `requested == 0`은 설정을 보라는 뜻인데 그 설정 이름이 틀렸다.

- [ ] **Step 8: 테스트**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*Fred*' --tests '*MarketRate*' --tests '*MarketQuery*' --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: 커밋**

```bash
git add allfolio-backend/backend-app/src allfolio-backend/backend-app/src/main/resources/application.yml
git commit -m "feat(fred): 미국 금리 4종 수집 — 스프레드는 저장하지 않는다"
```

---

### Task 5: 전 모듈 검증 + PR

- [ ] **Step 1: 전 모듈 테스트**

```bash
cd allfolio-backend && ./gradlew test --rerun-tasks --no-daemon
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 범위 확인**

```bash
git diff --stat origin/main...HEAD
```

**`MarketQueryService`는 diff에 있는 것이 맞다** — Task 4 Step 6에서 `allCodes`를 쓰게 고쳤다.
계획이 원래 "조회 API는 아무것도 안 고쳐도 미국 금리가 실린다"고 적었는데 그건 틀렸다:
조회 서비스가 설정 목록에서 코드를 열거하므로 고치지 않으면 수집만 되고 화면에는 안 나온다.

범위가 샌 신호는 이쪽이다 — `MarketRateEntity`·`MarketRateJpaRepository`·마이그레이션이
diff에 있으면 저장 계층을 건드린 것이고, 이 계획은 소스만 추가한다.

- [ ] **Step 3: 푸시하고 PR**

```bash
git push -u origin feat/af-fred-us-rates
```

PR 본문에 담을 것:
- **키를 등록하기 전에는 미국 금리가 안 들어온다** — `FRED_API_KEY`가 비면 `NO_KEY`로 실패하고
  요약의 `failures`에 4종이 남는다. 한국 6종은 그대로 수집된다(종목별 격리)
- `series` → `ecos` 설정 키 이름 변경이 있다는 것
- 결측 마침표를 값 검증이 아니라 파싱 단계에서 거르는 이유

- [ ] **Step 4: CI 확인**

```bash
gh pr checks --watch
```

---

### Task 6: 배포 후 — 키 등록·확인·백필 (사용자 작업 포함)

- [ ] **Step 1: Render에 `FRED_API_KEY`를 등록하고 재시작한다**

- [ ] **Step 2: 세 가지를 확인한다**

```bash
curl -sS -X POST -H "Authorization: Bearer $JWT" \
  "https://allfolio.onrender.com/api/admin/rate/collect?from=2026-07-01&to=$(date +%F)" \
  | python3 -m json.tool
```

확인할 것:
- **`failed`가 0인가** — 4종이 다 들어왔는지
- **`skippedRows`가 몇인가** — 결측 마침표가 걸러진 수다. 주말·휴일이 낀 구간이면 0이 아닌 게 정상이다
- **없는 시리즈 ID에 FRED가 무엇을 주는가** — 설정에 일부러 오타(`DGS10X`)를 넣고 한 번 돌려
  HTTP 400인지 빈 결과인지 본다. **오류를 준다면 ECOS 때 필요했던 탐색 엔드포인트가 필요 없다.**
  확인 후 오타는 되돌린다

- [ ] **Step 3: 값 단위를 확인한다**

`UST_10Y`가 `4.25` 근처인지 `0.0425` 근처인지 본다. 후자면 소스에서 100을 곱해야 하고,
그건 설계가 틀린 것이므로 멈추고 보고한다.

- [ ] **Step 4: 백필**

```bash
for y in 2020 2021 2022 2023 2024 2025 2026; do
  echo "=== $y ==="
  curl -sS -X POST -H "Authorization: Bearer $JWT" \
    "https://allfolio.onrender.com/api/admin/rate/collect?from=$y-01-01&to=$y-12-31" \
    | python3 -c "import json,sys; b=json.load(sys.stdin); print(' '.join(f'{k}={b.get(k)}' for k in ('requested','collected','inserted','skippedRows','failed')))"
done
```

**해마다 끊는다** — `em.merge`가 행마다 SELECT를 내므로 한 번에 부르면 순차 왕복이 수천 회가 된다.

- [ ] **Step 5: 값 대조**

```sql
SELECT rate_code, COUNT(*) AS rows, MIN(quote_date), MAX(quote_date)
FROM market_rate WHERE source = 'FRED' GROUP BY rate_code ORDER BY rate_code;
```

최근 값을 FRED 사이트와 눈으로 대조한다. **"수집됐다"에서 멈추지 말 것** —
AF-102를 그 자리에서 닫은 이유가 그것이다.

- [ ] **Step 6: 노션 갱신**

FRED 태스크를 완료로 바꾸고, 확인한 시리즈 ID·값 단위·없는 ID의 응답을 적는다.

---

## 완료 후 보고할 것

- Task 5 전 모듈 테스트 결과
- Task 6 Step 2의 세 가지 — 특히 **없는 시리즈 ID에 FRED가 오류를 주는지**
- Task 6 Step 5 값 대조 결과 (수집됐다가 아니라 값이 맞다까지)
