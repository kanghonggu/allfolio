# TWR/MWR 수익률 계산 엔진 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** cash_flow 원장 + 순수 TWR/MWR 계산 엔진 + RETURNS 리포트 생성기 — #32 프레임에 첫 생성기를 꽂아 `generate` API를 실동작시킨다.

**Architecture:** 순수 계산(`ReturnsCalculator`)은 report 모듈 domain에 두고 스프링·DB와 격리한다. 현금흐름 원장(도메인·JPA·API)과 생성기(`ReturnsReportGenerator`, `ReportBodyGenerator` 구현)는 unified-asset에 둔다(FxConverter·performance_daily 접근 관례). 플로우 미기록 사용자는 플로우=0으로 자연 동작.

**Tech Stack:** Kotlin 1.9.25 / Spring Boot 3.2.5 / JdbcTemplate(performance_daily) + JPA(cash_flow) / JUnit5

**Spec:** `docs/superpowers/specs/2026-07-16-returns-engine-design.md`

---

### Task 1: DDL — cash_flow + 계정 파기 연결 (TDD)

**Files:**
- Modify: `allfolio-backend/infra/postgres/init.sql` (끝에 추가)
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/account/AccountPurgeRepository.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/account/AccountDeletionService.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/account/AccountDeletionServiceTest.kt`

- [ ] **Step 1: init.sql 끝에 DDL 추가**

```sql
-- ── cash_flow ──────────────────────────────────────────────────
-- 입출금 원장 (R1 #33): TWR/MWR 계산의 현금흐름 조정 입력. KRW 환산은 기록 시점 고정
CREATE TABLE IF NOT EXISTS cash_flow (
    id           UUID           PRIMARY KEY,
    user_id      UUID           NOT NULL,
    account_id   UUID,
    flow_date    DATE           NOT NULL,
    flow_type    VARCHAR(20)    NOT NULL,
    amount       NUMERIC(30,10) NOT NULL,
    currency     VARCHAR(10)    NOT NULL,
    amount_krw   NUMERIC(30,10) NOT NULL,
    memo         VARCHAR(500),
    created_at   TIMESTAMP      NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_cash_flow_user_date
    ON cash_flow (user_id, flow_date);
```

- [ ] **Step 2: AccountDeletionServiceTest에 deleteCashFlow 검증 추가 (RED)**

두 테스트에 각각 아래 한 줄 추가 (deleteReportArchive 검증 다음 위치):

```kotlin
ordered.verify(repo).deleteCashFlow(userId)   // 첫 테스트
verify(repo).deleteCashFlow(userId)           // 둘째 테스트
```

Run: `cd allfolio-backend && ./gradlew :backend-app:compileTestKotlin` → Expected: 컴파일 실패

- [ ] **Step 3: 구현 (GREEN)**

AccountPurgeRepository에 (deleteReportArchive 아래):

```kotlin
@Modifying
@Query("DELETE FROM cash_flow WHERE user_id = :userId", nativeQuery = true)
fun deleteCashFlow(userId: UUID): Int
```

AccountDeletionService.purge에 `purgeRepository.deleteReportArchive(userId)` 다음 줄:

```kotlin
purgeRepository.deleteCashFlow(userId)
```

Run: `./gradlew :backend-app:test --tests '*AccountDeletionServiceTest*'` → Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add allfolio-backend/infra/postgres/init.sql allfolio-backend/backend-app
git commit -m "feat(returns): cash_flow 원장 DDL + 계정 파기 연결"
```

### Task 2: ReturnsCalculator 순수 엔진 (report 모듈, TDD)

**Files:**
- Create: `allfolio-backend/report/src/main/kotlin/com/allfolio/report/domain/returns/ReturnsCalculator.kt` (NavPoint·Flow·PeriodReturns 포함)
- Test: `allfolio-backend/report/src/test/kotlin/com/allfolio/report/domain/returns/ReturnsCalculatorTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성 (RED)**

```kotlin
package com.allfolio.report.domain.returns

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class ReturnsCalculatorTest {

    private fun bd(v: String) = BigDecimal(v)
    private fun d(day: Int) = LocalDate.of(2026, 6, day)
    private fun assertClose(expected: String, actual: BigDecimal?, eps: String = "0.0001") {
        requireNotNull(actual) { "expected $expected but was null" }
        assertTrue((actual - bd(expected)).abs() < bd(eps)) { "expected $expected but was $actual" }
    }

    @Test
    fun `simple growth without flows`() {
        val result = ReturnsCalculator.calculate(
            navSeries = listOf(NavPoint(d(1), bd("1000")), NavPoint(d(30), bd("1100"))),
            flows = emptyList(),
            from = d(1), to = d(30),
        )
        assertClose("0.1", result.twr)
        assertClose("0.1", result.mwr, eps = "0.001")
        assertClose("100", result.investmentPnl)
        assertEquals(BigDecimal.ZERO, result.netFlow)
    }

    @Test
    fun `deposit is not counted as return in TWR`() {
        // 1000 → (6/15 입금 1000, 당일 NAV 2000 관측) → 6/30 NAV 2200 (전액 +10% 성장)
        val result = ReturnsCalculator.calculate(
            navSeries = listOf(
                NavPoint(d(1), bd("1000")),
                NavPoint(d(15), bd("2000")),
                NavPoint(d(30), bd("2200")),
            ),
            flows = listOf(Flow(d(15), bd("1000"))),
            from = d(1), to = d(30),
        )
        // 구간1: (2000-1000-1000)/(1000+1000)=0, 구간2: 200/2000=0.1 → TWR=0.1
        assertClose("0.1", result.twr)
        assertClose("1000", result.netFlow)
        assertClose("200", result.investmentPnl)   // 2200-1000-1000
    }

    @Test
    fun `withdrawal adjusts TWR upward not downward`() {
        // 1000 → 6/15 출금 500 (당일 NAV 550 관측: 500 출금 후 +10%) → 6/30 NAV 605
        val result = ReturnsCalculator.calculate(
            navSeries = listOf(
                NavPoint(d(1), bd("1000")),
                NavPoint(d(15), bd("550")),
                NavPoint(d(30), bd("605")),
            ),
            flows = listOf(Flow(d(15), bd("-500"))),
            from = d(1), to = d(30),
        )
        // 구간1: (550-1000+500)/1000=0.05, 구간2: 55/550=0.1 → 1.05*1.1-1=0.155
        assertClose("0.155", result.twr)
        assertClose("-500", result.netFlow)
    }

    @Test
    fun `mwr reflects deposit timing while twr does not`() {
        // 큰 입금 직후 하락: TWR(시간가중)은 완만, MWR(금액가중)은 더 나쁨
        val result = ReturnsCalculator.calculate(
            navSeries = listOf(
                NavPoint(d(1), bd("1000")),
                NavPoint(d(2), bd("1100")),     // +10%
                NavPoint(d(3), bd("11100")),    // 입금 10000 반영
                NavPoint(d(30), bd("9990")),    // -10%
            ),
            flows = listOf(Flow(d(3), bd("10000"))),
            from = d(1), to = d(30),
        )
        requireNotNull(result.twr); requireNotNull(result.mwr)
        assertTrue(result.mwr!! < result.twr!!) { "mwr=${result.mwr} should be worse than twr=${result.twr}" }
    }

    @Test
    fun `single observation returns nulls`() {
        val result = ReturnsCalculator.calculate(
            navSeries = listOf(NavPoint(d(1), bd("1000"))),
            flows = emptyList(),
            from = d(1), to = d(30),
        )
        assertNull(result.twr)
        assertNull(result.mwr)
    }

    @Test
    fun `xirr converges to known answer`() {
        // 1년 정확히: 1000 → 1100, 플로우 없음 → 연율=기간수익률=10%
        val result = ReturnsCalculator.calculate(
            navSeries = listOf(
                NavPoint(LocalDate.of(2025, 6, 30), bd("1000")),
                NavPoint(LocalDate.of(2026, 6, 30), bd("1100")),
            ),
            flows = emptyList(),
            from = LocalDate.of(2025, 6, 30), to = LocalDate.of(2026, 6, 30),
        )
        assertClose("0.1", result.mwr, eps = "0.001")
    }

    @Test
    fun `decomposition identity holds`() {
        val result = ReturnsCalculator.calculate(
            navSeries = listOf(
                NavPoint(d(1), bd("1000")),
                NavPoint(d(15), bd("2000")),
                NavPoint(d(30), bd("2200")),
            ),
            flows = listOf(Flow(d(15), bd("1000"))),
            from = d(1), to = d(30),
        )
        // endNav = startNav + netFlow + investmentPnl
        assertClose("2200", result.startNav!! + result.netFlow + result.investmentPnl!!)
    }
}
```

Run: `./gradlew :report:test --tests '*ReturnsCalculatorTest*'` → Expected: 컴파일 실패

- [ ] **Step 2: 구현 (GREEN)**

```kotlin
package com.allfolio.report.domain.returns

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.pow

data class NavPoint(val date: LocalDate, val nav: BigDecimal)

/** amountKrw: 입금 양수, 출금 음수 */
data class Flow(val date: LocalDate, val amountKrw: BigDecimal)

data class PeriodReturns(
    val twr: BigDecimal?,
    val mwr: BigDecimal?,
    val startNav: BigDecimal?,
    val endNav: BigDecimal?,
    val netFlow: BigDecimal,
    val investmentPnl: BigDecimal?,
)

/**
 * TWR/MWR 수익률 계산 — abor npsRor의 NAV+현금흐름 조정 방식 이식.
 * 순수 함수: 스프링·DB 무관. NAV 시계열은 구멍(미관측일)을 허용하며
 * 관측일 사이 "구간" 단위로 체인링킹한다.
 */
object ReturnsCalculator {

    private val MC = MathContext(20, RoundingMode.HALF_UP)

    fun calculate(navSeries: List<NavPoint>, flows: List<Flow>, from: LocalDate, to: LocalDate): PeriodReturns {
        val series = navSeries.filter { it.date in from..to }.sortedBy { it.date }
        val periodFlows = flows.filter { it.date in from..to }
        val netFlow = periodFlows.fold(BigDecimal.ZERO) { acc, f -> acc + f.amountKrw }

        if (series.size < 2) {
            return PeriodReturns(
                twr = null, mwr = null,
                startNav = series.firstOrNull()?.nav, endNav = series.lastOrNull()?.nav,
                netFlow = netFlow, investmentPnl = null,
            )
        }

        val startNav = series.first().nav
        val endNav = series.last().nav
        // 분해는 기초 관측 이후~기말 관측까지의 플로우만 반영해야 항등식이 성립한다
        val effectiveFlows = periodFlows.filter { it.date > series.first().date && it.date <= series.last().date }
        val effectiveNet = effectiveFlows.fold(BigDecimal.ZERO) { acc, f -> acc + f.amountKrw }

        return PeriodReturns(
            twr = twr(series, effectiveFlows),
            mwr = xirrPeriodReturn(series.first(), series.last(), effectiveFlows),
            startNav = startNav,
            endNav = endNav,
            netFlow = effectiveNet,
            investmentPnl = endNav - startNav - effectiveNet,
        )
    }

    /** 구간별 r_i = (NAV_i − NAV_{i−1} − 순플로우_i) / (NAV_{i−1} + 입금_i) 체인링킹 */
    private fun twr(series: List<NavPoint>, flows: List<Flow>): BigDecimal {
        var product = BigDecimal.ONE
        for (i in 1 until series.size) {
            val prev = series[i - 1]
            val cur = series[i]
            val window = flows.filter { it.date > prev.date && it.date <= cur.date }
            val net = window.fold(BigDecimal.ZERO) { acc, f -> acc + f.amountKrw }
            val inflow = window.filter { it.amountKrw > BigDecimal.ZERO }
                .fold(BigDecimal.ZERO) { acc, f -> acc + f.amountKrw }
            val denominator = prev.nav + inflow
            // 전액 출금 후 재개 등 분모≤0 구간은 수익률 판단 불가 — r=0으로 건너뜀 (v1 단순화)
            if (denominator <= BigDecimal.ZERO) continue
            val r = (cur.nav - prev.nav - net).divide(denominator, MC)
            product = product.multiply(BigDecimal.ONE + r, MC)
        }
        return product - BigDecimal.ONE
    }

    /** XIRR(연율)을 풀고 기간 수익률로 환산해 반환. 미수렴 시 null */
    private fun xirrPeriodReturn(start: NavPoint, end: NavPoint, flows: List<Flow>): BigDecimal? {
        val cashFlows = buildList {
            add(start.date to -start.nav.toDouble())
            flows.forEach { add(it.date to -it.amountKrw.toDouble()) }
            add(end.date to end.nav.toDouble())
        }
        val t0 = start.date
        val days = ChronoUnit.DAYS.between(start.date, end.date).toDouble()
        if (days <= 0.0) return null

        fun npv(rate: Double): Double = cashFlows.sumOf { (date, amount) ->
            val years = ChronoUnit.DAYS.between(t0, date).toDouble() / 365.0
            amount / (1.0 + rate).pow(years)
        }

        val annual = solveNewton(::npv) ?: solveBisection(::npv) ?: return null
        val periodReturn = (1.0 + annual).pow(days / 365.0) - 1.0
        if (periodReturn.isNaN() || periodReturn.isInfinite()) return null
        return BigDecimal(periodReturn, MathContext(10, RoundingMode.HALF_UP))
    }

    private fun solveNewton(npv: (Double) -> Double): Double? {
        var rate = 0.1
        repeat(100) {
            val f = npv(rate)
            if (abs(f) < 1e-8) return rate
            val h = 1e-6
            val df = (npv(rate + h) - f) / h
            if (df == 0.0 || df.isNaN()) return null
            val next = rate - f / df
            if (next <= -0.9999 || next.isNaN() || next.isInfinite()) return null
            rate = next
        }
        return null
    }

    private fun solveBisection(npv: (Double) -> Double): Double? {
        var lo = -0.9999
        var hi = 10.0
        var fLo = npv(lo)
        if (fLo * npv(hi) > 0) return null
        repeat(200) {
            val mid = (lo + hi) / 2
            val fMid = npv(mid)
            if (abs(fMid) < 1e-8) return mid
            if (fLo * fMid < 0) { hi = mid } else { lo = mid; fLo = fMid }
        }
        return (lo + hi) / 2
    }
}
```

Run: `./gradlew :report:test --tests '*ReturnsCalculatorTest*'` → Expected: 7 tests PASS

- [ ] **Step 3: report 모듈 전체 테스트 + Commit**

```bash
./gradlew :report:test
git add allfolio-backend/report
git commit -m "feat(returns): TWR 체인링킹 + XIRR MWR 순수 계산 엔진"
```

### Task 3: CashFlow 도메인 + 저장 유스케이스 (unified-asset, TDD)

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/domain/cashflow/CashFlow.kt` (FlowType 포함)
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/port/CashFlowRepository.kt`
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/RecordCashFlowUseCase.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/RecordCashFlowUseCaseTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성 (RED)**

```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class RecordCashFlowUseCaseTest {

    private val userId = UUID.randomUUID()

    private class InMemoryRepo : CashFlowRepository {
        val saved = mutableListOf<CashFlow>()
        override fun save(cashFlow: CashFlow): CashFlow { saved.add(cashFlow); return cashFlow }
        override fun findById(id: UUID) = saved.firstOrNull { it.id == id }
        override fun findByUserIdAndPeriod(userId: UUID, from: LocalDate, to: LocalDate) =
            saved.filter { it.userId == userId && it.flowDate in from..to }
        override fun findByUserId(userId: UUID) = saved.filter { it.userId == userId }
        override fun delete(id: UUID) { saved.removeIf { it.id == id } }
    }

    private val fx = FxConverter { amount, currency ->
        if (currency.uppercase() == "KRW") amount else amount * BigDecimal("1400")
    }

    @Test
    fun `records deposit with fixed krw conversion`() {
        val repo = InMemoryRepo()
        val useCase = RecordCashFlowUseCase(repo, fx)

        val flow = useCase.record(
            userId = userId, accountId = null, flowDate = LocalDate.of(2026, 6, 15),
            type = FlowType.DEPOSIT, amount = BigDecimal("100"), currency = "USD", memo = null,
        )

        assertEquals(BigDecimal("140000"), flow.amountKrw)
        assertEquals(1, repo.saved.size)
    }

    @Test
    fun `krw flow keeps amount as is`() {
        val useCase = RecordCashFlowUseCase(InMemoryRepo(), fx)
        val flow = useCase.record(
            userId = userId, accountId = null, flowDate = LocalDate.of(2026, 6, 15),
            type = FlowType.WITHDRAWAL, amount = BigDecimal("500000"), currency = "KRW", memo = "출금",
        )
        assertEquals(BigDecimal("500000"), flow.amountKrw)
    }

    @Test
    fun `non-positive amount is rejected`() {
        val useCase = RecordCashFlowUseCase(InMemoryRepo(), fx)
        assertThrows(IllegalArgumentException::class.java) {
            useCase.record(
                userId = userId, accountId = null, flowDate = LocalDate.of(2026, 6, 15),
                type = FlowType.DEPOSIT, amount = BigDecimal.ZERO, currency = "KRW", memo = null,
            )
        }
    }
}
```

주의: `FxConverter`가 fun interface가 아니면 SAM 람다가 안 됨 — 그 경우 object 익명 구현으로 작성.

Run: `./gradlew :unified-asset:compileTestKotlin` → Expected: 컴파일 실패

- [ ] **Step 2: 구현 (GREEN)**

```kotlin
// CashFlow.kt
package com.allfolio.unifiedasset.domain.cashflow

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

enum class FlowType { DEPOSIT, WITHDRAWAL }

class CashFlow private constructor(
    val id: UUID,
    val userId: UUID,
    val accountId: UUID?,
    val flowDate: LocalDate,
    val type: FlowType,
    val amount: BigDecimal,      // 원통화, 양수
    val currency: String,
    val amountKrw: BigDecimal,   // 기록 시점 환율 고정
    val memo: String?,
    val createdAt: LocalDateTime,
) {
    /** TWR/MWR 입력용 부호 금액: 입금 +, 출금 − */
    fun signedKrw(): BigDecimal = if (type == FlowType.DEPOSIT) amountKrw else amountKrw.negate()

    companion object {
        fun create(
            userId: UUID, accountId: UUID?, flowDate: LocalDate, type: FlowType,
            amount: BigDecimal, currency: String, amountKrw: BigDecimal, memo: String?,
        ): CashFlow {
            require(amount > BigDecimal.ZERO) { "입출금 금액은 양수여야 합니다" }
            return CashFlow(
                id = UUID.randomUUID(), userId = userId, accountId = accountId,
                flowDate = flowDate, type = type, amount = amount,
                currency = currency.uppercase(), amountKrw = amountKrw,
                memo = memo?.trim(), createdAt = LocalDateTime.now(),
            )
        }

        fun reconstruct(
            id: UUID, userId: UUID, accountId: UUID?, flowDate: LocalDate, type: FlowType,
            amount: BigDecimal, currency: String, amountKrw: BigDecimal, memo: String?, createdAt: LocalDateTime,
        ) = CashFlow(id, userId, accountId, flowDate, type, amount, currency, amountKrw, memo, createdAt)
    }
}
```

```kotlin
// CashFlowRepository.kt
package com.allfolio.unifiedasset.application.port

import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import java.time.LocalDate
import java.util.UUID

interface CashFlowRepository {
    fun save(cashFlow: CashFlow): CashFlow
    fun findById(id: UUID): CashFlow?
    fun findByUserIdAndPeriod(userId: UUID, from: LocalDate, to: LocalDate): List<CashFlow>
    fun findByUserId(userId: UUID): List<CashFlow>
    fun delete(id: UUID)
}
```

```kotlin
// RecordCashFlowUseCase.kt
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@Service
class RecordCashFlowUseCase(
    private val repository: CashFlowRepository,
    private val fxConverter: FxConverter,
) {
    fun record(
        userId: UUID, accountId: UUID?, flowDate: LocalDate, type: FlowType,
        amount: BigDecimal, currency: String, memo: String?,
    ): CashFlow {
        require(amount > BigDecimal.ZERO) { "입출금 금액은 양수여야 합니다" }
        val amountKrw = fxConverter.toKrw(amount, currency)
        return repository.save(
            CashFlow.create(userId, accountId, flowDate, type, amount, currency, amountKrw, memo)
        )
    }
}
```

Run: `./gradlew :unified-asset:test --tests '*RecordCashFlowUseCaseTest*'` → Expected: 3 tests PASS

- [ ] **Step 3: Commit**

```bash
git add allfolio-backend/unified-asset
git commit -m "feat(returns): 현금흐름 원장 도메인 + 기록 유스케이스 (KRW 고정 환산)"
```

### Task 4: CashFlow JPA 어댑터 + API

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/entity/CashFlowEntity.kt`
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/jpa/CashFlowJpaRepository.kt`
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/repository/CashFlowRepositoryImpl.kt`
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/api/CashFlowController.kt`

- [ ] **Step 1: 엔티티·JPA·어댑터·컨트롤러 구현** (씬 계층 — 컴파일+전체 테스트로 확인)

```kotlin
// CashFlowEntity.kt
package com.allfolio.unifiedasset.infrastructure.entity

import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "cash_flow")
class CashFlowEntity(
    @Id val id: UUID,
    @Column(name = "user_id", nullable = false) val userId: UUID,
    @Column(name = "account_id") val accountId: UUID?,
    @Column(name = "flow_date", nullable = false) val flowDate: LocalDate,
    @Enumerated(EnumType.STRING)
    @Column(name = "flow_type", nullable = false, length = 20) val flowType: FlowType,
    @Column(name = "amount", nullable = false, precision = 30, scale = 10) val amount: BigDecimal,
    @Column(name = "currency", nullable = false, length = 10) val currency: String,
    @Column(name = "amount_krw", nullable = false, precision = 30, scale = 10) val amountKrw: BigDecimal,
    @Column(name = "memo", length = 500) val memo: String?,
    @Column(name = "created_at", nullable = false) val createdAt: LocalDateTime,
) {
    fun toDomain(): CashFlow = CashFlow.reconstruct(
        id, userId, accountId, flowDate, flowType, amount, currency, amountKrw, memo, createdAt,
    )

    companion object {
        fun from(domain: CashFlow) = CashFlowEntity(
            id = domain.id, userId = domain.userId, accountId = domain.accountId,
            flowDate = domain.flowDate, flowType = domain.type, amount = domain.amount,
            currency = domain.currency, amountKrw = domain.amountKrw,
            memo = domain.memo, createdAt = domain.createdAt,
        )
    }
}
```

```kotlin
// CashFlowJpaRepository.kt
package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.CashFlowEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

interface CashFlowJpaRepository : JpaRepository<CashFlowEntity, UUID> {
    fun findByUserIdOrderByFlowDateDesc(userId: UUID): List<CashFlowEntity>
    fun findByUserIdAndFlowDateBetweenOrderByFlowDateDesc(
        userId: UUID, from: LocalDate, to: LocalDate,
    ): List<CashFlowEntity>
}
```

```kotlin
// CashFlowRepositoryImpl.kt
package com.allfolio.unifiedasset.infrastructure.repository

import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.infrastructure.entity.CashFlowEntity
import com.allfolio.unifiedasset.infrastructure.jpa.CashFlowJpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

@Repository
class CashFlowRepositoryImpl(private val jpa: CashFlowJpaRepository) : CashFlowRepository {
    override fun save(cashFlow: CashFlow): CashFlow =
        jpa.save(CashFlowEntity.from(cashFlow)).toDomain()

    override fun findById(id: UUID): CashFlow? =
        jpa.findById(id).orElse(null)?.toDomain()

    override fun findByUserIdAndPeriod(userId: UUID, from: LocalDate, to: LocalDate): List<CashFlow> =
        jpa.findByUserIdAndFlowDateBetweenOrderByFlowDateDesc(userId, from, to).map { it.toDomain() }

    override fun findByUserId(userId: UUID): List<CashFlow> =
        jpa.findByUserIdOrderByFlowDateDesc(userId).map { it.toDomain() }

    override fun delete(id: UUID) = jpa.deleteById(id)
}
```

```kotlin
// CashFlowController.kt
package com.allfolio.unifiedasset.api

import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.usecase.RecordCashFlowUseCase
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/cashflows")
class CashFlowController(
    private val recordCashFlow: RecordCashFlowUseCase,
    private val repository: CashFlowRepository,
) {

    data class RecordRequest(
        val accountId: UUID?,
        val flowDate: LocalDate,
        val flowType: FlowType,
        val amount: BigDecimal,
        val currency: String,
        val memo: String?,
    )

    data class CashFlowResponse(
        val id: UUID,
        val accountId: UUID?,
        val flowDate: LocalDate,
        val flowType: FlowType,
        val amount: BigDecimal,
        val currency: String,
        val amountKrw: BigDecimal,
        val memo: String?,
    )

    @PostMapping
    fun record(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestBody request: RecordRequest,
    ): CashFlowResponse = recordCashFlow.record(
        userId = userId, accountId = request.accountId, flowDate = request.flowDate,
        type = request.flowType, amount = request.amount, currency = request.currency,
        memo = request.memo,
    ).toResponse()

    @GetMapping
    fun list(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
    ): List<CashFlowResponse> =
        (if (from != null && to != null) repository.findByUserIdAndPeriod(userId, from, to)
         else repository.findByUserId(userId)).map { it.toResponse() }

    @DeleteMapping("/{id}")
    fun delete(
        @RequestHeader("X-User-Id") userId: UUID,
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        val flow = repository.findById(id)
        // 소유권 검증: 남의 기록은 존재 여부도 노출하지 않는다
        if (flow == null || flow.userId != userId) return ResponseEntity.notFound().build()
        repository.delete(id)
        return ResponseEntity.noContent().build()
    }

    private fun CashFlow.toResponse() = CashFlowResponse(
        id = id, accountId = accountId, flowDate = flowDate, flowType = type,
        amount = amount, currency = currency, amountKrw = amountKrw, memo = memo,
    )
}
```

Run: `./gradlew :unified-asset:build` → Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Commit**

```bash
git add allfolio-backend/unified-asset
git commit -m "feat(returns): cash_flow JPA 어댑터 + /api/cashflows API (소유권 검증)"
```

### Task 5: ReturnsReportGenerator (TDD)

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/ReturnsReportGenerator.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/ReturnsReportGeneratorTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성 (RED)**

NAV 조회 포트를 생성기 내부 인터페이스로 두고 fake 주입:

```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.returns.NavPoint
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class ReturnsReportGeneratorTest {

    private val userId = UUID.randomUUID()
    private val period = ReportPeriod.monthly(2026, 6)
    private val mapper = jacksonObjectMapper()

    private class FakeNavSource(private val points: List<NavPoint>) : NavHistorySource {
        override fun navSeries(userId: UUID, from: LocalDate, to: LocalDate) =
            points.filter { it.date in from..to }
    }

    private class FakeCashFlowRepo(private val flows: List<CashFlow>) : CashFlowRepository {
        override fun save(cashFlow: CashFlow) = cashFlow
        override fun findById(id: UUID): CashFlow? = null
        override fun findByUserIdAndPeriod(userId: UUID, from: LocalDate, to: LocalDate) =
            flows.filter { it.flowDate in from..to }
        override fun findByUserId(userId: UUID) = flows
        override fun delete(id: UUID) {}
    }

    private fun nav(day: Int, v: String) = NavPoint(LocalDate.of(2026, 6, day), BigDecimal(v))

    @Test
    fun `generates returns body with period and standard sections`() {
        val generator = ReturnsReportGenerator(
            FakeNavSource(listOf(nav(1, "1000"), nav(15, "1050"), nav(30, "1100"))),
            FakeCashFlowRepo(emptyList()),
        )

        val generated = generator.generate(userId, period)

        assertEquals(LocalDate.of(2026, 6, 30), generated.asOfDate)
        val body = mapper.readTree(generated.bodyJson)
        assertTrue(body.has("period"))
        assertTrue(body.has("standard"))
        assertTrue(body.has("flowDecomposition"))
        assertTrue(body.has("navSeries"))
        assertEquals(0.1, body["period"]["twr"].asDouble(), 0.001)
    }

    @Test
    fun `deposit does not inflate twr in generated body`() {
        val deposit = CashFlow.create(
            userId = userId, accountId = null, flowDate = LocalDate.of(2026, 6, 15),
            type = FlowType.DEPOSIT, amount = BigDecimal("1000"), currency = "KRW",
            amountKrw = BigDecimal("1000"), memo = null,
        )
        val generator = ReturnsReportGenerator(
            FakeNavSource(listOf(nav(1, "1000"), nav(15, "2000"), nav(30, "2200"))),
            FakeCashFlowRepo(listOf(deposit)),
        )

        val body = mapper.readTree(generator.generate(userId, period).bodyJson)

        assertEquals(0.1, body["period"]["twr"].asDouble(), 0.001)
        assertEquals(1000.0, body["flowDecomposition"]["netFlow"].asDouble(), 0.001)
    }

    @Test
    fun `insufficient nav observations throw`() {
        val generator = ReturnsReportGenerator(
            FakeNavSource(listOf(nav(1, "1000"))),
            FakeCashFlowRepo(emptyList()),
        )
        assertThrows(InsufficientDataException::class.java) {
            generator.generate(userId, period)
        }
    }
}
```

Run: `./gradlew :unified-asset:compileTestKotlin` → Expected: 컴파일 실패

- [ ] **Step 2: 구현 (GREEN)**

```kotlin
// ReturnsReportGenerator.kt
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.application.GeneratedReport
import com.allfolio.report.application.ReportBodyGenerator
import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.archive.ReportType
import com.allfolio.report.domain.returns.Flow
import com.allfolio.report.domain.returns.NavPoint
import com.allfolio.report.domain.returns.PeriodReturns
import com.allfolio.report.domain.returns.ReturnsCalculator
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

class InsufficientDataException(message: String) : RuntimeException(message)

/** performance_daily NAV 시계열 조회 포트 — JDBC 구현은 인프라에 */
interface NavHistorySource {
    fun navSeries(userId: UUID, from: LocalDate, to: LocalDate): List<NavPoint>
}

/**
 * R-02 수익률 리포트 생성기. #32 프레임의 첫 ReportBodyGenerator.
 * NAV(KRW)와 cash_flow(KRW 고정 환산)로 TWR/MWR·입출금 효과 분해를 계산한다.
 */
@Component
class ReturnsReportGenerator(
    private val navSource: NavHistorySource,
    private val cashFlowRepository: CashFlowRepository,
) : ReportBodyGenerator {

    override val type = ReportType.RETURNS

    private val mapper = jacksonObjectMapper()

    override fun generate(userId: UUID, period: ReportPeriod): GeneratedReport {
        val periodSeries = navSource.navSeries(userId, period.start, period.end)
        if (periodSeries.size < 2) {
            throw InsufficientDataException(
                "수익률 계산에 필요한 NAV 스냅샷이 부족합니다 (기간 내 ${periodSeries.size}건, 최소 2건)"
            )
        }
        val asOfDate = periodSeries.last().date

        val earliest = LocalDate.of(2000, 1, 1)
        val fullSeries = navSource.navSeries(userId, earliest, period.end)
        val flows = cashFlowRepository.findByUserIdAndPeriod(userId, earliest, period.end)
            .map { Flow(it.flowDate, it.signedKrw()) }

        val periodResult = ReturnsCalculator.calculate(fullSeries, flows, period.start, period.end)

        val inception = fullSeries.first().date
        val standardFrom = mapOf(
            "1M" to period.end.minusMonths(1),
            "3M" to period.end.minusMonths(3),
            "6M" to period.end.minusMonths(6),
            "YTD" to period.end.withDayOfYear(1),
            "1Y" to period.end.minusYears(1),
            "SI" to inception,
        )
        val standard = standardFrom.mapValues { (_, from) ->
            ReturnsCalculator.calculate(fullSeries, flows, maxOf(from, inception), period.end).toMap()
        }

        val body = mapOf(
            "period" to periodResult.toMap(),
            "standard" to standard,
            "flowDecomposition" to mapOf(
                "startNav" to periodResult.startNav,
                "netFlow" to periodResult.netFlow,
                "investmentPnl" to periodResult.investmentPnl,
                "endNav" to periodResult.endNav,
            ),
            "navSeries" to periodSeries.map { mapOf("date" to it.date.toString(), "nav" to it.nav) },
        )
        return GeneratedReport(asOfDate = asOfDate, bodyJson = mapper.writeValueAsString(body))
    }

    private fun PeriodReturns.toMap() = mapOf(
        "twr" to twr,
        "mwr" to mwr,
        "startNav" to startNav,
        "endNav" to endNav,
        "netFlow" to netFlow,
        "investmentPnl" to investmentPnl,
    )
}
```

Run: `./gradlew :unified-asset:test --tests '*ReturnsReportGeneratorTest*'` → Expected: 3 tests PASS

- [ ] **Step 3: NavHistorySource JDBC 구현 + 400 핸들러**

`allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/adapter/JdbcNavHistorySource.kt`:

```kotlin
package com.allfolio.unifiedasset.infrastructure.adapter

import com.allfolio.report.domain.returns.NavPoint
import com.allfolio.unifiedasset.application.usecase.NavHistorySource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/** performance_daily(사용자 단위: portfolio_id = userId) NAV 시계열 조회 */
@Component
class JdbcNavHistorySource(private val jdbc: JdbcTemplate) : NavHistorySource {
    override fun navSeries(userId: UUID, from: LocalDate, to: LocalDate): List<NavPoint> =
        jdbc.query(
            """SELECT date, nav FROM performance_daily
               WHERE portfolio_id = ? AND date BETWEEN ? AND ?
               ORDER BY date ASC""",
            { rs, _ -> NavPoint(rs.getDate("date").toLocalDate(), rs.getBigDecimal("nav")) },
            userId, from, to,
        )
}
```

`ReportArchiveController`에 예외 핸들러 추가:

```kotlin
@ExceptionHandler(InsufficientDataException::class)
fun insufficientData(e: InsufficientDataException): ResponseEntity<Map<String, String>> =
    ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to (e.message ?: "insufficient data")))
```

(import: `com.allfolio.unifiedasset.application.usecase.InsufficientDataException`)

Run: `./gradlew build -x :backend-app:test && ./gradlew test` → Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add allfolio-backend/unified-asset
git commit -m "feat(returns): RETURNS 리포트 생성기 — #32 프레임 첫 생성기 등록"
```

### Task 6: 스모크 검증 + 마무리

- [ ] **Step 1: 로컬 기동 스모크** — 도커 PG에 cash_flow DDL 적용 → 유저 생성 → performance_daily 시드 3건 + `/api/cashflows`로 입금 기록 → `POST /api/reports/archive/generate` type=RETURNS → 본문 수치 검산(TWR에 입금 미반영 확인) → `GET /api/reports/archive/{id}` → 계정 삭제로 cash_flow 정리 확인
- [ ] **Step 2: 플랜 체크박스 완료 처리, push, PR 생성(#30 머지 후 main 베이스), 노션 #33 업데이트**
