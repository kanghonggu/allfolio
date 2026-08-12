# AF-105 순자산 하단 환율 출처 표기 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 대시보드 순자산 아래에, 그 숫자를 만드는 데 실제로 쓰인 환율과 출처(하나은행 고시일·회차 포함)를 밝힌다.

**Architecture:** 통화별 환율 소스 분기표를 `CurrencyConverter.sourceOf` **한 곳에만** 두고 `toKrw`가 그 위에 올라탄다 — 화면이 밝히는 환율과 환산에 쓰인 환율이 갈라지는 게 구조적으로 불가능해진다. `GetDashboardUseCase`가 보유 통화만 골라 `DashboardResponse.fxSources`로 내려주므로, "원화만 있으면 숨긴다"는 조건부 노출도 프론트 분기가 아니라 백엔드 결과가 된다.

**Tech Stack:** Kotlin 1.9.25 / Spring Boot 3.2.5 / JUnit5 + 순수 Mockito / Next.js 14 App Router + TypeScript + Tailwind

**Spec:** `docs/superpowers/specs/2026-08-12-fx-source-label-design.md`

---

## 사전 필독 (모든 태스크 공통)

- **Gradle 테스트는 반드시 `--rerun-tasks`.** 없으면 전부 UP-TO-DATE로 보고되고 아무것도 실행되지 않는다.
- Gradle 콘솔은 개별 테스트를 나열하지 않는다. 건수 확인은 JUnit XML을 읽을 것:
  `allfolio-backend/backend-app/build/test-results/test/TEST-<FQCN>.xml`
- **`mockito-kotlin`은 이 저장소에 없다.** 테스트 의존성은 `spring-boot-starter-test`뿐이고
  기존 테스트는 전부 순수 `org.mockito.Mockito`나 익명 객체 스텁을 쓴다. 의존성을 추가하지 말 것.
- 브랜치는 `feat/af-105-fx-source-label` (생성돼 있고 설계 문서 커밋이 올라가 있다).
- 백엔드 모듈은 `backend-app`과 `unified-asset` 둘 다 건드린다(1번은 `backend-app`뿐).
- 프론트는 `frontend/allfolio_app` (Next.js App Router).
- 마이그레이션 없음. 새 테이블 없음.

## 파일 구조

| 파일 | 책임 |
|---|---|
| `backend-app/.../fx/FxRateService.kt` (수정) | `UsdQuoteRef` + `usdQuoteRef()` 기본 구현 추가 |
| `backend-app/.../fx/HanaFxRateService.kt` (수정) | 캐시가 rate 대신 `UsdQuoteRef`를 담게. `getUsdToKrw()`를 그 위에서 구현 |
| `backend-app/.../fx/FxSource.kt` (신규) | 출처 DTO — 도메인 값이라 컨버터와 분리 |
| `backend-app/.../fx/CurrencyConverter.kt` (수정) | `sourceOf` 유일 분기표 + `toKrw`가 그 위에 올라탐 |
| `backend-app/.../dashboard/DashboardResponse.kt` (수정) | `fxSources: List<FxSourceDto>` |
| `backend-app/.../dashboard/GetDashboardUseCase.kt` (수정) | 보유 통화 수집 → `sourceOf` → 사전순 정렬 |
| `frontend/allfolio_app/types/dashboard.ts` (수정) | `FxSource` 타입 + `fxSources` 필드 |
| `frontend/allfolio_app/components/dashboard/NetWorthBar.tsx` (수정) | 출처 줄 렌더 |
| `frontend/allfolio_app/app/unified/page.tsx` (수정) | `fxSources` 전달 |

---

### Task 1: `FxRateService`에 고시 메타 조회 추가

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/FxRateService.kt`

- [ ] **Step 1: `UsdQuoteRef`와 `usdQuoteRef()`를 추가한다**

파일 상단 import에 추가:

```kotlin
import java.time.LocalDate
```

`interface FxRateService { ... }` 블록의 **닫는 중괄호 바로 위**에 추가:

```kotlin

    /**
     * 공식 고시의 출처 메타 (AF-105). 고시 기반 구현이 아니면 null.
     *
     * 화면이 "무슨 환율로 계산했나"에 답하려면 값만으론 부족하다 — 기준일과 회차가 있어야
     * 사용자가 하나은행 화면과 직접 대조할 수 있고, 그 대조 가능성이 이 기능의 전부다.
     *
     * **기본 구현을 두는 이유**: 이 인터페이스는 테스트에서 열 곳 넘게 익명 객체로 구현돼 있다.
     * 추상 메서드로 추가하면 그 전부가 컴파일되지 않는다.
     * (AF-100에서 `FxConverter.toKrwOn`에 쓴 것과 같은 방식.)
     */
    fun usdQuoteRef(): UsdQuoteRef? = null
