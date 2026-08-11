# AF-100 과거 환율 백필 + 현금흐름 환산 오차 수정 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `cash_flow.amount_krw`가 조회 시점이 아니라 `flowDate` 시점 환율로 저장되게 만든다.

**Architecture:** `fx_rate_daily` 테이블에 ECOS 일별 환율을 백필하고, `FxConverter` 포트에 날짜 인식 메서드 `toKrwOn`을 default 구현과 함께 추가한다. 어댑터가 "과거 조회 → 없으면 직전 영업일 → 그래도 없으면 현재 환율(추정치 표시)" 폴백을 전담하고, 현금흐름 생성 3곳만 새 메서드로 바꾼다. 자산 평가(NAV) 경로 약 25곳은 손대지 않는다.

**Tech Stack:** Kotlin 1.9.25 · Spring Boot 3.2.5 · JPA/Hibernate · PostgreSQL(운영) / H2(테스트) · WebClient · JUnit 5 + AssertJ + Mockito

**Spec:** `docs/superpowers/specs/2026-08-11-historical-fx-backfill-design.md`

---

## 사전 확인 (구현 시작 전 필수)

ECOS 통계표 코드·항목 코드를 ECOS 사이트(https://ecos.bok.or.kr)에서 직접 확인해 둔다.
**추정하지 않는다.** 틀린 코드는 조용히 0건을 반환하고, Task 10의 안전장치에 "0건 → 실패"로만
잡혀서 코드가 틀린 건지 기간이 빈 건지 구분되지 않는다.

확인할 값 둘:
- 일별 원/미국달러 환율 통계표 코드 (`ECOS_USD_STAT_CODE`)
- 그 통계표 안의 원/미국달러 항목 코드 (`ECOS_USD_ITEM_CODE`)

값이 확정될 때까지 Task 1~8은 그대로 진행 가능하다. Task 11의 라이브 백필에서만 필요하다.

## 파일 구조

배치 원칙: **엔티티·JPA 리포지토리는 `unified-asset`, 수집·어댑터·어드민은 `backend-app`.**
`unified-asset`에 모든 엔티티(`AccountEntity`, `TaxRateEntity`, `CashFlowEntity`…)가 모여 있고
H2 테스트 의존성(`testRuntimeOnly("com.h2database:h2")`)도 그 모듈에만 있다. `backend-app`은
`unified-asset`에 단방향 의존하므로 어댑터에서 리포지토리를 그대로 주입받을 수 있다.

**생성**
| 파일 | 책임 |
|---|---|
| `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/entity/HistoricalFxRateEntity.kt` | `fx_rate_daily` 행 |
| `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/jpa/HistoricalFxRateJpaRepository.kt` | 직전 영업일 조회 + 범위 조회 |
| `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/EcosProperties.kt` | ECOS 인증키·통화별 시계열 설정 |
| `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/EcosResponseParser.kt` | ECOS JSON → `EcosRate` (HTTP와 분리된 순수 함수) |
| `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/EcosApiClient.kt` | 인터페이스 + WebClient 구현 |
| `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/FxRateBackfillService.kt` | 수집 → 정규화 → 저장, 요약 반환 |
| `docs/superpowers/migrations/2026-08-11-fx-rate-daily.sql` | 운영 Neon 마이그레이션 |

**수정**
| 파일 | 변경 |
|---|---|
| `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/port/FxConverter.kt` | `KrwConversion` + `toKrwOn` default 추가 |
| `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/UnifiedAssetFxConverterAdapter.kt` | `toKrwOn` override + 폴백 + 캐시 |
| `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/SyncAccountUseCase.kt:181` | `toKrwOn` + 메모 표기 |
| `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/RecordCashFlowUseCase.kt:33` | `toKrwOn` + WARN 로그 |
| `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/RecordInternalFlowUseCase.kt:29,49,50` | `toKrwOn` + WARN 로그 |
| `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/api/admin/FxRateAdminController.kt` | 백필 엔드포인트 추가 |
| `allfolio-backend/backend-app/src/main/resources/application.yml:220` | `ecos:` 블록 추가 |
| `allfolio-backend/infra/postgres/init.sql` | `fx_rate_daily` 추가 |

**손대지 않음:** `NavCalculator`, `ReportService`, `GetDashboardUseCase`, `GetPortfolioUseCase`,
`GoalService`, `EsgReportService`, `DailyNavScheduler` — 전부 자산 평가라 현재 환율이 맞다.

---

## Task 1: `FxConverter` 포트에 날짜 인식 메서드 추가

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/port/FxConverter.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/port/FxConverterDefaultTest.kt`

**왜 default 구현인가:** `object : FxConverter { ... }` 형태의 익명 fake가 테스트 19곳에 흩어져 있다.
default 없이 메서드를 추가하면 이 변경 하나로 테스트 컴파일이 전부 무너진다 (AF-98에서 물린 것과 같은 계열).

- [ ] **Step 1: 실패하는 테스트 작성**

`allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/port/FxConverterDefaultTest.kt`:

```kotlin
package com.allfolio.unifiedasset.application.port

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 포트 default 구현 — 과거 환율을 모르는 구현체(테스트 fake 포함)가 그대로 동작해야 한다.
 * KRW는 환산이 없으므로 추정치가 아니다. 이 구분이 없으면 원화 계좌 메모에까지
 * "환율 추정치"가 붙는다.
 */
class FxConverterDefaultTest {

    private val fx = object : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
            if (currency.uppercase() == "KRW") amount else amount.multiply(BigDecimal("1300"))
    }

    @Test
    fun `default는 toKrw에 위임하고 추정치로 표시한다`() {
        val result = fx.toKrwOn(BigDecimal("100"), "USD", LocalDate.of(2025, 8, 11))

        assertThat(result.amountKrw).isEqualByComparingTo("130000")
        assertThat(result.estimated).isTrue()
        assertThat(result.rateDate).isNull()
    }

    @Test
    fun `KRW는 환산이 없으므로 추정치가 아니다`() {
        val result = fx.toKrwOn(BigDecimal("5000"), "krw", LocalDate.of(2025, 8, 11))

        assertThat(result.amountKrw).isEqualByComparingTo("5000")
        assertThat(result.estimated).isFalse()
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests '*FxConverterDefaultTest*'`
Expected: 컴파일 실패 — `Unresolved reference: toKrwOn`

- [ ] **Step 3: 포트 수정**

`FxConverter.kt` 전체를 아래로 교체:

```kotlin
package com.allfolio.unifiedasset.application.port

import java.math.BigDecimal
import java.time.LocalDate

/**
 * 환산 결과.
 *
 * @param amountKrw KRW 환산 금액
 * @param rateDate  적용된 환율 고시일. 과거 환율표에서 찾았을 때만 채워진다.
 *                  KRW(환산 없음)와 현재환율 폴백은 null.
 * @param estimated 요청한 날짜의 환율이 아니라 현재 환율로 근사했으면 true
 */
data class KrwConversion(
    val amountKrw: BigDecimal,
    val rateDate: LocalDate?,
    val estimated: Boolean,
)

/**
 * 통화 → KRW 환산 포트.
 *
 * unified-asset는 여러 증권사/거래소 자산을 통합하므로 통화가 섞인다
 * (예: KIS 국내주식=KRW, Binance 보유=USD). NAV·총자산을 합산하기 전에
 * 반드시 이 포트로 단일 기준통화(KRW)로 환산해야 한다.
 *
 * 구현 어댑터는 backend-app FX 인프라(Redis 캐시 환율 + fx_rate_daily)를 래핑한다.
 */
interface FxConverter {
    /**
     * 현재 환율로 환산한다. 자산 평가액(NAV·보유·리포트)에 쓴다.
     *
     * @param amount   환산 전 금액
     * @param currency ISO 통화 코드("KRW", "USD", "USDT" 등, 대소문자 무관)
     * @return KRW 환산 금액
     */
    fun toKrw(amount: BigDecimal, currency: String): BigDecimal

    /**
     * 지정한 날짜의 환율로 환산한다. 현금흐름(cash_flow.amount_krw)에 쓴다.
     *
     * 자산 평가는 오늘 환율, 현금흐름은 발생일 환율 — 이 경계를 지켜야
     * netFlow가 맞고 TWR/MWR이 왜곡되지 않는다.
     *
     * default 구현은 과거 환율을 모르는 구현체를 위한 것으로, 현재 환율로 근사하고
     * estimated=true를 반환한다.
     */
    fun toKrwOn(amount: BigDecimal, currency: String, date: LocalDate): KrwConversion =
        KrwConversion(
            amountKrw = toKrw(amount, currency),
            rateDate = null,
            estimated = !currency.trim().equals("KRW", ignoreCase = true),
        )
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests '*FxConverterDefaultTest*'`
Expected: PASS (2 tests)

- [ ] **Step 5: 기존 테스트 회귀 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test :backend-app:test`
Expected: BUILD SUCCESSFUL — 익명 fake 19곳이 default 덕분에 그대로 컴파일된다

- [ ] **Step 6: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/port/FxConverter.kt allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/port/FxConverterDefaultTest.kt
git commit -m "feat(fx): FxConverter에 날짜 인식 toKrwOn 추가 (default 구현 포함)"
```

---

## Task 2: `fx_rate_daily` 스키마

**Files:**
- Create: `docs/superpowers/migrations/2026-08-11-fx-rate-daily.sql`
- Modify: `allfolio-backend/infra/postgres/init.sql` (파일 맨 끝에 추가)

테스트 없음 — 스키마는 Task 3의 `@DataJpaTest`가 엔티티로부터 생성해 검증한다.

- [ ] **Step 1: 마이그레이션 파일 작성**

`docs/superpowers/migrations/2026-08-11-fx-rate-daily.sql`:

```sql
-- AF-100 과거 환율 시계열 (ECOS) — 운영 Neon 1회성 마이그레이션
-- 실행: /opt/homebrew/opt/libpq/bin/psql "<NEON_DB_URL>" -f 2026-08-11-fx-rate-daily.sql
-- 반드시 백엔드 배포 "전"에 실행 (ddl-auto: none). 신규 테이블이라 기존 백엔드엔 무해. 멱등.

CREATE TABLE IF NOT EXISTS fx_rate_daily (
    id         UUID           NOT NULL,
    base_date  DATE           NOT NULL,
    currency   VARCHAR(10)    NOT NULL,
    rate_krw   NUMERIC(18, 6) NOT NULL,   -- 통화 1단위당 KRW. JPY 같은 100단위 고시는 수집기가 1단위로 정규화해 넣는다
    source     VARCHAR(20)    NOT NULL DEFAULT 'ECOS',
    created_at TIMESTAMP      NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_fx_rate_daily PRIMARY KEY (id),
    CONSTRAINT uk_fx_rate_daily UNIQUE (base_date, currency)
);

-- 체결일 조회는 "그 날짜 이하의 가장 최근 고시" 한 건 — 주말·공휴일이 직전 영업일로 이어진다
CREATE INDEX IF NOT EXISTS idx_fx_rate_daily_lookup
    ON fx_rate_daily (currency, base_date DESC);

-- 검증: 테이블만 생성되고 데이터는 어드민 백필 API로 채운다
SELECT COUNT(*) AS rows FROM fx_rate_daily;
```

- [ ] **Step 2: init.sql 맨 끝에 같은 DDL 추가**

`allfolio-backend/infra/postgres/init.sql` 파일 끝에 이어 붙인다:

```sql

-- ── fx_rate_daily ──────────────────────────────────────────────
-- AF-100 과거 환율 시계열. 현금흐름(cash_flow.amount_krw)을 발생일 환율로 환산하기 위한 것.
-- 자산 평가는 Redis 현재 환율을 계속 쓴다 — 여기는 "그때 얼마였나" 전용.
-- rate_krw는 항상 통화 1단위 기준. ECOS는 JPY를 100엔 기준으로 주므로 수집기가 정규화한다.
CREATE TABLE IF NOT EXISTS fx_rate_daily (
    id         UUID           NOT NULL,
    base_date  DATE           NOT NULL,
    currency   VARCHAR(10)    NOT NULL,
    rate_krw   NUMERIC(18, 6) NOT NULL,
    source     VARCHAR(20)    NOT NULL DEFAULT 'ECOS',
    created_at TIMESTAMP      NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_fx_rate_daily PRIMARY KEY (id),
    CONSTRAINT uk_fx_rate_daily UNIQUE (base_date, currency)
);

CREATE INDEX IF NOT EXISTS idx_fx_rate_daily_lookup
    ON fx_rate_daily (currency, base_date DESC);
```

- [ ] **Step 3: 커밋**

```bash
git add docs/superpowers/migrations/2026-08-11-fx-rate-daily.sql allfolio-backend/infra/postgres/init.sql
git commit -m "feat(fx): fx_rate_daily 테이블 스키마 + Neon 마이그레이션"
```

---

## Task 3: 엔티티와 조회 리포지토리

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/entity/HistoricalFxRateEntity.kt`
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/jpa/HistoricalFxRateJpaRepository.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/infrastructure/jpa/HistoricalFxRateJpaRepositoryTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

`HistoricalFxRateJpaRepositoryTest.kt`:

```kotlin
package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.HistoricalFxRateEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.test.context.ContextConfiguration
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 체결일 환율 조회는 "그 날짜 이하의 가장 최근 고시" 한 건이다.
 * 이 규칙 하나로 주말·공휴일이 직전 영업일로 자동으로 이어지고,
 * 백필 범위 이전 날짜는 행이 없어 miss로 떨어진다.
 */
@DataJpaTest
@ContextConfiguration(classes = [HistoricalFxRateJpaRepositoryTest.TestConfig::class])
class HistoricalFxRateJpaRepositoryTest {

    @Autowired
    private lateinit var repository: HistoricalFxRateJpaRepository

    @Test
    fun `정확히 그 날짜의 고시가 있으면 그것을 준다`() {
        save(LocalDate.of(2025, 8, 8), "1385.500000")
        save(LocalDate.of(2025, 8, 11), "1390.200000")

        val found = repository.findTopByCurrencyAndBaseDateLessThanEqualOrderByBaseDateDesc(
            "USD", LocalDate.of(2025, 8, 11),
        )

        assertThat(found?.baseDate).isEqualTo(LocalDate.of(2025, 8, 11))
        assertThat(found?.rateKrw).isEqualByComparingTo("1390.2")
    }

    @Test
    fun `주말은 직전 영업일 고시로 잇는다`() {
        save(LocalDate.of(2025, 8, 8), "1385.500000")   // 금요일

        // 2025-08-09는 토요일 — 고시가 없다
        val found = repository.findTopByCurrencyAndBaseDateLessThanEqualOrderByBaseDateDesc(
            "USD", LocalDate.of(2025, 8, 9),
        )

        assertThat(found?.baseDate).isEqualTo(LocalDate.of(2025, 8, 8))
    }

    @Test
    fun `백필 범위보다 이른 날짜는 찾지 못한다`() {
        save(LocalDate.of(2025, 8, 8), "1385.500000")

        val found = repository.findTopByCurrencyAndBaseDateLessThanEqualOrderByBaseDateDesc(
            "USD", LocalDate.of(2020, 1, 1),
        )

        assertThat(found).isNull()
    }

    @Test
    fun `다른 통화의 고시는 섞이지 않는다`() {
        save(LocalDate.of(2025, 8, 11), "1390.200000", currency = "JPY")

        val found = repository.findTopByCurrencyAndBaseDateLessThanEqualOrderByBaseDateDesc(
            "USD", LocalDate.of(2025, 8, 11),
        )

        assertThat(found).isNull()
    }

    @Test
    fun `범위 조회는 경계를 포함한다`() {
        save(LocalDate.of(2025, 8, 7), "1380.000000")
        save(LocalDate.of(2025, 8, 8), "1385.500000")
        save(LocalDate.of(2025, 8, 11), "1390.200000")

        val rows = repository.findAllByCurrencyAndBaseDateBetween(
            "USD", LocalDate.of(2025, 8, 7), LocalDate.of(2025, 8, 8),
        )

        assertThat(rows.map { it.baseDate })
            .containsExactlyInAnyOrder(LocalDate.of(2025, 8, 7), LocalDate.of(2025, 8, 8))
    }

    private fun save(date: LocalDate, rate: String, currency: String = "USD") {
        repository.save(
            HistoricalFxRateEntity(
                id = UUID.randomUUID(),
                baseDate = date,
                currency = currency,
                rateKrw = BigDecimal(rate),
                source = "ECOS",
                createdAt = LocalDateTime.now(),
            ),
        )
    }

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = [HistoricalFxRateEntity::class])
    @EnableJpaRepositories(basePackageClasses = [HistoricalFxRateJpaRepository::class])
    class TestConfig
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests '*HistoricalFxRateJpaRepositoryTest*'`
Expected: 컴파일 실패 — `Unresolved reference: HistoricalFxRateEntity`

- [ ] **Step 3: 엔티티 작성**

`HistoricalFxRateEntity.kt`:

```kotlin
package com.allfolio.unifiedasset.infrastructure.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 일별 확정 환율 (AF-100).
 *
 * 자산 평가에 쓰는 Redis 현재 환율과 별개다 — 이쪽은 "그날 얼마였나" 전용이고,
 * 현금흐름(cash_flow.amount_krw)을 발생일 환율로 환산하는 데 쓴다.
 *
 * rateKrw는 항상 통화 1단위당 KRW. ECOS는 JPY를 100엔 기준으로 주므로
 * 수집기(FxRateBackfillService)가 1단위로 정규화해서 넣는다.
 *
 * rateKrw·source가 var인 이유: 백필 재실행 시 같은 (baseDate, currency) 행을
 * 덮어쓰기 때문. 자연키가 UNIQUE로 걸려 있어 중복은 생기지 않는다.
 */
@Entity
@Table(name = "fx_rate_daily")
class HistoricalFxRateEntity(
    @Id val id: UUID,
    @Column(name = "base_date", nullable = false) val baseDate: LocalDate,
    @Column(name = "currency", nullable = false, length = 10) val currency: String,
    @Column(name = "rate_krw", nullable = false, precision = 18, scale = 6) var rateKrw: BigDecimal,
    @Column(name = "source", nullable = false, length = 20) var source: String,
    @Column(name = "created_at", nullable = false) val createdAt: LocalDateTime,
)
```

- [ ] **Step 4: 리포지토리 작성**

`HistoricalFxRateJpaRepository.kt`:

```kotlin
package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.HistoricalFxRateEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

interface HistoricalFxRateJpaRepository : JpaRepository<HistoricalFxRateEntity, UUID> {

    /**
     * 지정일 이하의 가장 최근 고시 한 건.
     * 주말·공휴일은 이 쿼리 하나로 직전 영업일에 이어진다.
     * 백필 범위보다 이른 날짜는 행이 없어 null.
     */
    fun findTopByCurrencyAndBaseDateLessThanEqualOrderByBaseDateDesc(
        currency: String,
        baseDate: LocalDate,
    ): HistoricalFxRateEntity?

    /** 백필 시 기존 행을 한 번에 읽어 덮어쓸 대상을 가려내는 용도 (경계 포함) */
    fun findAllByCurrencyAndBaseDateBetween(
        currency: String,
        from: LocalDate,
        to: LocalDate,
    ): List<HistoricalFxRateEntity>
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests '*HistoricalFxRateJpaRepositoryTest*'`
Expected: PASS (5 tests)

- [ ] **Step 6: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/entity/HistoricalFxRateEntity.kt allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/jpa/HistoricalFxRateJpaRepository.kt allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/infrastructure/jpa/HistoricalFxRateJpaRepositoryTest.kt
git commit -m "feat(fx): fx_rate_daily 엔티티 + 직전 영업일 조회 리포지토리"
```

---

## Task 4: 어댑터에 폴백과 캐시 구현

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/UnifiedAssetFxConverterAdapter.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/UnifiedAssetFxConverterAdapterTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

`UnifiedAssetFxConverterAdapterTest.kt`:

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
 * 폴백 정책이 어댑터 한 곳에 모여 있어야 소비 지점 3곳이 규칙을 몰라도 맞는 값을 받는다.
 */
class UnifiedAssetFxConverterAdapterTest {

    private val date = LocalDate.of(2025, 8, 11)

    @Test
    fun `KRW는 환산 없이 그대로 두고 추정치가 아니다`() {
        val result = adapter(FakeRepo()).toKrwOn(BigDecimal("5000"), "KRW", date)

        assertThat(result.amountKrw).isEqualByComparingTo("5000")
        assertThat(result.estimated).isFalse()
        assertThat(result.rateDate).isNull()
    }

    @Test
    fun `USD는 저장된 그날 환율로 환산한다`() {
        val repo = FakeRepo(row(date, "1390.200000"))

        val result = adapter(repo).toKrwOn(BigDecimal("100"), "USD", date)

        assertThat(result.amountKrw).isEqualByComparingTo("139020")
        assertThat(result.estimated).isFalse()
        assertThat(result.rateDate).isEqualTo(date)
    }

    @Test
    fun `USDT는 USD 시계열로 환산한다`() {
        val repo = FakeRepo(row(date, "1390.200000"))

        val result = adapter(repo).toKrwOn(BigDecimal("100"), "usdt", date)

        assertThat(result.amountKrw).isEqualByComparingTo("139020")
        assertThat(result.estimated).isFalse()
        assertThat(repo.lastCurrency).isEqualTo("USD")
    }

    @Test
    fun `과거 환율이 없으면 현재 환율로 폴백하고 추정치로 표시한다`() {
        val result = adapter(FakeRepo()).toKrwOn(BigDecimal("100"), "USD", date)

        // CurrencyConverter가 fallback 1350을 쓴다
        assertThat(result.amountKrw).isEqualByComparingTo("135000")
        assertThat(result.estimated).isTrue()
        assertThat(result.rateDate).isNull()
    }

    @Test
    fun `BTC는 과거 시세가 없으므로 현재가로 환산하고 추정치로 표시한다`() {
        val result = adapter(FakeRepo()).toKrwOn(BigDecimal("0.5"), "BTC", date)

        assertThat(result.amountKrw).isEqualByComparingTo("45000000")
        assertThat(result.estimated).isTrue()
    }

    @Test
    fun `조회가 실패해도 예외를 던지지 않고 현재 환율로 폴백한다`() {
        val result = adapter(ExplodingRepo()).toKrwOn(BigDecimal("100"), "USD", date)

        assertThat(result.amountKrw).isEqualByComparingTo("135000")
        assertThat(result.estimated).isTrue()
    }

    @Test
    fun `같은 과거 날짜를 반복 조회해도 DB는 한 번만 친다`() {
        val repo = FakeRepo(row(date, "1390.200000"))
        val adapter = adapter(repo)

        repeat(5) { adapter.toKrwOn(BigDecimal("100"), "USD", date) }

        assertThat(repo.callCount).isEqualTo(1)
    }

    // ── helpers ──────────────────────────────────────────────────

    private fun adapter(repo: HistoricalFxRateJpaRepository) =
        UnifiedAssetFxConverterAdapter(CurrencyConverter(StubFxRateService()), repo)

    private fun row(date: LocalDate, rate: String) = HistoricalFxRateEntity(
        id = UUID.randomUUID(), baseDate = date, currency = "USD",
        rateKrw = BigDecimal(rate), source = "ECOS", createdAt = LocalDateTime.now(),
    )

    private class StubFxRateService : FxRateService {
        override fun getUsdtToKrw(): BigDecimal = BigDecimal("1350")
        override fun setUsdtToKrw(rate: BigDecimal) = Unit
        override fun getCryptoToKrw(symbol: String): BigDecimal = BigDecimal("90000000")
        override fun setCryptoToKrw(symbol: String, rate: BigDecimal) = Unit
    }

    /** 조회 두 메서드만 쓰므로 나머지는 위임하지 않는다 */
    private open class FakeRepo(
        private val stored: HistoricalFxRateEntity? = null,
    ) : HistoricalFxRateJpaRepository by mock(HistoricalFxRateJpaRepository::class.java) {
        var callCount = 0
        var lastCurrency: String? = null

        override fun findTopByCurrencyAndBaseDateLessThanEqualOrderByBaseDateDesc(
            currency: String,
            baseDate: LocalDate,
        ): HistoricalFxRateEntity? {
            callCount++
            lastCurrency = currency
            return stored?.takeIf { it.currency == currency && !it.baseDate.isAfter(baseDate) }
        }
    }

    private class ExplodingRepo : FakeRepo() {
        override fun findTopByCurrencyAndBaseDateLessThanEqualOrderByBaseDateDesc(
            currency: String,
            baseDate: LocalDate,
        ): HistoricalFxRateEntity? = throw RuntimeException("DB down")
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*UnifiedAssetFxConverterAdapterTest*'`
Expected: 컴파일 실패 — `UnifiedAssetFxConverterAdapter`가 생성자 인자 2개를 받지 않는다

- [ ] **Step 3: 어댑터 구현**

`UnifiedAssetFxConverterAdapter.kt` 전체를 아래로 교체:

```kotlin
package com.allfolio.fx

import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.application.port.KrwConversion
import com.allfolio.unifiedasset.infrastructure.jpa.HistoricalFxRateJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * unified-asset의 [FxConverter] 포트를 backend-app FX 인프라로 연결하는 어댑터.
 *
 * - [toKrw]   현재 환율 (Redis 캐시) — 자산 평가액용
 * - [toKrwOn] 지정일 환율 (fx_rate_daily) — 현금흐름용
 *
 * 폴백 정책을 여기 한 곳에 모아 둔다. 소비 지점이 "과거 없으면 현재로" 규칙을
 * 각자 구현하면 같은 로직이 복제되고 넷째 소비자가 생길 때 또 복제된다.
 */
@Component
class UnifiedAssetFxConverterAdapter(
    private val currencyConverter: CurrencyConverter,
    private val historicalRates: HistoricalFxRateJpaRepository,
) : FxConverter {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 확정된 과거 환율은 변하지 않으므로 무기한 캐싱해도 안전하다.
     * 거래 수백 건짜리 sync에서 날짜별 조회가 반복되는 것을 막는 용도이고,
     * 프로세스 재시작 시 비워져도 무방하다. 오늘 이후는 캐싱하지 않는다 — 아직 확정 전이다.
     */
    private val cache = ConcurrentHashMap<String, Optional<ResolvedRate>>()

    private data class ResolvedRate(val rateKrw: BigDecimal, val rateDate: LocalDate)

    companion object {
        private val KST = ZoneId.of("Asia/Seoul")
        /** 과거 시계열을 가진 통화. ECOS로 채울 수 있는 것만 여기 들어간다. */
        private val HISTORICAL = setOf("USD")
    }

    override fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
        currencyConverter.toKrw(amount, currency)

    override fun toKrwOn(amount: BigDecimal, currency: String, date: LocalDate): KrwConversion {
        val code = normalize(currency)

        if (code == "KRW") return KrwConversion(amount, rateDate = null, estimated = false)

        // BTC/ETH는 과거 시세를 가진 소스가 없다 — 현행 현재가 환산을 유지한다
        if (code !in HISTORICAL) return estimatedNow(amount, currency)

        val resolved = lookup(code, date)
            ?: return estimatedNow(amount, currency).also {
                log.warn("[Fx] 과거 환율 없음 — 현재 환율로 환산 currency={} date={}", code, date)
            }

        return KrwConversion(
            amountKrw = (amount * resolved.rateKrw).setScale(0, RoundingMode.HALF_UP),
            rateDate = resolved.rateDate,
            estimated = false,
        )
    }

    private fun estimatedNow(amount: BigDecimal, currency: String) =
        KrwConversion(currencyConverter.toKrw(amount, currency), rateDate = null, estimated = true)

    /** USDT는 USD 시계열로 근사한다 — 현재 환율 경로(CurrencyConverter)와 같은 취급이다. */
    private fun normalize(currency: String): String =
        when (val code = currency.trim().uppercase()) {
            "USDT" -> "USD"
            else -> code
        }

    private fun lookup(code: String, date: LocalDate): ResolvedRate? {
        if (!date.isBefore(LocalDate.now(KST))) return query(code, date)
        return cache.computeIfAbsent("$code@$date") { Optional.ofNullable(query(code, date)) }.orElse(null)
    }

    private fun query(code: String, date: LocalDate): ResolvedRate? =
        runCatching {
            historicalRates
                .findTopByCurrencyAndBaseDateLessThanEqualOrderByBaseDateDesc(code, date)
                ?.let { ResolvedRate(it.rateKrw, it.baseDate) }
        }.getOrElse { e ->
            log.error("[Fx] 과거 환율 조회 실패 currency={} date={}: {}", code, date, e.message)
            null
        }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*UnifiedAssetFxConverterAdapterTest*'`
Expected: PASS (7 tests)

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/UnifiedAssetFxConverterAdapter.kt allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/UnifiedAssetFxConverterAdapterTest.kt
git commit -m "feat(fx): 어댑터에 과거 환율 조회·폴백·캐시 구현"
```

---

## Task 5: `SyncAccountUseCase` 소급 현금흐름 수정

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/SyncAccountUseCase.kt:169-192`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/SyncAccountUseCaseBackdatedInflowTest.kt`

- [ ] **Step 1: 실패하는 테스트 추가**

`SyncAccountUseCaseBackdatedInflowTest.kt`의 마지막 `@Test` 뒤, `// ── helpers ──` 주석 앞에 아래를 추가한다:

```kotlin
    @Test
    fun `USD 계좌는 오늘이 아니라 체결일 환율로 환산한다`() {
        val usdAccount = Account.create(
            userId = userId, provider = AccountProvider.STOCK,
            accountType = AccountType.STOCK, accountName = "달러계좌", currency = "USD",
        )
        val tradedOn = LocalDate.of(2025, 8, 11)
        val cashFlows = RecordingCashFlowRepository()
        // 체결일 1100, 오늘 1300 — 오늘 환율을 쓰면 130만이 나온다
        val datedFx = DatedFxConverter(on = tradedOn, rate = BigDecimal("1100"), now = BigDecimal("1300"))

        SyncAccountUseCase(
            accountRepository = FixedAccountRepository(usdAccount),
            assetRepository = StatefulAssetRepository(),
            adapters = listOf(object : SyncAdapter {
                override val supportedProvider = usdAccount.provider
                override fun sync(account: Account): List<Asset> = listOf(asset("1000"))
            }),
            snapshotService = mock(PerformanceSnapshotService::class.java),
            fx = datedFx,
            syncLogRepository = NoopSyncLogRepository(),
            reconMutex = AlwaysAcquiredReconMutex(),
            cashFlowRepository = cashFlows,
            stockTradeRepository = FakeStockTradeRepository(
                listOf(usdTrade(usdAccount, quantity = 10, price = 100, on = tradedOn)),
            ),
        ).execute(usdAccount.id)

        val flow = cashFlows.saved.single()
        assertThat(flow.amountKrw).isEqualByComparingTo("1100000")
        assertThat(flow.memo).doesNotContain("환율 추정치")
    }

    @Test
    fun `체결일 환율을 못 찾으면 메모에 추정치임을 남긴다`() {
        val usdAccount = Account.create(
            userId = userId, provider = AccountProvider.STOCK,
            accountType = AccountType.STOCK, accountName = "달러계좌", currency = "USD",
        )
        val tradedOn = LocalDate.of(2019, 3, 4)   // 백필 범위 밖
        val cashFlows = RecordingCashFlowRepository()
        val datedFx = DatedFxConverter(on = null, rate = BigDecimal.ZERO, now = BigDecimal("1300"))

        SyncAccountUseCase(
            accountRepository = FixedAccountRepository(usdAccount),
            assetRepository = StatefulAssetRepository(),
            adapters = listOf(object : SyncAdapter {
                override val supportedProvider = usdAccount.provider
                override fun sync(account: Account): List<Asset> = listOf(asset("1000"))
            }),
            snapshotService = mock(PerformanceSnapshotService::class.java),
            fx = datedFx,
            syncLogRepository = NoopSyncLogRepository(),
            reconMutex = AlwaysAcquiredReconMutex(),
            cashFlowRepository = cashFlows,
            stockTradeRepository = FakeStockTradeRepository(
                listOf(usdTrade(usdAccount, quantity = 10, price = 100, on = tradedOn)),
            ),
        ).execute(usdAccount.id)

        val flow = cashFlows.saved.single()
        assertThat(flow.amountKrw).isEqualByComparingTo("1300000")
        assertThat(flow.memo).contains("환율 추정치")
    }
```

같은 파일의 `// ── helpers ──` 구역에 아래 두 helper를 추가한다:

```kotlin
    private fun usdTrade(account: Account, quantity: Int, price: Int, on: LocalDate) =
        StockTrade.create(
            accountId = account.id, userId = userId, tradeType = StockTradeType.BUY,
            stockName = "AAPL", symbol = "AAPL",
            quantity = BigDecimal(quantity), price = BigDecimal(price),
            totalAmount = BigDecimal(quantity) * BigDecimal(price),
            fee = BigDecimal.ZERO, tax = BigDecimal.ZERO, tradedAt = on, memo = null,
        )
```

그리고 `// ── fakes ──` 구역에:

```kotlin
    /** on 날짜만 과거 환율을 가진 fake. on이 null이면 언제나 미보유(=추정치 폴백). */
    private class DatedFxConverter(
        private val on: LocalDate?,
        private val rate: BigDecimal,
        private val now: BigDecimal,
    ) : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
            if (currency.uppercase() == "KRW") amount else amount.multiply(now)

        override fun toKrwOn(amount: BigDecimal, currency: String, date: LocalDate) = when {
            currency.uppercase() == "KRW" -> KrwConversion(amount, null, false)
            date == on -> KrwConversion(amount.multiply(rate), date, false)
            else -> KrwConversion(amount.multiply(now), null, true)
        }
    }
```

파일 상단 임포트에 추가한다:
```kotlin
import com.allfolio.unifiedasset.application.port.KrwConversion
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests '*SyncAccountUseCaseBackdatedInflowTest*'`
Expected: FAIL — 첫 테스트가 `1300000`을 반환(오늘 환율), 둘째가 메모에 "환율 추정치" 없음

- [ ] **Step 3: `backdatedFlows` 수정**

`SyncAccountUseCase.kt`의 `backdatedFlows`(169~192행)를 아래로 교체:

```kotlin
    /** 매수는 투입(DEPOSIT), 매도는 회수(WITHDRAWAL). 포지션 계산과 같은 거래 유형 분류를 쓴다. */
    private fun backdatedFlows(account: Account, trades: List<StockTrade>): List<CashFlow> =
        trades.mapNotNull { trade ->
            val (type, amount) = when (trade.tradeType) {
                StockTradeType.BUY, StockTradeType.CREDIT_BUY ->
                    FlowType.DEPOSIT to trade.totalAmount + trade.fee + trade.tax
                StockTradeType.SELL, StockTradeType.CREDIT_SELL ->
                    FlowType.WITHDRAWAL to trade.totalAmount - trade.fee - trade.tax
                // 배당은 수익이지 외부 투입이 아니고, 미수는 포지션을 만들지 않는다
                else -> return@mapNotNull null
            }
            if (amount <= BigDecimal.ZERO) return@mapNotNull null

            // 오늘이 아니라 체결일 환율 — 오늘 환율로 환산하면 과거 USD 거래의 원금이 틀어진다
            val conversion = fx.toKrwOn(amount, account.currency, trade.tradedAt)
            val memo = buildString {
                append("거래 로그 기준 자동 기록(${trade.stockName})")
                // 시스템이 만드는 메모이므로 부정확함을 여기 남긴다
                if (conversion.estimated) append(" · 환율 추정치")
            }
            CashFlow.create(
                userId = account.userId,
                accountId = account.id,
                flowDate = trade.tradedAt,
                type = type,
                amount = amount,
                currency = account.currency,
                amountKrw = conversion.amountKrw,
                memo = memo,
            )
        }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests '*SyncAccountUseCase*'`
Expected: PASS — 신규 2건 + 기존 `BackdatedInflowTest`·`InitialFlowTest`·`NavTest`·`LoggingTest`·`SensitiveDataTest` 전부

기존 KRW 계좌 테스트가 깨지지 않는 이유: `toKrwOn` default가 KRW에 대해 `estimated=false`를
반환하므로 메모에 접미사가 붙지 않는다.

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/SyncAccountUseCase.kt allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/SyncAccountUseCaseBackdatedInflowTest.kt
git commit -m "fix(fx): 소급 현금흐름을 체결일 환율로 환산 (AF-93 오차 수정)"
```

---

## Task 6: `RecordCashFlowUseCase` 수정

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/RecordCashFlowUseCase.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/RecordCashFlowUseCaseTest.kt`

사용자가 쓴 메모는 서버가 고쳐 쓰지 않는다. 추정치일 때 WARN 로그만 남긴다.

- [ ] **Step 1: 실패하는 테스트 추가**

파일 상단 임포트에 추가한다 (`java.time.LocalDate`는 이미 있다):
```kotlin
import com.allfolio.unifiedasset.application.port.KrwConversion
```

`RecordCashFlowUseCaseTest.kt`의 마지막 `@Test`(`internal flow types are rejected on generic record path`) 뒤,
클래스 닫는 `}` 앞에 추가한다. helper 이름은 이 파일에 이미 있는 `InMemoryRepo`·`accountRepo`·`userId`를 쓰고,
단언은 이 파일의 기존 방식(JUnit `assertEquals`)에 맞춘다:

```kotlin
    @Test
    fun `과거 날짜 입금은 그날 환율로 환산하고 사용자 메모를 건드리지 않는다`() {
        val past = LocalDate.of(2025, 8, 11)
        val datedFx = object : FxConverter {
            override fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
                amount * BigDecimal("1400")

            override fun toKrwOn(amount: BigDecimal, currency: String, on: LocalDate) =
                KrwConversion(amount * BigDecimal("1100"), on, estimated = false)
        }
        val repo = InMemoryRepo()

        val flow = RecordCashFlowUseCase(repo, datedFx, accountRepo).record(
            userId = userId, accountId = null, flowDate = past, type = FlowType.DEPOSIT,
            amount = BigDecimal("1000"), currency = "USD", memo = "달러 입금",
        )

        // 오늘 환율 1400이 아니라 발생일 환율 1100
        assertEquals(0, BigDecimal("1100000").compareTo(flow.amountKrw))
        // 사용자가 쓴 메모는 그대로다
        assertEquals("달러 입금", flow.memo)
    }
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests '*RecordCashFlowUseCaseTest*'`
Expected: FAIL — `1300000`이 나온다 (현재 환율 사용)

- [ ] **Step 3: 유스케이스 수정**

`RecordCashFlowUseCase.kt` 전체를 아래로 교체:

```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@Service
class RecordCashFlowUseCase(
    private val repository: CashFlowRepository,
    private val fxConverter: FxConverter,
    private val accountRepository: AccountRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun record(
        userId: UUID, accountId: UUID?, flowDate: LocalDate, type: FlowType,
        amount: BigDecimal, currency: String, memo: String?,
    ): CashFlow {
        require(amount > BigDecimal.ZERO) { "입출금 금액은 양수여야 합니다" }
        require(!flowDate.isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Seoul")))) { "미래 날짜는 등록할 수 없습니다" }
        // 내부이동(환전·이체)은 반드시 페어 레그로만 기록 → /transfer, /fx 유스케이스 사용
        require(!type.isInternal()) { "환전·이체는 /transfer, /fx 로 기록해야 합니다(페어 레그)" }
        // 계좌 지정 시 소유권 검증 — 남의/없는 계좌는 404로 은닉 (QA)
        accountId?.let { id ->
            if (accountRepository.findById(id)?.userId != userId)
                throw NoSuchElementException("Account not found: $id")
        }
        val cur = com.allfolio.unifiedasset.domain.common.Currencies.normalize(currency)
        // 과거 날짜 입력을 허용하므로(위 require) 환산도 그 날짜 기준이어야 한다
        val conversion = fxConverter.toKrwOn(amount, cur, flowDate)
        if (conversion.estimated) {
            // 사용자가 쓴 메모는 서버가 고쳐 쓰지 않는다 — 로그로만 남긴다
            log.warn("[Fx] 과거 환율 없음 — 현재 환율로 환산 userId={} currency={} date={}", userId, cur, flowDate)
        }
        return repository.save(
            CashFlow.create(userId, accountId, flowDate, type, amount, cur, conversion.amountKrw, memo)
        )
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests '*RecordCashFlowUseCaseTest*'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/RecordCashFlowUseCase.kt allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/RecordCashFlowUseCaseTest.kt
git commit -m "fix(fx): 수동 입출금도 발생일 환율로 환산"
```

---

## Task 7: `RecordInternalFlowUseCase` 수정

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/RecordInternalFlowUseCase.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/RecordInternalFlowUseCaseTest.kt`

- [ ] **Step 1: 실패하는 테스트 추가**

파일 상단 임포트에 추가한다:
```kotlin
import com.allfolio.unifiedasset.application.port.KrwConversion
```

`RecordInternalFlowUseCaseTest.kt`의 마지막 `@Test` 뒤에 추가한다. helper 이름은 이 파일에 이미 있는
`repo`(15~23행)·`accountRepo`(43행)·`user`·`a1`·`date`를 그대로 쓴다. `toKrwOn`의 날짜 파라미터를
`on`으로 받는 이유는 클래스에 `date` 프로퍼티가 이미 있어 가려지기 때문이다:

```kotlin
    @Test
    fun `과거 날짜 환전은 그날 환율로 양쪽 레그를 환산한다`() {
        val datedFx = object : FxConverter {
            override fun toKrw(amount: BigDecimal, currency: String) =
                if (currency.uppercase() == "KRW") amount else amount * BigDecimal("1300")

            override fun toKrwOn(amount: BigDecimal, currency: String, on: LocalDate) =
                if (currency.uppercase() == "KRW") KrwConversion(amount, null, false)
                else KrwConversion(amount * BigDecimal("1100"), on, false)
        }

        val legs = RecordInternalFlowUseCase(repo, datedFx, accountRepo)
            .recordFx(user, a1, date, BigDecimal("1100000"), "KRW", BigDecimal("1000"), "USD", "달러 환전")

        // 오늘 환율 1300이 아니라 발생일 환율 1100
        assertThat(legs.single { it.currency == "USD" }.amountKrw).isEqualByComparingTo("1100000")
    }
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests '*RecordInternalFlowUseCaseTest*'`
Expected: FAIL — USD 레그가 `1300000`

- [ ] **Step 3: 유스케이스 수정**

`RecordInternalFlowUseCase.kt`에서 세 곳의 `fx.toKrw(...)`를 helper 호출로 바꾸고 helper를 추가한다.

`recordTransfer`의 29행:
```kotlin
        val krw = krwOn(amount, cur, flowDate)
```

`recordFx`의 49~50행:
```kotlin
        val (out, inn) = CashFlow.fxPair(
            userId, accountId, flowDate,
            fromAmount, fromCur, krwOn(fromAmount, fromCur, flowDate),
            toAmount, toCur, krwOn(toAmount, toCur, flowDate), memo,
            toAccountId = toAccountId,
        )
```

클래스 상단(`private val log`)과 `requireNotFuture` 위에 추가:
```kotlin
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 이체·환전은 과거 날짜를 허용하므로 환산도 그 날짜 기준이어야 한다.
     * 사용자가 쓴 메모는 건드리지 않는다 — 추정치일 때 로그만 남긴다.
     */
    private fun krwOn(amount: BigDecimal, currency: String, date: LocalDate): BigDecimal {
        val conversion = fx.toKrwOn(amount, currency, date)
        if (conversion.estimated) {
            log.warn("[Fx] 과거 환율 없음 — 현재 환율로 환산 currency={} date={}", currency, date)
        }
        return conversion.amountKrw
    }
```

파일 상단 임포트에 추가:
```kotlin
import org.slf4j.LoggerFactory
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests '*RecordInternalFlowUseCaseTest*'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/RecordInternalFlowUseCase.kt allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/RecordInternalFlowUseCaseTest.kt
git commit -m "fix(fx): 이체·환전도 발생일 환율로 환산"
```

---

## Task 8: ECOS 응답 파서

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/EcosResponseParser.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/EcosResponseParserTest.kt`

HTTP와 분리한다 — 파싱이 이 기능에서 가장 잘 틀리는 부분이고, 네트워크 없이 검증되어야 한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`EcosResponseParserTest.kt`:

```kotlin
package com.allfolio.fx

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

class EcosResponseParserTest {

    private val parser = EcosResponseParser()

    @Test
    fun `정상 응답에서 날짜와 값을 뽑는다`() {
        val json = """
            {"StatisticSearch":{"list_total_count":2,"row":[
              {"STAT_CODE":"X","TIME":"20250808","DATA_VALUE":"1385.5","UNIT_NAME":"원"},
              {"STAT_CODE":"X","TIME":"20250811","DATA_VALUE":"1390.2","UNIT_NAME":"원"}
            ]}}
        """.trimIndent()

        val result = parser.parse(json)

        assertThat(result.rates).hasSize(2)
        assertThat(result.rates[0].baseDate).isEqualTo(LocalDate.of(2025, 8, 8))
        assertThat(result.rates[0].rateKrw).isEqualByComparingTo("1385.5")
        assertThat(result.skipped).isZero()
    }

    @Test
    fun `값이 비었거나 0 이하인 행은 건너뛰고 센다`() {
        val json = """
            {"StatisticSearch":{"list_total_count":4,"row":[
              {"TIME":"20250808","DATA_VALUE":"1385.5"},
              {"TIME":"20250809","DATA_VALUE":""},
              {"TIME":"20250810","DATA_VALUE":"0"},
              {"TIME":"20250811","DATA_VALUE":"-1"}
            ]}}
        """.trimIndent()

        val result = parser.parse(json)

        assertThat(result.rates).hasSize(1)
        assertThat(result.skipped).isEqualTo(3)
    }

    @Test
    fun `날짜 형식이 어긋난 행은 건너뛴다`() {
        val json = """
            {"StatisticSearch":{"list_total_count":2,"row":[
              {"TIME":"2025Q3","DATA_VALUE":"1385.5"},
              {"TIME":"20250811","DATA_VALUE":"1390.2"}
            ]}}
        """.trimIndent()

        val result = parser.parse(json)

        assertThat(result.rates).hasSize(1)
        assertThat(result.skipped).isEqualTo(1)
    }

    @Test
    fun `ECOS 에러 응답은 예외로 올린다`() {
        val json = """{"RESULT":{"CODE":"INFO-200","MESSAGE":"해당하는 데이터가 없습니다."}}"""

        assertThatThrownBy { parser.parse(json) }
            .isInstanceOf(EcosApiException::class.java)
            .hasMessageContaining("INFO-200")
    }

    @Test
    fun `예상 밖 형식은 예외로 올린다`() {
        assertThatThrownBy { parser.parse("""{"something":"else"}""") }
            .isInstanceOf(EcosApiException::class.java)
    }

    @Test
    fun `행이 0건이면 빈 결과를 준다`() {
        val result = parser.parse("""{"StatisticSearch":{"list_total_count":0,"row":[]}}""")

        assertThat(result.rates).isEmpty()
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*EcosResponseParserTest*'`
Expected: 컴파일 실패 — `Unresolved reference: EcosResponseParser`

- [ ] **Step 3: 파서 작성**

`EcosResponseParser.kt`:

```kotlin
package com.allfolio.fx

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** ECOS 통계 한 행 — 기준일과 통화 1단위당 원화 값 */
data class EcosRate(val baseDate: LocalDate, val rateKrw: BigDecimal)

/** @param skipped 값·날짜가 이상해 버린 행 수. 조용히 삼키지 않고 호출자에게 보고한다. */
data class EcosParseResult(val rates: List<EcosRate>, val skipped: Int)

class EcosApiException(code: String, message: String) :
    RuntimeException("ECOS 오류 [$code] $message")

/**
 * ECOS StatisticSearch 응답 파서.
 *
 * 정상: {"StatisticSearch":{"row":[{"TIME":"20250811","DATA_VALUE":"1390.2"}, ...]}}
 * 오류: {"RESULT":{"CODE":"INFO-200","MESSAGE":"..."}}
 *
 * 두 형태가 최상위에서 갈리므로 트리로 읽고 분기한다.
 */
@Component
class EcosResponseParser(
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
    }

    fun parse(json: String): EcosParseResult {
        val root = mapper.readTree(json)

        val result = root.path("RESULT")
        if (!result.isMissingNode) {
            throw EcosApiException(result.path("CODE").asText(""), result.path("MESSAGE").asText(""))
        }

        val rows = root.path("StatisticSearch").path("row")
        if (!rows.isArray) {
            throw EcosApiException("PARSE", "예상치 못한 응답 형식입니다")
        }

        var skipped = 0
        val rates = rows.mapNotNull { row ->
            val time = row.path("TIME").asText("")
            val value = row.path("DATA_VALUE").asText("")

            val date = runCatching { LocalDate.parse(time, TIME_FORMAT) }.getOrNull()
            val rate = runCatching { BigDecimal(value) }.getOrNull()

            if (date == null || rate == null || rate <= BigDecimal.ZERO) {
                skipped++
                log.debug("[ECOS] 행 건너뜀 TIME={} DATA_VALUE={}", time, value)
                null
            } else {
                EcosRate(date, rate)
            }
        }

        return EcosParseResult(rates, skipped)
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*EcosResponseParserTest*'`
Expected: PASS (6 tests)

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/EcosResponseParser.kt allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/EcosResponseParserTest.kt
git commit -m "feat(fx): ECOS 응답 파서 (이상 행 스킵 카운트 포함)"
```

---

## Task 9: ECOS 설정과 HTTP 클라이언트

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/EcosProperties.kt`
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/EcosApiClient.kt`
- Modify: `allfolio-backend/backend-app/src/main/resources/application.yml` (228행 `fx:` 블록 뒤)

HTTP 호출부는 단위 테스트하지 않는다 — 파싱은 Task 8에서, 수집 로직은 Task 10에서 fake 클라이언트로
검증한다. 이 클래스에 남는 건 URL 조립뿐이다.

- [ ] **Step 1: 설정 클래스 작성**

`EcosProperties.kt`:

```kotlin
package com.allfolio.fx

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

/**
 * ECOS(한국은행 경제통계시스템) 접속 설정.
 *
 * series는 통화별 시계열 좌표다. 통계표·항목 코드는 ECOS 사이트에서 확인한 값을 넣는다 —
 * 추정한 코드는 조용히 0건을 반환해서 "코드가 틀렸는지 기간이 빈 건지" 구분되지 않는다.
 */
@ConfigurationProperties(prefix = "ecos")
data class EcosProperties(
    val apiKey: String = "",
    val baseUrl: String = "https://ecos.bok.or.kr",
    val series: Map<String, Series> = emptyMap(),
) {
    /**
     * @param unitDivisor 고시 단위를 1단위로 되돌리는 제수.
     *                    ECOS는 JPY를 100엔 기준으로 주므로 그때 100을 넣는다. USD는 1.
     */
    data class Series(
        val statCode: String = "",
        val itemCode: String = "",
        val unitDivisor: BigDecimal = BigDecimal.ONE,
    )
}
```

- [ ] **Step 2: 클라이언트 작성**

`EcosApiClient.kt`:

```kotlin
package com.allfolio.fx

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

interface EcosApiClient {
    /** 지정 기간의 일별 통계를 가져온다. 실패하면 예외를 던진다 — 호출자가 기존 값을 지키도록. */
    fun fetchDailyRates(
        statCode: String,
        itemCode: String,
        from: LocalDate,
        to: LocalDate,
    ): EcosParseResult
}

/**
 * ECOS StatisticSearch REST 호출.
 *
 * URL 형식:
 *   /api/StatisticSearch/{인증키}/json/kr/{시작건수}/{종료건수}/{통계표}/{주기}/{시작일}/{종료일}/{항목1}
 *
 * 인증키가 URL 경로에 들어가므로 로그에 전체 URL을 찍지 않는다.
 */
@Component
class EcosStatisticSearchClient(
    private val properties: EcosProperties,
    private val parser: EcosResponseParser,
) : EcosApiClient {

    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(properties.baseUrl)
            .codecs { it.defaultCodecs().maxInMemorySize(8 * 1024 * 1024) }
            .build()
    }

    companion object {
        private val TIMEOUT = Duration.ofSeconds(30)
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
        /** ECOS 1회 요청 상한. 일별 10년치가 약 2,600행이라 한 번에 받는다. */
        private const val MAX_ROWS = 100_000
    }

    override fun fetchDailyRates(
        statCode: String,
        itemCode: String,
        from: LocalDate,
        to: LocalDate,
    ): EcosParseResult {
        require(properties.apiKey.isNotBlank()) { "ECOS 인증키가 설정되지 않았습니다 (ECOS_API_KEY)" }
        require(statCode.isNotBlank() && itemCode.isNotBlank()) {
            "ECOS 통계표·항목 코드가 설정되지 않았습니다"
        }

        val path = "/api/StatisticSearch/${properties.apiKey}/json/kr/1/$MAX_ROWS/" +
            "$statCode/D/${from.format(DATE_FORMAT)}/${to.format(DATE_FORMAT)}/$itemCode"

        log.info("[ECOS] 조회 statCode={} itemCode={} {}~{}", statCode, itemCode, from, to)

        val body = webClient.get()
            .uri(path)
            .retrieve()
            .bodyToMono(String::class.java)
            .block(TIMEOUT)
            ?: throw EcosApiException("EMPTY", "응답 본문이 비어 있습니다")

        return parser.parse(body)
    }
}
```

- [ ] **Step 3: 설정 등록**

`application.yml`의 `fx:` 블록(220~228행) **바로 뒤**에 추가:

```yaml

# ECOS(한국은행) 과거 환율 시계열 — AF-100
# stat-code/item-code는 ECOS 사이트에서 확인한 값을 환경변수로 넣는다. 비어 있으면 백필만 실패하고
# 조회는 현재 환율 폴백으로 떨어지므로 서비스는 정상 동작한다.
ecos:
  api-key: ${ECOS_API_KEY:}
  base-url: ${ECOS_BASE_URL:https://ecos.bok.or.kr}
  series:
    USD:
      stat-code: ${ECOS_USD_STAT_CODE:}
      item-code: ${ECOS_USD_ITEM_CODE:}
      unit-divisor: 1
```

`BackendApplication.kt`에 `@ConfigurationPropertiesScan`이 없으면 `EcosProperties` 위에
`@Component`를 추가하는 대신 `@EnableConfigurationProperties(EcosProperties::class)`를
`BackendApplication`에 추가한다. 먼저 확인:

Run: `grep -n "ConfigurationPropertiesScan\|EnableConfigurationProperties" allfolio-backend/backend-app/src/main/kotlin/com/allfolio/app/BackendApplication.kt`

- [ ] **Step 4: 컴파일 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/EcosProperties.kt allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/EcosApiClient.kt allfolio-backend/backend-app/src/main/resources/application.yml allfolio-backend/backend-app/src/main/kotlin/com/allfolio/app/BackendApplication.kt
git commit -m "feat(fx): ECOS 설정 + StatisticSearch 클라이언트"
```

---

## Task 10: 백필 서비스

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/FxRateBackfillService.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/FxRateBackfillServiceTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

`FxRateBackfillServiceTest.kt`:

```kotlin
package com.allfolio.fx

import com.allfolio.unifiedasset.infrastructure.entity.HistoricalFxRateEntity
import com.allfolio.unifiedasset.infrastructure.jpa.HistoricalFxRateJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 하나은행 스크래퍼와 같은 원칙 — 빈 응답으로 기존 값을 덮지 않는다.
 * 마크업이든 통계표 코드든, 틀리면 조용히 0건이 오기 때문이다.
 */
class FxRateBackfillServiceTest {

    private val from = LocalDate.of(2025, 8, 7)
    private val to = LocalDate.of(2025, 8, 11)

    private val properties = EcosProperties(
        apiKey = "key",
        series = mapOf("USD" to EcosProperties.Series("STAT", "ITEM", BigDecimal.ONE)),
    )

    @Test
    fun `가져온 환율을 저장하고 요약을 반환한다`() {
        val repo = FakeRepo()
        val client = FakeClient(
            EcosParseResult(
                listOf(
                    EcosRate(LocalDate.of(2025, 8, 7), BigDecimal("1380.0")),
                    EcosRate(LocalDate.of(2025, 8, 8), BigDecimal("1385.5")),
                ),
                skipped = 1,
            ),
        )

        val summary = FxRateBackfillService(client, repo, properties).backfill("usd", from, to)

        assertThat(summary.currency).isEqualTo("USD")
        assertThat(summary.saved).isEqualTo(2)
        assertThat(summary.skipped).isEqualTo(1)
        assertThat(summary.firstDate).isEqualTo(LocalDate.of(2025, 8, 7))
        assertThat(summary.lastDate).isEqualTo(LocalDate.of(2025, 8, 8))
        assertThat(repo.saved).hasSize(2)
    }

    @Test
    fun `응답이 0건이면 아무것도 쓰지 않고 실패한다`() {
        val repo = FakeRepo()
        val client = FakeClient(EcosParseResult(emptyList(), skipped = 0))

        assertThatThrownBy { FxRateBackfillService(client, repo, properties).backfill("USD", from, to) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("0건")

        assertThat(repo.saved).isEmpty()
    }

    @Test
    fun `이미 있는 날짜는 새 행을 만들지 않고 값을 덮는다`() {
        val existing = HistoricalFxRateEntity(
            id = UUID.randomUUID(), baseDate = LocalDate.of(2025, 8, 7), currency = "USD",
            rateKrw = BigDecimal("1111.0"), source = "ECOS", createdAt = LocalDateTime.now(),
        )
        val repo = FakeRepo(existing)
        val client = FakeClient(
            EcosParseResult(listOf(EcosRate(LocalDate.of(2025, 8, 7), BigDecimal("1380.0"))), 0),
        )

        FxRateBackfillService(client, repo, properties).backfill("USD", from, to)

        assertThat(repo.saved).hasSize(1)
        assertThat(repo.saved.single().id).isEqualTo(existing.id)
        assertThat(repo.saved.single().rateKrw).isEqualByComparingTo("1380.0")
    }

    @Test
    fun `고시 단위를 1단위로 정규화해 저장한다`() {
        val repo = FakeRepo()
        val jpyProperties = EcosProperties(
            apiKey = "key",
            series = mapOf("JPY" to EcosProperties.Series("STAT", "ITEM", BigDecimal("100"))),
        )
        val client = FakeClient(
            EcosParseResult(listOf(EcosRate(LocalDate.of(2025, 8, 7), BigDecimal("950.0"))), 0),
        )

        FxRateBackfillService(client, repo, jpyProperties).backfill("JPY", from, to)

        // 100엔당 950원 → 1엔당 9.5원
        assertThat(repo.saved.single().rateKrw).isEqualByComparingTo("9.5")
    }

    @Test
    fun `설정에 없는 통화는 거부한다`() {
        assertThatThrownBy {
            FxRateBackfillService(FakeClient(EcosParseResult(emptyList(), 0)), FakeRepo(), properties)
                .backfill("EUR", from, to)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `from이 to보다 뒤면 거부한다`() {
        assertThatThrownBy {
            FxRateBackfillService(FakeClient(EcosParseResult(emptyList(), 0)), FakeRepo(), properties)
                .backfill("USD", to, from)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    // ── fakes ────────────────────────────────────────────────────

    private class FakeClient(private val result: EcosParseResult) : EcosApiClient {
        override fun fetchDailyRates(
            statCode: String, itemCode: String, from: LocalDate, to: LocalDate,
        ): EcosParseResult = result
    }

    private class FakeRepo(
        private vararg val existing: HistoricalFxRateEntity,
    ) : HistoricalFxRateJpaRepository by mock(HistoricalFxRateJpaRepository::class.java) {
        val saved = mutableListOf<HistoricalFxRateEntity>()

        override fun findAllByCurrencyAndBaseDateBetween(
            currency: String, from: LocalDate, to: LocalDate,
        ): List<HistoricalFxRateEntity> = existing.toList()

        override fun <S : HistoricalFxRateEntity> saveAll(entities: MutableIterable<S>): MutableList<S> {
            entities.forEach { saved.add(it) }
            return entities.toMutableList()
        }
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*FxRateBackfillServiceTest*'`
Expected: 컴파일 실패 — `Unresolved reference: FxRateBackfillService`

- [ ] **Step 3: 서비스 작성**

`FxRateBackfillService.kt`:

```kotlin
package com.allfolio.fx

import com.allfolio.unifiedasset.infrastructure.entity.HistoricalFxRateEntity
import com.allfolio.unifiedasset.infrastructure.jpa.HistoricalFxRateJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * @param saved   저장(신규+갱신)된 행 수
 * @param skipped 값·날짜가 이상해 버린 행 수 — 조용히 삼키지 않는다
 */
data class BackfillSummary(
    val currency: String,
    val from: LocalDate,
    val to: LocalDate,
    val saved: Int,
    val skipped: Int,
    val firstDate: LocalDate?,
    val lastDate: LocalDate?,
)

/**
 * ECOS 과거 환율 백필 (AF-100).
 *
 * 재실행이 안전해야 긴 기간을 나눠 돌릴 수 있으므로, 기존 행을 한 번에 읽어
 * 같은 (통화, 기준일)이면 값만 덮는다. 네이티브 UPSERT를 쓰지 않는 이유는
 * H2(테스트)와 Postgres(운영) 문법이 갈리기 때문이고, 어드민이 수동으로 한 번씩
 * 돌리는 경로라 동시 실행 경합을 걱정할 필요가 없다. 자연키 UNIQUE 제약이 최후 방어선이다.
 */
@Service
class FxRateBackfillService(
    private val client: EcosApiClient,
    private val repository: HistoricalFxRateJpaRepository,
    private val properties: EcosProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val SOURCE = "ECOS"
        private const val SCALE = 6
    }

    @Transactional
    fun backfill(currency: String, from: LocalDate, to: LocalDate): BackfillSummary {
        val code = currency.trim().uppercase()
        val series = properties.series[code]
            ?: throw IllegalArgumentException("ECOS 시계열 설정이 없는 통화입니다: $code")
        require(!from.isAfter(to)) { "from은 to보다 이후일 수 없습니다: $from > $to" }

        val result = client.fetchDailyRates(series.statCode, series.itemCode, from, to)

        // 빈 응답으로 기존 값을 덮지 않는다 — 통계표 코드가 틀려도 0건이 온다
        check(result.rates.isNotEmpty()) {
            "ECOS 응답 0건 — 기존 값을 덮지 않고 중단합니다 (currency=$code $from~$to)"
        }

        val existing = repository.findAllByCurrencyAndBaseDateBetween(code, from, to)
            .associateBy { it.baseDate }

        val rows = result.rates.map { rate ->
            // 고시 단위를 1단위로 되돌린다 — JPY 100엔 고시가 그대로 들어가면 100배가 된다
            val normalized = rate.rateKrw.divide(series.unitDivisor, SCALE, RoundingMode.HALF_UP)
            existing[rate.baseDate]?.apply {
                rateKrw = normalized
                source = SOURCE
            } ?: HistoricalFxRateEntity(
                id = UUID.randomUUID(),
                baseDate = rate.baseDate,
                currency = code,
                rateKrw = normalized,
                source = SOURCE,
                createdAt = LocalDateTime.now(),
            )
        }
        repository.saveAll(rows)

        val summary = BackfillSummary(
            currency = code, from = from, to = to,
            saved = rows.size, skipped = result.skipped,
            firstDate = result.rates.minOf { it.baseDate },
            lastDate = result.rates.maxOf { it.baseDate },
        )
        log.info("[ECOS] 백필 완료 {}", summary)
        return summary
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*FxRateBackfillServiceTest*'`
Expected: PASS (6 tests)

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/FxRateBackfillService.kt allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/FxRateBackfillServiceTest.kt
git commit -m "feat(fx): ECOS 백필 서비스 (0건 방어 + 단위 정규화 + 멱등)"
```

---

## Task 11: 어드민 엔드포인트와 전체 검증

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/api/admin/FxRateAdminController.kt`

`/api/admin/**`는 `SecurityConfig.kt:65`에서 `hasRole("ADMIN")`으로 이미 막혀 있다 — 별도 인가 코드 불필요.

- [ ] **Step 1: 엔드포인트 추가**

`FxRateAdminController.kt`의 `setCryptoKrw` 메서드 뒤(43행 `}` 앞)에 추가:

```kotlin

    /**
     * POST /api/admin/fx/backfill — ECOS 과거 환율 백필 (어드민 전용, AF-100)
     *
     * 예: POST /api/admin/fx/backfill?currency=USD&from=2020-01-01&to=2026-08-11
     * 멱등하다 — 같은 구간을 다시 돌리면 값만 덮는다.
     */
    @PostMapping("/backfill")
    fun backfill(
        @RequestParam(defaultValue = "USD") currency: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): ResponseEntity<BackfillSummary> =
        try {
            ResponseEntity.ok(backfillService.backfill(currency, from, to))
        } catch (e: IllegalStateException) {
            // ECOS가 0건을 준 경우 — 우리 잘못이 아니라 외부 응답 문제다
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, e.message, e)
        }
```

생성자에 서비스를 주입한다 (14~16행):

```kotlin
class FxRateAdminController(
    private val fxRateService: FxRateService,
    private val backfillService: FxRateBackfillService,
) {
```

파일 상단 임포트에 추가:

```kotlin
import com.allfolio.fx.BackfillSummary
import com.allfolio.fx.FxRateBackfillService
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
```

`ResponseStatusException`은 `GlobalExceptionHandler.kt:98`에서 이미 처리되므로 상태코드가 그대로 나간다.

- [ ] **Step 2: 전 모듈 테스트**

Run: `cd allfolio-backend && ./gradlew test`
Expected: BUILD SUCCESSFUL — 10개 모듈 전체. AF-98에서 만든 `Backend tests` CI 잡이 도는 것과 같은 범위다.

실패하면 고치고 다시 돌린다. 특히 `FxRateAdminControllerTest`나 `SecurityConfigAdminTest`가
`FxRateAdminController` 생성자 변경으로 깨질 수 있다 — 그 테스트에 `FxRateBackfillService`
목을 추가한다.

- [ ] **Step 3: 프론트엔드 영향 없음 확인**

Run: `grep -rn "amount_krw\|amountKrw" frontend/ --include="*.ts" --include="*.tsx" | grep -v node_modules | head`
Expected: 응답 필드명이 바뀌지 않았으므로 변경 불필요. 출력이 있어도 읽기만 하는 코드다.

- [ ] **Step 4: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/api/admin/FxRateAdminController.kt
git commit -m "feat(fx): 어드민 백필 엔드포인트 POST /api/admin/fx/backfill"
```

- [ ] **Step 5: PR 생성**

```bash
git push -u origin feat/af-100-historical-fx
```

```bash
gh pr create --base main --title "feat(fx): AF-100 과거 환율 백필 + 현금흐름 환산 오차 수정" --body "$(cat <<'EOF'
## 무엇

`cash_flow.amount_krw`가 조회 시점이 아니라 `flowDate` 시점 환율로 저장되게 바꿉니다.

## 왜

`FxConverter` → `CurrencyConverter` → `RedisFxRateService`(TTL 60초) 체인에 날짜 개념이 없어,
2023년 체결된 USD 매수가 2026년 환율로 환산돼 저장되고 있었습니다. 그 값이 netFlow로 들어가
TWR/MWR을 왜곡합니다. AF-93이 체결일·체결금액까지는 바로잡았지만 환율은 여전히 오늘 것이었습니다.

## 고친 곳

- `SyncAccountUseCase` — AF-93 소급 현금흐름
- `RecordCashFlowUseCase` — 사용자 과거 날짜 입출금
- `RecordInternalFlowUseCase` — 과거 날짜 이체·환전

자산 평가(NAV·리포트·대시보드) 약 25개 호출부는 현재 환율이 맞으므로 손대지 않았습니다.
경계 규칙: **cash_flow는 발생일 환율, 자산 평가는 오늘 환율.**

## 배포 순서

1. `docs/superpowers/migrations/2026-08-11-fx-rate-daily.sql`을 Neon에 실행 (ddl-auto=none)
2. 머지·배포
3. Render에 `ECOS_API_KEY`, `ECOS_USD_STAT_CODE`, `ECOS_USD_ITEM_CODE` 등록
4. `POST /api/admin/fx/backfill?currency=USD&from=...&to=...` 실행

**3·4를 안 해도 회귀는 없습니다** — 테이블이 비면 조회가 전부 miss로 떨어져 현재 환율 폴백,
즉 현행과 동일하게 동작합니다.

## 범위 밖

이미 저장된 잘못된 `cash_flow` 레코드 소급 정정, BTC/ETH 과거 시세, 일일 자동 갱신(AF-103).

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## 배포 체크리스트 (머지 후, 사용자 작업)

- [ ] Neon에 `2026-08-11-fx-rate-daily.sql` 실행 — **배포 전에**
- [ ] Render 환경변수 `ECOS_API_KEY` 등록
- [ ] Render 환경변수 `ECOS_USD_STAT_CODE` · `ECOS_USD_ITEM_CODE` 등록 (사전 확인한 값)
- [ ] 백필 실행 — 시작일은 가장 오래된 거래 체결일 기준
- [ ] 응답 요약에서 `saved`·`firstDate`·`lastDate`가 기대와 맞는지 확인
- [ ] 노션 AF-100 상태를 '완료'로 변경
