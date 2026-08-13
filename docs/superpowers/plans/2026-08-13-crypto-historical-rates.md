# 과거 크립토 시세 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 날짜를 소급한 BTC·ETH 현금흐름에 "오늘 시세"가 굳는 것을 막는다 — Upbit 일봉을 과거 시세 소스로 붙인다.

**Architecture:** `FxRateBackfillService`에서 **가져오기와 저장하기를 가른다.** 소스는 1단위로 정규화된 `DailyRate`를 돌려주고 자기 이름을 밝히며, 서비스는 0건 중단·범위 밖 제거·dedupe·계수·캐시 무효화를 소스와 무관하게 한 벌만 유지한다. 저장은 기존 `fx_rate_daily`에 `source='UPBIT'`로 들어가 마이그레이션이 없다.

**Tech Stack:** Kotlin, Spring Boot(WebFlux `WebClient`), Jackson, JUnit 5, AssertJ, Gradle

**Spec:** [2026-08-13-crypto-historical-rates-design.md](../specs/2026-08-13-crypto-historical-rates-design.md)

---

## File Structure

**신규** (`allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/`)

| 파일 | 책임 |
|---|---|
| `HistoricalRateSource.kt` | 포트 + `DailyRate` · `SourceFetch` |
| `EcosHistoricalRateSource.kt` | ECOS 전용 가져오기 (기존 서비스에서 추출) |
| `upbit/UpbitCandleParser.kt` | 일봉 JSON → `SourceFetch`(rates + skipped) (순수) |
| `upbit/UpbitCandleClient.kt` | 일봉 HTTP 호출 |
| `upbit/UpbitCandleRateSource.kt` | 페이지네이션 + `supports("BTC"\|"ETH")` |

**수정**

| 파일 | 변경 |
|---|---|
| `FxRateBackfillService.kt` | 생성자가 `List<HistoricalRateSource>`를 받고, ECOS 전용 로직을 뺀다 |
| `UnifiedAssetFxConverterAdapter.kt` | `HISTORICAL`에 BTC·ETH 추가, `CRYPTO` 조기 반환 삭제 |
| `FxRateBackfillServiceTest.kt` | `service(...)` 헬퍼 **한 곳**만 고친다 (17개 테스트는 그대로) |

**신규 테스트**: `upbit/UpbitCandleParserTest.kt` · `upbit/UpbitCandleRateSourceTest.kt` · `HistoricalCryptoRateTest.kt`

### 실측으로 확인한 제약 (2026-08-13)

- `GET /v1/candles/days?market=KRW-{SYM}&to={ISO8601}&count={n}` — 무인증
- **요청당 최대 200건.** `count=201`도 `count=500`도 **200건만 조용히** 돌아온다 → 페이지네이션 필수
- 레이트리밋 `remaining-req: group=candles; min=600; sec=9`
- 응답은 **최신순 내림차순**, 종가는 `trade_price`, 날짜는 `candle_date_time_kst`(일봉은 `T09:00:00`)
- **`to`는 배타적이다**: `to=2026-08-03T00:00:00+09:00`이 08-02·08-01·07-31을 돌려줬다.
  따라서 날짜 D를 포함하려면 `to = (D+1)T00:00:00+09:00`

---

### Task 1: 포트와 타입

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/HistoricalRateSource.kt`

- [ ] **Step 1: 포트 작성**

```kotlin
package com.allfolio.fx

import java.math.BigDecimal
import java.time.LocalDate

/**
 * 하루치 환율. **이미 1단위 기준으로 정규화돼 있다.**
 *
 * 정규화를 소스 책임으로 둔 이유: ECOS는 JPY를 100엔 단위로 고시해 나눗셈이 필요하지만
 * Upbit 일봉에는 그런 개념이 아예 없다. 서비스에 두면 Upbit 값에 엉뚱한 제수가 걸린다.
 */
data class DailyRate(val baseDate: LocalDate, val rateKrw: BigDecimal)

/**
 * @param rates   요청 구간의 일별 환율. 비어 있으면 호출자가 기존 값을 덮지 않고 중단한다
 * @param skipped 소스가 파싱 단계에서 버린 행 수 — [BackfillSummary.skipped]로 그대로 나간다
 */
data class SourceFetch(val rates: List<DailyRate>, val skipped: Int)

/**
 * 과거 환율 한 소스.
 *
 * **가져오기만 소스별이고 저장하기는 공용이다.** 0건 중단·범위 밖 제거·중복 접기·
 * inserted/updated/unchanged 계수는 [FxRateBackfillService]가 한 벌만 갖는다 —
 * ECOS를 겪으며 생긴 방어지만 소스와 무관하게 옳다.
 */
interface HistoricalRateSource {
    /** `fx_rate_daily.source`에 들어갈 값 */
    val sourceName: String

    fun supports(currency: String): Boolean