```

파일 맨 끝(인터페이스 밖)에 추가:

```kotlin

/**
 * 하나은행 고시 한 건의 식별 정보 (AF-105).
 *
 * `roundNo`가 핵심이다. 기준일만으로는 하루에 수십 번 바뀌는 고시 중 어느 것인지 특정할 수 없어
 * 대조가 불가능하다.
 */
data class UsdQuoteRef(
    val rate: BigDecimal,
    val baseDate: LocalDate,
    val roundNo: Int,
)
```

- [ ] **Step 2: 전 모듈이 여전히 컴파일되는지 확인한다**

기본 구현이 실제로 기존 익명 구현들을 살려두는지가 이 태스크의 유일한 위험이다.

Run:
```bash
cd allfolio-backend && ./gradlew :backend-app:compileTestKotlin --rerun-tasks --no-daemon -q && echo OK
```
Expected: `OK` (기존 페이크 10곳 이상이 손대지 않고 컴파일돼야 한다)

- [ ] **Step 3: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/FxRateService.kt
git commit -m "feat(af-105): FxRateService에 고시 메타 조회(usdQuoteRef) 기본 구현 추가"
```

---

### Task 2: `HanaFxRateService`가 고시 메타를 캐시하고 노출

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/HanaFxRateService.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/HanaFxRateServiceTest.kt`

이 클래스는 이미 `findTopByCurrencyOrderByBaseDateDescRoundNoDesc`로 **엔티티 전체를 읽고 `baseRate`만
남기고 버린다.** 캐시가 담는 타입만 바꾸면 DB 조회가 늘지 않는다.

- [ ] **Step 1: 먼저 실패 테스트를 추가한다**

`HanaFxRateServiceTest.kt`를 읽고 그 파일의 기존 스텁·픽스처 관례를 그대로 따라 아래 두 테스트를
추가한다. (리포지토리 스텁을 어떻게 만드는지는 기존 테스트가 정답이다 — 추측하지 말 것.)

```kotlin
    @Test
    fun `고시가 있으면 usdQuoteRef가 기준일과 회차까지 돌려준다`() {
        // 기존 테스트가 고시 한 건을 넣는 방식 그대로 사용할 것 (baseDate·roundNo·baseRate 지정)
        val ref = service.usdQuoteRef()

        assertThat(ref).isNotNull
        assertThat(ref!!.rate).isEqualByComparingTo(/* 픽스처의 baseRate */)
        assertThat(ref.baseDate).isEqualTo(/* 픽스처의 baseDate */)
        assertThat(ref.roundNo).isEqualTo(/* 픽스처의 roundNo */)
    }

    // 둘이 다른 값을 말하면 화면이 밝히는 환율과 환산에 쓰인 환율이 갈라진다.
    @Test
    fun `usdQuoteRef와 getUsdToKrw는 같은 환율을 말한다`() {
        assertThat(service.usdQuoteRef()!!.rate)
            .isEqualByComparingTo(service.getUsdToKrw())
    }

    @Test
    fun `고시가 없으면 usdQuoteRef는 null이다`() {
        // 빈 리포지토리 스텁으로 service를 새로 만들 것
        assertThat(emptyService.usdQuoteRef()).isNull()
    }
```

Run:
```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests '*HanaFxRateServiceTest*' --rerun-tasks --no-daemon
```
Expected: FAIL — `usdQuoteRef()`가 아직 기본 구현(null)이라 첫 두 테스트가 깨진다.

- [ ] **Step 2: 캐시 타입을 바꾸고 `usdQuoteRef()`를 구현한다**

import 추가:

```kotlin
import java.time.LocalDate
```

`private val cached = AtomicReference<Pair<Long, BigDecimal>?>(null)` 를 다음으로 바꾼다
(**위에 붙어 있는 긴 KDoc은 그대로 두고 타입만 바꾼다** — 실패를 캐시하지 않는 이유, 단일 인스턴스
전제 등 그 주석의 내용은 전부 여전히 유효하다):

```kotlin
    private val cached = AtomicReference<Pair<Long, UsdQuoteRef>?>(null)
