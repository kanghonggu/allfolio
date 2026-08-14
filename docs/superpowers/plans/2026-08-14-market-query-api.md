# 시장 조회 API Implementation Plan (AF-104 · PR 1/2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 수집해 둔 지수·환율·금리를 화면이 한 번에 읽을 수 있는 `GET /api/market`을 만든다.

**Architecture:** 조회 전용 서비스 하나가 세 레포지토리에서 최신값을 모아 한 응답으로 조립한다. 전일대비·bp 변동은 저장하지 않은 값이라 조회 시점에 직전 값과 비교해 만든다. 지수는 재배포 약관(AF-108)이 미결이라 플래그로 통째로 뺄 수 있게 둔다.

**Tech Stack:** Kotlin · Spring Boot · JPA · JUnit5 + AssertJ + Mockito

**설계 문서:** `docs/superpowers/specs/2026-08-14-market-screen-design.md`

---

## 사전 필독 (모든 태스크 공통)

### 이건 두 PR 중 첫 번째다

화면(FE)은 이 API가 머지된 뒤 별도 계획으로 만든다. **이 PR만으로는 사용자에게 보이는 변화가 없다.**
그래도 단독으로 검증 가능하다 — 엔드포인트를 직접 호출해 값을 확인할 수 있다.

### 인증

`SecurityConfig`의 `.anyRequest().authenticated()`가 이미 잡는다. **`/api/market`에 별도 규칙을 추가하지 않는다** —
로그인한 사용자만 보는 것이 기본값이고, 재배포 관점에서도 공개보다 안전하다.

### 이름은 프런트가 붙인다

응답에는 `KOSPI`·`KTB_3Y` 같은 **코드만** 싣는다. 한글 표시명(`코스피`·`국고채 3년`)은 프런트가 매핑한다.
설정의 `nameContains`는 KIS 응답 검증용 문자열이지 표시명이 아니다 — 표시명으로 쓰지 말 것.

### 왕복 횟수에 대한 판단

지수는 종목마다 `findLatest(code)`를 부른다(국내 5 + 해외 9 = 14회). 한 번에 긁는 쿼리를 쓰면
슬롯 순서 규칙(`CLOSE > MID > OPEN`)을 JPQL의 `CASE`와 코틀린 양쪽에 두 벌로 갖게 된다 —
그 규칙이 갈리면 같은 날 개장 값이 종가보다 최신으로 잡힌다. **이미 테스트된 규칙 한 벌을 재사용하는 쪽을 택했다.**
금리는 한 번의 구간 조회로, 환율은 4회로 끝나므로 화면 한 번에 총 19회다. 부차적 화면이고 사용자가 소수라 감수한다.

### 검증 명령

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests '*Market*' --no-daemon
```

## 파일 구조

| 파일 | 책임 |
|---|---|
| `market/query/MarketSnapshot.kt` (신규) | 응답 DTO 일체 |
| `market/query/MarketQueryService.kt` (신규) | 세 소스에서 최신값 조립 + 파생값 계산 |
| `market/query/MarketQueryProperties.kt` (신규) | 지수 노출 플래그 |
| `api/market/MarketQueryController.kt` (신규) | `GET /api/market` |
| `unified-asset/.../jpa/HanaFxQuoteJpaRepository.kt` (수정) | 최신·직전 기준일 조회 두 개 추가 |
| `application.yml` (수정) | `market.indices-enabled` |

---

### Task 1: 응답 DTO + 지수 구간

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/query/MarketSnapshot.kt`
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/query/MarketQueryService.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/query/MarketQueryServiceTest.kt`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```kotlin
package com.allfolio.market.query

