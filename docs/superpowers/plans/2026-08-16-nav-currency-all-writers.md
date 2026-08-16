# 통화별 행을 모든 NAV 기록 경로에서 남긴다 — 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `performance_daily`에 쓰는 모든 unified-asset 경로가 `nav_currency_daily`도 같이 쓰게 해서, AF-106 수익 기여도 분해가 거래 없이도 동작하게 한다.

**Architecture:** 쓰기가 `PerformanceSnapshotService.record()` 하나로 모이므로 거기서 총액 대신 통화별 내역을 받는다 — 타입이 호출자 넷에게 통화 내역을 강제한다. 환율은 `FxConverter`에 `rateOf`를 더해 어댑터가 `CurrencyConverter.sourceOf`로 위임하고(거래 경로와 같은 식), 통화 행 쓰기는 unified-asset 포트를 통해 기존 `NavCurrencyDailyStore`가 받는다.

**Tech Stack:** Kotlin / Spring Boot / JUnit 5 + Mockito + AssertJ

**설계 문서:** `docs/superpowers/specs/2026-08-16-nav-currency-all-writers-design.md`

---

## 사전 필독 — 이걸 모르면 조용히 틀린다

**1. `rateOf`는 `normalized()`를 써야 한다. `canonical()`을 쓰면 안 된다.**
`UnifiedAssetFxConverterAdapter`에 두 정규화가 있다:
- `normalized(c)` = `trim().uppercase()` — **`toKrw`가 쓰는 것**
- `canonical(c)` = `normalized` + `USDT → USD` 접기 — **과거 환율 경로 전용**

그 파일에 *"현재 환율 경로(`toKrw`)는 AF-99부터 둘을 구분하므로 이 함수를 쓰지 않는다. **통일하지 말 것.**"* 이라고 적혀 있다. `rateOf`가 `canonical`을 쓰면 USDT 보유가 USD 환율로 기록되는데, `toKrw`는 USDT 거래소 시세를 쓰므로 **합계 불변식이 깨진다.** 증상은 "USDT 있는 사용자만 NAV가 안 맞음"이라 찾기 어렵다.

**2. 미지원 통화는 예외가 아니라 `fx_rate = 1`이다.**
`CurrencyConverter`가 환산하는 통화는 `KRW·USD·USDT·BTC·ETH` 다섯뿐이고, 나머지는 경고만 남기고 **원금을 그대로 돌려준다**. `sourceOf`는 KRW와 미지원 통화 **둘 다 null**을 준다. `?: BigDecimal.ONE`이 `toKrw`의 실제 동작과 정확히 같다. 예외를 던지면 NAV 기록이 깨진다.

**3. `sourceOf`가 BTC·ETH에서 예외를 던질 수 있다.** 코인 시세가 캐시에 없으면 던진다(그 KDoc이 "0.5 BTC가 0.5원이 되는 버그"를 이유로 일부러 그렇게 만들었다). `rateOf`는 그 예외를 **삼키지 않는다** — `toKrw`도 같은 상황에서 던지므로 동작이 일치한다.

**4. 트랜잭션 안이라 실패를 삼킬 수 없다.** `SyncAccountUseCase.record()` 호출은 `@Transactional` 안이다. Postgres는 트랜잭션 안 SQL 오류 뒤 그 트랜잭션을 abort 상태로 만들어서, 예외를 잡아도 커밋이 실패한다. **설계 결정은 "둘을 한 트랜잭션으로 묶는다"** — 통화 행이 실패하면 NAV 행도 안 남는다. `try/catch`를 넣지 말 것.

**5. `record()`의 로그 문자열을 지우지 말 것.** `log.info("Performance snapshot recorded: ...")`가 운영에서 이 경로를 확인하는 유일한 신호다.

---

## 파일 구조