```

`getUsdToKrw()` 전체를 다음으로 교체한다. **기존의 긴 KDoc(트랜잭션·rollback-only 관련)은 그대로
`getUsdToKrw()` 위에 남긴다** — 그 한계는 조회 경로가 그대로라 변하지 않았다.

```kotlin
    override fun getUsdToKrw(): BigDecimal =
        usdQuoteRef()?.rate ?: delegate.getUsdtToKrw()

    /**
     * 고시 한 건을 통째로 돌려준다 (AF-105). 없거나 조회에 실패하면 null.
     *
     * [getUsdToKrw]가 이 위에 올라타 있어 둘이 다른 값을 말할 수 없다.
     * 화면이 "이 환율로 계산했다"고 밝히는 근거가 실제 환산에 쓰인 값과 갈라지면
     * 신뢰를 만들려던 표기가 반대로 동작한다.
     */
    override fun usdQuoteRef(): UsdQuoteRef? {
        val now = System.currentTimeMillis()
        cached.get()?.let { (at, ref) -> if (now - at < TTL_MILLIS) return ref }

        val quote = runCatching {
            quotes.findTopByCurrencyOrderByBaseDateDescRoundNoDesc(CURRENCY)
        }.getOrElse { e ->
            log.error("[하나은행] 고시 조회 실패 — USDT 환율로 근사한다: {}", e.message)
            null
        } ?: return null

        val ref = UsdQuoteRef(
            rate = quote.baseRate,
            baseDate = quote.baseDate,
            roundNo = quote.roundNo,
        )
        cached.set(now to ref)
        return ref
    }
```

**엔티티 필드명이 `baseRate`·`baseDate`·`roundNo`가 맞는지 먼저 확인할 것**:
`allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/entity/HanaFxQuoteEntity.kt`

- [ ] **Step 3: 테스트를 돌려 통과를 확인한다**

Run:
```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests '*HanaFxRateServiceTest*' --rerun-tasks --no-daemon
```
Expected: PASS (기존 테스트 전부 + 신규 3건)

기존 테스트 중 "조회 실패를 캐시하지 않는다" 성질을 검증하는 것이 있으면 그대로 통과해야 한다.
깨지면 `?: return null`이 캐시 set 앞에 있는지 확인할 것 — 실패·부재는 절대 캐시하지 않는다.

- [ ] **Step 4: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/HanaFxRateService.kt allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/HanaFxRateServiceTest.kt
git commit -m "feat(af-105): 하나은행 고시 메타(기준일·회차) 캐시·노출"
```

---

### Task 3: `FxSource` + `CurrencyConverter.sourceOf` (이 계획의 핵심)

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/FxSource.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/CurrencyConverter.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/CurrencyConverterTest.kt`

- [ ] **Step 1: 실패 테스트를 먼저 쓴다**

`CurrencyConverterTest.kt`를 읽고 그 파일의 `FxRateService` 스텁 관례를 따른다.
스텁은 `usdQuoteRef()`를 오버라이드할 수 있어야 한다(기본 구현은 null).

```kotlin
    // 이 테스트가 이 파일에서 가장 중요하다.
    // 화면이 밝히는 환율이 실제 환산에 쓰인 환율과 같다는 것 — 그게 AF-105의 전제 전부다.
    // 두 값이 갈라지면 신뢰를 만들려던 표기가 정확히 반대로 동작한다.
    @Test
    fun `sourceOf가 밝히는 환율은 toKrw가 실제로 쓴 환율과 같다`() {
        listOf("USD", "USDT", "BTC", "ETH").forEach { code ->
            val source = converter.sourceOf(code)
            assertThat(source).describedAs("sourceOf(%s)", code).isNotNull

            val viaSource = (source!!.rate * BigDecimal("1000")).setScale(0, RoundingMode.HALF_UP)
            assertThat(converter.toKrw(BigDecimal("1000"), code))
                .describedAs("환산 결과가 %s의 표기 환율과 다르다", code)
                .isEqualByComparingTo(viaSource)
        }
    }

    @Test
    fun `KRW는 환산이 없으므로 출처도 없다`() {
        assertThat(converter.sourceOf("KRW")).isNull()
        assertThat(converter.toKrw(BigDecimal("1000"), "KRW")).isEqualByComparingTo(BigDecimal("1000"))
    }

    @Test
    fun `미지원 통화는 환산도 출처도 없다`() {
        assertThat(converter.sourceOf("JPY")).isNull()
        assertThat(converter.toKrw(BigDecimal("1000"), "JPY")).isEqualByComparingTo(BigDecimal("1000"))
    }

    @Test
    fun `고시가 있으면 USD 출처에 기준일과 회차가 실린다`() {
        // usdQuoteRef()가 UsdQuoteRef(1383.50, 2026-08-11, 32)를 돌려주는 스텁으로 converter 구성
        val source = converter.sourceOf("usd")   // 소문자도 받아야 한다

        assertThat(source!!.currency).isEqualTo("USD")
        assertThat(source.rate).isEqualByComparingTo(BigDecimal("1383.50"))
        assertThat(source.baseDate).isEqualTo(LocalDate.of(2026, 8, 11))
        assertThat(source.roundNo).isEqualTo(32)
        assertThat(source.source).isEqualTo("하나은행 매매기준율")
    }

    // 고시가 없는 날에도 줄은 사라지지 않고 문구만 바뀐다.
    // 숨기면 값이 가장 못 미더운 순간에 출처 표기가 사라져, 신뢰가 목적인 기능이 반대로 동작한다.
    // 기존 스텁(`fxRates`)은 usdQuoteRef()를 오버라이드하지 않아 기본값 null이고,
    // getUsdToKrw()=1390 / getUsdtToKrw()=1400으로 갈라 둔다. 그 스텁을 그대로 쓰면
    // 폴백이 getUsdToKrw()를 존중하는지가 바로 드러난다 — 1400이 나오면 계약을 우회한 것이다.
    @Test
    fun `고시가 없으면 근사임을 밝히되 표기를 없애지 않는다`() {
        val source = converter.sourceOf("USD")

        assertThat(source).isNotNull
        assertThat(source!!.rate).isEqualByComparingTo(BigDecimal("1390"))
        assertThat(source.source).contains("고시 없음")
        assertThat(source.baseDate).isNull()
        assertThat(source.roundNo).isNull()
    }