import com.allfolio.market.index.MarketIndexProperties
import com.allfolio.unifiedasset.infrastructure.entity.MarketIndexQuoteEntity
import com.allfolio.unifiedasset.infrastructure.jpa.MarketIndexQuoteJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class MarketQueryServiceTest {

    private val indexRepo: MarketIndexQuoteJpaRepository = mock(MarketIndexQuoteJpaRepository::class.java)

    @Test
    fun `설정에 있는 지수를 국내와 해외로 나눠 싣는다`() {
        `when`(indexRepo.findLatest("KOSPI")).thenReturn(indexQuote("KOSPI", "2500.00"))
        `when`(indexRepo.findLatest("SPX")).thenReturn(indexQuote("SPX", "5600.00"))

        val snapshot = service().snapshot()

        assertThat(snapshot.domestic?.map { it.code }).containsExactly("KOSPI")
        assertThat(snapshot.overseas?.map { it.code }).containsExactly("SPX")
        assertThat(snapshot.domestic?.single()?.price).isEqualByComparingTo("2500.00")
    }

    /**
     * 수집이 한 번도 안 된 지수는 행이 없다. 그때 0이나 빈 값을 만들어 내면
     * 화면이 "0.00"을 진짜 값처럼 보여준다 — 아예 빼야 한다.
     */
    @Test
    fun `행이 없는 지수는 응답에서 빠진다`() {
        `when`(indexRepo.findLatest("KOSPI")).thenReturn(indexQuote("KOSPI", "2500.00"))
        `when`(indexRepo.findLatest("SPX")).thenReturn(null)

        val snapshot = service().snapshot()

        assertThat(snapshot.domestic).hasSize(1)
        assertThat(snapshot.overseas).isEmpty()
    }

    /** 등락은 KIS가 준 값을 그대로 쓴다 — 우리가 다시 계산하지 않는다 */
    @Test
    fun `등락값과 등락률과 장상태를 그대로 싣는다`() {
        `when`(indexRepo.findLatest("KOSPI")).thenReturn(
            indexQuote("KOSPI", "2500.00", change = "12.40", changeRate = "0.44", status = "장마감"),
        )
        `when`(indexRepo.findLatest("SPX")).thenReturn(null)

        val view = service().snapshot().domestic!!.single()

        assertThat(view.change).isEqualByComparingTo("12.40")
        assertThat(view.changeRate).isEqualByComparingTo("0.44")
        assertThat(view.marketStatus).isEqualTo("장마감")
        assertThat(view.tradeDate).isEqualTo(LocalDate.of(2026, 8, 13))
        assertThat(view.slot).isEqualTo("CLOSE")
    }

    private fun service(): MarketQueryService {
        val properties = MarketIndexProperties().apply {
            domestic = listOf(MarketIndexProperties.DomesticIndex().apply { code = "KOSPI" })
            overseas = listOf(MarketIndexProperties.OverseasIndex().apply { code = "SPX" })
        }
        return MarketQueryService(indexRepo, properties)
    }

    private fun indexQuote(
        code: String,
        price: String,
        change: String = "0",
        changeRate: String = "0",
        status: String = "장중",
    ) = MarketIndexQuoteEntity(
        id = UUID.randomUUID(),
        indexCode = code,
        tradeDate = LocalDate.of(2026, 8, 13),
        slot = "CLOSE",
        price = BigDecimal(price),
        prevClose = BigDecimal(price),
        changeValue = BigDecimal(change),
        changeRate = BigDecimal(changeRate),
        prevCloseDate = null,
        marketStatus = status,
        source = "KIS",
        collectedAt = LocalDateTime.of(2026, 8, 13, 15, 50),
    )
}
```

- [ ] **Step 2: 컴파일 실패를 확인한다**

Run: `cd allfolio-backend && ./gradlew :backend-app:compileTestKotlin --no-daemon`
Expected: FAIL — `Unresolved reference: MarketQueryService`

- [ ] **Step 3: DTO를 만든다**

`MarketSnapshot.kt`:

```kotlin
package com.allfolio.market.query

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 시장 화면 한 번의 응답 (AF-104).
 *
 * **네 탭 데이터를 한 번에 싣는다.** 지수 14 + 환율 58 + 금리 6 = 78행이라 합쳐도 작고,
 * 탭마다 따로 부르면 전환마다 스피너가 돈다.
 *
 * **사용자별 데이터가 없다.** "내 통화" 카드는 프런트가 이미 받아 둔 계좌 데이터와 합쳐 만든다.
 * 여기 섞으면 시장 데이터가 포트폴리오에 묶여 캐시도 못 하고 테스트도 무거워진다.
 *
 * [domestic]·[overseas]가 **null이면 플래그로 꺼진 것**이고, 빈 리스트면 켜져 있으나 데이터가 없는 것이다.
 * 둘을 구분하는 이유는 프런트가 탭 자체를 지울지 "데이터 없음"을 띄울지 갈라야 하기 때문이다.
 */
data class MarketSnapshot(
    val domestic: List<IndexQuoteView>?,
    val overseas: List<IndexQuoteView>?,
    val flags: MarketFlags,
)

/**
 * 지수 한 종.
 *
 * 표시명을 싣지 않는다 — 프런트가 코드로 매핑한다. 설정의 `nameContains`는 KIS 응답 검증용
 * 문자열이지 표시명이 아니다(`"다우존스 산업"`처럼 부분 문자열이다).
 */
data class IndexQuoteView(
    val code: String,
    val price: BigDecimal,
    val change: BigDecimal,
    val changeRate: BigDecimal,
    /** 장중 | 장마감 | 개장전 */
    val marketStatus: String,
    val tradeDate: LocalDate,
    /** OPEN | MID | CLOSE. 화면이 "언제 기준인지"를 말할 때 쓴다 */
    val slot: String,
    val collectedAt: LocalDateTime,
)

data class MarketFlags(
    /** AF-108 재배포 미결. false면 지수를 아예 싣지 않는다 */
    val indicesEnabled: Boolean,
)
```

- [ ] **Step 4: 서비스를 만든다**

`MarketQueryService.kt`:

```kotlin
package com.allfolio.market.query