| 파일 | 책임 | 태스크 |
|---|---|---|
| `unified-asset/.../application/port/FxConverter.kt` | `rateOf` 추가 | 1 |
| `backend-app/.../fx/UnifiedAssetFxConverterAdapter.kt` | `rateOf` 구현 | 1 |
| `unified-asset/.../application/port/NavCurrencyStore.kt` | 포트 + `CurrencyValue` (신규) | 2 |
| `backend-app/.../snapshot/NavCurrencyDailyStore.kt` | 포트 구현 선언, `CurrencyValue` 제거 | 2 |
| `unified-asset/.../application/usecase/NavCalculator.kt` | `navByCurrency()` 확장 | 3 |
| `unified-asset/.../application/usecase/PerformanceSnapshotService.kt` | 두 테이블에 쓴다 | 4 |
| `unified-asset/.../application/usecase/DailyNavScheduler.kt` | 통화별 내역을 넘긴다 | 4 |
| `unified-asset/.../application/usecase/SyncAccountUseCase.kt` | `navByCurrency()` | 4 |
| `unified-asset/.../api/AccountController.kt` | `navByCurrency()` (2곳) | 4 |

---

## Task 1: `FxConverter.rateOf`

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/port/FxConverter.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/UnifiedAssetFxConverterAdapter.kt`
- Create: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/FxConverterRateOfTest.kt`

- [ ] **Step 1: 포트에 메서드를 더한다**

`FxConverter.kt`의 `toKrw` 아래에 추가:

```kotlin
    /**
     * 이 통화를 KRW로 바꿀 때 [toKrw]가 쓰는 환율. KRW와 미지원 통화는 1.
     *
     * `toKrw(1, c)`로 역산할 수 없어서 필요하다 — 구현이 환산 결과를 원 단위로
     * 반올림하므로 환율 1400.5가 1401이 된다.
     *
     * **[toKrw]와 같은 환율을 돌려줘야 한다.** AF-106의 합계 불변식
     * `Σ value_native × fx_rate ≈ nav`가 이 일치에 기대고 있다.
     */
    fun rateOf(currency: String): BigDecimal
```

> `FxConverter`는 인터페이스이고 `toKrwOn`에만 default 구현이 있다. `rateOf`는 **default를 두지 않는다** — 두면 구현체가 조용히 1을 돌려주고 불변식이 깨진다.

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/FxConverterRateOfTest.kt`

**먼저 `CurrencyConverterTest.kt`를 열어 `FxRateService` 페이크를 어떻게 만드는지 보고 그대로 베낄 것** — 인터페이스 메서드 목록(`getUsdToKrw`·`getUsdtToKrw`·`getCryptoToKrw`·`setter`들·`usdQuoteRef`)이 그 파일에 이미 구현돼 있고, 일부는 default가 있어 오버라이드가 파일마다 다르다. 아래 코드의 `fakeFxRateService()` 자리를 그 페이크로 채운다.

```kotlin
package com.allfolio.fx

import com.allfolio.unifiedasset.infrastructure.jpa.HistoricalFxRateJpaRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 어댑터의 rateOf가 toKrw가 실제로 쓴 환율과 같은지 못 박는다.
 *
 * AF-105가 CurrencyConverter 층에서 같은 성격의 테스트(sourceOf의 환율 == toKrw의 환율)를
 * 갖고 있다. 여기서는 **포트 어댑터 층**에서 확인한다 — AF-106의 합계 불변식
 * `Σ value_native × fx_rate ≈ nav`가 이 일치에 통째로 기대고 있다.
 */
class FxConverterRateOfTest {

    // USD와 USDT에 **다른** 값을 준다 — 둘이 같으면 canonical 접기 버그를 못 잡는다
    private val usdRate = BigDecimal("1400.5")
    private val usdtRate = BigDecimal("1385.0")

    private fun adapter() = UnifiedAssetFxConverterAdapter(
        CurrencyConverter(fakeFxRateService()),
        mock(HistoricalFxRateJpaRepository::class.java),   // rateOf는 과거 환율을 안 본다
    )