```

Run:
```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests '*CurrencyConverterTest*' --rerun-tasks --no-daemon
```
Expected: FAIL — `Unresolved reference: sourceOf`

- [ ] **Step 2: `FxSource.kt`를 만든다**

```kotlin
package com.allfolio.fx

import java.math.BigDecimal
import java.time.LocalDate

/**
 * KRW 환산에 실제로 쓰인 환율 한 건과 그 출처 (AF-105).
 *
 * [baseDate]·[roundNo]는 하나은행 고시일 때만 채워진다. 거래소 시세·코인 시세에는
 * 대조할 수 있는 "고시 회차"라는 개념이 없어서, 있는 척하면 사용자가 대조하러 갔다가
 * 아무것도 못 찾는다.
 */
data class FxSource(
    /** ISO 통화 코드 (대문자) */
    val currency: String,
    /** 1단위당 KRW */
    val rate: BigDecimal,
    /** 화면에 그대로 노출되는 한국어 문구 */
    val source: String,
    val baseDate: LocalDate?,
    val roundNo: Int?,
)
```

- [ ] **Step 3: `CurrencyConverter`를 `sourceOf` 위로 재구성한다**

`toKrw`의 기존 `when` 블록을 통째로 아래로 교체한다. import에 `java.math.RoundingMode`가 이미 있다.

```kotlin
    /**
     * 금액을 KRW로 환산한다.
     *
     * **분기표를 여기 두지 않고 [sourceOf] 하나에만 둔다.** 화면에 밝히는 환율과 환산에 쓰는
     * 환율이 서로 다른 `when`에서 나오면, 누가 통화를 추가하며 한쪽만 고치는 날 조용히 갈라진다.
     * 그러면 화면이 틀린 근거를 자신 있게 제시하게 되고, 신뢰를 만들려던 표기가 반대로 동작한다.
     * 두 경로를 같은 코드로 묶어 드리프트를 규율이 아니라 구조로 막는다.
     *
     * @param amount   환산 전 금액
     * @param currency "KRW" | "USD" | "USDT" | "BTC" | "ETH" (대소문자 구분 없음, 공백은 못 봐준다)
     * @return KRW 환산금액 (소수점 0자리, HALF_UP 반올림)
     */
    fun toKrw(amount: BigDecimal, currency: String): BigDecimal {
        if (currency.uppercase() == "KRW") return amount
        val source = sourceOf(currency)
            ?: run {
                log.warn("[CurrencyConverter] unsupported currency={} — returning as-is", currency)
                return amount
            }
        return (amount * source.rate).setScale(0, RoundingMode.HALF_UP)
    }

    /**
     * 이 통화를 KRW로 바꿀 때 쓰는 환율과 그 출처 (AF-105).
     *
     * **KRW와 미지원 통화가 똑같이 null인 것은 의도다.** 둘 다 환산이 일어나지 않았고,
     * 일어나지 않은 환산에는 밝힐 출처가 없다.
     */
    fun sourceOf(currency: String): FxSource? =
        when (val code = currency.uppercase()) {
            "KRW" -> null
            // AF-99: 법정통화 USD는 하나은행 공식 매매기준율.
            // 고시가 없으면 근사로 떨어지지만 표기를 없애지는 않는다 — 문구로 밝힌다.
            //
            // 폴백은 getUsdToKrw()를 부른다. getUsdtToKrw()를 직접 부르면 고시 조회를 한 번
            // 아낄 수 있지만, getUsdToKrw()는 인터페이스의 공개 계약이고 구현체가 usdQuoteRef()
            // 없이 그것만 오버라이드할 수 있다. 우회하면 "USD 환율"의 정의가 구현체마다 갈린다.
            "USD" -> fxRateService.usdQuoteRef()
                ?.let { FxSource("USD", it.rate, "하나은행 매매기준율", it.baseDate, it.roundNo) }
                ?: FxSource("USD", fxRateService.getUsdToKrw(), "고시 없음 · 거래소 시세 근사", null, null)
            // 스테이블코인은 거래소 시세를 유지한다 — 김치 프리미엄은 부정확이 아니라
            // 거래소에 실제 USDT를 들고 있는 사용자에게 실현 가능한 값이다
            "USDT" -> FxSource("USDT", fxRateService.getUsdtToKrw(), "거래소 시세", null, null)
            // QA P3: BTC/ETH도 코인당 KRW 시세로 환산 — 1:1 폴백은 0.5 BTC를 0.5원으로 축소하던 버그
            "BTC", "ETH" -> FxSource(code, fxRateService.getCryptoToKrw(code), "코인 시세", null, null)
            else -> null
        }