import com.allfolio.market.index.MarketIndexProperties
import com.allfolio.unifiedasset.infrastructure.entity.MarketIndexQuoteEntity
import com.allfolio.unifiedasset.infrastructure.jpa.MarketIndexQuoteJpaRepository
import org.springframework.stereotype.Service

/**
 * 시장 화면용 조회 (AF-104).
 *
 * **읽기 전용이고 파생값은 여기서 만든다.** 전일대비·bp 변동은 저장하지 않기로 한 값이라
 * (원본이 정정되면 파생값은 같이 안 고쳐져 화석이 된다 — AF-102 설계 판단) 조회 시점에 계산한다.
 *
 * 지수는 종목마다 `findLatest`를 부른다. 한 쿼리로 긁으면 슬롯 순서 규칙을 JPQL과 코틀린에
 * 두 벌로 갖게 되고, 갈리는 순간 같은 날 개장 값이 종가보다 최신으로 잡힌다.
 * 이미 테스트된 규칙 한 벌을 재사용하는 쪽이 낫다고 봤다.
 */
@Service
class MarketQueryService(
    private val indexRepository: MarketIndexQuoteJpaRepository,
    private val indexProperties: MarketIndexProperties,
) {
    fun snapshot(): MarketSnapshot = MarketSnapshot(
        domestic = indexProperties.domestic.mapNotNull { view(it.code) },
        overseas = indexProperties.overseas.mapNotNull { view(it.code) },
        flags = MarketFlags(indicesEnabled = true),
    )

    /** 수집된 적 없는 지수는 null이다 — 0으로 채우면 화면이 그걸 진짜 값으로 보여준다 */
    private fun view(code: String): IndexQuoteView? =
        indexRepository.findLatest(code)?.toView()

    private fun MarketIndexQuoteEntity.toView() = IndexQuoteView(
        code = indexCode,
        price = price,
        change = changeValue,
        changeRate = changeRate,
        marketStatus = marketStatus,
        tradeDate = tradeDate,
        slot = slot,
        collectedAt = collectedAt,
    )
}
```

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*MarketQueryService*' --no-daemon`
Expected: BUILD SUCCESSFUL (3 tests)

- [ ] **Step 6: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/query allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/query
git commit -m "feat(af-104): 시장 조회 서비스 — 지수 구간"
```

---

### Task 2: 환율 구간 — 전일대비는 직전 기준일과 비교한다

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/jpa/HanaFxQuoteJpaRepository.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/query/MarketSnapshot.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/query/MarketQueryService.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/query/MarketQueryServiceTest.kt`

**왜 조심해야 하는가:** 하나은행 고시는 하루에 여러 **회차**가 나온다. "직전 회차"와 비교하면
전일대비가 아니라 **장중 변동**이 된다. 반드시 **직전 기준일의 마지막 회차**와 비교해야 한다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`MarketQueryServiceTest.kt`에 아래를 추가하고, 필드에 `private val fxRepo: HanaFxQuoteJpaRepository = mock(HanaFxQuoteJpaRepository::class.java)`를 더한 뒤 `service()`가 그것을 넘기도록 고친다.

