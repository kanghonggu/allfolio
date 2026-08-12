# BTC·ETH KRW 시세 수집 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** BTC·ETH의 하드코딩 폴백 상수를 없애고 실제 거래소 시세로 갱신한다 (ETH 69% 과대평가 해소).

**Architecture:** 기존 `FxQuoteSource`·`FxApiClient` 포트를 "한 번에 여러 마켓"으로 일반화한다. 두 거래소 모두 한 요청으로 세 마켓을 주므로 HTTP 호출이 늘지 않는다. 체인은 **심볼 단위로** 해소해 한 심볼의 장애가 나머지를 낡게 만들지 않는다.

**Tech Stack:** Kotlin, Spring Boot(WebFlux `WebClient`), Jackson, JUnit 5, AssertJ, Gradle

**Spec:** [2026-08-12-crypto-krw-feed-design.md](../specs/2026-08-12-crypto-krw-feed-design.md)

---

## File Structure

**수정** (`allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/`)

| 파일 | 변경 |
|---|---|
| `exchange/FxQuoteSource.kt` | `fetchUsdtKrw()` → `fetchKrwRates(): Map<String, BigDecimal>` + `FxSymbols` 상수 |
| `exchange/UpbitFxParser.kt` | 단일 값 → 심볼 맵 (`market` 필드로 매칭) |
| `exchange/BithumbFxParser.kt` | 단일 심볼 응답 → `ALL_KRW` 응답 |
| `exchange/UpbitFxSource.kt` | 경로에 3개 마켓, `fetchKrwRates` |
| `exchange/BithumbFxSource.kt` | `ALL_KRW` 경로, 코덱 1MB, `fetchKrwRates` |
| `exchange/ExchangeFxApiClient.kt` | 심볼 단위 해소 + 심볼별 범위 가드 |
| `FxApiClient.kt` | `getUsdtKrw()` → `fetchKrwRates()` |
| `FxRateScheduler.kt` | 세 심볼을 각각 Redis에 기록 |
| `RedisFxRateService.kt` | 크립토 상수 제거, TTL 분리, 미스 시 예외 |
| `resources/application.yml` | `fx.btc-krw`·`fx.eth-krw` 블록 삭제 |

**테스트**: 위 각 파일의 기존 테스트를 새 시그니처로 옮기고 케이스를 늘린다.

**핵심 주의 (스펙에서 실측 확인)**
- Bithumb `ALL_KRW`의 `data`에는 **`date` 문자열 키가 섞여 있다**(481개 중 1개). 맵을 순회하면 깨진다 — **세 심볼을 키로 직접 꺼낸다.**
- `ALL_KRW` 응답이 **169KB**다. 현재 코덱 한도 256KB로는 여유가 1.5배뿐이라 **1MB로 올린다.**
- Upbit 배열 순서는 보장되지 않는다 — **인덱스가 아니라 `market` 필드로 매칭한다.**

---

### Task 1: 포트 마이그레이션 (원자적 — 한 커밋)

> **이 태스크는 쪼개면 컴파일이 깨진다.** 포트 시그니처를 바꾸는 순간 두 파서·두 소스·체인·스케줄러가 동시에 깨지므로, 중간 커밋을 만들면 그 커밋에서 테스트를 돌릴 수 없다. 리뷰어가 태스크마다 검증할 수 있어야 하므로 **Step 1~7을 모두 끝낸 뒤 한 번만 커밋한다.**

#### Step 1: 포트 일반화와 심볼 상수

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/exchange/FxQuoteSource.kt`

- [ ] **Step 1: 포트를 맵 반환으로 바꾸고 심볼 상수를 추가**

`FxQuoteSource.kt` 전체를 아래로 교체한다:

```kotlin
package com.allfolio.fx.exchange

import java.math.BigDecimal

/**
 * 수집 대상 심볼.
 *
 * 한 곳에 모으는 이유는 파서·소스·가드·스케줄러가 같은 목록을 봐야 하기 때문이다.
 * 흩어 두면 심볼을 늘릴 때 한 곳을 빠뜨려도 컴파일이 통과한다.
 */
object FxSymbols {
    const val USDT = "USDT"
    const val BTC = "BTC"
    const val ETH = "ETH"

    /** 수집 순서와 무관한 전체 목록 */
    val ALL = listOf(USDT, BTC, ETH)

    /** 코인만 — USDT는 스테이블코인이라 FxRateService에서 다른 경로를 탄다 */
    val CRYPTO = listOf(BTC, ETH)
}

/**
 * 개별 거래소의 KRW 시세 소스.
 *
 * [com.allfolio.fx.FxApiClient]가 아니라 그 구현체(ExchangeFxApiClient)의 부품이다.
 * FxApiClient를 직접 구현하면 빈이 둘이 되어 FxRateScheduler의 주입이 깨진다.
 *
 * 실패는 예외로 알린다. 0이나 null을 돌려주면 호출자가 "실패"와 "진짜 0원"을
 * 구분할 수 없고, 그 값이 그대로 모든 자산 평가에 흘러든다.
 */
interface FxQuoteSource {
    /** 로그·진단에 쓰는 소스 이름 (예: "UPBIT") */
    val sourceName: String

    /**
     * 심볼 → KRW 현재가. 키는 [FxSymbols]의 값.
     *
     * **일부만 담겨 있어도 된다.** 거래소가 특정 마켓을 안 주는 경우가 있고,
     * 그때 전체를 실패로 만들면 멀쩡한 나머지 심볼까지 낡는다.
     * 호출자가 심볼 단위로 부족한 것만 다음 소스에서 채운다.
     *
     * 호출 자체가 실패하면 [FxQuoteException].
     */
    fun fetchKrwRates(): Map<String, BigDecimal>
}

