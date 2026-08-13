# 현금흐름 KRW 환산액 소급 재계산 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 이미 저장된 `cash_flow.amount_krw`를 그 흐름의 날짜 환율로 다시 계산한다. 드라이런이 기본.

**Architecture:** 새 계산 로직을 만들지 않는다 — 쓰기 경로가 쓰는 `FxConverter.toKrwOn`을 그대로 다시 호출한다. 재계산은 `(amount, currency, flowDate)`와 `fx_rate_daily`의 순수 함수라 멱등하고, 이미 맞는 행은 자기 자신으로 계산된다.

**Tech Stack:** Kotlin 1.9.25 / Spring Boot 3.2.5 / JPA / JUnit5 + 순수 Mockito·손수 만든 페이크

**Spec:** `docs/superpowers/specs/2026-08-13-cashflow-krw-recompute-design.md`

---

## 스펙에서 범위를 하나 뺐다 — 읽고 시작할 것

스펙은 `fx_rate_date`·`fx_estimated` 컬럼 추가를 포함했다. **이 계획은 그걸 2단계로 미룬다.**

이유는 파급 범위다. `CashFlow`는 모든 필드가 `val`인 불변 객체이고 `create`가
`transferPair`·`fxPair`에서도 불린다. 필드를 늘리면 도메인 시그니처가 바뀌어 쓰기 경로 셋,
페어 생성 둘, 엔티티 매퍼, 그리고 `CashFlow`를 만드는 모든 테스트가 함께 깨진다.

그런데 **재계산 자체는 그 컬럼이 없어도 완결된다.** 전수 재계산이 멱등하기 때문이다.
컬럼이 주는 것은 "다음에 추정치만 골라 돌리기"와 "화면에 추정치 표기"인데,
전자는 `cash_flow`가 작은 개인 앱에서 성능 이득이 없고 후자는 별개 판단이다.
드라이런 보고서가 "몇 건이 여전히 추정치인가"를 즉석에서 계산하므로 관측성도 지금 확보된다.

**틀린 숫자를 먼저 고치고, 컬럼은 필요해지면 그때 넣는다.**

## 사전 필독 (모든 태스크 공통)

- **Gradle 테스트는 반드시 `--rerun-tasks`.** 없으면 전부 UP-TO-DATE로 보고되고 아무것도 실행되지 않는다.
- Gradle 콘솔은 개별 테스트를 나열하지 않는다. 건수는 JUnit XML을 읽을 것:
  `allfolio-backend/<module>/build/test-results/test/TEST-<FQCN>.xml`
- **`mockito-kotlin`은 이 저장소에 없다.** `spring-boot-starter-test`뿐이고 기존 테스트는
  순수 `org.mockito.Mockito`나 손수 만든 페이크를 쓴다. 의존성을 추가하지 말 것.
- **Kotlin final 클래스를 Mockito로 목킹할 때 non-null 파라미터에 `any()`를 쓰면 스터빙 시점에 NPE**가 난다
  (inline mock maker가 `checkNotNullParameter` intrinsic을 남긴다).
  `any(X::class.java) ?: <기본값>`으로 우회한다 — AF-103·AF-101에서 실제로 물린 함정이다.
- 브랜치는 `feat/af-100-cashflow-recompute` (생성돼 있고 설계 문서 커밋이 올라가 있다).
- **마이그레이션 없음.** 스키마를 바꾸지 않는다.

## 파일 구조

| 파일 | 책임 |
|---|---|
| `unified-asset/.../infrastructure/jpa/CashFlowJpaRepository.kt` (수정) | 원화 아닌 행 전수 조회 |
| `backend-app/.../fx/CashFlowRecomputeService.kt` (신규) | 재계산 판정 + 적용 |
| `backend-app/.../api/admin/FxRateAdminController.kt` (수정) | 어드민 엔드포인트 |

### `CashFlowRepository` 포트를 건드리지 않는다

초안은 포트에 전수 조회를 추가하려 했다. 접었다.

**포트에 메서드를 더하면 테스트의 손수 만든 구현체 10곳 이상이 한꺼번에 깨진다**
(`GetDashboardUseCaseReturnsTest`, `RecordCashFlowUseCaseTest`, `ReturnsReportGeneratorTest` 등).
전부 한 줄씩 채워야 하는데, 그 대가로 얻는 게 없다 —
**재계산은 도메인 유스케이스가 아니라 일회성 운영 작업이고**, 사용자 경계를 넘는 전수 조회를
도메인 포트에 넣으면 일반 조회 경로에서 실수로 부를 여지만 생긴다.