    @Test
    fun `rateOf가 toKrw가 쓴 환율과 같다`() {
        val fx = adapter()
        val amount = BigDecimal("137")
        // toKrw는 환산 후 원 단위로 반올림한다 — 그래서 rateOf가 따로 필요하다
        assertEquals(
            (amount * fx.rateOf("USD")).setScale(0, RoundingMode.HALF_UP),
            fx.toKrw(amount, "USD"),
        )
    }

    @Test
    fun `USDT가 USD로 접히지 않는다`() {
        // canonical()은 USDT를 USD로 접는 과거 환율 경로 전용이다. rateOf가 그걸 쓰면
        // USDT 보유가 USD 환율로 기록되고, 합계 불변식이 USDT 사용자에게만 깨진다.
        val fx = adapter()
        assertNotEquals(fx.rateOf("USD"), fx.rateOf("USDT"))
        assertEquals(0, usdtRate.compareTo(fx.rateOf("USDT")))
    }

    @Test
    fun `KRW는 1이다`() {
        // sourceOf가 null을 주는 경로 — 환산이 없으므로 환율도 1
        assertEquals(0, BigDecimal.ONE.compareTo(adapter().rateOf("KRW")))
    }

    @Test
    fun `미지원 통화도 예외 없이 1이다`() {
        // CurrencyConverter가 환산하는 건 KRW·USD·USDT·BTC·ETH 다섯뿐이고
        // 나머지는 원금을 그대로 돌려준다. 예외를 던지면 NAV 기록이 통째로 깨진다.
        val fx = adapter()
        assertEquals(0, BigDecimal.ONE.compareTo(fx.rateOf("JPY")))
        assertEquals(BigDecimal("500"), fx.toKrw(BigDecimal("500"), "JPY"))
    }

