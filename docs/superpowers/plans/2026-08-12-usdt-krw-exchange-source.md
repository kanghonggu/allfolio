# USDT/KRW 시세 소스 교체 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 동작 불가능한 `BinanceFxApiClient`를 Upbit(주)·Bithumb(폴백) 기반 구현으로 교체해, 거래소 자산의 4.1% 저평가를 없앤다.

**Architecture:** `FxApiClient` 포트는 그대로 두고 그 아래에 `FxQuoteSource` 체인을 만든다. `ExchangeFxApiClient`가 유일한 `FxApiClient` 빈으로서 소스를 순서대로 시도하고, 전부 실패할 때만 예외를 던져 `FxRateScheduler`의 기존 catch 계약을 지킨다. 각 소스의 응답 파싱은 순수 함수로 분리해 네트워크 없이 픽스처로 검증한다.

**Tech Stack:** Kotlin, Spring Boot(WebFlux `WebClient`), Jackson, JUnit 5, AssertJ, Gradle

**Spec:** [2026-08-12-usdt-krw-exchange-source-design.md](../specs/2026-08-12-usdt-krw-exchange-source-design.md)

---

## File Structure

**신규** (`allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/exchange/`)

| 파일 | 책임 |
|---|---|
| `FxQuoteSource.kt` | 소스 포트 + `FxQuoteException` |
| `ExchangeFxProperties.kt` | `fx.exchange.*` base-url 설정 |
| `UpbitFxParser.kt` | Upbit JSON → `BigDecimal` (순수) |
| `UpbitFxSource.kt` | Upbit HTTP 호출 |
| `BithumbFxParser.kt` | Bithumb JSON → `BigDecimal` (순수, `status` 검사) |
| `BithumbFxSource.kt` | Bithumb HTTP 호출 |
| `ExchangeFxApiClient.kt` | 소스 체인 + 유효범위 가드 |
| `ExchangeFxConfig.kt` | 소스 순서 명시적 조립 |

**신규 테스트** (`allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/exchange/`)

`UpbitFxParserTest.kt`, `BithumbFxParserTest.kt`, `ExchangeFxApiClientTest.kt`

**수정**

- 삭제: `backend-app/src/main/kotlin/com/allfolio/fx/BinanceFxApiClient.kt`
- `backend-app/src/main/kotlin/com/allfolio/fx/RedisFxRateService.kt` — TTL 60s → 180s
- `backend-app/src/main/kotlin/com/allfolio/external/crypto/BinanceProperties.kt:21` — `@DefaultValue`
- `backend-app/src/main/kotlin/com/allfolio/app/BackendApplication.kt` — 프로퍼티 등록
- `backend-app/src/main/resources/application.yml` — 196행, 249행, 252행 + `fx.exchange` 추가
- `render.yaml` — `FX_*` envVars 명시

**파싱을 HTTP에서 분리하는 이유:** 이 자리에 테스트가 없어서 동작 불가능한 클라이언트가 배포됐다. `bodyToMono(String)`으로 본문을 받고 파싱은 순수 함수에 두면, 실제로 받은 응답을 픽스처로 박아 네트워크 없이 회귀를 막을 수 있다.

---

### Task 1: `FxQuoteSource` 포트와 설정 프로퍼티

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/exchange/FxQuoteSource.kt`
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/exchange/ExchangeFxProperties.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/app/BackendApplication.kt`

- [ ] **Step 1: 포트 인터페이스 작성**

`FxQuoteSource.kt`:

```kotlin
package com.allfolio.fx.exchange

import java.math.BigDecimal

/**
 * 개별 거래소의 USDT/KRW 시세 소스.
 *
 * [com.allfolio.fx.FxApiClient]가 아니라 그 구현체([ExchangeFxApiClient])의 부품이다.
 * FxApiClient를 직접 구현하면 빈이 둘이 되어 FxRateScheduler의 주입이 깨진다.
 *
 * 실패는 예외로 알린다. 0이나 null을 돌려주면 호출자가 "실패"와 "진짜 0원"을
 * 구분할 수 없고, 그 값이 그대로 모든 자산 평가에 흘러든다.
 */
interface FxQuoteSource {
    /** 로그·진단에 쓰는 소스 이름 (예: "UPBIT") */
    val sourceName: String

    /** USDT → KRW 현재가. 실패 시 [FxQuoteException]. */
    fun fetchUsdtKrw(): BigDecimal
}

/** 소스 하나가 실패했다는 신호. 체인이 다음 소스로 넘어가는 근거가 된다. */
class FxQuoteException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
```

- [ ] **Step 2: 설정 프로퍼티 작성**

`ExchangeFxProperties.kt`:

```kotlin
package com.allfolio.fx.exchange

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 거래소 시세 소스 접속 설정.
 *
 * base-url을 환경변수로 뺀 이유는 BinanceFxApiClient가 testnet 기본값에 묶여 있던 문제를
 * 되풀이하지 않기 위해서다. 기본값은 운영 주소이고, 테스트에서만 로컬 스텁으로 덮는다.
 */
@ConfigurationProperties(prefix = "fx.exchange")
data class ExchangeFxProperties(
    val upbitBaseUrl: String = "https://api.upbit.com",
    val bithumbBaseUrl: String = "https://api.bithumb.com",
)
```

- [ ] **Step 3: 프로퍼티 등록**

`BackendApplication.kt`에서 import를 추가하고:

```kotlin
import com.allfolio.fx.exchange.ExchangeFxProperties
```

`@EnableConfigurationProperties` 목록의 `EcosProperties::class,` 뒤에 추가:

```kotlin
    EcosProperties::class,
    ExchangeFxProperties::class,
)
```