대신 서비스가 `CashFlowJpaRepository`를 직접 주입받는다. **선례가 있다** —
`backend-app`의 `HanaFxRateService`가 `unified-asset`의 `HanaFxQuoteJpaRepository`를 그대로 받는다.

기본값을 준 포트 메서드(`= emptyList()`)로 우회하는 것도 안 된다.
페이크가 빈 목록을 돌려주면 **재계산이 조용히 0건으로 끝나고 테스트는 초록**이 된다.

---

### Task 1: 전수 조회 쿼리

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/jpa/CashFlowJpaRepository.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/infrastructure/jpa/CashFlowRecomputeQueryTest.kt` (신규)

**포트(`CashFlowRepository`)는 건드리지 않는다** — 위 "파일 구조" 절의 이유를 먼저 읽을 것.
JPA 리포지토리에만 메서드를 더한다.

- [ ] **Step 1: 실패 테스트를 먼저 쓴다**

기존 JPA 테스트의 하네스를 그대로 따른다. **먼저 읽을 것**:
`allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/infrastructure/jpa/HanaFxQuoteJpaRepositoryTest.kt`
(`@DataJpaTest` + `@ContextConfiguration(classes = [...TestConfig::class])` 형태).

```kotlin
    // 원화 행은 재계산 대상이 아니다 — amount_krw == amount가 정의라 계산할 것이 없다.
    // 조회 단계에서 걸러야 서비스가 "바뀐 것 없음"을 세느라 헛돌지 않는다.
    @Test
    fun `원화가 아닌 행만 돌려준다`() {
        save(currency = "KRW", amount = "1000", amountKrw = "1000")
        save(currency = "USD", amount = "100", amountKrw = "140000")
        save(currency = "BTC", amount = "0.5", amountKrw = "45000000")

        val rows = repository.findNonKrwOrderByFlowDate()

        assertThat(rows.map { it.currency }).containsExactlyInAnyOrder("USD", "BTC")
    }

    // 소문자로 저장된 행이 있어도 원화는 원화다. 저장 시 uppercase()가 걸리지만
    // 과거 데이터나 직접 INSERT된 행까지 보장되지는 않는다.
    @Test
    fun `원화 판정은 대소문자를 가리지 않는다`() {
        save(currency = "krw", amount = "1000", amountKrw = "1000")

        assertThat(repository.findNonKrwOrderByFlowDate()).isEmpty()
    }

    // 환율 조회 캐시는 (통화, 날짜) 단위다. 날짜순으로 주면 같은 날짜가 뭉쳐 와
    // 캐시 적중률이 올라간다 — 재계산은 같은 날짜를 수없이 반복한다.
    @Test
    fun `흐름일자 오름차순으로 준다`() {
        save(currency = "USD", flowDate = LocalDate.of(2025, 3, 1))
        save(currency = "USD", flowDate = LocalDate.of(2024, 1, 1))
        save(currency = "USD", flowDate = LocalDate.of(2024, 6, 1))

        assertThat(repository.findNonKrwOrderByFlowDate().map { it.flowDate })
            .containsExactly(
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 6, 1),
                LocalDate.of(2025, 3, 1),
            )
    }
```

`save(...)` 헬퍼는 기본값을 가진 private 함수로, **`CashFlowEntity`를 직접 만들어
`jpa.saveAndFlush(...)`로 넣는다.** 이 테스트가 검증할 대상은 JPA 쿼리이지 도메인 검증이 아니고,
`CashFlow.create(...)`를 거치면 `currency`가 `uppercase()`로 정규화돼
**소문자 `krw` 케이스를 아예 만들 수 없다.**

`saveAndFlush`를 쓰는 이유는 1차 캐시만 보고 통과하는 걸 막기 위해서다 —
쿼리가 실제로 DB에 나가야 `UPPER(...)` 비교가 검증된다.

Run — 컴파일 실패를 확인한다:
```bash
cd allfolio-backend && ./gradlew :unified-asset:test --tests '*CashFlowRecomputeQueryTest*' --rerun-tasks --no-daemon
```

- [ ] **Step 2: JPA 리포지토리에 메서드를 추가한다**

```kotlin
    /**
     * 원화가 아닌 모든 행을 흐름일자 오름차순으로 (소급 재계산용).
     *
     * 다른 조회와 달리 **사용자 경계를 넘는다.** 재계산은 일회성 운영 작업이라 그렇다 —
     * 일반 조회 경로에서 쓰지 말 것. 도메인 포트(`CashFlowRepository`)에 두지 않은 이유이기도 하다.
     *
     * 날짜 오름차순인 이유는 환율 해석 캐시가 (통화, 날짜) 단위이기 때문이다.
     * 같은 날짜가 뭉쳐 오면 적중률이 오른다.
     *
     * `UPPER`로 비교하는 이유: 저장 시 `uppercase()`가 걸리지만 과거 데이터나
     * 직접 INSERT된 행까지 보장되지는 않는다.
     */
    @Query("SELECT c FROM CashFlowEntity c WHERE UPPER(c.currency) <> 'KRW' ORDER BY c.flowDate ASC")
    fun findNonKrwOrderByFlowDate(): List<CashFlowEntity>