/** 소스 하나가 실패했다는 신호. 체인이 다음 소스로 넘어가는 근거가 된다. */
class FxQuoteException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
```


---

#### Step 2: `UpbitFxParser` — 다중 마켓 (TDD)

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/exchange/UpbitFxParser.kt`
- Modify: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/exchange/UpbitFxParserTest.kt`

- [ ] **Step 1: 테스트를 새 시그니처로 교체**

`UpbitFxParserTest.kt` 전체를 아래로 교체한다:

```kotlin
package com.allfolio.fx.exchange

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class UpbitFxParserTest {

    private val parser = UpbitFxParser(ObjectMapper())

    /** 2026-08-12 api.upbit.com/v1/ticker?markets=KRW-USDT,KRW-BTC,KRW-ETH 실제 응답에서 필드를 줄인 것 */
    private val realResponse = """
        [{"market":"KRW-USDT","trade_price":1409.00000000,"opening_price":1409.0},
         {"market":"KRW-BTC","trade_price":89825000.00000000,"opening_price":89800000.0},
         {"market":"KRW-ETH","trade_price":2663000.00000000,"opening_price":2660000.0}]
    """.trimIndent()

    @Test
    fun `세 마켓을 심볼로 키를 바꿔 돌려준다`() {
        val rates = parser.parse(realResponse)

        assertThat(rates).containsOnlyKeys("USDT", "BTC", "ETH")
        assertThat(rates["USDT"]).isEqualByComparingTo("1409.0")
        assertThat(rates["BTC"]).isEqualByComparingTo("89825000.0")
        assertThat(rates["ETH"]).isEqualByComparingTo("2663000.0")
    }

    @Test
    fun `배열 순서가 뒤바뀌어도 market 필드로 맞춘다`() {
        // Upbit이 markets= 순서를 지킨다는 보장이 없다. 인덱스로 매칭하면 조용히 뒤바뀐다 —
        // BTC 가격이 USDT 자리에 들어가면 자산이 6만 배가 된다.
        val shuffled = """
            [{"market":"KRW-ETH","trade_price":2663000.0},
             {"market":"KRW-USDT","trade_price":1409.0},
             {"market":"KRW-BTC","trade_price":89825000.0}]
        """.trimIndent()

        val rates = parser.parse(shuffled)

        assertThat(rates["USDT"]).isEqualByComparingTo("1409.0")
        assertThat(rates["BTC"]).isEqualByComparingTo("89825000.0")
        assertThat(rates["ETH"]).isEqualByComparingTo("2663000.0")
    }

    @Test
    fun `일부 마켓만 와도 온 것만 돌려준다 - 나머지는 다음 소스가 채운다`() {
        val partial = """[{"market":"KRW-USDT","trade_price":1409.0}]"""

        val rates = parser.parse(partial)

        assertThat(rates).containsOnlyKeys("USDT")
    }

    @Test
    fun `모르는 마켓은 무시한다`() {
        val extra = """
            [{"market":"KRW-USDT","trade_price":1409.0},
             {"market":"KRW-DOGE","trade_price":300.0}]
        """.trimIndent()

        assertThat(parser.parse(extra)).containsOnlyKeys("USDT")
    }

    @Test
    fun `소수점이 있는 가격도 정밀도를 잃지 않는다`() {
        val json = """[{"market":"KRW-USDT","trade_price":1408.55}]"""

        assertThat(parser.parse(json)["USDT"]).isEqualByComparingTo("1408.55")
    }

    @Test
    fun `빈 배열이면 예외 - 조용히 빈 맵을 돌려주면 실패가 안 보인다`() {
        assertThatThrownBy { parser.parse("[]") }
            .isInstanceOf(FxQuoteException::class.java)
            .hasMessageContaining("비어")
    }

    @Test
    fun `아는 마켓이 하나도 없으면 예외`() {
        assertThatThrownBy { parser.parse("""[{"market":"KRW-DOGE","trade_price":300.0}]""") }
            .isInstanceOf(FxQuoteException::class.java)
    }

    @Test
    fun `trade_price가 없는 항목은 건너뛰고 나머지는 살린다`() {
        val json = """
            [{"market":"KRW-USDT","trade_price":1409.0},
             {"market":"KRW-BTC","opening_price":89800000.0}]
        """.trimIndent()

        assertThat(parser.parse(json)).containsOnlyKeys("USDT")
    }

    @Test
    fun `trade_price가 숫자가 아니면 그 항목만 건너뛴다`() {
        val json = """
            [{"market":"KRW-USDT","trade_price":1409.0},
             {"market":"KRW-BTC","trade_price":"89800000"}]
        """.trimIndent()

        assertThat(parser.parse(json)).containsOnlyKeys("USDT")
    }

    @Test
    fun `JSON이 아니면 예외`() {
        assertThatThrownBy { parser.parse("<html>maintenance</html>") }
            .isInstanceOf(FxQuoteException::class.java)
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.fx.exchange.UpbitFxParserTest"`
Expected: FAIL (컴파일 오류 — `parse`가 아직 `BigDecimal`을 돌려준다)

- [ ] **Step 3: 구현**

`UpbitFxParser.kt` 전체를 아래로 교체한다:

```kotlin
package com.allfolio.fx.exchange

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Upbit ticker 응답 → 심볼별 KRW.
 *
 * HTTP에서 분리한 이유: 이 자리에 테스트가 없어서 동작할 수 없는 Binance 클라이언트가
 * 배포됐다. 순수 함수로 두면 실제 응답 픽스처로 네트워크 없이 회귀를 막는다.
 *
 * 응답 형태: [{"market":"KRW-USDT","trade_price":1409.0}, {"market":"KRW-BTC", ...}]
 *
 * **인덱스가 아니라 `market` 필드로 매칭한다.** Upbit이 markets= 순서를 지킨다는 보장이 없고,
 * 뒤바뀌면 BTC 가격이 USDT 자리에 들어가 자산이 6만 배가 된다.
 *
 * 숫자를 BigDecimal로 만들 때 asText()를 거치는 이유는 asDouble()이 2진 부동소수점을
 * 경유하면서 정밀도를 잃기 때문이다.
 */
@Component
class UpbitFxParser(private val objectMapper: ObjectMapper) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** "KRW-USDT" → "USDT" */
        private const val KRW_PREFIX = "KRW-"
    }

    fun parse(body: String): Map<String, BigDecimal> {
        val root = try {
            objectMapper.readTree(body)
        } catch (e: Exception) {
            throw FxQuoteException("Upbit 응답이 JSON이 아닙니다", e)
        }

        if (!root.isArray || root.isEmpty) {
            throw FxQuoteException("Upbit 응답이 비어 있습니다")
        }

        val rates = mutableMapOf<String, BigDecimal>()
        for (node in root) {
            val market = node.get("market")?.asText() ?: continue
            if (!market.startsWith(KRW_PREFIX)) continue

            val symbol = market.removePrefix(KRW_PREFIX)
            if (symbol !in FxSymbols.ALL) continue

            val price = node.get("trade_price")
            if (price == null || !price.isNumber) {
                // 한 심볼이 이상하다고 나머지를 버리지 않는다. 못 채운 심볼은 다음 소스가 맡는다.
                log.warn("[UpbitFx] {} trade_price가 없거나 숫자가 아니라 건너뜀", market)
                continue
            }

            rates[symbol] = BigDecimal(price.asText())
        }

        if (rates.isEmpty()) {
            throw FxQuoteException("Upbit 응답에서 아는 마켓을 찾지 못했습니다")
        }

        return rates
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

아직 소스·체인이 옛 시그니처라 **이 시점에는 컴파일이 깨져 있는 것이 정상이다.** Step 6까지 끝낸 뒤 Step 7에서 한 번에 돌린다. **테스트를 코드에 맞추지 말 것.**


---

#### Step 3: `BithumbFxParser` — `ALL_KRW` (TDD)

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/exchange/BithumbFxParser.kt`
- Modify: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/exchange/BithumbFxParserTest.kt`

응답 형태가 완전히 바뀐다. 단일 심볼 응답은 `data`가 곧 가격 객체였지만, `ALL_KRW`는 `data`가 **심볼 → 가격객체** 맵이다. 그리고 그 맵에 `date` 문자열이 섞여 있다.

- [ ] **Step 1: 테스트를 새 형태로 교체**

`BithumbFxParserTest.kt` 전체를 아래로 교체한다:

```kotlin
package com.allfolio.fx.exchange

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BithumbFxParserTest {

    private val parser = BithumbFxParser(ObjectMapper())

    /** 2026-08-12 api.bithumb.com/public/ticker/ALL_KRW 실제 응답에서 코인 수와 필드를 줄인 것 */
    private val realResponse = """
        {"status":"0000","data":{
          "BTC":{"opening_price":"89800000","closing_price":"89880000"},
          "ETH":{"opening_price":"2660000","closing_price":"2664000"},
          "USDT":{"opening_price":"1409","closing_price":"1410"},
          "DOGE":{"opening_price":"300","closing_price":"301"},
          "date":"1786521700219"}}
    """.trimIndent()

    @Test
    fun `아는 세 심볼만 뽑는다`() {
        val rates = parser.parse(realResponse)

        assertThat(rates).containsOnlyKeys("USDT", "BTC", "ETH")
        assertThat(rates["BTC"]).isEqualByComparingTo("89880000")
        assertThat(rates["ETH"]).isEqualByComparingTo("2664000")
        assertThat(rates["USDT"]).isEqualByComparingTo("1410")
    }

    @Test
    fun `data에 섞인 date 문자열에 걸려 넘어지지 않는다`() {
        // 실측: ALL_KRW의 data는 481개 키 중 480개가 코인이고 하나가 date 문자열이다.
        // 맵을 순회하는 구현이면 여기서 깨진다. 심볼을 키로 직접 꺼내면 구조적으로 안전하다.
        assertThat(parser.parse(realResponse)).doesNotContainKey("date")
    }

    @Test
    fun `일부 심볼만 있어도 있는 것만 돌려준다`() {
        val partial = """{"status":"0000","data":{"USDT":{"closing_price":"1410"},"date":"1"}}"""

        assertThat(parser.parse(partial)).containsOnlyKeys("USDT")
    }

    @Test
    fun `status가 0000이 아니면 예외 - HTTP 200이라 이 검사가 유일한 방어선이다`() {
        val error = """{"status":"5500","message":"상장 코인이 아닙니다."}"""

        assertThatThrownBy { parser.parse(error) }
            .isInstanceOf(FxQuoteException::class.java)
            .hasMessageContaining("5500")
            .hasMessageContaining("상장 코인이 아닙니다")
    }

    @Test
    fun `status 필드가 아예 없으면 예외 - 없는 것을 정상으로 읽으면 안 된다`() {
        assertThatThrownBy { parser.parse("""{"data":{"BTC":{"closing_price":"1"}}}""") }
            .isInstanceOf(FxQuoteException::class.java)
            .hasMessageContaining("status=null")
    }

    @Test
    fun `status가 정상인데 data가 없으면 예외`() {
        assertThatThrownBy { parser.parse("""{"status":"0000"}""") }
            .isInstanceOf(FxQuoteException::class.java)
            .hasMessageContaining("data")
    }

    @Test
    fun `아는 심볼이 하나도 없으면 예외`() {
        val none = """{"status":"0000","data":{"DOGE":{"closing_price":"300"},"date":"1"}}"""

        assertThatThrownBy { parser.parse(none) }
            .isInstanceOf(FxQuoteException::class.java)
    }

    @Test
    fun `closing_price가 숫자가 아니면 그 심볼만 건너뛴다`() {
        val json = """
            {"status":"0000","data":{
              "BTC":{"closing_price":"N/A"},
              "USDT":{"closing_price":"1410"}}}
        """.trimIndent()

        assertThat(parser.parse(json)).containsOnlyKeys("USDT")
    }

    @Test
    fun `closing_price가 없으면 그 심볼만 건너뛴다`() {
        val json = """
            {"status":"0000","data":{
              "BTC":{"opening_price":"89800000"},
              "USDT":{"closing_price":"1410"}}}
        """.trimIndent()

        assertThat(parser.parse(json)).containsOnlyKeys("USDT")
    }

    @Test
    fun `JSON이 아니면 예외`() {
        assertThatThrownBy { parser.parse("<html>점검중</html>") }
            .isInstanceOf(FxQuoteException::class.java)
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.fx.exchange.BithumbFxParserTest"`
Expected: FAIL

- [ ] **Step 3: 구현**

`BithumbFxParser.kt` 전체를 아래로 교체한다:

```kotlin
package com.allfolio.fx.exchange

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Bithumb `ALL_KRW` 응답 → 심볼별 KRW.
 *
 * 응답 형태: {"status":"0000","data":{"BTC":{"closing_price":"89880000"}, ..., "date":"1786..."}}
 *
 * **status 검사가 이 파서의 핵심이다.** Bithumb은 조회에 실패해도 HTTP 200을 주고
 * status만 바꾼다(2026-08-12 실측: 잘못된 심볼 → 200 + {"status":"5500"}, data 없음).
 * WebClient의 retrieve()가 예외를 던져 주지 않으므로 여기서 막지 않으면
 * 조회 실패가 그대로 환율로 흘러든다.
 *
 * **data를 순회하지 않는다.** 실측 481개 키 중 하나가 코인이 아니라 `date` 문자열이라
 * 순회하면 거기서 깨진다. 아는 심볼을 키로 직접 꺼내면 그 문제가 구조적으로 사라지고,
 * 상장 코인이 늘어도 영향을 받지 않는다.
 *
 * 가격이 문자열로 온다는 점도 Upbit과 다르다.
 */
@Component
class BithumbFxParser(private val objectMapper: ObjectMapper) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val STATUS_OK = "0000"
    }

    fun parse(body: String): Map<String, BigDecimal> {
        val root = try {
            objectMapper.readTree(body)
        } catch (e: Exception) {
            throw FxQuoteException("Bithumb 응답이 JSON이 아닙니다", e)
        }

        val status = root.get("status")?.asText()
        if (status != STATUS_OK) {
            val message = root.get("message")?.asText()
            throw FxQuoteException("Bithumb status=$status message=$message")
        }

        val data = root.get("data")
            ?: throw FxQuoteException("Bithumb 응답에 data가 없습니다")

        val rates = mutableMapOf<String, BigDecimal>()
        for (symbol in FxSymbols.ALL) {
            val entry = data.get(symbol) ?: continue
            val raw = entry.get("closing_price")?.asText() ?: run {
                log.warn("[BithumbFx] {} closing_price가 없어 건너뜀", symbol)
                null
            } ?: continue

            val value = raw.toBigDecimalOrNull() ?: run {
                log.warn("[BithumbFx] {} closing_price가 숫자가 아니라 건너뜀: {}", symbol, raw)
                null
            } ?: continue

            rates[symbol] = value
        }

        if (rates.isEmpty()) {
            throw FxQuoteException("Bithumb 응답에서 아는 심볼을 찾지 못했습니다")
        }

        return rates
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Step 7에서 전체와 함께 돌린다 (10 tests).


---

#### Step 4: HTTP 소스 두 개 이행

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/exchange/UpbitFxSource.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/exchange/BithumbFxSource.kt`
- Modify: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/exchange/ExchangeFxSourceTest.kt`

- [ ] **Step 1: `UpbitFxSource` 수정**

`PATH` 상수와 `fetchUsdtKrw` 시그니처만 바꾼다. 나머지(예외 처리, `by lazy`, 타임아웃)는 그대로 둔다.

`PATH` 상수를 교체:

```kotlin
        private const val PATH = "/v1/ticker?markets=KRW-USDT,KRW-BTC,KRW-ETH"
```

메서드 선언과 마지막 줄을 교체 (`override fun fetchUsdtKrw(): BigDecimal {` → 아래):

```kotlin
    override fun fetchKrwRates(): Map<String, BigDecimal> {
```

본문 마지막 `return parser.parse(body)`는 그대로 두면 된다 — 파서가 이제 맵을 돌려준다.

KDoc 첫 줄도 고친다: `Upbit KRW-USDT 시세 소스 (주 소스).` → `Upbit KRW 시세 소스 (주 소스). USDT·BTC·ETH를 한 번에 가져온다.`

- [ ] **Step 2: `BithumbFxSource` 수정**

`PATH`와 코덱 한도, 시그니처를 바꾼다.

```kotlin
        private const val PATH = "/public/ticker/ALL_KRW"
```

WebClient 빌더의 코덱 줄을 교체:

```kotlin
            .codecs { it.defaultCodecs().maxInMemorySize(1024 * 1024) }
```

그 위에 이유를 남긴다:

```kotlin
    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(baseUrl)
            // ALL_KRW는 상장 전 종목을 다 준다 — 2026-08-12 실측 169KB(480종목, 종목당 약 350B).
            // 기존 256KB로는 여유가 1.5배뿐이라 상장이 265개만 늘어도 조용히 실패한다.
            .codecs { it.defaultCodecs().maxInMemorySize(1024 * 1024) }
            .build()
    }
```

메서드 선언 교체:

```kotlin
    override fun fetchKrwRates(): Map<String, BigDecimal> {
```

KDoc 첫 줄: `Bithumb USDT_KRW 시세 소스 (폴백).` → `Bithumb KRW 시세 소스 (폴백). ALL_KRW로 USDT·BTC·ETH를 한 번에 가져온다.`

- [ ] **Step 3: 요청 경로 테스트 갱신**

`ExchangeFxSourceTest.kt`에서 응답 픽스처와 단언을 새 형태로 바꾼다. 파일 전체를 교체한다:

```kotlin
package com.allfolio.fx.exchange

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * 소스가 **어떤 URL을 때리는지**를 못 박는다.
 *
 * Binance 클라이언트가 조용히 실패한 원인이 정확히 여기였다 — 파싱이 아니라
 * 존재하지 않는 심볼을 조회하는 URL. 파서 테스트로는 잡을 수 없는 계열이라 따로 둔다.
 */
class ExchangeFxSourceTest {

    private lateinit var server: HttpServer
    private val requestUri = AtomicReference<String>()
    @Volatile private var responseCode = 200
    @Volatile private var responseBody = ""

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            requestUri.set(exchange.requestURI.toString())
            val bytes = responseBody.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(responseCode, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun baseUrl() = "http://localhost:${server.address.port}"

    private fun upbit() = UpbitFxSource(baseUrl(), UpbitFxParser(ObjectMapper()))
    private fun bithumb() = BithumbFxSource(baseUrl(), BithumbFxParser(ObjectMapper()))

    @Test
    fun `Upbit은 세 마켓을 한 번에 정확한 경로로 조회한다`() {
        responseBody = """
            [{"market":"KRW-USDT","trade_price":1409.0},
             {"market":"KRW-BTC","trade_price":89825000.0},
             {"market":"KRW-ETH","trade_price":2663000.0}]
        """.trimIndent()

        val rates = upbit().fetchKrwRates()

        assertThat(rates).containsOnlyKeys("USDT", "BTC", "ETH")
        assertThat(requestUri.get()).isEqualTo("/v1/ticker?markets=KRW-USDT,KRW-BTC,KRW-ETH")
    }

    @Test
    fun `Bithumb은 ALL_KRW를 정확한 경로로 조회한다`() {
        responseBody = """
            {"status":"0000","data":{
              "BTC":{"closing_price":"89880000"},
              "ETH":{"closing_price":"2664000"},
              "USDT":{"closing_price":"1410"},
              "date":"1786521700219"}}
        """.trimIndent()

        val rates = bithumb().fetchKrwRates()

        assertThat(rates).containsOnlyKeys("USDT", "BTC", "ETH")
        assertThat(requestUri.get()).isEqualTo("/public/ticker/ALL_KRW")
    }

    @Test
    fun `Upbit이 404를 주면 FxQuoteException - 잘못된 마켓의 실제 응답이다`() {
        responseCode = 404
        responseBody = """{"error":{"name":404,"message":"Code not found"}}"""

        assertThatThrownBy { upbit().fetchKrwRates() }
            .isInstanceOf(FxQuoteException::class.java)
    }

    @Test
    fun `Bithumb은 HTTP 200이어도 status가 나쁘면 FxQuoteException`() {
        responseCode = 200
        responseBody = """{"status":"5500","message":"상장 코인이 아닙니다."}"""

        assertThatThrownBy { bithumb().fetchKrwRates() }
            .isInstanceOf(FxQuoteException::class.java)
    }

    @Test
    fun `Upbit 본문이 비면 FxQuoteException - 파서까지 가지 않는다`() {
        assertThatThrownBy { upbit().fetchKrwRates() }
            .isInstanceOf(FxQuoteException::class.java)
            .hasMessageContaining("본문이 비어")
    }

    @Test
    fun `Bithumb 본문이 비면 FxQuoteException`() {
        assertThatThrownBy { bithumb().fetchKrwRates() }
            .isInstanceOf(FxQuoteException::class.java)
            .hasMessageContaining("본문이 비어")
    }

    @Test
    fun `Bithumb은 큰 ALL_KRW 응답도 받는다 - 코덱 한도 회귀 방지`() {
        // 실측 169KB. 기존 256KB 한도로는 상장이 늘면 조용히 깨진다.
        // 500KB짜리 응답을 만들어 1MB 상향이 실제로 먹는지 확인한다.
        val filler = (1..1500).joinToString(",") {
            """"FILLER$it":{"opening_price":"1","closing_price":"1","min_price":"1","max_price":"1","units_traded":"1","acc_trade_value":"1"}"""
        }
        responseBody = """{"status":"0000","data":{$filler,"USDT":{"closing_price":"1410"},"date":"1"}}"""
        check(responseBody.length > 256 * 1024) { "픽스처가 기존 한도보다 커야 의미가 있다: ${responseBody.length}" }

        assertThat(bithumb().fetchKrwRates()).containsOnlyKeys("USDT")
    }

    @Test
    fun `소스 이름은 로그에서 구분되도록 고정한다`() {
        assertThat(upbit().sourceName).isEqualTo("UPBIT")
        assertThat(bithumb().sourceName).isEqualTo("BITHUMB")
    }
}
```

- [ ] **Step 4: 테스트 실행**

Step 7에서 전체와 함께 돌린다 (8 tests). **경로 단언을 코드에 맞추지 말 것.**


---

#### Step 5: 심볼 단위 해소와 심볼별 범위 가드 (TDD)

이 태스크가 설계의 핵심이다. **한 심볼이 실패해도 나머지는 살린다.**

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/exchange/ExchangeFxApiClient.kt`
- Modify: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/exchange/ExchangeFxApiClientTest.kt`

- [ ] **Step 1: 테스트를 새 시그니처로 교체**

`ExchangeFxApiClientTest.kt` 전체를 아래로 교체한다:

```kotlin
package com.allfolio.fx.exchange

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ExchangeFxApiClientTest {

    /** 지정한 맵을 돌려주거나 예외를 던지는 가짜 소스. 네트워크 없이 체인만 검증한다. */
    private class FakeSource(
        override val sourceName: String,
        private val result: Result<Map<String, BigDecimal>>,
    ) : FxQuoteSource {
        var callCount = 0
            private set

        override fun fetchKrwRates(): Map<String, BigDecimal> {
            callCount++
            return result.getOrThrow()
        }
    }

    private fun ok(name: String, vararg pairs: Pair<String, String>) =
        FakeSource(name, Result.success(pairs.associate { it.first to BigDecimal(it.second) }))

    private fun fail(name: String) =
        FakeSource(name, Result.failure(FxQuoteException("$name 실패")))

    private val allThree = arrayOf("USDT" to "1409", "BTC" to "89825000", "ETH" to "2663000")

    @Test
    fun `첫 소스가 전부 채우면 두 번째는 부르지 않는다`() {
        val first = ok("UPBIT", *allThree)
        val second = ok("BITHUMB", *allThree)

        val rates = ExchangeFxApiClient(listOf(first, second)).fetchKrwRates()

        assertThat(rates).containsOnlyKeys("USDT", "BTC", "ETH")
        assertThat(rates["BTC"]).isEqualByComparingTo("89825000")
        assertThat(second.callCount).isZero()
    }

    @Test
    fun `첫 소스가 실패하면 두 번째로 넘어간다`() {
        val client = ExchangeFxApiClient(listOf(fail("UPBIT"), ok("BITHUMB", *allThree)))

        assertThat(client.fetchKrwRates()).containsOnlyKeys("USDT", "BTC", "ETH")
    }

    @Test
    fun `부족한 심볼만 다음 소스에서 채운다 - 이미 채운 것은 덮지 않는다`() {
        // 설계의 핵심. ETH 하나 때문에 멀쩡한 USDT·BTC 갱신을 막으면
        // 한 심볼의 장애가 나머지 둘을 낡게 만든다.
        val upbit = ok("UPBIT", "USDT" to "1409", "BTC" to "89825000")
        val bithumb = ok("BITHUMB", "USDT" to "9999", "BTC" to "9999", "ETH" to "2664000")

        val rates = ExchangeFxApiClient(listOf(upbit, bithumb)).fetchKrwRates()

        assertThat(rates["USDT"]).isEqualByComparingTo("1409")       // Upbit 것을 지킨다
        assertThat(rates["BTC"]).isEqualByComparingTo("89825000")    // Upbit 것을 지킨다
        assertThat(rates["ETH"]).isEqualByComparingTo("2664000")     // Bithumb이 채운다
    }

    @Test
    fun `일부 심볼을 끝내 못 채워도 채운 것은 돌려준다`() {
        val client = ExchangeFxApiClient(listOf(ok("UPBIT", "USDT" to "1409")))

        assertThat(client.fetchKrwRates()).containsOnlyKeys("USDT")
    }

    @Test
    fun `모든 소스가 실패하면 예외 - 스케줄러가 잡아 기존 캐시를 지킨다`() {
        val client = ExchangeFxApiClient(listOf(fail("UPBIT"), fail("BITHUMB")))

        assertThatThrownBy { client.fetchKrwRates() }
            .isInstanceOf(FxQuoteException::class.java)
            .hasMessageContaining("모든 소스")
    }

    @Test
    fun `USDT가 범위를 벗어나면 그 심볼만 다음 소스에서 받는다`() {
        val upbit = ok("UPBIT", "USDT" to "0", "BTC" to "89825000", "ETH" to "2663000")
        val bithumb = ok("BITHUMB", "USDT" to "1410", "BTC" to "9", "ETH" to "9")

        val rates = ExchangeFxApiClient(listOf(upbit, bithumb)).fetchKrwRates()

        assertThat(rates["USDT"]).isEqualByComparingTo("1410")
        assertThat(rates["BTC"]).isEqualByComparingTo("89825000")
    }

    @Test
    fun `BTC 범위는 USDT와 다르다 - 8900만은 정상이다`() {
        // 옛 가드(500~5000)를 그대로 뒀다면 BTC가 전부 걸러진다
        val client = ExchangeFxApiClient(listOf(ok("UPBIT", "BTC" to "89825000")))

        assertThat(client.fetchKrwRates()["BTC"]).isEqualByComparingTo("89825000")
    }

    @Test
    fun `ETH 범위도 따로다 - 266만은 정상이다`() {
        val client = ExchangeFxApiClient(listOf(ok("UPBIT", "ETH" to "2663000")))

        assertThat(client.fetchKrwRates()["ETH"]).isEqualByComparingTo("2663000")
    }

    @Test
    fun `BTC 자리에 USDT 값이 오면 범위 밖으로 걸러진다`() {
        // 파싱이 뒤바뀐 상황. 1409원짜리 BTC를 그대로 쓰면 자산이 6만분의 1이 된다.
        val client = ExchangeFxApiClient(listOf(ok("UPBIT", "BTC" to "1409")))

        assertThatThrownBy { client.fetchKrwRates() }
            .isInstanceOf(FxQuoteException::class.java)
    }

    @Test
    fun `모르는 심볼은 무시한다`() {
        val client = ExchangeFxApiClient(listOf(ok("UPBIT", "USDT" to "1409", "DOGE" to "300")))

        assertThat(client.fetchKrwRates()).containsOnlyKeys("USDT")
    }

    @Test
    fun `소스가 하나도 없으면 예외`() {
        assertThatThrownBy { ExchangeFxApiClient(emptyList()).fetchKrwRates() }
            .isInstanceOf(FxQuoteException::class.java)
    }

    @Test
    fun `FxQuoteException이 아닌 예외는 전파한다 - 진짜 버그를 폴백으로 삼키면 안 된다`() {
        val broken = FakeSource("UPBIT", Result.failure(IllegalStateException("파서 버그")))
        val healthy = ok("BITHUMB", *allThree)

        assertThatThrownBy { ExchangeFxApiClient(listOf(broken, healthy)).fetchKrwRates() }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(healthy.callCount).isZero()
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.fx.exchange.ExchangeFxApiClientTest"`
Expected: FAIL

- [ ] **Step 3: 구현**

`ExchangeFxApiClient.kt` 전체를 아래로 교체한다:

```kotlin
package com.allfolio.fx.exchange

import com.allfolio.fx.FxApiClient
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/**
 * 유일한 [FxApiClient] 빈. 거래소 소스를 순서대로 시도하되 **심볼 단위로 해소한다.**
 *
 * 앞선 소스가 채우지 못한 심볼만 다음 소스에서 받는다. 한 심볼이 실패했다고 전체를
 * 버리면 그 하나 때문에 나머지 심볼의 Redis 값이 낡는다.
 *
 * 하나도 못 채웠을 때만 예외를 던진다 — [com.allfolio.fx.FxRateScheduler]가 그 예외를 잡아
 * 기존 Redis 값을 지키는 계약을 그대로 유지한다.
 */
class ExchangeFxApiClient(
    private val sources: List<FxQuoteSource>,
) : FxApiClient {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /**
         * 심볼별 타당 범위.
         *
         * 이 가드가 잡으려는 것은 "시세가 이상하다"가 아니라 **"파싱이 깨졌다"**이다.
         * 0이나 타임스탬프가, 혹은 BTC 자리에 USDT 값이 들어오면 실패보다 나쁘다 —
         * 예외 없이 모든 자산 평가를 오염시킨다.
         *
         * 좁게 잡으면 실제 급변동 때 시세가 얼어붙으므로 일부러 넓게 둔다.
         * (2026-08-12 실측: USDT 1409 · BTC 89,825,000 · ETH 2,663,000)
         */
        private val RANGES: Map<String, ClosedRange<BigDecimal>> = mapOf(
            FxSymbols.USDT to BigDecimal("500")..BigDecimal("5000"),
            FxSymbols.BTC to BigDecimal("1000000")..BigDecimal("1000000000"),
            FxSymbols.ETH to BigDecimal("100000")..BigDecimal("100000000"),
        )
    }

    override fun fetchKrwRates(): Map<String, BigDecimal> {
        val resolved = mutableMapOf<String, BigDecimal>()
        var lastFailure: FxQuoteException? = null

        for (source in sources) {
            if (resolved.keys.containsAll(FxSymbols.ALL)) break

            val fetched = try {
                source.fetchKrwRates()
            } catch (e: FxQuoteException) {
                log.warn("[ExchangeFx] {} 실패: {}", source.sourceName, e.message)
                lastFailure = e
                continue
            }

            for ((symbol, rate) in fetched) {
                if (symbol in resolved) continue          // 앞선 소스가 이미 채웠다

                val range = RANGES[symbol] ?: continue    // 우리가 안 쓰는 심볼
                if (rate !in range) {
                    // 예외를 안 던지고 값을 돌려준 소스가 범위를 벗어났다 = 파싱이 깨졌다는 뜻이다.
                    log.warn("[ExchangeFx] {} {} 값이 범위 밖이라 무시: {}", source.sourceName, symbol, rate)
                    lastFailure = FxQuoteException("${source.sourceName} $symbol 범위 밖: $rate")
                    continue
                }

                resolved[symbol] = rate
            }

            log.info("[ExchangeFx] source={} 해소={}", source.sourceName, resolved.keys)
        }

        if (resolved.isEmpty()) {
            // cause를 붙이는 이유: 스케줄러는 e.message만 찍는다. 원인을 안 달면
            // 전량 실패했을 때 로그 한 줄로는 왜 실패했는지 알 수 없다.
            throw FxQuoteException(
                "모든 소스에서 KRW 시세를 가져오지 못했습니다 (시도=${sources.size})",
                lastFailure,
            )
        }

        val missing = FxSymbols.ALL - resolved.keys
        if (missing.isNotEmpty()) {
            // 부분 성공은 실패가 아니다. 다만 조용히 넘어가면 특정 심볼만 영영 낡는다.
            log.warn("[ExchangeFx] 끝내 못 채운 심볼={} — 그 심볼은 기존 값이 유지된다", missing)
        }

        return resolved
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Step 7에서 전체와 함께 돌린다 (12 tests).


---

#### Step 6: `FxApiClient` 포트와 스케줄러 이행

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/FxApiClient.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/FxRateScheduler.kt`

- [ ] **Step 1: 포트 교체**

`FxApiClient.kt` 전체를 아래로 교체한다:

```kotlin
package com.allfolio.fx

import java.math.BigDecimal

/**
 * 외부 시세 API 클라이언트 인터페이스
 *
 * 구현체는 @ConditionalOnProperty 로 선택적 활성화.
 * 실패 시 예외를 던지면 스케줄러에서 캐치 → 기존 Redis 값 유지.
 */
interface FxApiClient {
    /**
     * 심볼 → KRW 현재가. 키는 [com.allfolio.fx.exchange.FxSymbols]의 값.
     *
     * **일부만 담겨 있을 수 있다.** 한 심볼을 못 가져왔다고 나머지 갱신을 막지 않는다.
     * 하나도 못 가져오면 예외를 던진다.
     */
    fun fetchKrwRates(): Map<String, BigDecimal>
}
```

- [ ] **Step 2: 스케줄러 교체**

`FxRateScheduler.kt` 전체를 아래로 교체한다:

```kotlin
package com.allfolio.fx

import com.allfolio.fx.exchange.FxSymbols
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * KRW 시세 자동 수집 스케줄러 (USDT·BTC·ETH)
 *
 * 활성화 조건: fx.scheduler.enabled=true
 * 기본 주기: 60초 (fx.scheduler.delay-ms 로 조정 가능)
 *
 * 실패 시:
 *   - ERROR 로그만 남기고 계속 실행
 *   - Redis 기존 값 유지 (USDT는 TTL 180초, 코인은 24시간)
 *   - USDT는 TTL 만료 후 fallback-rate로, 코인은 상수가 없으므로 조회가 예외를 던진다
 */
@Component
@ConditionalOnProperty(name = ["fx.scheduler.enabled"], havingValue = "true")
class FxRateScheduler(
    private val fxApiClient: FxApiClient,
    private val fxRateService: FxRateService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${fx.scheduler.delay-ms:60000}")
    fun updateFx() {
        runCatching {
            val rates = fxApiClient.fetchKrwRates()

            rates[FxSymbols.USDT]?.let { fxRateService.setUsdtToKrw(it) }
            FxSymbols.CRYPTO.forEach { symbol ->
                rates[symbol]?.let { fxRateService.setCryptoToKrw(symbol, it) }
            }

            log.info("[FxScheduler] updated {}", rates)
        }.onFailure { e ->
            log.error("[FxScheduler] FX update failed — keeping cached rate: {}", e.message)
        }
    }
}
```

`rates[...]?.let` 로 쓰는 이유: 부분 성공일 때 못 가져온 심볼의 Redis 값을 건드리지 않기 위해서다. `rates[symbol]!!` 로 쓰면 부분 성공이 전량 실패가 된다.

- [ ] **Step 3: 컴파일 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:compileKotlin`
Expected: `BUILD SUCCESSFUL` — 여기서 처음으로 전체가 컴파일된다.

#### Step 7: 전체 테스트와 단일 커밋

- [ ] **전체 테스트**

Run: `cd allfolio-backend && ./gradlew :backend-app:test`
Expected: `BUILD SUCCESSFUL`, 실패 0건.

`ExchangeFxConfigWiringTest`도 함께 통과해야 한다 — 반사로 `sources` 필드를 읽어 소스 순서(UPBIT→BITHUMB)를 검증하는 테스트이고, 이번 시그니처 변경과 무관하므로 그대로 통과해야 한다. 깨지면 보고할 것.

- [ ] **커밋 (이 태스크 전체를 한 번에)**

```bash
cd /Users/hong9/IdeaProjects/allfolio/.claude/worktrees/silly-almeida-a439a1
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/ allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/
git commit -m "feat(af-99): 시세 포트를 다중 심볼로 일반화 (USDT·BTC·ETH)

포트 시그니처를 바꾸면 두 파서·두 소스·체인·스케줄러가 동시에 깨지므로
한 커밋으로 묶는다. 쪼개면 중간 커밋에서 테스트를 돌릴 수 없다.

- FxQuoteSource/FxApiClient: fetchKrwRates(): Map<String, BigDecimal>
- Upbit: markets= 3개를 한 요청으로. 배열 순서를 못 믿으므로 market 필드로 매칭
- Bithumb: ALL_KRW로 전환. data에 섞인 date 키를 피하려고 순회하지 않고 키로 꺼낸다
- 체인: 심볼 단위 해소 — 한 심볼의 장애가 나머지를 낡게 만들지 않는다
- 범위 가드를 심볼별로 분리 (USDT 500~5000은 BTC 8900만에 무의미하다)"
```


---

### Task 2: 크립토 상수 제거와 TTL 분리 (TDD)

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/RedisFxRateService.kt`
- Create: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/RedisFxRateServiceCryptoTest.kt`

주의: 지금 `setCryptoToKrw`는 `cryptoFallback(symbol)`을 **심볼 검증용으로** 호출한다. 상수를 없애면 그 검증도 같이 사라지므로 대체가 필요하다.

- [ ] **Step 1: 실패하는 테스트 작성**

`RedisFxRateServiceCryptoTest.kt`:

```kotlin
package com.allfolio.fx

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.math.BigDecimal

/**
 * 코인 KRW 시세에는 폴백 상수가 없다.
 *
 * 상수를 두면 갱신 주체가 사라진 순간 조용히 틀린 값이 평가에 들어간다 —
 * 실제로 ETH가 4,500,000으로 박혀 있어 실제 2,663,000 대비 69% 과대평가였다.
 * 없는 값은 없다고 말하게 한다.
 */
class RedisFxRateServiceCryptoTest {

    private lateinit var ops: ValueOperations<String, String>
    private lateinit var service: RedisFxRateService

    @Suppress("UNCHECKED_CAST")
    @BeforeEach
    fun setUp() {
        ops = Mockito.mock(ValueOperations::class.java) as ValueOperations<String, String>
        val redis = Mockito.mock(StringRedisTemplate::class.java)
        Mockito.`when`(redis.opsForValue()).thenReturn(ops)
        service = RedisFxRateService(redis, BigDecimal("1400"))
    }

    @Test
    fun `Redis에 값이 있으면 그 값을 쓴다`() {
        Mockito.`when`(ops.get("fx:btckrw")).thenReturn("89825000")

        assertThat(service.getCryptoToKrw("BTC")).isEqualByComparingTo("89825000")
    }

    @Test
    fun `Redis가 비어 있으면 예외 - 상수를 지어내지 않는다`() {
        Mockito.`when`(ops.get("fx:ethkrw")).thenReturn(null)

        assertThatThrownBy { service.getCryptoToKrw("ETH") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("ETH")
    }

    @Test
    fun `Redis가 죽어도 예외 - 옛 구현은 여기서 상수를 돌려줬다`() {
        Mockito.`when`(ops.get("fx:btckrw")).thenThrow(RuntimeException("connection refused"))

        assertThatThrownBy { service.getCryptoToKrw("BTC") }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `지원하지 않는 심볼은 IllegalArgumentException`() {
        assertThatThrownBy { service.getCryptoToKrw("DOGE") }
            .isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy { service.setCryptoToKrw("DOGE", BigDecimal.ONE) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `대소문자를 가리지 않는다`() {
        Mockito.`when`(ops.get("fx:btckrw")).thenReturn("89825000")

        assertThat(service.getCryptoToKrw("btc")).isEqualByComparingTo("89825000")
    }

    @Test
    fun `USDT는 여전히 폴백 상수를 쓴다 - 코인만 달라진다`() {
        Mockito.`when`(ops.get("fx:usdtkrw")).thenReturn(null)

        assertThat(service.getUsdtToKrw()).isEqualByComparingTo("1400")
    }
}
```

**mockito-kotlin을 쓰지 않는다.** 이 프로젝트에 없다 — `FxRateAdminHanaControllerTest:101`에 그 사실이 주석으로 남아 있다. 위처럼 순수 `org.mockito.Mockito`를 쓰고, `ValueOperations`는 제네릭이라 캐스팅이 필요하다(`BrokerSyncSchedulerTest`가 쓰는 방식과 같은 계열).

- [ ] **Step 2: 실패 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.fx.RedisFxRateServiceCryptoTest"`
Expected: FAIL (생성자 인자 개수가 안 맞는다)

- [ ] **Step 3: 구현**

`RedisFxRateService.kt`에서 아래 네 곳을 바꾼다.

**(1) 클래스 KDoc** — 크립토 항목을 추가:

```kotlin
/**
 * Redis 기반 환율 캐시 서비스
 *
 * Redis key: fx:usdtkrw · fx:btckrw · fx:ethkrw
 * TTL: USDT 180초 (폴링 주기의 3배) · 코인 24시간
 *
 * 폴백 우선순위:
 *   1. Redis 캐시
 *   2. USDT만 설정값 (FX_USDT_KRW_FALLBACK, 기본값 1400)
 *
 * **코인에는 폴백 상수가 없다.** 상수를 두면 갱신 주체가 사라진 순간 조용히 틀린 값이
 * 평가에 들어간다 — ETH가 4,500,000으로 박혀 실제 2,663,000 대비 69% 과대평가였다.
 * 값이 없으면 IllegalStateException을 던진다. 수집기가 60초마다 채우므로
 * 이 예외는 두 거래소가 동시에 죽어 있는 동안에만 나온다.
 */
```

**(2) 생성자** — 크립토 두 파라미터를 없앤다:

```kotlin
@Service
class RedisFxRateService(
    private val redisTemplate: StringRedisTemplate,
    @Value("\${fx.usdt-krw.fallback-rate:1400}")
    private val fallbackRate: BigDecimal,
) : FxRateService {
```

**(3) companion object** — 크립토 TTL을 추가 (기존 `TTL`과 그 주석은 그대로 두고 이름만 `TTL_USDT`로 바꾼다):

```kotlin
    companion object {
        private const val KEY = "fx:usdtkrw"

        /** 코인은 이 목록만 다룬다. CurrencyConverter의 "BTC","ETH" 분기와 짝이다. */
        private val CRYPTO_SUPPORTED = setOf("BTC", "ETH")

        /**
         * 폴링 주기(기본 60초)의 3배.
         *
         * TTL이 폴링 주기와 같으면 안 된다. @Scheduled(fixedDelay)는 직전 실행이 *끝난*
         * 시점부터 재므로 다음 쓰기는 항상 `주기 + fetch 시간` 뒤에 일어나는데,
         * 키는 정확히 주기에 만료된다 — 즉 매 주기 갱신 직전에 반드시 만료 창이 생기고
         * 그 동안 수집기가 멀쩡한데도 폴백 상수가 반환된다.
         *
         * 3배로 두면 연속 2회 실패까지는 마지막 정상 환율을 지킨다.
         *
         * 이 값은 fx.scheduler.delay-ms(기본 60초)와 짝이다. 한쪽만 바꾸면 이 관계가 깨지는데
         * 컴파일러도 테스트도 잡아 주지 않는다.
         */
        private val TTL_USDT = Duration.ofSeconds(180)

        /**
         * 코인은 24시간.
         *
         * USDT와 사정이 다르다. USDT는 폴백 상수가 있어 만료가 "상수로 떨어짐"을 뜻하지만,
         * 코인은 상수가 없으므로 만료가 곧 **데이터 없음**이다.
         * 60초마다 덮어쓰므로 24시간 만료는 "수집이 하루 종일 죽어 있었다"는 뜻이고,
         * 그건 만료보다 훨씬 먼저 드러나야 할 사건이다.
         */
        private val TTL_CRYPTO = Duration.ofHours(24)
    }
```

기존에 `TTL`을 쓰던 `setUsdtToKrw`의 호출부를 `TTL_USDT`로 바꾼다.

**(4) 크립토 메서드** — `cryptoFallback`을 지우고 검증·조회·기록을 다시 쓴다:

```kotlin
    private fun cryptoKey(symbol: String) = "fx:${symbol.lowercase()}krw"

    /** 상수가 사라졌으므로 심볼 검증을 여기서 따로 한다 — 예전엔 cryptoFallback이 겸하고 있었다. */
    private fun requireSupported(symbol: String): String {
        val upper = symbol.uppercase()
        require(upper in CRYPTO_SUPPORTED) { "지원하지 않는 코인: $symbol" }
        return upper
    }

    override fun getCryptoToKrw(symbol: String): BigDecimal {
        val upper = requireSupported(symbol)

        val cached = runCatching { redisTemplate.opsForValue().get(cryptoKey(upper)) }
            .getOrElse { e ->
                log.error("[FxRate] Redis error {}krw: {}", upper, e.message)
                null
            }

        // 상수를 돌려주지 않는다. 없는 값을 지어내면 조용히 틀린 평가가 나간다.
        return cached?.let { BigDecimal(it) }
            ?: throw IllegalStateException(
                "$upper KRW 시세가 없습니다 — 수집기가 도는지 [ExchangeFx] 로그를 확인하십시오"
            )
    }

    override fun setCryptoToKrw(symbol: String, rate: BigDecimal) {
        val upper = requireSupported(symbol)
        runCatching { redisTemplate.opsForValue().set(cryptoKey(upper), rate.toPlainString(), TTL_CRYPTO) }
            .onFailure { e -> log.error("[FxRate] Redis SET {}krw failed: {}", upper, e.message) }
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.fx.RedisFxRateServiceCryptoTest"`
Expected: PASS (6 tests)

- [ ] **Step 5: 전체 테스트**

Run: `cd allfolio-backend && ./gradlew :backend-app:test`
Expected: `BUILD SUCCESSFUL`

깨지는 테스트가 있으면 **원인을 보고할 것.** 스펙에서 확인한 바로는 테스트의 `90000000`·`4500000`은 전부 가짜 구현의 자체 값이라 영향이 없어야 한다.

- [ ] **Step 6: Commit**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/RedisFxRateService.kt allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/RedisFxRateServiceCryptoTest.kt
git commit -m "fix(af-99): 코인 폴백 상수 제거 — ETH 69% 과대평가 해소"
```

---

### Task 3: 설정 정리와 빈 조립 테스트 갱신

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/resources/application.yml`
- Modify: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/exchange/ExchangeFxConfigWiringTest.kt`

- [ ] **Step 1: yml에서 코인 폴백 블록 삭제**

아래 네 줄을 **지운다**:

```yaml
  # 코인 KRW 시세 폴백 (QA P3) — 라이브 피드 없을 때 어드민 PUT /api/admin/fx/crypto/{symbol}로 갱신
  btc-krw:
    fallback-rate: ${FX_BTC_KRW_FALLBACK:90000000}
  eth-krw:
    fallback-rate: ${FX_ETH_KRW_FALLBACK:4500000}
```

`fx.exchange`와 `fx.usdt-krw` 블록은 그대로 둔다. 지운 자리에 아래 주석을 남긴다:

```yaml
  # 코인(BTC·ETH)에는 폴백 상수를 두지 않는다. 갱신 주체가 없는 상수는 반드시 어긋나고
  # 조용히 평가에 들어간다 — ETH가 4,500,000으로 박혀 실제 2,663,000 대비 69% 과대평가였다.
  # 값이 없으면 RedisFxRateService가 예외를 던진다.
```

- [ ] **Step 2: 빈 조립 테스트에 심볼 순서 단언 유지 확인**

`ExchangeFxConfigWiringTest.kt`는 `sources` 필드를 반사로 읽어 순서를 검증한다. 시그니처 변경과 무관하므로 **그대로 통과해야 한다.** 통과하지 않으면 보고할 것.

- [ ] **Step 3: `render.yaml` 확인**

`FX_BTC_KRW_FALLBACK`·`FX_ETH_KRW_FALLBACK`이 `render.yaml`에 있는지 확인한다:

```bash
grep -n "FX_BTC\|FX_ETH" render.yaml
```

있으면 지운다. 없으면 그대로 둔다(현재는 없을 것이다).

- [ ] **Step 4: 전체 테스트**

Run: `cd allfolio-backend && ./gradlew :backend-app:test`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add allfolio-backend/backend-app/src/main/resources/application.yml render.yaml
git commit -m "chore(af-99): 코인 폴백 상수 설정 제거"
```

---

### Task 4: 실제 엔드포인트 종단 확인 (수동, 네트워크 필요)

단위 테스트는 픽스처로 돈다. 배포 전 한 번은 진짜 응답으로 확인한다. 커밋을 만들지 않는다.

**Files:** 임시 파일 하나 (검증 후 삭제)

- [ ] **Step 1: 임시 종단 테스트 작성**

Create (임시): `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/exchange/TempLiveCryptoTest.kt`

```kotlin
package com.allfolio.fx.exchange

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** 임시 검증용 — 커밋하지 않는다. 실제 거래소를 때린다. */
class TempLiveCryptoTest {

    private val mapper = ObjectMapper()

    private fun chain() = ExchangeFxApiClient(
        listOf(
            UpbitFxSource("https://api.upbit.com", UpbitFxParser(mapper)),
            BithumbFxSource("https://api.bithumb.com", BithumbFxParser(mapper)),
        )
    )

    @Test
    fun `실제 거래소에서 세 심볼을 다 가져온다`() {
        val rates = chain().fetchKrwRates()
        println(">>> LIVE CHAIN = $rates")

        assertThat(rates).containsOnlyKeys("USDT", "BTC", "ETH")
    }

    @Test
    fun `두 거래소 값이 크게 벌어지지 않는다`() {
        val upbit = UpbitFxSource("https://api.upbit.com", UpbitFxParser(mapper)).fetchKrwRates()
        val bithumb = BithumbFxSource("https://api.bithumb.com", BithumbFxParser(mapper)).fetchKrwRates()

        println(">>> UPBIT   = $upbit")
        println(">>> BITHUMB = $bithumb")

        FxSymbols.ALL.forEach { s ->
            val diff = ((upbit.getValue(s) - bithumb.getValue(s)).abs()
                .divide(upbit.getValue(s), 6, java.math.RoundingMode.HALF_UP))
            println(">>> $s 괴리율 = $diff")
            assertThat(diff).isLessThan(java.math.BigDecimal("0.05"))
        }
    }

    @Test
    fun `Upbit이 죽어도 Bithumb이 세 심볼을 채운다`() {
        val client = ExchangeFxApiClient(
            listOf(
                UpbitFxSource("http://127.0.0.1:1", UpbitFxParser(mapper)),
                BithumbFxSource("https://api.bithumb.com", BithumbFxParser(mapper)),
            )
        )

        val rates = client.fetchKrwRates()
        println(">>> FALLBACK = $rates")

        assertThat(rates).containsOnlyKeys("USDT", "BTC", "ETH")
    }
}
```

- [ ] **Step 2: 실행**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.fx.exchange.TempLiveCryptoTest" -i`

`>>>` 줄을 확인한다. ETH가 4,500,000이 아니라 260만 대인지 눈으로 볼 것.

- [ ] **Step 3: 임시 파일 삭제**

```bash
rm allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/exchange/TempLiveCryptoTest.kt
git status --porcelain   # 비어 있어야 한다
```

---

### Task 5: 최종 검증과 PR

- [ ] **Step 1: 전체 테스트**

Run: `cd allfolio-backend && ./gradlew :backend-app:test`
Expected: `BUILD SUCCESSFUL`, 실패 0건

- [ ] **Step 2: 상수 잔재 확인**

```bash
grep -rn "4500000\|90000000" allfolio-backend --include="*.kt" --include="*.yml" | grep -v "/test/"
```

Expected: 출력 없음 (테스트의 가짜 구현 값은 남아도 된다)

- [ ] **Step 3: 스펙 대비 확인**

- [ ] `fetchKrwRates`가 `FxQuoteSource`·`FxApiClient` 양쪽에 반영됐다
- [ ] Bithumb 코덱이 1MB다
- [ ] 범위가 심볼별로 나뉘었다
- [ ] `fx.btc-krw`·`fx.eth-krw`가 yml에서 사라졌다
- [ ] `getCryptoToKrw`가 미스 시 `IllegalStateException`을 던진다
- [ ] USDT의 TTL 180초와 폴백 1400은 그대로다

- [ ] **Step 4: 푸시하고 PR 생성**

```bash
git push -u origin fix/af-99-crypto-krw-feed
```

PR 본문은 이 계획의 상단 요약과 스펙의 「배경」·「오차」 표를 그대로 쓴다. 반드시 포함할 것:
- ETH 69% 과대평가 수치와 실측 근거
- `FxApiClient`·`FxRateScheduler`를 이번엔 바꾼 이유 (PR #140의 제약은 범위 축소 목적이었다)
- 코인에 폴백 상수가 없어졌고 미스 시 예외라는 **동작 변경**
- Bithumb 코덱 상향 근거 (169KB / 256KB 한도)

## 배포 후 확인

1. `[FxScheduler] updated {USDT=..., BTC=..., ETH=...}` 가 60초마다 찍히는지
2. `GET /api/admin/fx/crypto/ETH` 가 4,500,000이 아니라 실제 시세를 주는지
3. `[ExchangeFx] 끝내 못 채운 심볼=` 경고가 반복되지 않는지 — 반복되면 그 심볼의 마켓명을 확인할 것
