# R-06 환전/계좌간이체 데이터모델 Phase 1 (BE) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 환전(FX)·계좌간이체(TRANSFER)를 1급 현금흐름으로 모델링(FlowType 확장·linkId·기록 API), 수익률/현금흐름 리포트가 내부이동을 외부흐름으로 오분류하지 않게 한다. BE만(FE는 Phase 2).

**Architecture:** `FlowType`에 내부 4종 추가 + `CashFlow.linkId`로 두 레그 페어링. `signedKrw()` 내부→0. `RecordInternalFlowUseCase`가 페어 레그를 원자적 저장. 리포트는 내부유형을 외부흐름 뷰에서 제외.

**Tech Stack:** Kotlin/Spring(unified-asset), JPA, JUnit.

Spec: `docs/superpowers/specs/2026-07-31-cashflow-internal-flows-design.md`

> 모든 gradle 명령은 `cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend` 에서 실행.

---

## Task 1: 도메인 — FlowType 확장 + linkId + signedKrw + 팩토리

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/domain/cashflow/CashFlow.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/domain/cashflow/CashFlowTest.kt`

- [ ] **Step 1: 실패 테스트 작성** — `CashFlowTest.kt`:

```kotlin
package com.allfolio.unifiedasset.domain.cashflow

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class CashFlowTest {
    private val user = UUID.randomUUID()
    private val a1 = UUID.randomUUID()
    private val a2 = UUID.randomUUID()
    private val date = LocalDate.of(2026, 6, 10)

    @Test
    fun `FlowType 분류 헬퍼`() {
        assertThat(FlowType.TRANSFER_IN.isInternal()).isTrue
        assertThat(FlowType.FX_OUT.isInternal()).isTrue
        assertThat(FlowType.DEPOSIT.isInternal()).isFalse
        assertThat(FlowType.WITHDRAWAL.isInternal()).isFalse
        assertThat(FlowType.DEPOSIT.isInflow()).isTrue
        assertThat(FlowType.TRANSFER_IN.isInflow()).isTrue
        assertThat(FlowType.WITHDRAWAL.isOutflow()).isTrue
        assertThat(FlowType.FX_OUT.isOutflow()).isTrue
    }

    @Test
    fun `signedKrw는 외부흐름만 부호를 갖고 내부는 0`() {
        fun cf(t: FlowType) = CashFlow.create(user, a1, date, t, BigDecimal.TEN, "KRW", BigDecimal("1000"), null)
        assertThat(cf(FlowType.DEPOSIT).signedKrw()).isEqualByComparingTo("1000")
        assertThat(cf(FlowType.WITHDRAWAL).signedKrw()).isEqualByComparingTo("-1000")
        assertThat(cf(FlowType.TRANSFER_IN).signedKrw()).isEqualByComparingTo("0")
        assertThat(cf(FlowType.TRANSFER_OUT).signedKrw()).isEqualByComparingTo("0")
        assertThat(cf(FlowType.FX_IN).signedKrw()).isEqualByComparingTo("0")
        assertThat(cf(FlowType.FX_OUT).signedKrw()).isEqualByComparingTo("0")
    }

    @Test
    fun `transferPair는 동일 linkId로 OUT@from IN@to 2레그를 만든다`() {
        val (out, inn) = CashFlow.transferPair(user, a1, a2, date, BigDecimal("500"), "KRW", BigDecimal("500"), "이체")
        assertThat(out.type).isEqualTo(FlowType.TRANSFER_OUT)
        assertThat(out.accountId).isEqualTo(a1)
        assertThat(inn.type).isEqualTo(FlowType.TRANSFER_IN)
        assertThat(inn.accountId).isEqualTo(a2)
        assertThat(out.linkId).isNotNull
        assertThat(out.linkId).isEqualTo(inn.linkId)
        assertThat(out.amount).isEqualByComparingTo("500")
        assertThat(inn.amount).isEqualByComparingTo("500")
    }

    @Test
    fun `transferPair 같은 계좌면 예외`() {
        assertThatThrownBy { CashFlow.transferPair(user, a1, a1, date, BigDecimal.TEN, "KRW", BigDecimal.TEN, null) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `fxPair는 동일 linkId로 FX_OUT fromCcy FX_IN toCcy 2레그를 만든다`() {
        val (out, inn) = CashFlow.fxPair(user, a1, date,
            BigDecimal("1300000"), "KRW", BigDecimal("1300000"),
            BigDecimal("1000"), "USD", BigDecimal("1300000"), "환전")
        assertThat(out.type).isEqualTo(FlowType.FX_OUT)
        assertThat(out.currency).isEqualTo("KRW")
        assertThat(inn.type).isEqualTo(FlowType.FX_IN)
        assertThat(inn.currency).isEqualTo("USD")
        assertThat(out.linkId).isEqualTo(inn.linkId)
        assertThat(inn.amount).isEqualByComparingTo("1000")
    }

    @Test
    fun `fxPair 같은 통화면 예외`() {
        assertThatThrownBy {
            CashFlow.fxPair(user, a1, date, BigDecimal.TEN, "KRW", BigDecimal.TEN, BigDecimal.TEN, "krw", BigDecimal.TEN, null)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew :unified-asset:test --tests "*CashFlowTest*"` → Expected: compile FAIL(새 API 미정의).

- [ ] **Step 3: 구현** — `CashFlow.kt` 수정:
  1. `FlowType` enum을 spec §3.1 형태로 교체(6값 + isInternal/isInflow/isOutflow 멤버 함수).
  2. `CashFlow` 클래스 private 생성자에 `val linkId: UUID?` 추가(예: `createdAt` 앞 또는 뒤 — 뒤에 추가 권장).
  3. `signedKrw()`를 spec §3.1 `when` 버전으로 교체.
  4. `create(...)`에 마지막 파라미터 `linkId: UUID? = null` 추가하고 생성자 호출에 전달.
  5. `reconstruct(...)`에 `linkId: UUID? = null` 파라미터 추가하고 전달.
  6. companion에 `transferPair`·`fxPair` 추가(spec §3.1 코드). 통화는 create가 uppercase 처리하므로 require 비교 시 `fromCurrency.uppercase() != toCurrency.uppercase()` 사용.
  주의: `create`의 기존 `require(amount > ZERO)`는 유지. transferPair/fxPair는 create를 호출.

- [ ] **Step 4: 통과 확인** — Run: `./gradlew :unified-asset:test --tests "*CashFlowTest*"` → Expected: PASS.

- [ ] **Step 5: 커밋**
```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/domain/cashflow/CashFlow.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/domain/cashflow/CashFlowTest.kt
git commit -m "feat(cashflow): extend FlowType with internal types + linkId + pair factories (TDD)"
```

---

## Task 2: 영속화 — 엔티티 link_id + init.sql + 마이그레이션

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/entity/CashFlowEntity.kt`
- Modify: `allfolio-backend/infra/postgres/init.sql`
- Create: `docs/superpowers/migrations/2026-07-31-cashflow-link-id.sql`

- [ ] **Step 1: 엔티티 수정** — `CashFlowEntity`에 `@Column(name = "link_id") val linkId: UUID?` 추가(생성자 끝, createdAt 앞/뒤). `toDomain()`은 `CashFlow.reconstruct(... , linkId = linkId)`로 전달(reconstruct의 linkId 파라미터 위치에 맞춰). `from(domain)`은 `linkId = domain.linkId` 매핑.

- [ ] **Step 2: init.sql** — `cash_flow` CREATE TABLE에 `memo` 뒤(또는 created_at 앞)에 `link_id UUID,` 라인 추가. (신규 DB 반영용.)

- [ ] **Step 3: 마이그레이션 파일** — `docs/superpowers/migrations/2026-07-31-cashflow-link-id.sql`:
```sql
-- R-06 환전/계좌간이체 Phase 1 — 운영 Neon 1회성 (백엔드 배포 "전" 실행)
-- 추가형·멱등·무해(신규 nullable 컬럼).
ALTER TABLE cash_flow ADD COLUMN IF NOT EXISTS link_id UUID;
```

- [ ] **Step 4: 컴파일 확인** — Run: `./gradlew :unified-asset:compileKotlin` → Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: 커밋**
```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/entity/CashFlowEntity.kt \
        allfolio-backend/infra/postgres/init.sql \
        docs/superpowers/migrations/2026-07-31-cashflow-link-id.sql
git commit -m "feat(cashflow): persist link_id column + migration"
```

---

## Task 3: RecordInternalFlowUseCase + 테스트

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/RecordInternalFlowUseCase.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/RecordInternalFlowUseCaseTest.kt`

- [ ] **Step 1: 실패 테스트** — `RecordInternalFlowUseCaseTest.kt`. Fake `CashFlowRepository`(저장분을 리스트에 축적)와 Fake `FxConverter`(KRW=amount, else amount×1000) 사용:

```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class RecordInternalFlowUseCaseTest {
    private val saved = mutableListOf<CashFlow>()
    private val repo = object : CashFlowRepository {
        override fun save(cashFlow: CashFlow): CashFlow { saved.add(cashFlow); return cashFlow }
        override fun findById(id: UUID): CashFlow? = null
        override fun findByUserIdAndPeriod(userId: UUID, from: LocalDate, to: LocalDate) = emptyList<CashFlow>()
        override fun findByUserId(userId: UUID) = emptyList<CashFlow>()
        override fun delete(id: UUID) {}
    }
    private val fx = object : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String) =
            if (currency.uppercase() == "KRW") amount else amount * BigDecimal("1300")
    }
    private val uc = RecordInternalFlowUseCase(repo, fx)
    private val user = UUID.randomUUID()
    private val a1 = UUID.randomUUID(); private val a2 = UUID.randomUUID()
    private val date = LocalDate.of(2026, 6, 10)

    @Test
    fun `recordTransfer는 2레그를 linkId 공유로 저장한다`() {
        val legs = uc.recordTransfer(user, a1, a2, date, BigDecimal("500"), "KRW", "이체")
        assertThat(legs).hasSize(2)
        assertThat(saved).hasSize(2)
        assertThat(legs.map { it.type }).containsExactlyInAnyOrder(FlowType.TRANSFER_OUT, FlowType.TRANSFER_IN)
        assertThat(legs[0].linkId).isEqualTo(legs[1].linkId)
        assertThat(legs.all { it.amountKrw.compareTo(BigDecimal("500")) == 0 }).isTrue
    }

    @Test
    fun `recordFx는 레그별 amountKrw로 2레그를 저장한다`() {
        val legs = uc.recordFx(user, a1, date, BigDecimal("1300"), "KRW", BigDecimal("1"), "USD", "환전")
        val out = legs.first { it.type == FlowType.FX_OUT }
        val inn = legs.first { it.type == FlowType.FX_IN }
        assertThat(out.amountKrw).isEqualByComparingTo("1300")     // KRW 1300 → 1300
        assertThat(inn.amountKrw).isEqualByComparingTo("1300")     // USD 1 × 1300 → 1300
        assertThat(out.linkId).isEqualTo(inn.linkId)
    }

    @Test
    fun `음수-같은계좌-같은통화는 예외`() {
        assertThatThrownBy { uc.recordTransfer(user, a1, a2, date, BigDecimal("-1"), "KRW", null) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { uc.recordTransfer(user, a1, a1, date, BigDecimal("1"), "KRW", null) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { uc.recordFx(user, a1, date, BigDecimal("1"), "KRW", BigDecimal("1"), "KRW", null) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew :unified-asset:test --tests "*RecordInternalFlowUseCaseTest*"` → Expected: compile FAIL.

- [ ] **Step 3: 구현** — `RecordInternalFlowUseCase.kt`(spec §3.3). import: CashFlowRepository, FxConverter, CashFlow, `org.springframework.stereotype.Service`, `org.springframework.transaction.annotation.Transactional`, BigDecimal, LocalDate, UUID. `BigDecimal.ZERO` 비교 사용.

- [ ] **Step 4: 통과 확인** — Run: `./gradlew :unified-asset:test --tests "*RecordInternalFlowUseCaseTest*"` → Expected: PASS.

- [ ] **Step 5: 커밋**
```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/RecordInternalFlowUseCase.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/RecordInternalFlowUseCaseTest.kt
git commit -m "feat(cashflow): RecordInternalFlowUseCase for transfer/fx pairs (TDD)"
```

---

## Task 4: API — CashFlowController transfer/fx 엔드포인트

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/api/CashFlowController.kt`

- [ ] **Step 1: 구현** — 컨트롤러에 `RecordInternalFlowUseCase` 주입 추가(생성자 파라미터). `CashFlowResponse`에 `val linkId: UUID?` 필드 추가하고 `toResponse()`에 `linkId = linkId` 매핑. 엔드포인트 2개 추가:

```kotlin
    data class TransferRequest(
        val fromAccountId: UUID, val toAccountId: UUID, val flowDate: LocalDate,
        val amount: BigDecimal, val currency: String, val memo: String?,
    )
    data class FxRequest(
        val accountId: UUID?, val flowDate: LocalDate,
        val fromAmount: BigDecimal, val fromCurrency: String,
        val toAmount: BigDecimal, val toCurrency: String, val memo: String?,
    )

    @PostMapping("/transfer")
    fun transfer(@RequestHeader("X-User-Id") userId: UUID, @RequestBody req: TransferRequest): List<CashFlowResponse> =
        recordInternalFlow.recordTransfer(userId, req.fromAccountId, req.toAccountId, req.flowDate, req.amount, req.currency, req.memo)
            .map { it.toResponse() }

    @PostMapping("/fx")
    fun fx(@RequestHeader("X-User-Id") userId: UUID, @RequestBody req: FxRequest): List<CashFlowResponse> =
        recordInternalFlow.recordFx(userId, req.accountId, req.flowDate, req.fromAmount, req.fromCurrency, req.toAmount, req.toCurrency, req.memo)
            .map { it.toResponse() }
```
(생성자: `private val recordInternalFlow: RecordInternalFlowUseCase` 추가. import 추가. `IllegalArgumentException`은 스프링 기본 400 매핑이 아닐 수 있으나, 기존 프로젝트 관례를 따름 — 별도 핸들러 추가하지 않음. 검증 실패는 require로 400/500 처리. 기존 record()의 require도 동일 패턴이므로 그대로 둠.)

- [ ] **Step 2: 컴파일 + 전체 테스트** — Run: `./gradlew :unified-asset:compileKotlin :unified-asset:test` → Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: 커밋**
```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/api/CashFlowController.kt
git commit -m "feat(cashflow): add /transfer and /fx endpoints + linkId in response"
```

---

## Task 5: CashflowReportGenerator 내부흐름 제외 + 테스트

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/CashflowReportGenerator.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/CashflowReportGeneratorTest.kt`

- [ ] **Step 1: 실패 테스트 확장** — 기존 테스트 스타일(파일 확인) 맞춰, 내부유형(TRANSFER_IN/OUT, FX_IN/OUT) flow를 포함시켰을 때 (a) byType/deposit/withdrawal 합이 불변, (b) details에 "출금"으로 잘못 안 들어가는지 검증. 기존 `flowOn(date, type, krw)` 헬퍼 재사용(FlowType 인자를 받음). 예:
```kotlin
    @Test
    fun `내부유형(이체·환전)은 외부흐름 집계·상세에서 제외된다`() {
        val flows = listOf(
            deposit(3, "1000000"),
            withdrawal(5, "200000"),
            flowOn(LocalDate.of(2026,6,10), FlowType.TRANSFER_OUT, "5000000"),
            flowOn(LocalDate.of(2026,6,10), FlowType.TRANSFER_IN,  "5000000"),
            flowOn(LocalDate.of(2026,6,11), FlowType.FX_OUT, "1300000"),
            flowOn(LocalDate.of(2026,6,11), FlowType.FX_IN,  "1300000"),
        )
        val body = mapper.readTree(generator(flows).generate(userId, period).bodyJson)
        // byType 유입/유출에 이체·환전 미포함 (입금 1,000,000 / 출금 200,000 만)
        val types = body["byType"].associate { it["type"].asText() to it["amount"].asDouble() }
        assertEquals(1000000.0, types["입금"] ?: 0.0, 0.01)
        assertEquals(-200000.0, types["출금"] ?: 0.0, 0.01)
        assertTrue(types.keys.none { it.contains("이체") || it.contains("환전") })
        // details에 내부유형 레그가 "출금"으로 잘못 들어가지 않음: 출금 행은 1건(withdrawal)만
        val outRows = body["details"].filter { it["type"].asText() == "출금" }
        assertEquals(1, outRows.size)
    }
```
(주의: `generator(flows)`·`deposit`/`withdrawal`/`flowOn`·assertion 스타일은 기존 파일과 일치시킬 것. 기존 파일은 JUnit5 assertEquals/assertTrue 사용.)

- [ ] **Step 2: 실패 확인** — Run: `./gradlew :unified-asset:test --tests "*CashflowReportGeneratorTest*"` → Expected: FAIL(내부 레그가 "출금"으로 details에 들어가 outRows>1).

- [ ] **Step 3: 구현** — `CashflowReportGenerator.generate`:
  - `flowRows` 정의부(현재 `flows.map { ... }`)를 `flows.filter { !it.type.isInternal() }.map { ... }`로 변경.
  - `SpecialTransactionCalculator.build(flows, ...)` 호출을 `SpecialTransactionCalculator.build(flows.filter { !it.type.isInternal() }, ...)`로 변경.
  - (netCash·byType·monthly는 정확 타입매칭이라 무변경.)

- [ ] **Step 4: 통과 확인** — Run: `./gradlew :unified-asset:test --tests "*CashflowReportGeneratorTest*"` → Expected: PASS.

- [ ] **Step 5: 커밋**
```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/CashflowReportGenerator.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/CashflowReportGeneratorTest.kt
git commit -m "fix(cashflow): exclude internal flows from external-flow report views"
```

---

## Task 6: 백엔드 회귀

- [ ] **Step 1: 전체 테스트** — Run: `./gradlew :unified-asset:test` → Expected: BUILD SUCCESSFUL. (특히 기존 ReturnsReportGeneratorTest·CashflowReportGeneratorTest·SpecialTransactionCalculatorTest 불변 확인.)
- [ ] **Step 2: 앱 컴파일** — Run: `./gradlew :backend-app:compileKotlin` → Expected: BUILD SUCCESSFUL(DI 무결성).
- [ ] **Step 3: (실패 시) 수정 후 재실행.**

---

## Self-Review 체크
- [ ] FlowType 6값·헬퍼·signedKrw(내부0)·transferPair/fxPair(linkId 공유·검증) 커버.
- [ ] `create`/`reconstruct` linkId 기본값 null → 기존 호출부 전부 호환.
- [ ] 엔티티 link_id ↔ 도메인 linkId 왕복 매핑.
- [ ] 리포트 외부흐름 뷰에서 내부유형 제외(details·special). netCash 불변.
- [ ] 마이그레이션 파일 추가형·멱등. init.sql 반영.

## Rollout
- **스키마 변경(link_id)** → 배포 전 `docs/superpowers/migrations/2026-07-31-cashflow-link-id.sql` Neon 수동 실행(승인 게이트). 이후 main 병합.
- Phase 2(후속): FE 기록 폼·환전/이체 전용 리포트 섹션·워터폴.