```

테스트는 엔티티를 직접 다루므로 `save(...)` 헬퍼가 `CashFlowEntity`를 만들어
`jpa.saveAndFlush(...)`로 넣게 할 것. 단언은 `it.currency`·`it.flowDate`로 한다.

- [ ] **Step 3: 통과 확인 후 커밋**

Run:
```bash
cd allfolio-backend && ./gradlew :unified-asset:test --tests '*CashFlowRecomputeQueryTest*' --rerun-tasks --no-daemon
```
Expected: PASS (3건)

포트를 안 건드렸으므로 다른 테스트가 깨질 일이 없다. 그래도 전 모듈로 확인한다:
```bash
cd allfolio-backend && ./gradlew test --rerun-tasks --no-daemon
```

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/jpa/CashFlowJpaRepository.kt \
        allfolio-backend/unified-asset/src/test/kotlin/
git commit -m "feat(fx): 소급 재계산용 전수 조회 쿼리"
```

---

### Task 2: 재계산 서비스

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/CashFlowRecomputeService.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/CashFlowRecomputeServiceTest.kt`

**먼저 읽을 것**: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/FxRateBackfillService.kt`
(요약 data class·`@Transactional` 판단·주석 톤을 맞춘다).

`CashFlow`는 모든 필드가 `val`인 불변 객체다. 값을 바꾸려면 `CashFlow.reconstruct(...)`로
같은 `id`·`createdAt`을 유지한 새 객체를 만들어 저장한다 — `id`가 같으므로 JPA가 UPDATE로 처리한다.
**도메인 필드를 `var`로 바꾸지 말 것.**

- [ ] **Step 1: 실패 테스트를 먼저 쓴다**

`FxConverter`는 인터페이스라 익명 객체로 충분하다. Mockito를 쓰면 `toKrwOn(amount, currency, date)`의
non-null 파라미터에서 `any()`가 NPE를 낸다.

**`CashFlowJpaRepository`는 손으로 다 구현할 수 없다** — `JpaRepository`를 상속해 메서드가 수십 개다.
이 저장소가 이미 쓰는 방식대로 **위임 + 부분 오버라이드**를 쓴다
(`HanaFxCollectServiceTest`가 같은 형태다):

```kotlin
    private class FakeRepo(rows: List<CashFlow>) :
        CashFlowJpaRepository by mock(CashFlowJpaRepository::class.java) {

        val stored = mutableListOf<CashFlowEntity>()
        private val all = rows.map { CashFlowEntity.from(it) }

        override fun findNonKrwOrderByFlowDate(): List<CashFlowEntity> = all
        override fun <S : CashFlowEntity> save(entity: S): S = entity.also { stored += it }
    }
```

위임 대상 목은 **스터빙하지 않는다** — 쓰지 않는 메서드를 채우기 위한 자리일 뿐이다.
`save`의 시그니처가 제네릭(`<S : CashFlowEntity>`)인 점에 주의할 것. 컴파일이 안 되면
`JpaRepository`의 실제 시그니처를 확인하고 맞출 것.

단언은 `repo.stored`(엔티티)에 대고 하되, 읽기 쉽도록 `toDomain()`을 거쳐도 좋다.