```

클래스 KDoc의 "지원 통화" 줄은 그대로 둔다.

- [ ] **Step 4: 테스트를 돌려 통과를 확인한다**

Run:
```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests '*CurrencyConverterTest*' --rerun-tasks --no-daemon
```
Expected: PASS (기존 테스트 전부 + 신규 5건)

**기존 테스트가 깨지면 그게 신호다.** `toKrw`의 동작은 바뀌면 안 된다 — 특히 미지원 통화가
원값을 그대로 돌려주고 WARN 로그를 남기는 것, 그리고 BTC/ETH 환산 결과. 깨진 내용을 보고할 것.

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/FxSource.kt allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/CurrencyConverter.kt allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/CurrencyConverterTest.kt
git commit -m "feat(af-105): 환율 출처 분기표를 sourceOf 한 곳으로 통합"
```

---

### Task 4: 변이 테스트 — 드리프트 방지가 실제로 작동하는지

이 계획 전체가 "분기표가 한 벌"이라는 전제 위에 서 있다. 그 전제가 테스트로 지켜지는지 확인한다.
AF-99·AF-100·AF-103에서 계획이 여러 번 틀렸고 전부 이 절차가 잡았다. 건너뛰지 않는다.

**Files:** 임시로 변형했다가 되돌린다. 커밋하지 않는다.

- [ ] **Step 1: 변이 A — USD 출처만 다른 환율을 말하게 한다**

`sourceOf`의 USD 분기에서 `it.rate`를 `it.rate + BigDecimal.ONE`으로 바꾼다.

Run:
```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests '*CurrencyConverterTest*' --rerun-tasks --no-daemon
```
Expected: **FAIL** — `sourceOf가 밝히는 환율은 toKrw가 실제로 쓴 환율과 같다`가 깨져야 한다.

통과하면 그 테스트가 무의미하다. `toKrw`가 `sourceOf` 위에 올라타 있어 둘이 같이 움직여
못 잡는 것일 수 있는데, 그렇다면 그건 **설계가 의도대로 동작한다는 뜻**이다.
그 경우 대신 변이 A'를 시도할 것: `toKrw`에서 `source.rate` 대신
`fxRateService.getUsdtToKrw()`를 쓰도록 바꿔 두 경로를 억지로 분리한다 —
이건 반드시 잡혀야 한다. 어느 쪽이 잡았는지 정확히 보고할 것.

- [ ] **Step 2: 변이 A를 되돌린다**

- [ ] **Step 3: 변이 B — 고시 없을 때 표기를 숨긴다**

`sourceOf`의 USD 폴백 `?: FxSource(...)` 전체를 `?: null`로 바꾼다.

Run: 위와 같은 명령
Expected: **FAIL** — `고시가 없으면 근사임을 밝히되 표기를 없애지 않는다`가 깨져야 한다.

- [ ] **Step 4: 변이 B를 되돌린다**

- [ ] **Step 5: 변이 C — 미지원 통화를 환산하는 척한다**

`sourceOf`의 `else -> null`을 `else -> FxSource(code, BigDecimal.ONE, "미상", null, null)`로 바꾼다.

Run: 위와 같은 명령
Expected: **FAIL** — `미지원 통화는 환산도 출처도 없다`가 깨져야 한다.

- [ ] **Step 6: 되돌리고 무결성을 확인한다**

```bash
git diff --stat allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/CurrencyConverter.kt
```
Expected: 출력 없음

- [ ] **Step 7: 커밋할 것이 없다 — 결과만 보고한다**

세 변이 각각에 대해 "잡혔는가 / 어느 테스트가 깨졌는가"를 표로 보고한다.
살아남은 변이가 있으면 그게 진짜 발견이다.

---