```kotlin
    /**
     * **직전 회차가 아니라 직전 기준일과 비교한다.** 하나은행은 하루에 회차가 여러 번 나오므로,
     * 직전 회차와 비교하면 전일대비가 아니라 장중 변동이 된다.
     */
    @Test
    fun `전일대비는 직전 기준일의 마지막 회차와 비교한다`() {
        val today = LocalDate.of(2026, 8, 13)
        val yesterday = LocalDate.of(2026, 8, 12)
        `when`(fxRepo.findTopByOrderByBaseDateDescRoundNoDesc()).thenReturn(fxQuote("USD", today, 32, "1390.00"))
        `when`(fxRepo.findAllByBaseDateAndRoundNo(today, 32)).thenReturn(listOf(fxQuote("USD", today, 32, "1390.00")))
        `when`(fxRepo.findTopByBaseDateLessThanOrderByBaseDateDescRoundNoDesc(today))
            .thenReturn(fxQuote("USD", yesterday, 40, "1380.00"))
        `when`(fxRepo.findAllByBaseDateAndRoundNo(yesterday, 40))
            .thenReturn(listOf(fxQuote("USD", yesterday, 40, "1380.00")))

        val fx = service().snapshot().fx!!

        assertThat(fx.baseDate).isEqualTo(today)
        assertThat(fx.roundNo).isEqualTo(32)
        assertThat(fx.quotes.single().change).isEqualByComparingTo("10.00")
    }

    /** 어제 없던 통화가 오늘 생기면 전일대비를 만들어 낼 수 없다. 0이 아니라 null이다 */
    @Test
    fun `직전 기준일에 없던 통화는 전일대비가 null이다`() {
        val today = LocalDate.of(2026, 8, 13)
        val yesterday = LocalDate.of(2026, 8, 12)
        `when`(fxRepo.findTopByOrderByBaseDateDescRoundNoDesc()).thenReturn(fxQuote("XPF", today, 32, "12.00"))
        `when`(fxRepo.findAllByBaseDateAndRoundNo(today, 32)).thenReturn(listOf(fxQuote("XPF", today, 32, "12.00")))
        `when`(fxRepo.findTopByBaseDateLessThanOrderByBaseDateDescRoundNoDesc(today))
            .thenReturn(fxQuote("USD", yesterday, 40, "1380.00"))
        `when`(fxRepo.findAllByBaseDateAndRoundNo(yesterday, 40))
            .thenReturn(listOf(fxQuote("USD", yesterday, 40, "1380.00")))

        assertThat(service().snapshot().fx!!.quotes.single().change).isNull()
    }

    /** 수집이 한 번도 안 됐으면 환율 구간 자체가 null이다 */
    @Test
    fun `환율 데이터가 없으면 fx가 null이다`() {
        `when`(fxRepo.findTopByOrderByBaseDateDescRoundNoDesc()).thenReturn(null)

        assertThat(service().snapshot().fx).isNull()
    }

    private fun fxQuote(currency: String, baseDate: LocalDate, roundNo: Int, rate: String) = HanaFxQuoteEntity(
        id = UUID.randomUUID(),
        baseDate = baseDate,
        roundNo = roundNo,
        currency = currency,
        baseRate = BigDecimal(rate),
        cashBuy = null,
        cashSell = null,
        remitSend = null,
        remitReceive = null,
        collectedAt = LocalDateTime.of(2026, 8, 13, 18, 0),
    )
```

임포트를 더한다: `com.allfolio.unifiedasset.infrastructure.entity.HanaFxQuoteEntity`,
`com.allfolio.unifiedasset.infrastructure.jpa.HanaFxQuoteJpaRepository`.

> `HanaFxQuoteEntity`의 실제 생성자 파라미터 이름·순서를 파일에서 확인하고 맞출 것.
> 위 헬퍼는 `id / baseDate / roundNo / currency / baseRate / cashBuy / cashSell / remitSend / remitReceive / collectedAt` 기준이다.

- [ ] **Step 2: 컴파일 실패를 확인한다**

Run: `cd allfolio-backend && ./gradlew :backend-app:compileTestKotlin --no-daemon`
Expected: FAIL — `Unresolved reference: findTopByOrderByBaseDateDescRoundNoDesc`

- [ ] **Step 3: 레포지토리에 조회 둘을 더한다**

`HanaFxQuoteJpaRepository.kt`:

```kotlin
    /**
     * 전체에서 가장 최근 고시 한 건. 여기서 얻은 `(baseDate, roundNo)`로 그 회차 전 통화를 읽는다.
     * 통화마다 최신을 따로 찾지 않는 이유: 58번 왕복이 되고, 통화별로 회차가 갈려
     * 한 화면에 서로 다른 회차가 섞인다.
     */
    fun findTopByOrderByBaseDateDescRoundNoDesc(): HanaFxQuoteEntity?

    /**
     * [baseDate]보다 앞선 기준일 중 가장 최근 고시 한 건.
     *
     * **직전 "회차"가 아니라 직전 "기준일"이다.** 하나은행은 하루에 회차가 여러 번 나오므로
     * 직전 회차와 비교하면 전일대비가 아니라 장중 변동이 된다. 연휴로 며칠이 비어도
     * 이 쿼리가 알아서 그 앞의 영업일을 찾는다.
     */
    fun findTopByBaseDateLessThanOrderByBaseDateDescRoundNoDesc(baseDate: LocalDate): HanaFxQuoteEntity?
```

- [ ] **Step 4: DTO를 더한다**

`MarketSnapshot.kt`에 추가하고, `MarketSnapshot`에 `val fx: FxSnapshot?,`를 `flags` 앞에 넣는다:

```kotlin
/**
 * 환율 한 회차 전체.
 *
 * 고시 회차를 응답에 싣는다 — 화면 우측 상단의 `하나은행 고시 / 32회차 / 2026.08.13` 도장이
 * 사용자가 은행 화면과 직접 대조할 수 있게 하는 신뢰 장치다.
 *
 * [collectedAt]은 **UTC다.** `LocalDateTime`이라 직렬화에 오프셋이 안 붙으므로,
 * 프런트가 `new Date(...)`로 읽으면 로컬 시각으로 해석해 KST 사용자에게 9시간 이르게 보인다.
 * 화면에 그대로 찍지 말고 KST로 옮기고 나서 쓸 것.
 * (지수 쪽은 `tradeDate`+`slot`+`marketStatus`가 "언제 기준인지"를 더 정확히 말해서 이 필드를 뺐다.
 *  환율은 회차 안에서의 신선도를 이것 말고 말할 방법이 없어 남긴다.)
 */
data class FxSnapshot(
    val baseDate: LocalDate,
    val roundNo: Int,
    val collectedAt: LocalDateTime,
    val quotes: List<FxQuoteView>,
)

/**
 * 통화 한 종. [change]가 null이면 직전 기준일에 그 통화가 없었다는 뜻이다 —
 * 0으로 채우면 "안 움직였다"는 거짓말이 된다.
 */
data class FxQuoteView(
    val currency: String,
    val baseRate: BigDecimal,
    val cashBuy: BigDecimal?,
    val cashSell: BigDecimal?,
    val remitSend: BigDecimal?,
    val remitReceive: BigDecimal?,
    val change: BigDecimal?,
    val changeRate: BigDecimal?,
)
```

- [ ] **Step 5: 서비스에 환율 구간을 더한다**

생성자에 `private val fxRepository: HanaFxQuoteJpaRepository,`를 더하고, `snapshot()`에 `fx = fxSnapshot(),`를 넣은 뒤:

```kotlin
    /**
     * 최신 회차 전 통화 + 직전 기준일 대비.
     *
     * 쿼리 4회로 끝난다: 최신 한 건 → 그 회차 전량 → 직전 기준일 한 건 → 그 회차 전량.
     */
    private fun fxSnapshot(): FxSnapshot? {
        val latest = fxRepository.findTopByOrderByBaseDateDescRoundNoDesc() ?: return null
        val current = fxRepository.findAllByBaseDateAndRoundNo(latest.baseDate, latest.roundNo)

        // 직전 "기준일"이다. 직전 회차와 비교하면 전일대비가 아니라 장중 변동이 된다
        val priorHead = fxRepository.findTopByBaseDateLessThanOrderByBaseDateDescRoundNoDesc(latest.baseDate)
        val prior = priorHead
            ?.let { fxRepository.findAllByBaseDateAndRoundNo(it.baseDate, it.roundNo) }
            ?.associateBy { it.currency }
            ?: emptyMap()

        return FxSnapshot(
            baseDate = latest.baseDate,
            roundNo = latest.roundNo,
            collectedAt = latest.collectedAt,
            quotes = current.map { q ->
                val before = prior[q.currency]?.baseRate
                FxQuoteView(
                    currency = q.currency,
                    baseRate = q.baseRate,
                    cashBuy = q.cashBuy,
                    cashSell = q.cashSell,
                    remitSend = q.remitSend,
                    remitReceive = q.remitReceive,
                    // 어제 없던 통화는 0이 아니라 null이다 — 0은 "안 움직였다"는 뜻이 된다
                    change = before?.let { q.baseRate - it },
                    changeRate = before?.takeIf { it.signum() != 0 }?.let {
                        (q.baseRate - it).divide(it, 6, java.math.RoundingMode.HALF_UP)
                            .multiply(BigDecimal(100)).setScale(2, java.math.RoundingMode.HALF_UP)
                    },
                )
            }.sortedBy { it.currency },
        )
    }
```

- [ ] **Step 6: 테스트가 통과하는지 확인한다**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*MarketQueryService*' --no-daemon`
Expected: BUILD SUCCESSFUL (6 tests)

- [ ] **Step 7: 커밋**

```bash
git add allfolio-backend/backend-app/src allfolio-backend/unified-asset/src
git commit -m "feat(af-104): 환율 구간 — 전일대비는 직전 기준일과 비교한다"
```

---