```kotlin
package com.allfolio.fx

import com.allfolio.unifiedasset.infrastructure.entity.CashFlowEntity
import com.allfolio.unifiedasset.infrastructure.jpa.CashFlowJpaRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.application.port.KrwConversion
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class CashFlowRecomputeServiceTest {

    private val userId = UUID.randomUUID()

    private fun flow(
        currency: String = "USD",
        amount: String = "100",
        amountKrw: String = "140000",
        type: FlowType = FlowType.DEPOSIT,
        flowDate: LocalDate = LocalDate.of(2024, 5, 20),
    ) = CashFlow.reconstruct(
        id = UUID.randomUUID(), userId = userId, accountId = null, flowDate = flowDate,
        type = type, amount = BigDecimal(amount), currency = currency,
        amountKrw = BigDecimal(amountKrw), memo = null, createdAt = LocalDateTime.now(),
    )

    /** 요청한 날짜의 환율을 그대로 돌려주는 페이크. rate=null이면 "과거 환율 없음". */
    private fun converter(rate: String?, estimated: Boolean = rate == null) = object : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
            amount.multiply(BigDecimal("1400"))

        override fun toKrwOn(amount: BigDecimal, currency: String, date: LocalDate): KrwConversion =
            KrwConversion(
                amountKrw = amount.multiply(BigDecimal(rate ?: "1400")),
                rateDate = if (rate == null) null else date,
                estimated = estimated,
            )
    }

    // FakeRepo는 위 '위임 + 부분 오버라이드' 절의 것을 쓴다

    // 드라이런이 기본값인 게 이 기능의 안전장치 전부다. 저장이 한 번이라도 일어나면
    // 사용자가 보고서를 보기 전에 금융 이력이 바뀐다.
    @Test
    fun `드라이런은 저장하지 않는다`() {
        val repo = FakeRepo(listOf(flow(amountKrw = "140000")))
        val service = CashFlowRecomputeService(repo, converter("1300"))

        val summary = service.recompute(apply = false)

        assertThat(repo.stored).isEmpty()
        assertThat(summary.changed).isEqualTo(1)
    }

    @Test
    fun `apply면 그 날짜 환율로 저장한다`() {
        val repo = FakeRepo(listOf(flow(amount = "100", amountKrw = "140000")))
        val service = CashFlowRecomputeService(repo, converter("1300"))

        service.recompute(apply = true)

        assertThat(repo.stored).hasSize(1)
        assertThat(repo.stored[0].amountKrw).isEqualByComparingTo("130000")
    }

    // 재계산은 순수 함수라 이미 맞는 행은 자기 자신으로 계산된다.
    // 불필요한 UPDATE를 내면 두 번째 실행이 첫 번째와 다른 일을 하는 셈이 된다.
    @Test
    fun `값이 그대로면 저장하지 않는다`() {
        val repo = FakeRepo(listOf(flow(amount = "100", amountKrw = "130000")))
        val service = CashFlowRecomputeService(repo, converter("1300"))

        val summary = service.recompute(apply = true)

        assertThat(repo.stored).isEmpty()
        assertThat(summary.changed).isZero()
        assertThat(summary.unchanged).isEqualTo(1)
    }

    // 과거 환율이 없으면 현재 환율 근사가 그대로 유지된다. 그 행이 몇 건인지가
    // 보고서의 핵심이다 — 이걸 모르면 "다 고쳤다"고 착각한다.
    @Test
    fun `과거 환율이 없는 행은 추정치로 세고 값을 바꾸지 않는다`() {
        val repo = FakeRepo(listOf(flow(amount = "100", amountKrw = "140000")))
        val service = CashFlowRecomputeService(repo, converter(rate = null))

        val summary = service.recompute(apply = true)

        assertThat(summary.stillEstimated).isEqualTo(1)
        assertThat(repo.stored).isEmpty()
    }

    // amount_krw는 부호를 담지 않는다 — signedKrw()가 type에서 파생한다.
    // 쓰기 경로와 같은 toKrwOn을 쓰면 규약이 저절로 보존되므로, 부호를 손대는 코드가 없어야 한다.
    @Test
    fun `출금 행의 부호 규약이 보존된다`() {
        val repo = FakeRepo(listOf(flow(type = FlowType.WITHDRAWAL, amount = "100", amountKrw = "140000")))
        val service = CashFlowRecomputeService(repo, converter("1300"))

        service.recompute(apply = true)

        val saved = repo.stored.single().toDomain()
        assertThat(saved.amountKrw).isEqualByComparingTo("130000")   // 양수 크기
        assertThat(saved.signedKrw()).isEqualByComparingTo("-130000") // 부호는 type에서
    }

    // 같은 id·createdAt을 유지해야 JPA가 INSERT가 아니라 UPDATE로 처리한다.
    // 새 id를 만들면 원본이 남은 채 중복 행이 생겨 입출금이 두 배가 된다.
    @Test
    fun `id와 생성시각을 보존한다`() {
        val original = flow(amountKrw = "140000")
        val repo = FakeRepo(listOf(original))
        val service = CashFlowRecomputeService(repo, converter("1300"))

        service.recompute(apply = true)

        val saved = repo.stored.single().toDomain()
        assertThat(saved.id).isEqualTo(original.id)
        assertThat(saved.createdAt).isEqualTo(original.createdAt)
    }

    @Test
    fun `보고서에 변동 폭 상위가 담긴다`() {
        val repo = FakeRepo(listOf(
            flow(amount = "100", amountKrw = "140000"),    // −10,000
            flow(amount = "1000", amountKrw = "1400000"),  // −100,000
        ))
        val service = CashFlowRecomputeService(repo, converter("1300"))

        val summary = service.recompute(apply = false)

        assertThat(summary.topChanges.first().delta.abs()).isEqualByComparingTo("100000")
        assertThat(summary.totalDelta).isEqualByComparingTo("-110000")
    }
}
```