### Task 5: 대시보드 응답에 `fxSources` 싣기

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dashboard/DashboardResponse.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dashboard/GetDashboardUseCase.kt`
- Test: `GetDashboardUseCase`의 기존 테스트 파일(먼저 찾을 것: `grep -rl "GetDashboardUseCase" allfolio-backend/backend-app/src/test`)

- [ ] **Step 1: DTO를 추가한다**

`DashboardResponse.kt`의 `DashboardResponse`에 필드를 추가한다:

```kotlin
data class DashboardResponse(
    val netWorth: NetWorthDto,
    val portfolio: PortfolioDto,
    val realAssets: List<RealAssetDto>,
    /**
     * 이 순자산을 만드는 데 실제로 쓰인 환율들 (AF-105). 통화 코드 사전순.
     *
     * **원화 자산만 가진 사용자에게는 빈 배열이다.** 조건부 노출을 프론트의 if가 아니라
     * 여기 결과로 만든 것 — 두 곳이 각자 판단하면 언젠가 어긋난다.
     */
    val fxSources: List<FxSourceDto>,
)
```

파일 맨 끝에 추가:

```kotlin
/** @see com.allfolio.fx.FxSource */
data class FxSourceDto(
    val currency: String,
    val rate: BigDecimal,
    val source: String,
    /** 하나은행 고시일 때만 채워진다 */
    val baseDate: LocalDate?,
    val roundNo: Int?,
)
```

`LocalDate`는 이 파일에 이미 import돼 있다.

- [ ] **Step 2: `GetDashboardUseCase`가 채우게 한다**

이 클래스는 지금 포트 `fx: FxConverter`만 받는다. `CurrencyConverter`를 **추가로** 주입한다.

생성자의 `private val cashFlowRepository: CashFlowRepository,` 다음 줄에 추가:

```kotlin
    private val currencyConverter: CurrencyConverter,