    /** 실패는 예외로 알린다 — 호출자가 상태 코드로 옮긴다. */
    fun fetch(currency: String, from: LocalDate, to: LocalDate): SourceFetch
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:compileKotlin`
Expected: `BUILD SUCCESSFUL` (순수 추가라 아무것도 깨지지 않는다)

- [ ] **Step 3: Commit**

```bash
cd /Users/hong9/IdeaProjects/allfolio/.claude/worktrees/silly-almeida-a439a1
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/HistoricalRateSource.kt
git commit -m "feat(af-100): 과거 환율 소스 포트 추가"
```

---

### Task 2: ECOS 소스 추출과 서비스 재배선 (원자적 — 한 커밋)

> **쪼개면 컴파일이 깨진다.** 서비스 생성자를 바꾸는 순간 테스트 헬퍼가 같이 깨지므로 Step 1~5를 끝낸 뒤 한 번만 커밋한다.

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/EcosHistoricalRateSource.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/FxRateBackfillService.kt`
- Modify: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/FxRateBackfillServiceTest.kt`

#### Step 1: ECOS 소스 작성

`EcosHistoricalRateSource.kt`:

```kotlin
package com.allfolio.fx

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.RoundingMode
import java.time.LocalDate

/**
 * ECOS(한국은행) 과거 환율 소스 (AF-100).
 *
 * [FxRateBackfillService]에서 뽑아낸 것이라 동작은 그대로다. 옮겨 온 것은 셋이다:
 * 시계열 설정 조회 · 호출과 예외 로깅 · **고시 단위 정규화**.
 *
 * 정규화가 여기 있어야 하는 이유: `unitDivisor`는 ECOS가 JPY를 100엔 단위로 고시해서
 * 필요한 것이다. 서비스에 두면 Upbit 일봉에도 제수가 걸린다.
 */
@Component
class EcosHistoricalRateSource(
    private val client: EcosApiClient,
    private val properties: EcosProperties,
) : HistoricalRateSource {

    private val log = LoggerFactory.getLogger(javaClass)

    override val sourceName = "ECOS"

    companion object {
        private const val SCALE = 6
    }

    override fun supports(currency: String): Boolean = seriesOf(currency) != null

    override fun fetch(currency: String, from: LocalDate, to: LocalDate): SourceFetch {
        val series = seriesOf(currency)
            ?: throw IllegalArgumentException("ECOS 시계열 설정이 없는 통화입니다: $currency")

        // 예외는 그대로 올려보낸다 — 호출자(어드민 엔드포인트)가 상태 코드로 옮긴다.
        // 스택을 통째로 찍지 않는 이유: EcosStatisticSearchClient가 인증키(URL 경로에 있다)를
        // 흘리지 않도록 예외를 정제해 두는데, 여기서 원본 스택을 찍으면 그 방어가 무의미해질 수 있다.
        val result = try {
            client.fetchDailyRates(series.statCode, series.itemCode, from, to)
        } catch (e: Exception) {
            // INFO-200("해당 기간 데이터 없음")도 여기로 온다. 별도로 가르지 않는 이유는
            // 결과가 같기 때문이다 — 어느 쪽이든 한 행도 쓰지 않고 중단한다.
            log.warn(
                "[ECOS] 백필 실패 currency={} {}~{} reason={} code={}",
                currency, from, to, e.javaClass.simpleName, (e as? EcosApiException)?.code,
            )
            throw e
        }

        // 고시 단위를 1단위로 되돌린다 — JPY 100엔 고시가 그대로 들어가면 100배가 된다
        val rates = result.rates.map {
            DailyRate(it.baseDate, it.rateKrw.divide(series.unitDivisor, SCALE, RoundingMode.HALF_UP))
        }
        return SourceFetch(rates, result.skipped)
    }

    /**
     * 통화 설정을 대소문자 무관하게 찾는다.
     *
     * 맵 키는 YAML에 쓴 그대로 들어오는데, 환경변수로 주입하면(ECOS_SERIES_JPY_STAT_CODE)
     * relaxed binding이 `ecos.series.jpy.*`로 소문자화한다. 대문자만 보면 그때 "설정이 없는 통화"로
     * 오진하고, 그건 설정 문제로 위장한 코드 문제라 운영에서 가장 찾기 어려운 종류다.
     */
    private fun seriesOf(code: String): EcosProperties.Series? =
        properties.series.entries.firstOrNull { it.key.equals(code, ignoreCase = true) }?.value
}
```

#### Step 2: 서비스에서 ECOS 전용 로직을 뺀다

`FxRateBackfillService.kt`를 아래 넷만 고친다. **다른 부분(0건 중단·범위 밖 제거·dedupe·계수·`saveAll`·`invalidate`·`BackfillSummary`)은 그대로 둔다.**

**(1) import에서 `RoundingMode` 제거** (더 이상 안 쓴다), 나머지는 유지.

**(2) 생성자와 companion:**

```kotlin
@Service
class FxRateBackfillService(
    private val sources: List<HistoricalRateSource>,
    private val repository: HistoricalFxRateJpaRepository,
    private val fxConverter: UnifiedAssetFxConverterAdapter,
) {
    private val log = LoggerFactory.getLogger(javaClass)
```

companion object는 **통째로 삭제한다** — `SOURCE`는 소스가 밝히고 `SCALE`은 소스로 갔다.

**(3) `backfill` 앞부분** — `seriesOf`/`client` 호출을 소스 선택으로 바꾼다:

```kotlin
    fun backfill(currency: String, from: LocalDate, to: LocalDate): BackfillSummary {
        val code = currency.trim().uppercase()
        require(!from.isAfter(to)) { "from은 to보다 이후일 수 없습니다: $from > $to" }

        val source = sources.firstOrNull { it.supports(code) }
            ?: throw IllegalArgumentException("과거 환율 소스가 없는 통화입니다: $code")

        val result = source.fetch(code, from, to)

        // 빈 응답으로 기존 값을 덮지 않는다 — 통계표 코드가 틀려도 0건이 온다
        check(result.rates.isNotEmpty()) {
            "${source.sourceName} 응답 0건 — 기존 값을 덮지 않고 중단합니다 (currency=$code $from~$to)"
        }
```

**(4) 이후 본문**에서 ECOS 이름과 정규화를 걷어낸다:

- `val inRange = result.rates.filter { ... }` 이후 로그 `"[ECOS] 요청 범위 밖 ..."` → `"[Backfill] 요청 범위 밖 {}건 제거 source={} currency={} {}~{}"` (인자에 `source.sourceName` 추가)
- `dedupe(inRange, code, from, to)` 시그니처의 `List<EcosRate>` → `List<DailyRate>`, 반환 `Map<LocalDate, DailyRate>`. 본문 로직은 그대로. 로그 접두사 `[ECOS]` → `[Backfill]`이고 `source.sourceName`을 인자로 받는다 (`dedupe(inRange, source.sourceName, code, from, to)`)
- `check(rates.isNotEmpty())` 메시지의 `ECOS` → `${source.sourceName}`
- `rows` 매핑에서 **정규화를 없앤다**:

```kotlin
        val rows = rates.values.map { rate ->
            val prior = existing[rate.baseDate]
                ?: return@map HistoricalFxRateEntity(
                    id = UUID.randomUUID(),
                    baseDate = rate.baseDate,
                    currency = code,
                    rateKrw = rate.rateKrw,
                    source = source.sourceName,
                    createdAt = LocalDateTime.now(),
                ).also { inserted++ }

            // 반드시 덮기 전에 센다. compareTo로 비교하는 이유는 스케일이 달라도 같은 값이기 때문이다
            // (1385.5와 1385.500000은 equals로는 다르다).
            if (prior.rateKrw.compareTo(rate.rateKrw) == 0) unchanged++ else updated++
            prior.apply {
                rateKrw = rate.rateKrw
                source = source.sourceName
            }
        }
```

- 마지막 로그 `log.info("[ECOS] 백필 완료 {}", summary)` → `log.info("[Backfill] 완료 source={} {}", source.sourceName, summary)`
- `seriesOf` 함수와 그 KDoc은 **삭제한다** (소스로 옮겼다)

> `prior.apply { source = source.sourceName }` 는 `apply` 안에서 `source`가 엔티티 필드를 가려 컴파일 오류가 난다. 바깥 변수 이름을 `rateSource`로 바꾸거나 `this.source = rateSource.sourceName`로 쓸 것. **이 이름 충돌을 반드시 처리하고 넘어갈 것.**

#### Step 3: 테스트 헬퍼 한 곳만 고친다

`FxRateBackfillServiceTest.kt`의 `service(...)` 헬퍼 본문만 바꾼다. **시그니처는 그대로 두어 17개 테스트가 손대지 않고 통과해야 한다.**

```kotlin
    private fun service(
        client: EcosApiClient,
        repo: HistoricalFxRateJpaRepository,
        properties: EcosProperties = this.properties,
        converter: UnifiedAssetFxConverterAdapter = adapter(repo),
    ) = FxRateBackfillService(listOf(EcosHistoricalRateSource(client, properties)), repo, converter)
```

#### Step 4: 기존 테스트 전량 통과 확인

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.fx.FxRateBackfillServiceTest"`
Expected: PASS (17 tests)

**이게 이 태스크의 핵심 검증이다.** 특히 아래 둘이 통과해야 추출이 옳다:
- `고시 단위를 1단위로 정규화해 저장한다` — `unitDivisor`가 소스로 갔는데도 그대로 동작
- 소문자 시계열 키 테스트 — `seriesOf`가 소스로 갔는데도 그대로 동작

**하나라도 깨지면 테스트를 고치지 말고 보고할 것.** 추출이 동작을 바꿨다는 뜻이다.

#### Step 5: 전체 테스트 + 커밋

Run: `cd allfolio-backend && ./gradlew :backend-app:test`
Expected: `BUILD SUCCESSFUL`

```bash
cd /Users/hong9/IdeaProjects/allfolio/.claude/worktrees/silly-almeida-a439a1
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/ allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/FxRateBackfillServiceTest.kt
git commit -m "refactor(af-100): 백필에서 가져오기와 저장하기를 가른다

ECOS 전용이던 셋(시계열 조회·호출·고시 단위 정규화)을 EcosHistoricalRateSource로
옮기고, 서비스에는 소스와 무관하게 옳은 방어만 남긴다 — 0건 중단·범위 밖 제거·
중복 접기·계수·캐시 무효화.

unitDivisor를 소스로 내린 이유: ECOS가 JPY를 100엔 단위로 고시해서 필요한 나눗셈이라
서비스에 두면 Upbit 일봉에 엉뚱한 제수가 걸린다.

기존 17개 테스트는 헬퍼 한 줄만 고쳐 그대로 통과한다."
```

---

### Task 3: `UpbitCandleParser` (TDD)

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/upbit/UpbitCandleParser.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/upbit/UpbitCandleParserTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
package com.allfolio.fx.upbit

import com.allfolio.fx.DailyRate
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

class UpbitCandleParserTest {

    private val parser = UpbitCandleParser(ObjectMapper())

    /** 2026-08-13 api.upbit.com/v1/candles/days 실제 응답에서 필드를 줄인 것 (최신순 내림차순) */
    private val realResponse = """
        [{"market":"KRW-BTC","candle_date_time_utc":"2026-08-02T00:00:00",
          "candle_date_time_kst":"2026-08-02T09:00:00","opening_price":90557000.0,
          "high_price":91398000.0,"low_price":90419000.0,"trade_price":90890000.0},
         {"market":"KRW-BTC","candle_date_time_utc":"2026-08-01T00:00:00",
          "candle_date_time_kst":"2026-08-01T09:00:00","opening_price":90360000.0,
          "high_price":90818000.0,"low_price":89800000.0,"trade_price":90557000.0}]
    """.trimIndent()

    @Test
    fun `종가와 KST 날짜를 뽑는다`() {
        val rates = parser.parse(realResponse)

        assertThat(rates).hasSize(2)
        assertThat(rates[0]).isEqualTo(DailyRate(LocalDate.of(2026, 8, 2), java.math.BigDecimal("9.089E+7")))
        assertThat(rates[1].baseDate).isEqualTo(LocalDate.of(2026, 8, 1))
        assertThat(rates[1].rateKrw).isEqualByComparingTo("90557000")
    }

    @Test
    fun `UTC가 아니라 KST 날짜를 쓴다`() {
        // candle_date_time_utc는 2026-08-01T00:00, kst는 2026-08-01T09:00으로 같은 날이지만
        // 우리 도메인(cash_flow.flow_date)이 KST라 kst를 봐야 한다. utc를 쓰면 어떤 날은 하루 밀린다.
        val json = """
            [{"market":"KRW-ETH","candle_date_time_utc":"2026-07-31T00:00:00",
              "candle_date_time_kst":"2026-07-31T09:00:00","trade_price":2674000.0}]
        """.trimIndent()

        assertThat(parser.parse(json).single().baseDate).isEqualTo(LocalDate.of(2026, 7, 31))
    }

    @Test
    fun `빈 배열이면 빈 리스트 - 예외가 아니다`() {
        // 구간에 데이터가 없는 것은 정상 응답이다. 중단 판단은 호출자(백필 서비스)가 한다.
        assertThat(parser.parse("[]")).isEmpty()
    }

    @Test
    fun `trade_price가 없는 캔들은 건너뛰고 나머지는 살린다`() {
        val json = """
            [{"candle_date_time_kst":"2026-08-02T09:00:00","trade_price":90890000.0},
             {"candle_date_time_kst":"2026-08-01T09:00:00","opening_price":90360000.0}]
        """.trimIndent()

        assertThat(parser.parse(json)).hasSize(1)
    }

    @Test
    fun `날짜가 없는 캔들은 건너뛴다`() {
        val json = """[{"trade_price":90890000.0}]"""

        assertThat(parser.parse(json)).isEmpty()
    }

    @Test
    fun `JSON이 아니면 예외`() {
        assertThatThrownBy { parser.parse("<html>점검중</html>") }
            .isInstanceOf(UpbitCandleException::class.java)
    }

    @Test
    fun `배열이 아니면 예외 - 오류 응답이 객체로 온다`() {
        assertThatThrownBy { parser.parse("""{"error":{"name":"invalid_market"}}""") }
            .isInstanceOf(UpbitCandleException::class.java)
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.fx.upbit.UpbitCandleParserTest"`
Expected: FAIL — `Unresolved reference: UpbitCandleParser`

- [ ] **Step 3: 구현**

```kotlin
package com.allfolio.fx.upbit

import com.allfolio.fx.DailyRate
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate

/** Upbit 일봉 조회가 실패했다는 신호. */
class UpbitCandleException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Upbit 일봉 응답 → 일별 종가.
 *
 * 응답 형태(최신순 내림차순):
 *   [{"candle_date_time_kst":"2026-08-01T09:00:00","trade_price":90557000.0}, ...]
 *
 * HTTP에서 분리한 이유는 앞선 FX 작업과 같다 — 파서에 테스트가 없어서 동작할 수 없는
 * 클라이언트가 배포된 적이 있고, 이 시리즈에서 파서 테스트가 실제 회귀를 두 번 잡았다.
 *
 * **UTC가 아니라 KST 날짜를 쓴다.** `cash_flow.flow_date`가 KST 기준이라 utc를 쓰면
 * 어떤 날은 하루 밀린 환율이 붙는다.
 *
 * 빈 배열은 예외가 아니다 — 구간에 데이터가 없는 건 정상이고, 중단 판단은 백필 서비스가 한다.
 */
@Component
class UpbitCandleParser(private val objectMapper: ObjectMapper) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun parse(body: String): List<DailyRate> {
        val root = try {
            objectMapper.readTree(body)
        } catch (e: Exception) {
            throw UpbitCandleException("Upbit 일봉 응답이 JSON이 아닙니다", e)
        }

        // 오류 응답은 배열이 아니라 객체로 온다 — 배열 가정을 먼저 확인한다
        if (!root.isArray) {
            throw UpbitCandleException("Upbit 일봉 응답이 배열이 아닙니다: ${body.take(120)}")
        }

        val rates = mutableListOf<DailyRate>()
        for (node in root) {
            val kst = node.get("candle_date_time_kst")?.asText()
            if (kst == null || kst.length < 10) {
                log.warn("[UpbitCandle] candle_date_time_kst가 없어 건너뜀")
                continue
            }

            val price = node.get("trade_price")
            if (price == null || !price.isNumber) {
                log.warn("[UpbitCandle] {} trade_price가 없거나 숫자가 아니라 건너뜀", kst)
                continue
            }

            val date = try {
                LocalDate.parse(kst.substring(0, 10))
            } catch (e: Exception) {
                log.warn("[UpbitCandle] 날짜를 읽지 못해 건너뜀: {}", kst)
                continue
            }

            rates += DailyRate(date, BigDecimal(price.asText()))
        }
        return rates
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.fx.upbit.UpbitCandleParserTest"`
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/upbit/UpbitCandleParser.kt allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/upbit/UpbitCandleParserTest.kt
git commit -m "feat(af-100): Upbit 일봉 파서 추가 (KST 날짜 기준)"
```

---

### Task 4: 클라이언트와 페이지네이션 소스 (TDD)

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/upbit/UpbitCandleClient.kt`
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/upbit/UpbitCandleRateSource.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/upbit/UpbitCandleRateSourceTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
package com.allfolio.fx.upbit

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.LocalDate
import java.util.Collections

/**
 * **페이지네이션을 못 박는다.**
 *
 * Upbit은 요청당 200건만 준다 — count=201도 count=500도 조용히 200건만 돌아온다(실측).
 * 즉 200일이 넘는 구간에서 페이지를 안 넘기면 **오래된 쪽이 조용히 비어** 그 날짜의
 * 현금흐름이 계속 현재가 폴백으로 떨어진다. 오류도 로그도 없이.
 */
class UpbitCandleRateSourceTest {

    private lateinit var server: HttpServer
    private val requests = Collections.synchronizedList(mutableListOf<String>())

    /** 요청받은 `to`에 따라 200건씩 내려주는 가짜 Upbit. 날짜만 있으면 되므로 가격은 고정한다. */
    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            val query = exchange.requestURI.query ?: ""
            requests += query
            val to = Regex("to=([^&]+)").find(query)?.groupValues?.get(1)?.let {
                LocalDate.parse(java.net.URLDecoder.decode(it, "UTF-8").substring(0, 10))
            } ?: LocalDate.of(2026, 8, 13)

            // to는 배타적이라 to-1일부터 200일치를 내림차순으로 만든다
            val body = (0 until 200).joinToString(",") { i ->
                val d = to.minusDays(1L + i)
                """{"candle_date_time_kst":"${d}T09:00:00","trade_price":90000000.0}"""
            }
            val bytes = "[$body]".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    /**
     * 쿼리에서 `to` 값을 꺼내 **시각으로** 비교한다.
     *
     * 문자열 비교를 하지 않는 이유가 두 겹이다. WebClient가 `:`를 퍼센트 인코딩할지는 구현
     * 세부이고, 더 고약하게는 `URLDecoder.decode`가 리터럴 `+`를 **공백으로** 바꾼다
     * (form-urlencoded 규칙). `+09:00`이 ` 09:00`이 되어, 커서 계산이 완벽히 맞는데도
     * 단언만 깨진다 — 실제로 이 함정에 한 번 빠졌다.
     * 그래서 `+`를 먼저 보호한 뒤 디코드하고, OffsetDateTime으로 파싱해 값을 비교한다.
     */
    private fun toParam(query: String): java.time.OffsetDateTime {
        val raw = Regex("to=([^&]+)").find(query)!!.groupValues[1]
        val decoded = java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
        return java.time.OffsetDateTime.parse(decoded)
    }

    private fun cursorOf(date: java.time.LocalDate): java.time.OffsetDateTime =
        java.time.OffsetDateTime.parse("${date}T00:00:00+09:00")

    private fun source() = UpbitCandleRateSource(
        UpbitCandleClient("http://localhost:${server.address.port}"),
        UpbitCandleParser(ObjectMapper()),
    )

    @Test
    fun `BTC와 ETH만 지원한다`() {
        val s = source()

        assertThat(s.supports("BTC")).isTrue()
        assertThat(s.supports("ETH")).isTrue()
        assertThat(s.supports("btc")).isTrue()
        assertThat(s.supports("USD")).isFalse()
        assertThat(s.supports("DOGE")).isFalse()
    }

    @Test
    fun `소스 이름은 UPBIT다`() {
        assertThat(source().sourceName).isEqualTo("UPBIT")
    }

    @Test
    fun `200일 이하 구간은 한 번만 요청한다`() {
        val to = LocalDate.of(2026, 8, 1)
        val from = to.minusDays(10)

        val fetched = source().fetch("BTC", from, to)

        assertThat(requests).hasSize(1)
        assertThat(fetched.rates.map { it.baseDate }).containsExactlyInAnyOrderElementsOf((0..10L).map { to.minusDays(it) })
    }

    @Test
    fun `to는 배타적이므로 요청에 to+1일을 싣는다`() {
        // to=2026-08-03T00:00 이 08-02까지 돌려주는 것을 실측했다. to 당일을 포함하려면 +1일.
        val to = LocalDate.of(2026, 8, 1)

        source().fetch("BTC", to.minusDays(3), to)

        assertThat(toParam(requests.single())).isEqualTo(cursorOf(LocalDate.of(2026, 8, 2)))
    }

    @Test
    fun `400일 구간은 두 번 요청하고 두 번째 to가 첫 페이지의 가장 오래된 날짜다`() {
        val to = LocalDate.of(2026, 8, 1)
        val from = to.minusDays(399)

        val fetched = source().fetch("BTC", from, to)

        assertThat(requests).hasSize(2)
        // 커서는 to+1에서 출발하므로 첫 페이지는 to ~ (to+1-200) = to-199 를 덮는다.
        // 따라서 두 번째 to는 to-199다. to-200으로 쓰면 하루가 조용히 빈다.
        assertThat(toParam(requests[1])).isEqualTo(cursorOf(to.minusDays(199)))
        assertThat(fetched.rates.map { it.baseDate }.min()).isEqualTo(from)
    }

    @Test
    fun `요청 구간 밖 날짜는 담지 않는다`() {
        val to = LocalDate.of(2026, 8, 1)
        val from = to.minusDays(5)

        val fetched = source().fetch("BTC", from, to)

        assertThat(fetched.rates.map { it.baseDate }).allMatch { it in from..to }
    }

    @Test
    fun `to가 뒤로 물러나지 않으면 중단한다 - 무한루프 방지`() {
        // 같은 페이지를 반복해 주는 서버. 방어가 없으면 영원히 돈다.
        server.removeContext("/")
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.query ?: ""
            val body = """[{"candle_date_time_kst":"2026-08-01T09:00:00","trade_price":9.0E7}]"""
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        assertThatThrownBy { source().fetch("BTC", LocalDate.of(2020, 1, 1), LocalDate.of(2026, 8, 1)) }
            .isInstanceOf(UpbitCandleException::class.java)
            .hasMessageContaining("진행하지 못했습니다")
    }

    @Test
    fun `빈 응답이면 더 요청하지 않고 모은 것만 돌려준다`() {
        server.removeContext("/")
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.query ?: ""
            val bytes = "[]".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        val fetched = source().fetch("BTC", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 1))

        assertThat(fetched.rates).isEmpty()
        assertThat(requests).hasSize(1)
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.fx.upbit.UpbitCandleRateSourceTest"`
Expected: FAIL — `Unresolved reference: UpbitCandleRateSource`

- [ ] **Step 3: 클라이언트 구현**

`UpbitCandleClient.kt`:

```kotlin
package com.allfolio.fx.upbit

import org.slf4j.LoggerFactory
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration

/**
 * Upbit 일봉 조회. HTTP만 한다 — 파싱은 [UpbitCandleParser].
 *
 * `GET /v1/candles/days?market=KRW-{SYM}&to={ISO8601}&count={n}` — 무인증, 무료.
 * 레이트리밋은 `remaining-req: group=candles; min=600; sec=9`.
 *
 * 코덱을 512KB로 두는 이유: 200건 × 캔들당 약 300B면 60KB 남짓이라 여유가 넉넉하다.
 */
class UpbitCandleClient(baseUrl: String) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(baseUrl)
            .codecs { it.defaultCodecs().maxInMemorySize(512 * 1024) }
            .build()
    }

    companion object {
        private val TIMEOUT = Duration.ofSeconds(10)
    }

    /** @param to 배타적 상한. 이 시각 **이전** 캔들만 온다. */
    fun fetchDays(market: String, to: String, count: Int): String =
        try {
            webClient.get()
                .uri { b -> b.path("/v1/candles/days").queryParam("market", market)
                    .queryParam("to", to).queryParam("count", count).build() }
                .retrieve()
                .bodyToMono(String::class.java)
                .block(TIMEOUT)
                ?: throw UpbitCandleException("Upbit 일봉 응답 본문이 비어 있습니다")
        } catch (e: UpbitCandleException) {
            throw e
        } catch (e: Throwable) {
            if (e is Error) throw e
            if (e is InterruptedException || e.cause is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            // block(TIMEOUT)의 타임아웃은 WebClientException이 아니라 IllegalStateException으로
            // 새로 던져진다. 예외 종류를 열거하면 그런 경로가 샌다.
            log.warn("[UpbitCandle] 호출 실패 market={} to={} reason={}", market, to, e.javaClass.simpleName)
            throw UpbitCandleException("Upbit 일봉 호출에 실패했습니다", e)
        }
}
```

- [ ] **Step 4: 소스 구현**

`UpbitCandleRateSource.kt`:

```kotlin
package com.allfolio.fx.upbit

import com.allfolio.fx.DailyRate
import com.allfolio.fx.HistoricalRateSource
import com.allfolio.fx.SourceFetch
import org.slf4j.LoggerFactory
import java.time.LocalDate

/**
 * Upbit 일봉 기반 과거 크립토 시세 소스.
 *
 * **페이지네이션이 이 클래스의 존재 이유다.** Upbit은 요청당 200건만 주는데
 * `count=201`도 `count=500`도 오류가 아니라 **조용히 200건만** 돌려준다(실측).
 * 페이지를 안 넘기면 오래된 구간이 소리 없이 비고, 그 날짜의 현금흐름은 계속
 * 현재가 폴백으로 떨어진다 — 오류도 로그도 없이.
 *
 * `to`는 **배타적**이다: `to=2026-08-03T00:00:00+09:00`이 08-02까지 돌려준다(실측).
 * 그래서 날짜 D를 포함하려면 `(D+1)T00:00:00+09:00`을 싣는다.
 */
class UpbitCandleRateSource(
    private val client: UpbitCandleClient,
    private val parser: UpbitCandleParser,
) : HistoricalRateSource {

    private val log = LoggerFactory.getLogger(javaClass)

    override val sourceName = "UPBIT"

    companion object {
        private val SUPPORTED = setOf("BTC", "ETH")
        private const val PAGE = 200
        /** 안전장치. 200건 × 100페이지 = 약 54년이라 어떤 현실적 구간도 덮는다. */
        private const val MAX_PAGES = 100
    }

    override fun supports(currency: String): Boolean = currency.trim().uppercase() in SUPPORTED

    /** `to`는 배타적이므로 포함하려는 마지막 날짜 + 1일을 넘긴다. */
    private fun cursor(exclusiveUpper: LocalDate) = "${exclusiveUpper}T00:00:00+09:00"

    override fun fetch(currency: String, from: LocalDate, to: LocalDate): SourceFetch {
        val code = currency.trim().uppercase()
        require(code in SUPPORTED) { "Upbit 일봉을 지원하지 않는 통화입니다: $currency" }

        val market = "KRW-$code"
        val collected = mutableListOf<DailyRate>()
        // 파서가 버린 행을 페이지마다 더한다. 0으로 박아 두면 BackfillSummary.skipped가
        // 늘 0이 되어 "조용히 삼키지 않는다"는 이 서브시스템의 규약이 무너진다.
        var skipped = 0
        var exclusiveUpper = to.plusDays(1)

        repeat(MAX_PAGES) {
            val page = parser.parse(client.fetchDays(market, cursor(exclusiveUpper), PAGE))
            skipped += page.skipped
            if (page.rates.isEmpty()) return SourceFetch(collected, skipped)

            collected += page.rates.filter { it.baseDate in from..to }

            val oldest = page.rates.minOf { it.baseDate }
            if (oldest <= from) return SourceFetch(collected, skipped)

            // 커서가 반드시 과거로 가야 한다. 안 가면 같은 페이지를 영원히 받는다.
            val next = oldest
            if (next >= exclusiveUpper) {
                throw UpbitCandleException(
                    "Upbit 일봉 페이지가 진행하지 못했습니다 (market=$market to=$exclusiveUpper oldest=$oldest)"
                )
            }
            exclusiveUpper = next
        }

        log.warn("[UpbitCandle] 최대 페이지({})에 도달해 중단 market={} {}~{}", MAX_PAGES, market, from, to)
        return SourceFetch(collected, skipped)
    }
}
```

- [ ] **Step 5: 통과 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.fx.upbit.UpbitCandleRateSourceTest"`
Expected: PASS (8 tests)

- [ ] **Step 6: Commit**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/upbit/ allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/upbit/UpbitCandleRateSourceTest.kt
git commit -m "feat(af-100): Upbit 일봉 클라이언트와 페이지네이션 소스"
```

---

### Task 5: 조회 경로 연결과 빈 등록

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/UnifiedAssetFxConverterAdapter.kt`
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/upbit/UpbitCandleConfig.kt`
- Create: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/HistoricalCryptoRateTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
package com.allfolio.fx

import com.allfolio.unifiedasset.infrastructure.entity.HistoricalFxRateEntity
import com.allfolio.unifiedasset.infrastructure.jpa.HistoricalFxRateJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 과거 크립토 시세가 조회 경로에 실제로 닿는지 본다.
 *
 * 이 연결이 없으면 백필을 아무리 돌려도 `toKrwOn`이 크립토를 현재가로 우회시켜
 * 저장된 `cash_flow.amount_krw`가 계속 틀린다 — 2026-08-01 두 행에서 실제로 일어난 일이다.
 */
class HistoricalCryptoRateTest {

    private val date = LocalDate.of(2026, 8, 1)

    /**
     * `HistoricalFxRateJpaRepository`는 `JpaRepository`를 상속해 메서드가 수십 개다.
     * 이 리포의 관례대로 **Mockito 목에 위임하고 필요한 것만 오버라이드한다**
     * (`FxRateBackfillServiceTest.FakeRepo`·`UnifiedAssetFxConverterAdapterTest.FakeRepo`와 같은 방식).
     */
    private class FakeRepo(private val rows: List<HistoricalFxRateEntity>) :
        HistoricalFxRateJpaRepository by mock(HistoricalFxRateJpaRepository::class.java) {

        override fun findTopByCurrencyAndBaseDateLessThanEqualOrderByBaseDateDesc(
            currency: String,
            baseDate: LocalDate,
        ): HistoricalFxRateEntity? =
            rows.filter { it.currency == currency && !it.baseDate.isAfter(baseDate) }
                .maxByOrNull { it.baseDate }
    }

    private fun adapter(rows: List<HistoricalFxRateEntity>) =
        UnifiedAssetFxConverterAdapter(CurrencyConverter(StubFx()), FakeRepo(rows))

    private fun row(currency: String, rate: String) = HistoricalFxRateEntity(
        id = UUID.randomUUID(), baseDate = date, currency = currency,
        rateKrw = BigDecimal(rate), source = "UPBIT", createdAt = LocalDateTime.now(),
    )

    @Test
    fun `행이 있으면 그날 시세로 환산하고 estimated가 아니다`() {
        val result = adapter(listOf(row("ETH", "2660000"))).toKrwOn(BigDecimal("2.0"), "ETH", date)

        assertThat(result.amountKrw).isEqualByComparingTo("5320000")
        assertThat(result.estimated).isFalse()
        assertThat(result.rateDate).isEqualTo(date)
    }

    @Test
    fun `BTC도 같은 경로를 탄다`() {
        val result = adapter(listOf(row("BTC", "90557000"))).toKrwOn(BigDecimal("0.5"), "BTC", date)

        assertThat(result.amountKrw).isEqualByComparingTo("45278500")
        assertThat(result.estimated).isFalse()
    }

    @Test
    fun `행이 없으면 현재가 폴백이고 estimated가 true다`() {
        // 백필을 안 돌린 구간. 동작은 기존과 같고 경고만 남는다.
        val result = adapter(emptyList()).toKrwOn(BigDecimal("2.0"), "ETH", date)

        assertThat(result.estimated).isTrue()
        assertThat(result.rateDate).isNull()
    }

    private class StubFx : FxRateService {
        override fun getUsdtToKrw(): BigDecimal = BigDecimal("1400")
        override fun setUsdtToKrw(rate: BigDecimal) = Unit
        override fun getCryptoToKrw(symbol: String): BigDecimal = BigDecimal("1")
        override fun setCryptoToKrw(symbol: String, rate: BigDecimal) = Unit
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.fx.HistoricalCryptoRateTest"`
Expected: FAIL — 크립토가 아직 `estimatedNow`로 우회해 `estimated=true`가 나온다

- [ ] **Step 3: 어댑터 연결**

`UnifiedAssetFxConverterAdapter.kt`에서:

```kotlin
        private val HISTORICAL = setOf("USD")

        /** 과거 시세 소스가 없어 현재가로만 환산되는 통화 */
        private val CRYPTO = setOf("BTC", "ETH")
```

를 아래로 바꾼다 (`CRYPTO` 집합을 삭제한다):

```kotlin
        /**
         * 과거 시세를 조회할 통화.
         *
         * BTC·ETH는 Upbit 일봉(`UpbitCandleRateSource`)이 채운다. 백필을 안 돌린 구간은
         * lookup이 null을 주고 현재가 폴백으로 떨어지므로, 행이 없어도 기존과 똑같이 굴러간다.
         */
        private val HISTORICAL = setOf("USD", "BTC", "ETH")
```

그리고 `toKrwOn`에서 아래 두 줄을 **삭제한다**:

```kotlin
        // BTC/ETH는 과거 시세를 가진 소스가 없다 — 현행 현재가 환산을 유지한다
        if (code in CRYPTO) return estimatedNow(amount, code)
```

- [ ] **Step 4: 빈 등록**

`UpbitCandleConfig.kt`:

```kotlin
package com.allfolio.fx.upbit

import com.allfolio.fx.HistoricalRateSource
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Upbit 일봉 소스 조립.
 *
 * base-url을 프로퍼티로 빼는 이유는 테스트에서 스텁 서버를 물리기 위해서다.
 * 기본값은 운영 주소다 — Binance가 testnet 기본값에 묶여 운영이 테스트넷 가격을 보던
 * 사고를 되풀이하지 않는다.
 */
@Configuration
class UpbitCandleConfig {

    @Bean
    fun upbitCandleRateSource(
        @Value("\${fx.upbit.candle-base-url:https://api.upbit.com}") baseUrl: String,
        parser: UpbitCandleParser,
    ): HistoricalRateSource = UpbitCandleRateSource(UpbitCandleClient(baseUrl), parser)
}
```

`EcosHistoricalRateSource`는 이미 `@Component`라 함께 주입된다. **순서가 중요하다** — `supports`로 가리므로 겹치지 않지만, 겹치면 리스트 앞쪽이 이긴다.

- [ ] **Step 5: 통과 확인 + 전체 테스트**

Run: `cd allfolio-backend && ./gradlew :backend-app:test`
Expected: `BUILD SUCCESSFUL`, 실패 0건

- [ ] **Step 6: Commit**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/ allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/HistoricalCryptoRateTest.kt
git commit -m "feat(af-100): toKrwOn이 BTC·ETH 과거 시세를 조회한다"
```

---

### Task 6: 실제 백필 확인과 PR

- [ ] **Step 1: 앱을 띄워 실제 백필을 돌린다**

Run: `cd allfolio-backend && ./gradlew :backend-app:bootRun`

다른 터미널에서 (어드민 인증이 필요하면 기존 어드민 토큰을 쓴다):

```bash
curl -X POST "http://localhost:8090/api/admin/fx/backfill?currency=ETH&from=2026-08-01&to=2026-08-01"
```

Expected: `saved=1, inserted=1`, `firstDate=lastDate=2026-08-01`

기대값 확인 — 2026-08-01 ETH 종가는 **2,660,000**이다:

```bash
curl -s "https://api.upbit.com/v1/candles/days?market=KRW-ETH&to=2026-08-02T00:00:00%2B09:00&count=1" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['trade_price'])"
```

- [ ] **Step 2: 200일 넘는 구간으로 페이지네이션을 실제로 확인**

```bash
curl -X POST "http://localhost:8090/api/admin/fx/backfill?currency=BTC&from=2026-01-01&to=2026-08-01"
```

Expected: `saved`가 200을 **넘는다**. 200에서 딱 멈추면 페이지네이션이 안 도는 것이다.

- [ ] **Step 3: 앱 종료 후 PR**

```bash
git push -u origin feat/af-100-crypto-historical-rates
```

PR 본문에 반드시 담을 것:
- `cash_flow.amount_krw`가 입력 시점에 굳는다는 것과, 그래서 **이미 저장된 행은 안 고쳐진다**는 것
- 2026-08-01 두 행에서 실제로 일어났고 8.1% 왜곡이었다는 것 (수동 정정 완료)
- seam을 어디에 그었는지와 `unitDivisor`가 ECOS 전용이라 소스로 내려갔다는 것
- 기존 ECOS 테스트 17개가 헬퍼 한 줄만 고쳐 통과한다는 것 (추출이 옳다는 근거)
- Upbit이 `count=201`도 200건만 조용히 준다는 실측과 그래서 페이지네이션이 필수라는 것
- 일봉 종가는 ±1% 근사이고, 백필은 사람이 돌려야 한다는 한계

## 배포 후

`POST /api/admin/fx/backfill?currency=ETH&from=...&to=...`를 실제 운영에서 한 번 돌려
`fx_rate_daily`에 `source='UPBIT'` 행이 들어가는지 확인한다.