Run — 컴파일 실패를 확인한다:
```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests '*CashFlowRecomputeServiceTest*' --rerun-tasks --no-daemon
```

- [ ] **Step 2: 서비스를 만든다**

요구사항:

- `recompute(apply: Boolean): RecomputeSummary`
- `findNonKrwOrderByFlowDate()`로 읽고, 각 행에 `fxConverter.toKrwOn(amount, currency, flowDate)`
- `conversion.estimated`면 **값을 바꾸지 않고** `stillEstimated`만 센다.
  과거 환율이 없어 현재 환율로 근사한 값을 다시 써봐야 같은 근사치이고,
  UPDATE만 늘고 `amount_krw`가 오늘 환율로 또 갱신되어 오히려 나빠진다
- 값이 같으면(`compareTo == 0`) 저장하지 않고 `unchanged`
- 다르면 `CashFlow.reconstruct(...)`로 **같은 `id`·`createdAt`**을 유지한 객체를 만들어,
  `apply`일 때만 `save`
- 요약: `scanned`, `changed`, `unchanged`, `stillEstimated`, `totalDelta`,
  `byCurrency: Map<String, CurrencyDelta>`, `topChanges: List<ChangeRow>` (변동 폭 절댓값 상위 20)
- `ChangeRow`에 `id`·`flowDate`·`currency`·`before`·`after`·`delta`

**`@Transactional`을 붙이지 않는다.** 전 행을 한 트랜잭션에 담으면 Neon 커넥션을 오래 쥐고,
무료 단일 인스턴스에서 다른 요청이 굶는다. `save`는 Spring Data 리포지토리 레벨에서 이미
트랜잭션이라 행 단위 원자성은 확보된다. `FxRateBackfillService`가 같은 판단을 한 이유를 볼 것.

KDoc에 남길 것: **왜 추정치 행을 건너뛰는가**, **왜 전수 재계산이 안전한가**(순수 함수·멱등),
**왜 `apply` 기본값이 없는가**(호출자가 매번 명시해야 한다).

- [ ] **Step 3: 통과 확인 후 커밋**

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests '*CashFlowRecomputeServiceTest*' --rerun-tasks --no-daemon
```
Expected: PASS (7건)

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/CashFlowRecomputeService.kt \
        allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/CashFlowRecomputeServiceTest.kt
git commit -m "feat(fx): 현금흐름 KRW 환산액 소급 재계산 — 드라이런 기본"
```

---

### Task 3: 변이 테스트

이 계획 전체가 "재계산이 멱등하고 안전하다"는 전제 위에 서 있다. 그 전제가 테스트로 지켜지는지 본다.
AF-99·AF-100·AF-101·AF-103에서 계획이 여러 번 틀렸고 전부 이 절차가 잡았다.

**Files:** 임시 변형 후 되돌린다. 커밋하지 않는다.

**`git diff`로는 되돌림을 검증할 수 없다** — 새로 만든 파일은 아직 추적되지 않아 diff가 항상 비어 보인다.
변형 전에 `shasum -a 256`으로 스냅샷을 떠서 대조할 것.

- [ ] **Step 1: 변이 A — 드라이런에서도 저장한다**

`apply` 조건을 없애고 항상 `save`.
Expected: **FAIL** — `드라이런은 저장하지 않는다`

- [ ] **Step 2: 변이 B — 추정치 행도 덮어쓴다**

`estimated` 분기를 없애고 그대로 저장.
Expected: **FAIL** — `과거 환율이 없는 행은 추정치로 세고 값을 바꾸지 않는다`