    @Test
    fun `공백과 소문자를 정규화한다`() {
        // toKrw가 trim().uppercase()를 쓰므로 rateOf도 같아야 조회가 안 어긋난다
        val fx = adapter()
        assertEquals(fx.rateOf("USD"), fx.rateOf(" usd "))
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

```bash
cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :backend-app:test --tests '*FxConverterRateOfTest*'
```

Expected: 컴파일 실패 — `rateOf`가 없다.

- [ ] **Step 4: 어댑터에 구현한다**

`UnifiedAssetFxConverterAdapter.kt`의 `toKrw` 바로 아래:

```kotlin
    /**
     * **`normalized()`를 쓴다 — `canonical()`이 아니다.**
     * `canonical`은 USDT를 USD로 접는 과거 환율 경로 전용이고, 이 클래스의 KDoc이
     * "현재 환율 경로는 AF-99부터 둘을 구분하므로 통일하지 말 것"이라고 못박아 뒀다.
     * 여기서 접으면 USDT 보유가 USD 환율로 기록되는데 [toKrw]는 거래소 시세를 쓰므로
     * AF-106의 합계 불변식이 USDT 보유 사용자에게만 깨진다.
     *
     * `?: ONE`이 [toKrw]의 실제 동작과 같다 — sourceOf는 KRW와 미지원 통화에 둘 다
     * null을 주고, CurrencyConverter는 그 경우 원금을 그대로 돌려준다.
     */
    override fun rateOf(currency: String): BigDecimal =
        currencyConverter.sourceOf(normalized(currency))?.rate ?: BigDecimal.ONE
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :backend-app:test --tests '*FxConverterRateOfTest*' :unified-asset:compileKotlin
```

Expected: PASS. `FxConverter`를 구현하는 테스트용 페이크가 있으면 컴파일이 깨진다 — `rateOf`를 더해 고친다. 어떤 파일을 고쳤는지 보고할 것.

- [ ] **Step 6: 변이 테스트**

| # | 변이 | 실패해야 하는 테스트 |
|---|---|---|
| 1 | `normalized(currency)` → `canonical(currency)` | `USDT가 USD로 접히지 않는다` |
| 2 | `?: BigDecimal.ONE` → `?: error("unsupported")` | KRW·미지원 통화 테스트 |

하나씩 넣고 확인 후 원복. `git diff`에 잔여물이 없는지 확인할 것.

- [ ] **Step 7: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/port/FxConverter.kt \
        allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/UnifiedAssetFxConverterAdapter.kt \
        allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/
git commit -m "feat(af-106): FxConverter가 환율을 밝힌다 — toKrw(1,c) 역산은 반올림에 먹힌다

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 2: 통화 행 쓰기 포트

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/port/NavCurrencyStore.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/snapshot/NavCurrencyDailyStore.kt`

모듈 의존은 단방향(`backend-app → unified-asset`)이라 `PerformanceSnapshotService`가 `NavCurrencyDailyStore`를 직접 못 부른다.

- [ ] **Step 1: 포트와 `CurrencyValue`를 unified-asset으로**

`allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/port/NavCurrencyStore.kt`:

```kotlin
package com.allfolio.unifiedasset.application.port

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * 통화 하나의 그날 평가액.
 *
 * @param valueNative 환산 전 원통화 금액
 * @param fxRate      그날 적용한 1단위당 KRW. KRW와 미지원 통화는 1
 */
data class CurrencyValue(val currency: String, val valueNative: BigDecimal, val fxRate: BigDecimal)

/**
 * 통화별 일간 평가액 저장 포트 (AF-106).
 *
 * 구현은 backend-app의 `NavCurrencyDailyStore`다 — 거래 경로(`SnapshotTriggerService`)와
 * 같은 스토어를 쓴다. **SQL 소유자를 하나로 유지하는 것이 이 포트의 존재 이유다**:
 * 같은 테이블에 쓰는 코드가 두 벌이면 스키마가 바뀌는 날 한쪽만 고쳐진다.
 */
interface NavCurrencyStore {
    /** 해당 (portfolio, date)의 기존 행을 지우고 [values]로 대체한다. 빈 목록이면 지우기만 한다. */
    fun replace(portfolioId: UUID, date: LocalDate, values: List<CurrencyValue>)
}
```

- [ ] **Step 2: `NavCurrencyDailyStore`가 포트를 구현하게 한다**

`NavCurrencyDailyStore.kt`에서 **`CurrencyValue` data class 선언을 지우고**(unified-asset으로 옮겼다) import로 바꾼다. `NativePrice`는 **그대로 둔다** — 거래 경로 전용이고 포트와 무관하다.

```kotlin
import com.allfolio.unifiedasset.application.port.CurrencyValue
import com.allfolio.unifiedasset.application.port.NavCurrencyStore
```

클래스 선언:

```kotlin
@Component
class NavCurrencyDailyStore(private val jdbc: JdbcTemplate) : NavCurrencyStore {
```

`replace`에 `override`를 붙인다. 본문은 그대로 — 이미 포트 시그니처와 같다.

`aggregate`는 companion object에 그대로 둔다(거래 경로가 쓴다).

- [ ] **Step 3: 컴파일과 기존 테스트**

```bash
cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :unified-asset:compileKotlin :backend-app:test --tests '*NavCurrency*' --tests '*SnapshotTrigger*'
```

Expected: PASS. `CurrencyValue`를 import하던 테스트가 있으면 경로를 고친다 — **단언은 건드리지 말 것.**

- [ ] **Step 4: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/port/NavCurrencyStore.kt \
        allfolio-backend/backend-app/src/main/kotlin/com/allfolio/snapshot/NavCurrencyDailyStore.kt \
        allfolio-backend/backend-app/src/test/
git commit -m "refactor(af-106): 통화 행 쓰기를 포트로 — unified-asset이 부를 수 있게

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 3: `navByCurrency()` 확장

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/NavCalculator.kt`
- Create: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/NavByCurrencyTest.kt`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`NavByCurrencyTest.kt`. `Asset`을 만드는 방법은 같은 디렉터리의 기존 테스트(예: `SyncAccountUseCaseNavTest`)에서 확인해 그대로 따를 것.

담을 것:

1. **같은 통화 자산이 하나로 묶인다** — USD 자산 둘이면 `{"USD": 합}`
2. **통화가 섞이면 키가 나뉜다** — KRW·USD 각각
3. **대소문자·공백이 정규화된다** — `"usd"`와 `"USD"`가 같은 키로. `toKrw`가 `normalized()`를 쓰므로 여기서도 대문자로 맞춰야 `rateOf` 조회가 어긋나지 않는다
4. **빈 목록은 빈 맵** — 자산 없는 사용자
5. **`navInKrw`와 정합** — `Σ toKrw(v, c)` over `navByCurrency()`가 `navInKrw`와 **가까운지**(자산별 vs 통화별 반올림 차이가 있으므로 정확히 같지 않다). 자산 수만큼의 원 단위 오차 이내인지 확인

- [ ] **Step 2: 테스트 실패 확인**

```bash
cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :unified-asset:test --tests '*NavByCurrencyTest*'
```

Expected: 컴파일 실패 — `navByCurrency`가 없다.

- [ ] **Step 3: 확장을 더한다**

`NavCalculator.kt`의 `navInKrw` 아래에:

```kotlin
/**
 * 여러 통화가 섞인 자산 목록을 **통화별 원통화 합계**로 묶는다 (AF-106).
 *
 * **위 [navInKrw]의 경고("통화를 무시하고 raw 합산하면 KRW와 USD를 그대로 더해 의미 없는
 * 숫자가 나온다")에 걸리지 않는다** — 여기서는 통화로 *묶은 뒤* 같은 통화끼리만 더하기
 * 때문이다. 그 경고를 보고 이 함수를 "고치려" 들지 말 것.
 *
 * 키는 대문자로 정규화한다. `FxConverter.toKrw`·`rateOf`가 `trim().uppercase()`로 통화를
 * 정규화하므로, 여기서 맞춰 두지 않으면 `"usd"`와 `"USD"`가 별개 행으로 저장된다.
 *
 * [navInKrw]와 달리 환산하지 않는다 — 환산은 `PerformanceSnapshotService.record()`가
 * 통화별로 한 번씩 하고, 그 값이 곧 `nav_currency_daily.value_native`가 된다.
 */
fun Collection<Asset>.navByCurrency(): Map<String, BigDecimal> =
    groupingBy { it.currency.trim().uppercase() }
        .fold(BigDecimal.ZERO) { acc, asset -> acc + asset.currentValue }
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :unified-asset:test --tests '*NavByCurrencyTest*'
```

Expected: PASS.

- [ ] **Step 5: 변이 테스트**

`groupingBy { it.currency.trim().uppercase() }`에서 `.uppercase()`를 지운다 → `대소문자·공백이 정규화된다`가 실패해야 한다. 확인 후 원복.

- [ ] **Step 6: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/NavCalculator.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/NavByCurrencyTest.kt
git commit -m "feat(af-106): 자산 목록을 통화별 원통화 합계로 묶는 확장

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 4: `record()`가 두 테이블에 쓴다

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/PerformanceSnapshotService.kt`
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/DailyNavScheduler.kt`
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/SyncAccountUseCase.kt` (~line 100)
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/api/AccountController.kt` (~lines 267, 283)
- Modify: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/PerformanceSnapshotDateTest.kt`

- [ ] **Step 1: `record()`를 바꾼다**

`PerformanceSnapshotService`에 의존성 둘을 더한다:

```kotlin
@Service
class PerformanceSnapshotService(
    private val jdbc: JdbcTemplate,
    private val fx: FxConverter,
    private val navCurrencyStore: NavCurrencyStore,
) {
```

import: `com.allfolio.unifiedasset.application.port.FxConverter`, `com.allfolio.unifiedasset.application.port.NavCurrencyStore`, `com.allfolio.unifiedasset.application.port.CurrencyValue`

시그니처와 앞부분:

```kotlin
    /**
     * NAV 스냅샷을 performance_daily에, 통화별 내역을 nav_currency_daily에 기록한다.
     *
     * **총액이 아니라 통화별 내역을 받는다 (AF-106).** 타입이 호출자에게 통화 내역을
     * 강제하므로 새 호출자가 생겨도 빠뜨릴 수 없다. 총액은 여기서 계산하므로 두 테이블이
     * 같은 숫자에서 나온다.
     *
     * **둘은 한 트랜잭션이다 — try/catch로 감싸지 말 것.** 호출자 일부가 @Transactional
     * 안이고, Postgres는 트랜잭션 안 SQL 오류 뒤 그 트랜잭션을 abort 상태로 만들어서
     * 예외를 잡아도 커밋이 실패한다. 그리고 NAV만 있고 통화 내역이 없는 상태가 정확히
     * AF-106이 분해를 포기하는 상태라, 둘 다 없는 편이 깨끗하다.
     *
     * **[date]에 기본값을 두지 않는다.** 기본 인자를 두면 호출자가 빠뜨렸을 때 조용히
     * UTC 날짜로 돌아가는데, 컨테이너가 UTC라 자정 KST 실행이 전날에 앉는다.
     *
     * tenant_id = portfolio_id = userId (unified-asset은 사용자=포트폴리오 단위)
     */
    fun record(userId: UUID, navByCurrency: Map<String, BigDecimal>, date: LocalDate) {
        // 통화별로 한 번씩 환산해 합산한다 — 자산별 환산 합보다 반올림이 적다.
        val values = navByCurrency.map { (currency, valueNative) ->
            CurrencyValue(currency, valueNative, fx.rateOf(currency))
        }
        val nav = navByCurrency.entries.fold(BigDecimal.ZERO) { acc, (currency, value) ->
            acc + fx.toKrw(value, currency)
        }
```

> `nav`를 `Σ value × rateOf`로 계산하지 **않는다.** `toKrw`가 통화마다 원 단위로 반올림하므로 두 식이 통화당 0.5원 다른데, `toKrw` 쪽이 기존 저장값과 이어진다. 불변식이 "1원 이내"인 이유가 이것이고, 정확하게 만들려고 식을 바꾸면 저장된 NAV가 움직인다.

기존 본문(prevNav·firstNav 조회, dailyReturn·cumulativeReturn, UPSERT, 로그)은 **그대로 둔다.** UPSERT 뒤에 한 줄을 더한다:

```kotlin
        navCurrencyStore.replace(userId, date, values)
```

> `performance_daily` 쓰기 **뒤**에 둔다. 순서가 바뀌면 NAV 행이 없는데 통화 행만 있는 순간이 생기고, 같은 트랜잭션이라 커밋 전엔 안 보이지만 로그를 읽는 사람이 헷갈린다.

- [ ] **Step 2: 호출자 넷을 고친다**

`DailyNavScheduler.recordDailySnapshots` — 지금 통화별을 접어서 총액을 만든다. **접지 말고 그대로 넘긴다:**

```kotlin
        val byUser: Map<UUID, Map<String, BigDecimal>> = perCurrency
            .groupBy { it.first }
            .mapValues { (_, rows) ->
                // 같은 (user, currency)는 SQL이 이미 GROUP BY로 합쳤으므로 키 충돌이 없다
                rows.associate { (_, currency, value) -> currency.trim().uppercase() to value }
            }

        if (byUser.isEmpty()) {
            log.debug("[DailyNavScheduler] no users with assets, skipping")
            return 0
        }

        log.info("[DailyNavScheduler] recording snapshots for {} users", byUser.size)
        byUser.forEach { (userId, navByCurrency) ->
            runCatching { snapshotService.record(userId, navByCurrency, ymd) }
                .onFailure { e -> log.error("[DailyNavScheduler] failed userId={}", userId, e) }
        }
        log.info("[DailyNavScheduler] done")
        return byUser.size
```

`fx` 생성자 파라미터가 이제 안 쓰이면 **제거한다**(환산이 `record()`로 옮겨갔다). 컴파일 경고를 보고 판단할 것.

`SyncAccountUseCase.kt` (~line 100):

```kotlin
            snapshotService.record(account.userId, allAssets.navByCurrency(), LocalDate.now(ZoneId.of("Asia/Seoul")))
```

`AccountController.kt` (~267, ~283) — 두 곳 모두 `assetRepository.findByUserId(userId)` 결과에 대해:

```kotlin
        val assets = assetRepository.findByUserId(userId)
        snapshotService.record(userId, assets.navByCurrency(), LocalDate.now(java.time.ZoneId.of("Asia/Seoul")))
```

기존에 `val nav = ...navInKrw(fx)` 줄이 있으면 지운다. `navInKrw`가 그 파일에서 더 안 쓰이면 import도 정리한다. **`navInKrw` 함수 자체는 지우지 말 것** — 다른 소비자가 있다.

- [ ] **Step 3: 기존 테스트를 고친다**

`record`를 참조하는 테스트 파일이 여덟이다:

```
unified-asset: PerformanceSnapshotDateTest, SyncAccountUseCaseNavTest, SyncAccountUseCaseLoggingTest,
               SyncAccountUseCaseInitialFlowTest, SyncAccountUseCaseSensitiveDataTest,
               SyncAccountUseCaseBackdatedInflowTest
backend-app:   AccountControllerAutoSyncTest, AccountControllerSecurityTest
```

- `PerformanceSnapshotDateTest`는 가짜 `JdbcTemplate` 서브클래스를 쓴다. 이제 `FxConverter`·`NavCurrencyStore` 페이크도 필요하다. **기존 두 단언(INSERT 날짜, `date < ?` 바인딩)을 유지할 것**
- 나머지는 대부분 `record`를 목으로만 쓴다 — 매처/인자 타입만 고치면 된다

**단언을 약화하지 말 것.** 어떤 파일을 왜 고쳤는지 보고한다.

- [ ] **Step 4: 새 테스트 — 두 테이블 계약**

`PerformanceSnapshotDateTest`에 더하거나 옆에 새 파일을 만든다. 담을 것:

1. **두 테이블에 같은 날짜로 쓴다** — `navCurrencyStore.replace`에 넘어간 date가 INSERT의 date와 같다
2. **합계 불변식** — `Σ valueNative × fxRate`가 기록된 `nav`와 **1원 이내**. 통화 2종(KRW + USD)으로 확인
3. **미지원 통화가 `fx_rate = 1`로 실린다** — 페이크 `FxConverter`가 `rateOf("JPY") = 1`을 주도록 하고, 예외 없이 행이 만들어지는지
4. **빈 맵이면 nav=0이고 통화 행은 빈 목록** — `replace(userId, date, emptyList())`가 불린다. 자산 없는 사용자 경로다

날짜는 **오늘일 수 없는 고정 날짜**를 쓴다(개발 머신이 KST라 `LocalDate.now()`가 로컬에선 정답을 주고, 그 때문에 변이를 못 잡은 전례가 이 저장소에 있다).

- [ ] **Step 5: 전체 테스트**

```bash
cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew test
```

Expected: 전부 PASS.

- [ ] **Step 6: 변이 테스트**

| # | 변이 | 실패해야 하는 테스트 |
|---|---|---|
| 1 | `navCurrencyStore.replace(...)` 줄을 지운다 | `두 테이블에 같은 날짜로 쓴다` |
| 2 | `CurrencyValue(currency, valueNative, fx.rateOf(currency))`의 `rateOf`를 `BigDecimal.ONE`로 고정 | `합계 불변식` |
| 3 | `record()`를 `try { ... } catch (e: Exception) { log.warn(...) }`로 감싼다 | 변이 1과 조합해야 잡힌다 — **이건 테스트로 못 잡는다.** 잡히지 않는다는 것을 확인하고 보고할 것 |

변이 3의 결과가 중요하다. **못 잡으면 그 계약(트랜잭션을 삼키지 않는다)은 주석과 리뷰로만 지켜진다는 뜻**이고, 그건 알고 있어야 할 사실이다. 억지 테스트를 만들지 말 것.

- [ ] **Step 7: 커밋**

```bash
git add allfolio-backend/unified-asset/
git commit -m "feat(af-106): NAV 기록이 통화별 내역도 남긴다 — 타입이 호출자에게 강제한다

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 5: PR + 배포 후 검증

- [ ] **Step 1: 전체 빌드**

```bash
cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew build -x test && ./gradlew test
```

- [ ] **Step 2: PR**

```bash
git push -u origin feat/nav-currency-all-writers
```

PR 본문에 적는다:
- **마이그레이션 없음** — `nav_currency_daily`는 AF-106에서 이미 적용됐다
- 동기화 경로의 NAV가 자산별 환산에서 통화별 환산으로 바뀌어 **수 원 움직인다**(의도된 개선)
- **통화 행 쓰기 실패가 계좌 동기화를 실패시킬 수 있다** — 알고 받는 대가

- [ ] **Step 3: 배포 후 — 동기화 한 번**

계좌 동기화를 유발하거나 자정 마감을 기다린다. 그 뒤:

```sql
SELECT date, currency, value_native, fx_rate, ROUND(value_native * fx_rate) AS krw
FROM nav_currency_daily ORDER BY date DESC, currency LIMIT 20;
```

**행이 생겨야 한다.** 자산 있는 사용자가 통화 2종이므로 날짜당 2행이 기대값이다.

- [ ] **Step 4: 합계 불변식 실측**

```sql
SELECT n.portfolio_id, n.date,
       SUM(n.value_native * n.fx_rate) AS sum_krw, p.nav,
       SUM(n.value_native * n.fx_rate) - p.nav AS drift
FROM nav_currency_daily n
JOIN performance_daily p ON p.portfolio_id = n.portfolio_id AND p.date = n.date
GROUP BY n.portfolio_id, n.date, p.nav
ORDER BY n.date DESC LIMIT 10;
```

`drift`가 **1원 이내**여야 한다(통화 2종 × 0.5원). 이보다 크면 `rateOf`가 `toKrw`와 다른 환율을 쓰고 있다는 뜻이다.

- [ ] **Step 5: 미환산 통화 진단**

```sql
SELECT currency, COUNT(*) FROM nav_currency_daily
WHERE currency <> 'KRW' AND fx_rate = 1 GROUP BY currency;
```

행이 나오면 그 통화는 `CurrencyConverter`가 환산하지 않는 통화다 — 버그가 아니라 기존 한계이고, 그 통화의 환율 기여는 0으로 잡힌다.

- [ ] **Step 6: 화면**

관측이 **2일 이상** 쌓인 뒤 `/unified/reports/returns`에서:
- 분해 블록이 뜨는가
- `(1+자산)(1+환율)−1`이 화면의 TWR과 일치하는가
- 기간 선택기를 바꾸면 분해도 바뀌는가

---

## 완료 기준

- [ ] `nav_currency_daily`에 동기화·마감 경로로 행이 쌓인다
- [ ] 합계 불변식 실측 drift < 1원
- [ ] 변이 테스트: `replace` 제거 / `rateOf` 고정 / `canonical` 사용 / `uppercase` 제거 — 전부 확인
- [ ] **변이 3(try/catch 감싸기)이 테스트로 안 잡힌다는 것을 확인하고 기록했다**
- [ ] 관측 2일 이상 쌓인 뒤 분해 블록이 화면에 뜬다