### Task 3: 금리 구간 — bp 변동

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/query/MarketSnapshot.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/query/MarketQueryService.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/query/MarketQueryServiceTest.kt`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

필드에 `private val rateRepo: MarketRateJpaRepository = mock(MarketRateJpaRepository::class.java)`와
`private val rateProperties = MarketRateProperties().apply { series = listOf(...) }`를 더하고 `service()`에 넘긴다.

```kotlin
    /** bp 변동은 직전 quote_date와 비교한다. 1%p = 100bp */
    @Test
    fun `금리의 bp 변동은 직전 기준일과 비교한다`() {
        `when`(rateRepo.findByRateCodeAndQuoteDateBetween(eq("KTB_3Y"), any(), any())).thenReturn(
            listOf(
                marketRate("KTB_3Y", LocalDate.of(2026, 8, 12), "3.7910"),
                marketRate("KTB_3Y", LocalDate.of(2026, 8, 13), "3.7810"),
            ),
        )

        val view = service().snapshot().rates.single()

        assertThat(view.code).isEqualTo("KTB_3Y")
        assertThat(view.value).isEqualByComparingTo("3.7810")
        assertThat(view.quoteDate).isEqualTo(LocalDate.of(2026, 8, 13))
        assertThat(view.changeBp).isEqualByComparingTo("-1.00")
    }

    /**
     * 기준금리 공표가 시장금리보다 이틀 늦은 것이 실측으로 확인됐다.
     * 그래서 항목마다 기준일이 다르고, 화면은 그걸 숨기면 안 된다.
     */
    @Test
    fun `행이 하나뿐이면 bp 변동이 null이다`() {
        `when`(rateRepo.findByRateCodeAndQuoteDateBetween(eq("KTB_3Y"), any(), any())).thenReturn(
            listOf(marketRate("KTB_3Y", LocalDate.of(2026, 8, 13), "3.7810")),
        )

        assertThat(service().snapshot().rates.single().changeBp).isNull()
    }

    @Test
    fun `행이 없는 금리는 응답에서 빠진다`() {
        `when`(rateRepo.findByRateCodeAndQuoteDateBetween(eq("KTB_3Y"), any(), any())).thenReturn(emptyList())

        assertThat(service().snapshot().rates).isEmpty()
    }

    private fun marketRate(code: String, date: LocalDate, value: String) = MarketRateEntity(
        id = UUID.randomUUID(),
        rateCode = code,
        quoteDate = date,
        rateValue = BigDecimal(value),
        source = "ECOS",
        collectedAt = LocalDateTime.of(2026, 8, 13, 18, 10),
    )
```

임포트: `com.allfolio.market.rate.MarketRateProperties`,
`com.allfolio.unifiedasset.infrastructure.entity.MarketRateEntity`,
`com.allfolio.unifiedasset.infrastructure.jpa.MarketRateJpaRepository`,
`org.mockito.ArgumentMatchers.any`, `org.mockito.ArgumentMatchers.eq`.

> `any()`가 `LocalDate` 자리에서 null을 돌려줘 NPE가 나면 `any(LocalDate::class.java) ?: LocalDate.EPOCH` 관례를
> 쓴다 — 이 저장소의 다른 테스트들이 같은 회피를 한다.

- [ ] **Step 2: 컴파일 실패를 확인한다**

Run: `cd allfolio-backend && ./gradlew :backend-app:compileTestKotlin --no-daemon`
Expected: FAIL — `No value passed for parameter 'rateRepository'`

- [ ] **Step 3: DTO를 더한다**

`MarketSnapshot`에 `val rates: List<RateView>,`를 `flags` 앞에 넣고:

```kotlin
/**
 * 금리 한 종.
 *
 * [quoteDate]를 항목마다 싣는다 — **같은 탭 안에서도 기준일이 다르다.**
 * 실측: 기준금리 공표가 시장금리보다 이틀 늦다. 공통 헤더에 시각 하나를 두면 화면이 거짓말을 한다.
 *
 * [changeBp]는 %p가 아니라 **bp**다(1%p = 100bp). null이면 비교할 직전 값이 없다는 뜻이다.
 */
data class RateView(
    val code: String,
    val value: BigDecimal,
    val quoteDate: LocalDate,
    val changeBp: BigDecimal?,
)
```

- [ ] **Step 4: 서비스에 금리 구간을 더한다**

생성자에 `private val rateRepository: MarketRateJpaRepository,`와
`private val rateProperties: MarketRateProperties,`를 더하고, `snapshot()`에 `rates = rateViews(),`를 넣은 뒤:

```kotlin
    companion object {
        /**
         * 금리 조회 창. 직전 값 하나만 있으면 되지만 넉넉히 잡는다 —
         * 연휴가 길면 직전 영업일이 2주 밖일 수 있고, 6종 x 30일이면 180행이라 비용이 없다.
         */
        private const val RATE_LOOKBACK_DAYS = 30L

        /** 1%p = 100bp */
        private val BP_PER_PERCENT = BigDecimal(100)
    }

    /**
     * 종목마다 최근 [RATE_LOOKBACK_DAYS]일을 한 번에 읽어 마지막 둘로 값과 bp 변동을 만든다.
     * 종목당 쿼리 하나이고 설정이 6종이라 6회다.
     */
    private fun rateViews(): List<RateView> {
        val to = LocalDate.now(KST)
        val from = to.minusDays(RATE_LOOKBACK_DAYS)
        return rateProperties.series.mapNotNull { series ->
            val rows = rateRepository
                .findByRateCodeAndQuoteDateBetween(series.code, from, to)
                .sortedBy { it.quoteDate }
            val latest = rows.lastOrNull() ?: return@mapNotNull null
            val prior = rows.getOrNull(rows.size - 2)
            RateView(
                code = latest.rateCode,
                value = latest.rateValue,
                quoteDate = latest.quoteDate,
                // 비교할 직전 값이 없으면 0이 아니라 null이다 — 0은 "안 움직였다"는 뜻이 된다
                changeBp = prior?.let { (latest.rateValue - it.rateValue) * BP_PER_PERCENT },
            )
        }
    }