- [ ] **Step 3: 변이 C — 새 id를 만든다**

`reconstruct`에 `UUID.randomUUID()`를 넘긴다.
Expected: **FAIL** — `id와 생성시각을 보존한다`

이게 제일 위험한 변이다. 통과해버리면 **원본이 남은 채 중복 행이 생겨 입출금이 두 배**가 된다.

- [ ] **Step 4: 변이 D — 값이 같아도 저장한다**

`compareTo` 비교를 없앤다.
Expected: **FAIL** — `값이 그대로면 저장하지 않는다`

- [ ] **Step 5: 자신만의 변이 두 개 이상**

살펴볼 곳: `totalDelta`의 부호, `topChanges` 정렬 방향(절댓값인지 부호값인지),
`byCurrency` 집계, `amountKrw` 대신 `amount`를 넘기는 실수, 요약 카운터의 중복 집계.

**살아남은 변이가 있으면 그게 진짜 발견이다.** 정확히 보고할 것.

- [ ] **Step 6: 전부 되돌리고 해시로 확인 — 커밋할 것 없음**

---

### Task 4: 어드민 엔드포인트 + 전 모듈 검증 + PR

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/api/admin/FxRateAdminController.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/api/admin/` (기존 관례를 따를 것)

- [ ] **Step 1: 엔드포인트를 추가한다**

`POST /api/admin/fx/cashflow-recompute?apply=false` → `ResponseEntity<RecomputeSummary>`

- `@RequestParam(defaultValue = "false") apply: Boolean`.
  **기본값이 `false`인 게 핵심이다** — 위험한 방향이 기본이 되면 안 된다.
  이 컨트롤러의 백필 엔드포인트가 `currency`에 기본값을 두지 않은 것과 같은 계열의 판단이되,
  여기서는 안전한 쪽이 명확하므로 기본값을 둔다
- KDoc에 **실행 순서**를 적을 것: ECOS 키 등록 → 재배포 → **백필 먼저** → 드라이런 → apply.
  `fx_rate_daily`가 비어 있으면 재계산이 전부 "추정치 유지"로 끝나고 아무것도 안 고쳐진다
- 스케줄러 트리거는 만들지 않는다. 일회성 운영 작업이지 주기 작업이 아니다

- [ ] **Step 2: 전 모듈 검증**

```bash
cd allfolio-backend && ./gradlew test --rerun-tasks --no-daemon
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 변경 범위 확인**

```bash
git diff --stat main...HEAD
```
**`SyncAccountUseCase`·`RecordCashFlowUseCase`·`RecordInternalFlowUseCase`가 diff에 있으면 범위가 샌 것이다** —
이 계획은 쓰기 경로를 건드리지 않는다(그건 2단계 `fx_estimated` 컬럼과 함께 간다).

- [ ] **Step 4: 푸시하고 PR**

```bash
git push -u origin feat/af-100-cashflow-recompute
```

PR 본문에 반드시 담을 것:
- **머지해도 아무것도 안 고쳐진다** — 어드민이 직접 호출해야 하고, 그 전에 백필이 돌아야 한다
- 실행 순서 4단계
- 드라이런 보고서를 보고 나서 `apply=true`

- [ ] **Step 5: CI 확인**

```bash
gh pr checks --watch
```
**실패한 필수 체크를 `--admin`으로 우회하지 않는다.**

---

## 2단계 (이 계획에 없음) — `fx_rate_date`·`fx_estimated` 컬럼

필요해지면 별도 계획으로 만든다. 그때 함께 가야 하는 것:

- 마이그레이션 2컬럼
- `CashFlowEntity`·`CashFlow` 도메인(`create`·`reconstruct`·`transferPair`·`fxPair`)
- 쓰기 경로 셋 — **여기를 빼먹으면 새 행이 즉시 NULL로 들어와 원점이 된다**
- `CashFlow`를 만드는 모든 테스트

값어치가 생기는 시점: 화면에 "이 값은 추정치"를 표기하고 싶을 때(AF-105와 같은 계열),
또는 ECOS·Upbit가 더 과거를 확보해 추정치만 골라 다시 돌리고 싶을 때.

## 완료 후 보고할 것

- PR 링크
- Task 3 변이 결과 표 (살아남은 것이 있는지)
- **사용자 조치**: ECOS 키 등록 → 재배포 → 백필 → 드라이런 → apply. 순서가 중요하다는 점