- [ ] **Step 4: 컴파일 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:compileKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/exchange/ allfolio-backend/backend-app/src/main/kotlin/com/allfolio/app/BackendApplication.kt
git commit -m "feat(af-99): FxQuoteSource 포트와 거래소 FX 설정 프로퍼티 추가"
```

---

### Task 2: `UpbitFxParser` (TDD)

Upbit 응답은 배열이고 가격은 `trade_price`에 있다. 아래 픽스처는 2026-08-12 실제 응답을 줄인 것이다.

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/exchange/UpbitFxParser.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/exchange/UpbitFxParserTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

`UpbitFxParserTest.kt`:

```kotlin
package com.allfolio.fx.exchange

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class UpbitFxParserTest {

    private val parser = UpbitFxParser(ObjectMapper())

    /** 2026-08-12 api.upbit.com/v1/ticker?markets=KRW-USDT 실제 응답에서 필드를 줄인 것 */
    private val realResponse = """
        [{"market":"KRW-USDT","trade_date":"20260812","trade_time":"052720",
          "opening_price":1409.00000000,"high_price":1411.00000000,
          "low_price":1407.00000000,"trade_price":1408.00000000,
          "prev_closing_price":1409.00000000,"timestamp":1786512440253}]
    """.trimIndent()

    @Test
    fun `실제 응답에서 trade_price를 읽는다`() {
        assertThat(parser.parse(realResponse)).isEqualByComparingTo("1408.0")
    }

    @Test
    fun `소수점이 있는 가격도 정밀도를 잃지 않는다`() {
        val json = """[{"market":"KRW-USDT","trade_price":1408.55}]"""

        assertThat(parser.parse(json)).isEqualByComparingTo("1408.55")
    }

    @Test
    fun `빈 배열이면 예외 - 조용히 0을 돌려주면 모든 평가가 오염된다`() {
        assertThatThrownBy { parser.parse("[]") }
            .isInstanceOf(FxQuoteException::class.java)
            .hasMessageContaining("비어")
    }

    @Test
    fun `trade_price 필드가 없으면 예외`() {
        val json = """[{"market":"KRW-USDT","opening_price":1409.0}]"""

        assertThatThrownBy { parser.parse(json) }
            .isInstanceOf(FxQuoteException::class.java)
            .hasMessageContaining("trade_price")
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
Expected: FAIL — `Unresolved reference: UpbitFxParser`

- [ ] **Step 3: 최소 구현**

`UpbitFxParser.kt`:

```kotlin
package com.allfolio.fx.exchange

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Upbit ticker 응답 → USDT/KRW.
 *
 * HTTP에서 분리한 이유: 이 자리에 테스트가 없어서 동작할 수 없는 Binance 클라이언트가
 * 배포됐다. 순수 함수로 두면 실제 응답 픽스처로 네트워크 없이 회귀를 막는다.
 *
 * 응답 형태: [{"market":"KRW-USDT","trade_price":1408.0, ...}]
 *
 * 숫자를 BigDecimal로 만들 때 asText()를 거치는 이유는 asDouble()이 2진 부동소수점을
 * 경유하면서 정밀도를 잃기 때문이다. 환율은 평가액 전체에 곱해지는 값이라 그 오차가 증폭된다.
 */
@Component
class UpbitFxParser(private val objectMapper: ObjectMapper) {

    fun parse(body: String): BigDecimal {
        val root = try {
            objectMapper.readTree(body)
        } catch (e: Exception) {
            throw FxQuoteException("Upbit 응답이 JSON이 아닙니다", e)
        }

        if (!root.isArray || root.isEmpty) {
            throw FxQuoteException("Upbit 응답이 비어 있습니다")
        }

        val price = root[0].get("trade_price")
            ?: throw FxQuoteException("Upbit 응답에 trade_price가 없습니다")

        if (!price.isNumber) {
            throw FxQuoteException("Upbit trade_price가 숫자가 아닙니다: ${price.asText()}")
        }

        return BigDecimal(price.asText())
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.fx.exchange.UpbitFxParserTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/exchange/UpbitFxParser.kt allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/exchange/UpbitFxParserTest.kt
git commit -m "feat(af-99): Upbit ticker 응답 파서 추가"
```

---

### Task 3: `BithumbFxParser` (TDD)

**`status` 검사가 방어가 아니라 핵심이다.** Bithumb은 실패해도 **HTTP 200**에 `{"status":"5500"}`만 돌려주고 `data` 필드 자체가 없다(2026-08-12 실측). `retrieve()`가 예외를 던지지 않으므로, status를 안 보면 조회 실패가 파싱 단계에서 0으로 흘러들어 모든 평가를 오염시킨다.

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/exchange/BithumbFxParser.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/exchange/BithumbFxParserTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

`BithumbFxParserTest.kt`:

```kotlin
package com.allfolio.fx.exchange

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BithumbFxParserTest {

    private val parser = BithumbFxParser(ObjectMapper())

    /** 2026-08-12 api.bithumb.com/public/ticker/USDT_KRW 실제 응답에서 필드를 줄인 것 */
    private val realResponse = """
        {"status":"0000","data":{"opening_price":"1409","closing_price":"1409",
          "min_price":"1407","max_price":"1411","prev_closing_price":"1408",
          "date":"1786512440962"}}
    """.trimIndent()

    /** 2026-08-12 실측: 잘못된 심볼도 HTTP 200으로 오고 data가 아예 없다 */
    private val errorResponse = """{"status":"5500","message":"상장 코인이 아닙니다."}"""

    @Test
    fun `실제 응답에서 closing_price를 읽는다`() {
        assertThat(parser.parse(realResponse)).isEqualByComparingTo("1409")
    }

    @Test
    fun `status가 0000이 아니면 예외 - HTTP 200이라 이 검사가 유일한 방어선이다`() {
        assertThatThrownBy { parser.parse(errorResponse) }
            .isInstanceOf(FxQuoteException::class.java)
            .hasMessageContaining("5500")
    }

    @Test
    fun `status가 정상인데 data가 없으면 예외`() {
        assertThatThrownBy { parser.parse("""{"status":"0000"}""") }
            .isInstanceOf(FxQuoteException::class.java)
            .hasMessageContaining("data")
    }

    @Test
    fun `closing_price가 숫자 문자열이 아니면 예외`() {
        val json = """{"status":"0000","data":{"closing_price":"N/A"}}"""

        assertThatThrownBy { parser.parse(json) }
            .isInstanceOf(FxQuoteException::class.java)
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
Expected: FAIL — `Unresolved reference: BithumbFxParser`

- [ ] **Step 3: 최소 구현**

`BithumbFxParser.kt`:

```kotlin
package com.allfolio.fx.exchange

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Bithumb public ticker 응답 → USDT/KRW.
 *
 * 응답 형태: {"status":"0000","data":{"closing_price":"1409", ...}}
 *
 * **status 검사가 이 파서의 핵심이다.** Bithumb은 조회에 실패해도 HTTP 200을 주고
 * status만 바꾼다(2026-08-12 실측: 잘못된 심볼 → 200 + {"status":"5500"}, data 없음).
 * WebClient의 retrieve()가 예외를 던져 주지 않으므로 여기서 막지 않으면
 * 조회 실패가 그대로 환율로 흘러든다.
 *
 * 가격이 문자열로 온다는 점도 Upbit과 다르다.
 */
@Component
class BithumbFxParser(private val objectMapper: ObjectMapper) {

    companion object {
        private const val STATUS_OK = "0000"
    }

    fun parse(body: String): BigDecimal {
        val root = try {
            objectMapper.readTree(body)
        } catch (e: Exception) {
            throw FxQuoteException("Bithumb 응답이 JSON이 아닙니다", e)
        }

        val status = root.get("status")?.asText()
        if (status != STATUS_OK) {
            throw FxQuoteException("Bithumb status=$status")
        }

        val data = root.get("data")
            ?: throw FxQuoteException("Bithumb 응답에 data가 없습니다")

        val raw = data.get("closing_price")?.asText()
            ?: throw FxQuoteException("Bithumb 응답에 closing_price가 없습니다")

        return try {
            BigDecimal(raw)
        } catch (e: NumberFormatException) {
            throw FxQuoteException("Bithumb closing_price가 숫자가 아닙니다: $raw", e)
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.fx.exchange.BithumbFxParserTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/exchange/BithumbFxParser.kt allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/exchange/BithumbFxParserTest.kt
git commit -m "feat(af-99): Bithumb ticker 응답 파서 추가 (HTTP 200 오류 응답 차단)"
```

---

### Task 4: HTTP 소스 두 개

파싱이 검증됐으니 HTTP는 얇게 남는다. 두 소스 모두 `bodyToMono(String)`으로 본문만 받아 파서에 넘긴다.

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/exchange/UpbitFxSource.kt`
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/exchange/BithumbFxSource.kt`

- [ ] **Step 1: Upbit 소스 작성**

`UpbitFxSource.kt`:

```kotlin
package com.allfolio.fx.exchange

import org.slf4j.LoggerFactory
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.time.Duration

/**
 * Upbit KRW-USDT 시세 소스 (주 소스).
 *
 * `GET /v1/ticker?markets=KRW-USDT` — 무인증, 무료.
 * 레이트리밋은 응답 헤더 `remaining-req: group=ticker; min=600; sec=8` 기준
 * 초당 10회·분당 600회다. 60초 폴링 대비 약 600배 여유.
 *
 * 국내 거래소를 쓰는 이유는 Binance에 KRW 마켓이 없어서만이 아니다.
 * 거래소 USDT를 KRW로 실현하는 실제 경로가 국내 거래소 매도이므로,
 * AF-99가 "실현 가능한 값"이라고 부른 것이 바로 이 시세다.
 */
class UpbitFxSource(
    baseUrl: String,
    private val parser: UpbitFxParser,
) : FxQuoteSource {

    private val log = LoggerFactory.getLogger(javaClass)

    override val sourceName = "UPBIT"

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(baseUrl)
            .codecs { it.defaultCodecs().maxInMemorySize(256 * 1024) }
            .build()
    }

    companion object {
        private const val PATH = "/v1/ticker?markets=KRW-USDT"
        private val TIMEOUT = Duration.ofSeconds(5)
    }

    override fun fetchUsdtKrw(): BigDecimal {
        val body = try {
            webClient.get()
                .uri(PATH)
                .retrieve()
                .bodyToMono(String::class.java)
                .block(TIMEOUT)
                ?: throw FxQuoteException("Upbit 응답 본문이 비어 있습니다")
        } catch (e: FxQuoteException) {
            // 위 "본문 비어 있음". 아래 Throwable 절이 메시지를 덮지 않도록 먼저 통과시킨다.
            throw e
        } catch (e: Throwable) {
            if (e is Error) throw e
            if (e is InterruptedException || e.cause is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            // block(TIMEOUT)의 타임아웃은 WebClientException이 아니라 IllegalStateException으로
            // 새로 던져진다. 예외 종류를 열거하면 그런 경로가 샌다.
            log.warn("[UpbitFx] 호출 실패 reason={}", e.javaClass.simpleName)
            throw FxQuoteException("Upbit 호출에 실패했습니다", e)
        }

        return parser.parse(body)
    }
}
```

- [ ] **Step 2: Bithumb 소스 작성**

`BithumbFxSource.kt`:

```kotlin
package com.allfolio.fx.exchange

import org.slf4j.LoggerFactory
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.time.Duration

/**
 * Bithumb USDT_KRW 시세 소스 (폴백).
 *
 * `GET /public/ticker/USDT_KRW` — 무인증, 무료.
 *
 * 폴백을 두는 이유는 이번 사고의 본질이 "단일 소스가 조용히 죽었다"는 것이기 때문이다.
 * 2026-08-12 기준 두 소스 값이 1408 vs 1409로 일치해 상호 검증 역할도 한다.
 *
 * 오류 판정은 [BithumbFxParser]가 status로 한다 — 이 API는 실패도 HTTP 200으로 준다.
 */
class BithumbFxSource(
    baseUrl: String,
    private val parser: BithumbFxParser,
) : FxQuoteSource {

    private val log = LoggerFactory.getLogger(javaClass)

    override val sourceName = "BITHUMB"

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(baseUrl)
            .codecs { it.defaultCodecs().maxInMemorySize(256 * 1024) }
            .build()
    }

    companion object {
        private const val PATH = "/public/ticker/USDT_KRW"
        private val TIMEOUT = Duration.ofSeconds(5)
    }

    override fun fetchUsdtKrw(): BigDecimal {
        val body = try {
            webClient.get()
                .uri(PATH)
                .retrieve()
                .bodyToMono(String::class.java)
                .block(TIMEOUT)
                ?: throw FxQuoteException("Bithumb 응답 본문이 비어 있습니다")
        } catch (e: FxQuoteException) {
            throw e
        } catch (e: Throwable) {
            if (e is Error) throw e
            if (e is InterruptedException || e.cause is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            log.warn("[BithumbFx] 호출 실패 reason={}", e.javaClass.simpleName)
            throw FxQuoteException("Bithumb 호출에 실패했습니다", e)
        }

        return parser.parse(body)
    }
}
```

- [ ] **Step 3: 컴파일 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:compileKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/exchange/UpbitFxSource.kt allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/exchange/BithumbFxSource.kt
git commit -m "feat(af-99): Upbit·Bithumb HTTP 시세 소스 추가"
```

---

### Task 5: `ExchangeFxApiClient` — 체인과 유효범위 가드 (TDD)

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/exchange/ExchangeFxApiClient.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/exchange/ExchangeFxApiClientTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

`ExchangeFxApiClientTest.kt`:

```kotlin
package com.allfolio.fx.exchange

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ExchangeFxApiClientTest {

    /** 지정한 값을 돌려주거나 예외를 던지는 가짜 소스. 네트워크 없이 체인만 검증한다. */
    private class FakeSource(
        override val sourceName: String,
        private val result: Result<BigDecimal>,
    ) : FxQuoteSource {
        var callCount = 0
            private set

        override fun fetchUsdtKrw(): BigDecimal {
            callCount++
            return result.getOrThrow()
        }
    }

    private fun ok(name: String, value: String) =
        FakeSource(name, Result.success(BigDecimal(value)))

    private fun fail(name: String) =
        FakeSource(name, Result.failure(FxQuoteException("$name 실패")))

    @Test
    fun `첫 소스가 성공하면 그 값을 쓰고 두 번째는 부르지 않는다`() {
        val first = ok("UPBIT", "1408")
        val second = ok("BITHUMB", "1409")

        val rate = ExchangeFxApiClient(listOf(first, second)).getUsdtKrw()

        assertThat(rate).isEqualByComparingTo("1408")
        assertThat(second.callCount).isZero()
    }

    @Test
    fun `첫 소스가 실패하면 두 번째로 넘어간다`() {
        val client = ExchangeFxApiClient(listOf(fail("UPBIT"), ok("BITHUMB", "1409")))

        assertThat(client.getUsdtKrw()).isEqualByComparingTo("1409")
    }

    @Test
    fun `모든 소스가 실패하면 예외 - 스케줄러가 잡아 기존 캐시를 지킨다`() {
        val client = ExchangeFxApiClient(listOf(fail("UPBIT"), fail("BITHUMB")))

        assertThatThrownBy { client.getUsdtKrw() }
            .isInstanceOf(FxQuoteException::class.java)
            .hasMessageContaining("모든 소스")
    }

    @Test
    fun `범위를 벗어난 값은 실패로 보고 다음 소스로 넘어간다`() {
        // 파싱이 깨져 0이 나온 상황. 0을 그대로 쓰면 모든 자산이 0원이 된다.
        val client = ExchangeFxApiClient(listOf(ok("UPBIT", "0"), ok("BITHUMB", "1409")))

        assertThat(client.getUsdtKrw()).isEqualByComparingTo("1409")
    }

    @Test
    fun `비정상적으로 큰 값도 거른다`() {
        // 원 단위와 다른 필드를 잘못 읽은 상황
        val client = ExchangeFxApiClient(listOf(ok("UPBIT", "1786512440253"), ok("BITHUMB", "1409")))

        assertThat(client.getUsdtKrw()).isEqualByComparingTo("1409")
    }

    @Test
    fun `모든 소스가 범위 밖이면 예외 - 그럴듯한 쓰레기를 쓰느니 캐시를 지킨다`() {
        val client = ExchangeFxApiClient(listOf(ok("UPBIT", "0"), ok("BITHUMB", "0")))

        assertThatThrownBy { client.getUsdtKrw() }
            .isInstanceOf(FxQuoteException::class.java)
    }

    @Test
    fun `소스가 하나도 없으면 예외`() {
        assertThatThrownBy { ExchangeFxApiClient(emptyList()).getUsdtKrw() }
            .isInstanceOf(FxQuoteException::class.java)
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.fx.exchange.ExchangeFxApiClientTest"`
Expected: FAIL — `Unresolved reference: ExchangeFxApiClient`

- [ ] **Step 3: 최소 구현**

`ExchangeFxApiClient.kt`:

```kotlin
package com.allfolio.fx.exchange

import com.allfolio.fx.FxApiClient
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/**
 * 유일한 [FxApiClient] 빈. 거래소 소스를 순서대로 시도한다.
 *
 * 전부 실패했을 때만 예외를 던진다 — [com.allfolio.fx.FxRateScheduler]가 그 예외를 잡아
 * 기존 Redis 값을 지키는 기존 계약을 그대로 유지한다.
 *
 * 소스를 FxApiClient로 직접 만들지 않은 이유는 빈이 둘이 되면 스케줄러의 주입이 깨지기 때문이다.
 */
class ExchangeFxApiClient(
    private val sources: List<FxQuoteSource>,
) : FxApiClient {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /**
         * 타당한 USDT/KRW 범위.
         *
         * 이 가드가 잡으려는 것은 "환율이 이상하다"가 아니라 **"파싱이 깨졌다"**이다.
         * 0이나 타임스탬프가 환율 자리에 들어오면 실패보다 나쁘다 — 예외 없이
         * 모든 자산 평가를 오염시키기 때문이다.
         *
         * 좁게 잡으면 실제 급변동 때 환율이 얼어붙으므로 일부러 넓게 둔다.
         * (2026-08-12 실측 1408, 52주 범위 1362~1655)
         */
        private val MIN_RATE = BigDecimal("500")
        private val MAX_RATE = BigDecimal("5000")
    }

    override fun getUsdtKrw(): BigDecimal {
        for (source in sources) {
            val rate = try {
                source.fetchUsdtKrw()
            } catch (e: FxQuoteException) {
                log.warn("[ExchangeFx] {} 실패: {}", source.sourceName, e.message)
                continue
            }

            if (rate < MIN_RATE || rate > MAX_RATE) {
                // 예외를 안 던지고 값을 돌려준 소스가 범위를 벗어났다 = 파싱이 깨졌다는 뜻이다.
                log.warn("[ExchangeFx] {} 값이 범위 밖이라 무시: {}", source.sourceName, rate)
                continue
            }

            log.info("[ExchangeFx] source={} USDTKRW={}", source.sourceName, rate)
            return rate
        }

        throw FxQuoteException("모든 소스에서 USDT/KRW를 가져오지 못했습니다 (시도=${sources.size})")
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.fx.exchange.ExchangeFxApiClientTest"`
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/exchange/ExchangeFxApiClient.kt allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/exchange/ExchangeFxApiClientTest.kt
git commit -m "feat(af-99): 거래소 시세 체인 클라이언트 + 유효범위 가드"
```

---

### Task 6: 빈 조립과 `BinanceFxApiClient` 삭제

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/exchange/ExchangeFxConfig.kt`
- Delete: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/BinanceFxApiClient.kt`

- [ ] **Step 1: 설정 클래스 작성**

`ExchangeFxConfig.kt`:

```kotlin
package com.allfolio.fx.exchange

import com.allfolio.fx.FxApiClient
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 거래소 FX 소스 조립.
 *
 * 소스 순서를 @Order로 흩뿌리지 않고 여기 한 줄에 모으는 이유는, 순서가 곧 폴백 정책이라
 * 코드를 읽는 사람이 한 곳에서 확인할 수 있어야 하기 때문이다.
 *
 * 활성화 조건은 fx.scheduler.enabled=true로 [com.allfolio.fx.FxRateScheduler]와 같다 —
 * 스케줄러 없이 클라이언트만 있으면 아무도 호출하지 않는다.
 */
@Configuration
@ConditionalOnProperty(name = ["fx.scheduler.enabled"], havingValue = "true")
class ExchangeFxConfig {

    @Bean
    fun exchangeFxApiClient(
        properties: ExchangeFxProperties,
        upbitParser: UpbitFxParser,
        bithumbParser: BithumbFxParser,
    ): FxApiClient = ExchangeFxApiClient(
        listOf(
            UpbitFxSource(properties.upbitBaseUrl, upbitParser),
            BithumbFxSource(properties.bithumbBaseUrl, bithumbParser),
        )
    )
}
```

조건을 `FxRateScheduler`와 **정확히 같게** 둔다(`matchIfMissing` 없음). 프로퍼티가 없으면 스케줄러도 안 만들어지므로 클라이언트만 살아 있을 이유가 없고, 두 조건이 어긋나면 "클라이언트는 있는데 아무도 안 부르는" 상태가 조용히 생긴다.

- [ ] **Step 2: 동작 불가능한 Binance 클라이언트 삭제**

```bash
git rm allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/BinanceFxApiClient.kt
```

남겨 두면 되살아난다. Binance는 한국 철수 후 KRW 마켓이 없어 이 클라이언트의 두 조회 경로가 모두 구조적으로 실패한다.

- [ ] **Step 3: 컴파일과 전체 테스트**

Run: `cd allfolio-backend && ./gradlew :backend-app:test`
Expected: `BUILD SUCCESSFUL` — 삭제한 클래스를 참조하는 곳이 없어야 한다. 참조가 남아 있으면 여기서 컴파일이 깨진다.

- [ ] **Step 4: Commit**

```bash
git add -A allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/
git commit -m "feat(af-99): 거래소 FX 빈 조립 + 동작 불가능한 BinanceFxApiClient 삭제"
```

---

### Task 7: TTL 경합 수정

`fixedDelay`는 **직전 실행이 끝난 시점**부터 재므로 다음 쓰기는 직전 쓰기로부터 `60초 + fetch 소요시간` 뒤다. 그런데 키는 정확히 60초에 만료된다 — **매 주기 갱신 직전에 반드시 만료된다.** 클라이언트만 고치면 영구적 4% 오차가 간헐적 4% 오차로 바뀔 뿐이다.

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/RedisFxRateService.kt`

- [ ] **Step 1: TTL 상수 수정**

`RedisFxRateService.kt`의 companion object에서:

```kotlin
    companion object {
        private const val KEY = "fx:usdtkrw"
        private val TTL = Duration.ofSeconds(60)
    }
```

를 아래로 바꾼다:

```kotlin
    companion object {
        private const val KEY = "fx:usdtkrw"

        /**
         * 폴링 주기(기본 60초)의 3배.
         *
         * TTL이 폴링 주기와 같으면 안 된다. @Scheduled(fixedDelay)는 직전 실행이 *끝난*
         * 시점부터 재므로 다음 쓰기는 항상 `주기 + fetch 시간` 뒤에 일어나는데,
         * 키는 정확히 주기에 만료된다 — 즉 매 주기 갱신 직전에 반드시 만료 창이 생기고
         * 그 동안 수집기가 멀쩡한데도 폴백 상수가 반환된다.
         *
         * 3배로 두면 연속 2회 실패까지는 마지막 정상 환율을 지킨다.
         */
        private val TTL = Duration.ofSeconds(180)
    }
```

- [ ] **Step 2: 기존 테스트가 깨지지 않는지 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.fx.*"`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/RedisFxRateService.kt
git commit -m "fix(af-99): Redis FX TTL을 폴링 주기 3배로 — 매 주기 만료되던 경합 제거"
```

---

### Task 8: 설정 정리 (yml · BinanceProperties · render.yaml)

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/resources/application.yml` (196행, 249행, 252행)
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/external/crypto/BinanceProperties.kt:21`
- Modify: `render.yaml`

- [ ] **Step 1: `binance.base-url` 환경변수화**

`application.yml:196`의:

```yaml
  base-url: https://testnet.binance.vision   # testnet — 실제 운영 시 https://api.binance.com
```

를 아래로 바꾼다:

```yaml
  base-url: ${BINANCE_API_BASE_URL:https://api.binance.com}   # 테스트넷은 BINANCE_API_BASE_URL로 덮는다
```

**`BINANCE_BASE_URL`을 쓰지 않는 이유 — 이미 다른 의미로 쓰이고 있다.**
`market-data/src/main/resources/application.yml:46`이 그 이름을 **WebSocket 스트림 주소**
(`wss://stream.binance.com:9443`)에 이미 쓴다. 같은 이름을 여기서 REST 주소로 재사용하면
한 환경변수가 `https://`와 `wss://` 두 가지를 뜻하게 되어, 두 서비스를 같은 환경에 올리는 순간
한쪽이 반드시 깨진다. 지금은 `market-data`가 배포되지 않아(render.yaml에 서비스가 하나뿐) 잠복 상태다.

기본값에 testnet 주소를 남기지 않는다. 주석으로도 남기지 않는 이유는 Task 10의 잔재 검사 grep이 그 문자열을 잡기 때문이다 — 검사가 주석 때문에 항상 실패하면 검사를 안 보게 된다.

**`binance` 프리픽스가 두 클래스에 걸쳐 있다는 점도 알아 둘 것.** `BinanceProperties`(backend-app)와
`BinanceWsProperties`(market-data)가 둘 다 `@ConfigurationProperties(prefix = "binance")`이고,
`BinanceWsAdapter:72`는 `baseUrl.contains("testnet")`으로 WS 엔드포인트를 고른다.
**backend-app은 `market-data`에 의존하지 않으므로**(`backend-app/build.gradle.kts` 35~46행)
이번 변경이 그 어댑터에 닿지 않는다. 모듈이 각자 `application.yml`을 갖고 있어 값도 섞이지 않는다.

- [ ] **Step 2: `BinanceProperties`의 기본값과 KDoc을 맞춘다**

기본값이 **두 겹**(yml + `@DefaultValue`)으로 박혀 있어 한쪽만 고치면 바인딩 경로에 따라 조용히 갈린다. 둘 다 고친다.

`BinanceProperties.kt`의 클래스 KDoc에서:

```kotlin
 * 테스트넷: https://testnet.binance.vision
 * 실운영:   https://api.binance.com
 */
```

를 아래로 바꾼다 (raw URL을 남기면 Task 10의 잔재 검사 grep이 잡는다):

```kotlin
 * base-url 기본값은 실운영이다. 테스트넷을 쓰려면 BINANCE_API_BASE_URL 환경변수로 덮는다.
 * (BINANCE_BASE_URL은 market-data가 WS 주소에 이미 쓰고 있어 재사용하지 않는다.)
 */
```

이어서:

```kotlin
    @DefaultValue("https://testnet.binance.vision")
    val baseUrl: String,
```

를 아래로 바꾼다:

```kotlin
    // 기본값이 테스트넷이면 운영이 테스트넷 가격으로 자산을 평가한다.
    @DefaultValue("https://api.binance.com")
    val baseUrl: String,
```

- [ ] **Step 3: FX 설정 갱신**

`application.yml`의 `fx:` 블록(248~257행)에서 세 줄을 바꾸고 `exchange`를 추가한다.

바꾸기 전:

```yaml
fx:
  scheduler:
    enabled: ${FX_SCHEDULER_ENABLED:false}   # true 시 BinanceFxApiClient + FxRateScheduler 활성화
    delay-ms: 60000                          # 갱신 주기 (ms)
  usdt-krw:
    fallback-rate: ${FX_USDT_KRW_FALLBACK:1350}  # Redis miss 시 사용할 fallback 환율
```

바꾼 뒤:

```yaml
fx:
  scheduler:
    enabled: ${FX_SCHEDULER_ENABLED:true}    # ExchangeFxApiClient + FxRateScheduler 활성화
    delay-ms: 60000                          # 갱신 주기 (ms). RedisFxRateService의 TTL이 이 값의 3배다
  # USDT/KRW 시세 소스. Upbit 우선, 실패 시 Bithumb.
  # Binance는 한국 철수 후 KRW 마켓이 없어 쓸 수 없다 — USDTKRW·USDKRW 모두 -1121 Invalid symbol.
  exchange:
    upbit-base-url: ${UPBIT_FX_BASE_URL:https://api.upbit.com}
    bithumb-base-url: ${BITHUMB_FX_BASE_URL:https://api.bithumb.com}
  usdt-krw:
    # 수집이 3주기 연속 실패했을 때만 쓰이는 최후 상수. 상수인 이상 반드시 어긋나므로
    # 이 값이 실제로 쓰이고 있다면 [FxScheduler] 로그를 먼저 봐야 한다.
    fallback-rate: ${FX_USDT_KRW_FALLBACK:1400}
```

기본값을 `true`로 올리는 근거: Upbit은 인증키가 필요 없으므로 이 플래그가 지키던 이유(자격증명 없이 켜면 실패)가 사라졌다. `false`로 두면 수정을 배포해도 Render에서 손으로 켜기 전까진 여전히 1350이다 — 즉 조용히 안 고쳐진다.

- [ ] **Step 4: `render.yaml`에 FX 설정 명시**

`render.yaml`의 `envVars` 목록 맨 끝(`ALLOWED_ORIGINS` 항목 뒤)에 추가한다:

```yaml
      # FX 환율 수집 (AF-99). Upbit KRW-USDT가 주 소스, Bithumb이 폴백.
      - key: FX_SCHEDULER_ENABLED
        value: "true"
      # 수집이 3주기 연속 실패했을 때만 쓰이는 최후 상수
      - key: FX_USDT_KRW_FALLBACK
        value: "1400"
```

지금 `render.yaml`에는 `FX_*`가 하나도 없어 대시보드 전용 설정이 코드에 문서화되어 있지 않다.

- [ ] **Step 5: 전체 테스트**

Run: `cd allfolio-backend && ./gradlew :backend-app:test`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add allfolio-backend/backend-app/src/main/resources/application.yml allfolio-backend/backend-app/src/main/kotlin/com/allfolio/external/crypto/BinanceProperties.kt render.yaml
git commit -m "chore(af-99): FX 기본 활성화·폴백 1400·binance.base-url 환경변수화"
```

---

### Task 9: 실제 엔드포인트 통합 확인 (수동, 네트워크 필요)

단위 테스트는 픽스처로 돌아가므로 **실제 응답 형태가 바뀌면 잡지 못한다.** 배포 전 한 번은 진짜로 찔러 본다. 이 태스크는 커밋을 만들지 않는다.

**Files:** 없음

- [ ] **Step 1: 두 소스가 지금도 같은 형태로 응답하는지 확인**

```bash
curl -s "https://api.upbit.com/v1/ticker?markets=KRW-USDT" | head -c 300
```

Expected: `trade_price` 필드가 있는 JSON 배열, 값이 1300~1500 범위

```bash
curl -s "https://api.bithumb.com/public/ticker/USDT_KRW" | head -c 300
```

Expected: `"status":"0000"`이고 `data.closing_price`가 1300~1500 범위

두 값의 차이가 5원을 넘으면 한쪽 파싱 대상이 바뀐 것이므로 Task 2·3의 픽스처를 갱신한다.

- [ ] **Step 2: 앱을 띄워 실제 수집이 도는지 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:bootRun`

로그에서 60초 안에 아래 두 줄을 확인한다:

```
[ExchangeFx] source=UPBIT USDTKRW=1408.0
[FxScheduler] updated USDTKRW=1408.0
```

`[FxScheduler] FX update failed`가 보이면 위 로그의 `[ExchangeFx] ... 실패` 줄에서 원인을 찾는다.

- [ ] **Step 3: 앱 종료**

`Ctrl+C`

---

### Task 10: 최종 검증과 PR

**Files:** 없음

- [ ] **Step 1: 전체 테스트**

Run: `cd allfolio-backend && ./gradlew :backend-app:test`
Expected: `BUILD SUCCESSFUL`, 실패 0건

- [ ] **Step 2: Binance FX 잔재가 없는지 확인**

```bash
grep -rn "BinanceFxApiClient\|USDTKRW\"\|testnet.binance" allfolio-backend --include="*.kt" --include="*.yml"
```

Expected: 출력 없음. `testnet.binance.vision`이 남아 있다면 Task 8의 주석(허용) 외에 코드 기본값으로 남은 것이다.

- [ ] **Step 3: 스펙 대비 확인**

- [ ] `FxApiClient` 인터페이스와 `FxRateScheduler`는 수정되지 않았다 (`git diff main --stat`로 확인)
- [ ] `BinanceFxApiClient.kt`가 삭제됐다
- [ ] Redis TTL이 180초다
- [ ] `fx.usdt-krw.fallback-rate` 기본값이 1400이다
- [ ] `fx.scheduler.enabled` 기본값이 true다

- [ ] **Step 4: 푸시하고 PR 생성**

```bash
git push -u origin fix/af-99-usdt-krw-exchange-source
```

```bash
gh pr create --title "fix(af-99): USDT/KRW 시세 소스 Binance → Upbit·Bithumb 교체" --body "$(cat <<'EOF'
## 문제

`BinanceFxApiClient`가 USDT/KRW를 한 번도 가져온 적이 없다. 버그가 아니라 구조적으로 불가능하다 — Binance는 한국 철수 후 KRW 마켓이 없다.

| 요청 | 응답 (2026-08-12 실측) |
|---|---|
| `api.binance.com/api/v3/ticker/price?symbol=USDTKRW` | `-1121 Invalid symbol.` |
| `...?symbol=USDKRW` | `-1121 Invalid symbol.` |
| `...?symbol=USDTUSD` | `0.99893` |

클라이언트의 두 경로가 모두 `USDKRW`를 필요로 해 항상 실패했고, 결과적으로 폴백 상수 `1350`이 쓰이면서 **거래소 자산이 4.1% 저평가**되고 있었다(실측 USDT/KRW 1408). PR #135로 거래소 자산이 `currency="USDT"`가 되면서 영향 범위가 명확해졌다.

## 해결

`FxApiClient` 포트는 그대로 두고 그 아래에 소스 체인을 만들었다. `FxRateScheduler`와 `FxApiClient` 인터페이스는 수정하지 않았다.

- `UpbitFxSource` (주) → `BithumbFxSource` (폴백), 둘 다 무료·무인증
- 응답 파싱을 HTTP에서 분리해 **실제 응답 픽스처로 단위 테스트** — 이 자리에 테스트가 없던 것이 동작 불가능한 클라이언트가 배포된 원인이다
- 유효범위 가드(500~5000): 파싱이 깨져 0이 나오면 실패보다 나쁘다. 조용히 모든 평가를 오염시킨다
- `BinanceFxApiClient` 삭제

## 함께 고친 것

**TTL 경합** — `RedisFxRateService`의 TTL(60초)과 폴링 주기(60초)가 같았다. `fixedDelay`는 직전 실행이 *끝난* 시점부터 재므로 다음 쓰기는 항상 `60초 + fetch 시간` 뒤인데 키는 정확히 60초에 만료된다. **매 주기 갱신 직전에 반드시 만료 창이 생긴다.** 클라이언트만 고쳤다면 영구적 4% 오차가 간헐적 4% 오차로 바뀌었을 뿐이다. TTL을 180초로 올렸다.

**testnet 하드코딩** — `binance.base-url`이 `https://testnet.binance.vision`으로 박혀 있었고, `BinanceProperties`의 `@DefaultValue`에도 같은 값이 두 겹으로 있었다. Binance에 KRW 마켓이 있었더라면 운영이 testnet 가격으로 자산을 평가했을 것이다. 양쪽을 `${BINANCE_API_BASE_URL:https://api.binance.com}`으로 통일했다.

환경변수 이름을 `BINANCE_BASE_URL`로 하지 않은 이유는 `market-data`가 그 이름을 이미 **WebSocket 스트림 주소**에 쓰고 있어서다(`market-data/.../application.yml:46`). 재사용하면 한 변수가 `https://`와 `wss://`를 동시에 뜻하게 된다.

**기본 활성화** — `FX_SCHEDULER_ENABLED` 기본값을 `true`로 올렸다. Upbit은 인증키가 필요 없어 이 플래그가 지키던 이유가 사라졌고, `false`로 두면 배포해도 대시보드에서 손으로 켜기 전까진 여전히 1350이다. `render.yaml`에 `FX_*`를 명시해 대시보드 전용 설정을 코드에 남겼다.

## 범위 밖

- `BinanceSyncAdapter`의 `api.binance.com` 하드코딩 3곳 — `unified-asset`이 `backend-app`에 의존하지 않아 공유 모듈에 프로퍼티를 새로 두어야 한다
- 어드민이 설정한 BTC·ETH 시세가 TTL 만료 후 상수로 되돌아가는 문제 (같은 계열, 별건)

## 검증

- 단위 테스트 17건 추가 (파서 10 + 체인 7)
- 실제 엔드포인트 응답 형태 확인
- 로컬 기동 후 `[ExchangeFx] source=UPBIT USDTKRW=...` 수집 확인

설계: `docs/superpowers/specs/2026-08-12-usdt-krw-exchange-source-design.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## 배포 후 확인

1. Render 로그에서 `[ExchangeFx] source=UPBIT USDTKRW=` 가 60초마다 찍히는지
2. `GET /api/admin/fx/usdtkrw` 가 1350이 아닌 실제 시세를 반환하는지
3. `GET /api/admin/fx/usdkrw` 와 값이 다른지 — 두 값이 갈리는 것이 AF-99가 의도한 동작이다. 같으면 하나은행 수집이 아직 안 돈 것이다

## 단기 완화책 (이 PR과 무관, 지금 바로 가능)

Render 대시보드에서 `FX_USDT_KRW_FALLBACK=1400` 설정 → 재시작 한 번으로 오차 4.1% → 0.6%. 배포 불필요.

**해결이 아니다.** 상수는 다시 어긋나고 실제 실패가 얼마나 시끄러운지를 가릴 뿐이다. PR이 리뷰를 도는 동안 비용 없이 손해를 줄이는 목적으로만 쓴다.