```

`KST` 상수를 companion에 더한다: `private val KST: ZoneId = ZoneId.of("Asia/Seoul")`.
임포트: `java.time.ZoneId`.

> **`LocalDate.now()`를 그냥 쓰지 않는다.** Render 컨테이너는 UTC라 KST 새벽에 하루 전으로 밀린다.
> 이 저장소가 같은 이유로 여러 곳에서 KST로 옮긴다.

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*MarketQueryService*' --no-daemon`
Expected: BUILD SUCCESSFUL (9 tests)

- [ ] **Step 6: 커밋**

```bash
git add allfolio-backend/backend-app/src
git commit -m "feat(af-104): 금리 구간 — bp 변동은 직전 기준일과 비교한다"
```

---

### Task 4: 플래그 + 엔드포인트

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/query/MarketQueryProperties.kt`
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/api/market/MarketQueryController.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/query/MarketQueryService.kt`
- Modify: `allfolio-backend/backend-app/src/main/resources/application.yml`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/query/MarketQueryServiceTest.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/api/market/MarketQueryControllerTest.kt`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`MarketQueryServiceTest.kt`에 추가한다. `service()` 헬퍼가 플래그를 받도록 고친다
(`private fun service(indicesEnabled: Boolean = true)`).

```kotlin
    /**
     * **플래그가 off면 서버가 지수를 아예 싣지 않는다 — 빈 리스트가 아니라 null이다.**
     * 빈 리스트로 내려보내면 프런트가 실수로 렌더해도 데이터는 이미 나간 뒤다.
     * 재배포를 실제로 멈추는 것은 서버가 안 싣는 것이지 프런트가 안 그리는 것이 아니다(AF-108).
     */
    @Test
    fun `플래그가 off면 지수를 싣지 않는다`() {
        val snapshot = service(indicesEnabled = false).snapshot()

        assertThat(snapshot.domestic).isNull()
        assertThat(snapshot.overseas).isNull()
        assertThat(snapshot.flags.indicesEnabled).isFalse()
        verifyNoInteractions(indexRepo)
    }
```

임포트: `org.mockito.Mockito.verifyNoInteractions`.

컨트롤러 테스트 `MarketQueryControllerTest.kt`:

```kotlin
package com.allfolio.api.market

import com.allfolio.market.query.MarketFlags
import com.allfolio.market.query.MarketQueryService
import com.allfolio.market.query.MarketSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class MarketQueryControllerTest {

    private val service: MarketQueryService = mock(MarketQueryService::class.java)
    private val controller = MarketQueryController(service)

    @Test
    fun `스냅샷을 그대로 돌려준다`() {
        val snapshot = MarketSnapshot(
            domestic = emptyList(),
            overseas = emptyList(),
            fx = null,
            rates = emptyList(),
            flags = MarketFlags(indicesEnabled = true),
        )
        `when`(service.snapshot()).thenReturn(snapshot)

        val response = controller.market()

        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.body).isSameAs(snapshot)
    }
}
```

- [ ] **Step 2: 컴파일 실패를 확인한다**

Run: `cd allfolio-backend && ./gradlew :backend-app:compileTestKotlin --no-daemon`
Expected: FAIL — `Unresolved reference: MarketQueryController`

- [ ] **Step 3: 플래그 설정을 만든다**

```kotlin
package com.allfolio.market.query

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * 시장 화면 노출 설정 (AF-104).
 *
 * **[indicesEnabled]는 AF-108 재배포 검토의 미결 때문에 있다.** KIS 개인용 오픈API의 시세
 * 재배포 가능 여부가 확정되지 않았고(원문 미확보), Twelve Data 무료 티어는 불가로 확정됐다.
 * 지금은 켜 두지만, 답이 "불가"로 오면 **설정 한 줄로 지수를 화면에서 뺄 수 있어야 한다** —
 * 그러지 않으면 화면을 통째로 들어내야 하고, 그게 AF-108이 막으려던 상황이다.
 *
 * 환율(하나은행)·금리(한국은행)는 성격이 달라 같은 제약을 받지 않을 가능성이 높아 플래그가 없다.
 */
@Component
@ConfigurationProperties(prefix = "market")
class MarketQueryProperties {
    var indicesEnabled: Boolean = true
}
```

- [ ] **Step 4: 서비스가 플래그를 보게 한다**

생성자에 `private val queryProperties: MarketQueryProperties,`를 더하고 `snapshot()`을 고친다:

```kotlin
    fun snapshot(): MarketSnapshot {
        val indicesOn = queryProperties.indicesEnabled
        return MarketSnapshot(
            // off면 null이다 — 빈 리스트로 내려보내면 데이터가 이미 응답에 실려 나간다
            domestic = if (indicesOn) indexProperties.domestic.mapNotNull { view(it.code) } else null,
            overseas = if (indicesOn) indexProperties.overseas.mapNotNull { view(it.code) } else null,
            fx = fxSnapshot(),
            rates = rateViews(),
            flags = MarketFlags(indicesEnabled = indicesOn),
        )
    }