```

import 추가:

```kotlin
import com.allfolio.fx.CurrencyConverter
```

**포트(`FxConverter`)에 `sourceOf`를 추가하지 않는 이유**를 적어둔다 — 리뷰에서 되돌리자는 말이
나올 지점이다. `FxConverter`는 `unified-asset`의 포트라 `FxSource` 타입도 그 모듈로 옮겨야 하고,
익명 구현이 20곳 가까이 된다. 반면 `GetDashboardUseCase`는 `backend-app`에 있는 앱 계층 서비스라
같은 모듈의 `CurrencyConverter`를 직접 쓰는 게 계층 위반이 아니다. 출처 표기는 화면 관심사지
도메인이 KRW 환산을 요구하는 지점이 아니라서, 포트를 넓힐 값어치가 없다.

`@Service`/`@Component` 빈이라 배선은 자동이다. 다만 **이 클래스의 기존 테스트가 생성자를 직접
부르고 있으면 전부 인자를 하나 더 넘겨야 한다** — Step 4에서 깨질 것이다.

`return DashboardResponse(` 바로 위에 추가:

```kotlin
        // 실제로 보유한 통화만 밝힌다. 안 가진 통화의 환율을 보여주면 "내 숫자가 어떻게 나왔나"라는
        // 질문에 답하는 대신 잡음이 된다. 정렬을 코드 사전순으로 고정하는 이유는, 자산 구성이
        // 조금 바뀔 때마다 줄 순서가 뒤바뀌면 화면이 불안정해 보이기 때문이다.
        val fxSources = (positions.map { it.currency } + realAssets.map { it.currency })
            .distinct()
            .sorted()
            .mapNotNull { currencyConverter.sourceOf(it) }
            .map { FxSourceDto(it.currency, it.rate, it.source, it.baseDate, it.roundNo) }
```

`DashboardResponse(...)` 호출의 `realAssets = realAssets,` 다음 줄에 추가:

```kotlin
            fxSources = fxSources,
```

- [ ] **Step 3: 테스트를 추가한다**

기존 `GetDashboardUseCase` 테스트 파일의 픽스처 관례를 그대로 따라 두 케이스를 추가한다:

```kotlin
    @Test
    fun `원화 자산만 있으면 환율 출처가 비어 있다`() {
        // KRW 자산만 가진 사용자로 execute
        assertThat(result.fxSources).isEmpty()
    }

    @Test
    fun `보유한 통화만 사전순으로 실린다`() {
        // USD 자산 + USDT 자산을 가진 사용자로 execute
        assertThat(result.fxSources.map { it.currency }).containsExactly("USD", "USDT")
    }
```

- [ ] **Step 4: 테스트를 돌린다**

Run:
```bash
cd allfolio-backend && ./gradlew :backend-app:test --rerun-tasks --no-daemon
```
Expected: BUILD SUCCESSFUL (전 모듈)

`DashboardResponse`에 필드를 추가했으므로 그 생성자를 직접 부르는 기존 테스트가 있으면 깨진다.
깨지면 새 인자를 넘기도록 고친다 — 필드에 기본값을 주지 말 것. 기본값을 주면
`fxSources`를 채우는 걸 잊은 새 코드 경로가 조용히 빈 배열을 내려보낸다.

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dashboard/
git add allfolio-backend/backend-app/src/test/kotlin/
git commit -m "feat(af-105): 대시보드 응답에 보유 통화 환율 출처 추가"
```

---

### Task 6: 프론트 — 순자산 하단 출처 줄

**Files:**
- Modify: `frontend/allfolio_app/types/dashboard.ts`
- Modify: `frontend/allfolio_app/components/dashboard/NetWorthBar.tsx`
- Modify: `frontend/allfolio_app/app/unified/page.tsx`

- [ ] **Step 1: 타입을 추가한다**

`types/dashboard.ts`에서 `netWorth: { ... }` 블록을 가진 인터페이스를 찾아, `portfolio` 필드 뒤에
추가한다:

```ts
  /** 이 순자산을 만든 환율들. 원화 자산만 있으면 빈 배열 (AF-105) */
  fxSources: FxSource[]
```

같은 파일 맨 끝에 추가:

```ts
export interface FxSource {
  currency: string
  rate: number
  /** 화면에 그대로 노출되는 한국어 문구 */
  source: string
  /** "2026-08-11" — 하나은행 고시일 때만 채워진다 */
  baseDate: string | null
  roundNo: number | null
}
```

- [ ] **Step 2: `NetWorthBar`에 렌더를 추가한다**

import에 타입을 추가한다 (이 파일이 타입을 어디서 import하는지 확인 후 관례를 따를 것):

```ts
import type { FxSource } from '@/types/dashboard'
```

`NetWorthBarProps`에 추가:

```ts
  /** 이 순자산을 만든 환율들. 비면 아무것도 렌더하지 않는다 (AF-105) */
  fxSources?: FxSource[]
```

구조분해에 `fxSources`를 추가한다:

```ts
export default function NetWorthBar({
  total, liquid, illiquid, debt, change30d, changeRate30d, netFlow30d, fxSources,
}: NetWorthBarProps) {
```

`hadFlows` 선언 아래에 추가:

```ts
  // 다중통화 트래커에서 사용자가 가장 먼저 의심하는 건 "무슨 환율로 계산했나"다.
  // 백엔드가 보유 통화만 골라 내려주므로 여기서 다시 판단하지 않는다 —
  // 원화만 가진 사용자에겐 애초에 빈 배열이 온다.
  const hasFx = fxSources != null && fxSources.length > 0

  // 회차까지 적는 이유: 하나은행 화면과 직접 대조가 가능해진다.
  // 기준일만으로는 하루에 수십 번 바뀌는 고시 중 어느 것인지 특정할 수 없다.
  const fxNote = (s: FxSource) => {
    if (s.baseDate == null || s.roundNo == null) return s.source
    const [, m, d] = s.baseDate.split('-')
    return `${Number(m)}/${Number(d)} ${s.roundNo}회차 고시`
  }
```

`30일 투자손익` 블록의 닫는 `)}` 다음, `<div className="mt-4 ...">` 앞에 추가:

```tsx
      {hasFx && (
        <div className="mt-2 space-y-0.5">
          {fxSources!.map((s) => (
            <p key={s.currency} className="text-[12px] text-fg-faint">
              원화 환산 · {s.currency}{' '}
              <Num className="text-[12px]">
                {s.rate.toLocaleString('ko-KR', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
              </Num>
              {'  '}({fxNote(s)})
            </p>
          ))}
        </div>
      )}
```

- [ ] **Step 3: 페이지가 값을 넘기게 한다**

`app/unified/page.tsx`에서 `const { netWorth, portfolio, realAssets } = data` 를 찾아
`fxSources`를 추가한다:

```ts
  const { netWorth, portfolio, realAssets, fxSources } = data
```

`<NetWorthBar` 호출의 `netFlow30d={netWorth.netFlow30d}` 다음 줄에 추가:

```tsx
        fxSources={fxSources}
```

- [ ] **Step 4: 타입 검사와 빌드를 통과시킨다**

Run:
```bash
cd frontend/allfolio_app && npx tsc --noEmit
```
Expected: 에러 없음

Run:
```bash
cd frontend/allfolio_app && npm run build
```
Expected: 빌드 성공

`Num`이 문자열 children만 받는다면 그냥 `<span>`으로 바꾸거나 `Num`의 시그니처를 확인해 맞출 것.
`text-fg-faint`가 이 프로젝트의 실제 토큰인지 `tailwind.config.ts`나 다른 컴포넌트 사용례로 확인할 것
(`NetWorthBar`가 이미 `text-fg-faint`를 쓰고 있으므로 있을 것이다).

- [ ] **Step 5: 커밋**

```bash
git add frontend/allfolio_app/types/dashboard.ts frontend/allfolio_app/components/dashboard/NetWorthBar.tsx frontend/allfolio_app/app/unified/page.tsx
git commit -m "feat(af-105): 순자산 하단에 환율 출처 표기"
```

---

### Task 7: 전 모듈 검증 + PR

- [ ] **Step 1: 백엔드 전 모듈 테스트**

`main` 브랜치 보호의 필수 체크가 `Backend tests`(전 모듈)라 `backend-app`만으로는 부족하다.

Run:
```bash
cd allfolio-backend && ./gradlew test --rerun-tasks --no-daemon
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 프론트 빌드**

Run:
```bash
cd frontend/allfolio_app && npm run build
```
Expected: 빌드 성공

- [ ] **Step 3: 변경 범위 확인**

Run:
```bash
git diff --stat main...HEAD
```
Expected: 문서 2 + 백엔드 프로덕션 5 + 백엔드 테스트 3 안팎 + 프론트 3.

**`HanaFxCollectService`·`FxRateAdminController`·`SchedulerTriggerController`가 diff에 있으면 범위가 샌 것이다** —
이 작업은 수집·스케줄러를 건드리지 않는다.

- [ ] **Step 4: 푸시하고 PR을 연다**

```bash
git push -u origin feat/af-105-fx-source-label
```

```bash
gh pr create --base main --title "feat(af-105): 순자산 하단 환율 출처 표기" --body "$(cat <<'EOF'
## 요약

대시보드 순자산 아래에, 그 숫자를 만드는 데 실제로 쓰인 환율과 출처를 밝힌다.

```
원화 환산 · USD 1,383.50  (8/11 32회차 고시)
원화 환산 · USDT 1,350.00  (거래소 시세)
```

## 노션 분류가 틀렸다 — 백엔드가 먼저였다

태스크 영역이 `FE`였지만 프론트만으로는 불가능했다. 환율값·기준일·**회차**는 `hana_fx_quote`에만
있고 사용자용 API가 없다. 유일한 통로인 `GET /api/admin/fx/usdkrw`는 ADMIN 권한이 필요한 데다
환율값만 돌려주고 기준일·회차 필드가 아예 없다 — 회차를 적는 근거가 그 없는 필드에 걸려 있었다.

## 설계 핵심 — 분기표를 한 벌만 둔다

출처 표기는 **실제 환산에 쓰인 값과 일치할 때만** 의미가 있다. 갈라지면 신뢰를 만들려던 기능이
반대로 동작한다 — 화면이 틀린 근거를 자신 있게 제시한다.

그래서 `CurrencyConverter.sourceOf`를 유일한 분기표로 두고 **`toKrw`가 그 위에 올라탄다.**
환산에 쓸 환율을 구하는 경로와 화면에 밝힐 환율을 구하는 경로가 같은 코드라, 드리프트가
규율이 아니라 구조로 막힌다.

## 그 밖의 판단

- **조건부 노출이 백엔드 결과다.** 원화만 가진 사용자는 빈 배열을 받고 프론트는 아무것도 그리지
  않는다. "원화만 있으면 숨긴다"를 프론트가 다시 판단하지 않으므로 두 곳이 어긋날 수 없다.
- **폴백 상태를 숨기지 않는다.** 고시가 없어 거래소 시세로 근사한 날에도 줄은 그대로 나오고
  문구만 `고시 없음 · 거래소 시세 근사`로 바뀐다. 숨기면 값이 가장 못 미더운 순간에 출처가
  사라져, 신뢰가 목적인 기능이 정확히 반대로 동작한다.
- **링크를 넣지 않았다.** 설계 원안은 `/market?tab=fx`로 보내지만 그 라우트가 없다(AF-104 대기).
  죽은 링크 대신 표기만 하고, 시장 화면을 만들 때 붙인다.
- **DB 조회가 늘지 않는다.** `HanaFxRateService`는 이미 엔티티 전체를 읽고 `baseRate`만 남기고
  버리고 있었다. 60초 캐시가 담는 타입만 바꿨다.

## 마이그레이션

없음. 스키마 변경 없음.

Spec: `docs/superpowers/specs/2026-08-12-fx-source-label-design.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 5: CI 확인**

```bash
gh pr checks --watch
```
Expected: `Backend tests` PASS, `Build backend JAR` PASS

**실패한 필수 체크를 `--admin`으로 우회하지 않는다.**

---

## 완료 후 보고할 것

- PR 링크
- Task 4 변이 결과 표 (세 변이가 각각 잡혔는지, 살아남은 게 있는지)
- 프론트 렌더를 실제로 확인했는지 — 확인했다면 스크린샷, 못 했다면 그 사실