```

- [ ] **Step 5: 컨트롤러를 만든다**

```kotlin
package com.allfolio.api.market

import com.allfolio.market.query.MarketQueryService
import com.allfolio.market.query.MarketSnapshot
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * GET /api/market — 시장 화면 데이터 (AF-104).
 *
 * **인증 규칙을 따로 두지 않는다.** `SecurityConfig`의 `.anyRequest().authenticated()`가 이미 잡는다.
 * 로그인한 사용자만 보는 것이 기본값이고, 재배포 관점에서도 공개보다 안전하다.
 *
 * **네 탭을 한 번에 돌려준다.** 탭마다 따로 부르면 전환마다 스피너가 돈다 —
 * 합쳐도 78행이라 나눌 이유가 없다.
 */
@RestController
@RequestMapping("/api/market")
class MarketQueryController(
    private val marketQueryService: MarketQueryService,
) {
    @GetMapping
    fun market(): ResponseEntity<MarketSnapshot> = ResponseEntity.ok(marketQueryService.snapshot())
}
```

- [ ] **Step 6: `application.yml`에 플래그를 둔다**

`market-index:` 블록 바로 앞에 넣는다:

```yaml
# 시장 화면 노출 (AF-104)
#
# indices-enabled는 AF-108 재배포 검토의 미결 때문에 있다. KIS 개인용 오픈API의 시세 재배포
# 가능 여부가 확정되지 않았다(원문 미확보). 답이 "불가"로 오면 이 값을 false로 바꾸는 것만으로
# 지수 두 탭이 화면에서 사라진다 — 서버가 아예 안 싣는다.
market:
  indices-enabled: ${MARKET_INDICES_ENABLED:true}
```

- [ ] **Step 7: 테스트가 통과하는지 확인한다**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*MarketQuery*' --no-daemon`
Expected: BUILD SUCCESSFUL (서비스 10 + 컨트롤러 1)

- [ ] **Step 8: 커밋**

```bash
git add allfolio-backend/backend-app/src allfolio-backend/backend-app/src/main/resources/application.yml
git commit -m "feat(af-104): GET /api/market + 지수 노출 플래그"
```

---

### Task 5: 전 모듈 검증 + PR

- [ ] **Step 1: 전 모듈 테스트**

```bash
cd allfolio-backend && ./gradlew test --rerun-tasks --no-daemon
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 변경 범위를 확인한다**

```bash
git diff --stat origin/main...HEAD
```

수집 경로(`RateCollectService`·`IndexCollectService`·`HanaFxCollectService`)가 diff에 있으면 범위가 샌 것이다 —
이 PR은 **읽기만** 더한다.

- [ ] **Step 3: 푸시하고 PR**

```bash
git push -u origin feat/af-104-market-screen
```

PR 본문에 담을 것:
- **화면은 다음 PR이다** — 이 PR만으로는 사용자에게 보이는 변화가 없다
- 전일대비가 직전 *기준일*과 비교된다는 것(직전 회차가 아니다)과 그 이유
- 플래그가 off면 서버가 지수를 **아예 안 싣는다**는 것
- 지수 카드의 "내 수익률 한 줄"을 왜 뺐는지 (설계 문서 링크)

- [ ] **Step 4: CI 확인**

```bash
gh pr checks --watch
```

- [ ] **Step 5: 머지·배포 후 응답을 눈으로 확인한다**

로그인한 세션으로:

```bash
curl -sS -H "Authorization: Bearer $JWT" "https://allfolio.onrender.com/api/market" | python3 -m json.tool | head -60
```

확인할 것:
- `flags.indicesEnabled`가 true이고 `domestic` 5종·`overseas` 9종이 실렸는지
- `fx.roundNo`가 하나은행 화면의 회차와 같은지 — 이게 사용자가 직접 대조할 신뢰 장치다
- `rates`의 `quoteDate`가 **항목마다 다를 수 있는지** (기준금리가 이틀 늦다)
- `changeBp`가 %p가 아니라 bp인지 (국고채가 하루에 0.01%p 움직이면 `1.00`이어야 한다)

**여기서 본 응답 모양이 FE 계획의 입력이다.** 바꿀 것이 있으면 FE 계획을 쓰기 전에 고친다 —
화면을 만든 뒤에 응답 모양을 바꾸면 양쪽을 다시 건드려야 한다.

---

## 완료 후 보고할 것

- Task 5 Step 1 전 모듈 테스트 결과
- Task 5 Step 5 실제 응답에서 확인한 네 항목
- FE 계획을 쓰기 전에 응답 모양에서 바꿀 것이 있었는지
