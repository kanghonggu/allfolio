# AF-99 하나은행 고시환율 수집기 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 로컬 `hana_fx_scraper.py`를 서버로 옮겨 회차별 고시환율을 수집·저장하고, 자산 평가의 USD 환산을 공식 매매기준율로 전환한다.

**Architecture:** 파싱을 HTTP와 분리해 네트워크 없이 검증한다. 저장 키는 `(기준일, 회차, 통화)` — 하나은행이 주말 조회에 직전 영업일 고시를 돌려주기 때문이다. 안전장치 넷이 저장을 막고, 2% 가드만 `force`로 뚫린다. 현재가 경로는 `FxRateService`에 default 메서드를 더해 갈아끼우고, USDT는 Binance에 남긴다.

**Tech Stack:** Kotlin 1.9.25 · Spring Boot 3.2.5 · Jsoup(신규) · WebClient · JPA/Hibernate · PostgreSQL(운영) / H2(테스트) · JUnit 5 + AssertJ + Mockito

**Spec:** `docs/superpowers/specs/2026-08-12-hana-fx-collector-design.md`

---

## 사전 확인

**H2 테스트가 가능한 모듈은 `unified-asset`뿐이다.** `backend-app`에는 H2가 없다(AF-100에서 확인). 따라서 엔티티·리포지토리는 `unified-asset`에, 수집·어댑터·어드민은 `backend-app`에 둔다 — AF-100과 같은 배치다.

**테스트 리소스 디렉터리가 리포에 없다.** HTML 픽스처는 Kotlin 문자열 리터럴로 인라인한다(AF-100의 ECOS JSON 픽스처와 같은 방식).

## 파일 구조

**생성**

| 파일 | 책임 |
|---|---|
| `unified-asset/.../infrastructure/entity/HanaFxQuoteEntity.kt` | `hana_fx_quote` 행 |
| `unified-asset/.../infrastructure/jpa/HanaFxQuoteJpaRepository.kt` | 최신 고시 조회 + 회차 단위 조회 |
| `backend-app/.../fx/hana/HanaFxParser.kt` | HTML → 스냅샷. HTTP와 분리 |
| `backend-app/.../fx/hana/HanaFxClient.kt` | POST 호출 |
| `backend-app/.../fx/hana/HanaFxGuards.kt` | 안전장치 판정 (순수 함수) |
| `backend-app/.../fx/hana/HanaFxCollectService.kt` | 조회 → 판정 → 저장 → 요약 |
| `backend-app/.../fx/HanaFxRateService.kt` | `FxRateService` 구현. `getUsdToKrw()`만 오버라이드 |
| `docs/superpowers/migrations/2026-08-12-hana-fx-quote.sql` | 운영 Neon 마이그레이션 |

**수정**

| 파일 | 변경 |
|---|---|
| `backend-app/build.gradle.kts` | `org.jsoup:jsoup` 추가 |
| `backend-app/.../fx/FxRateService.kt` | `getUsdToKrw()` default 추가 |
| `backend-app/.../fx/CurrencyConverter.kt` | USD/USDT 분기 분리 |
| `backend-app/.../fx/UnifiedAssetFxConverterAdapter.kt` | `toKrw`에서 `USDT→USD` 매핑 제거 |
| `backend-app/.../api/admin/FxRateAdminController.kt` | 수집 엔드포인트 + `GET /usdkrw` |
| `allfolio-backend/infra/postgres/init.sql` | `hana_fx_quote` 추가 |

---

## Task 1: Jsoup 의존성 추가

**Files:**
- Modify: `allfolio-backend/backend-app/build.gradle.kts`

- [ ] **Step 1: 의존성 추가**

`allfolio-backend/backend-app/build.gradle.kts`의 `implementation("com.opencsv:opencsv:5.9")` 바로 아래에 추가:

```kotlin
    // 하나은행 고시환율 HTML 파싱 (AF-99) — 공식 API가 없어 화면을 긁는다
    implementation("org.jsoup:jsoup:1.17.2")
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add allfolio-backend/backend-app/build.gradle.kts
git commit -m "chore(fx): 하나은행 HTML 파싱용 Jsoup 의존성 추가"
```

---

## Task 2: `hana_fx_quote` 스키마

**Files:**
- Create: `docs/superpowers/migrations/2026-08-12-hana-fx-quote.sql`
- Modify: `allfolio-backend/infra/postgres/init.sql` (파일 맨 끝)

- [ ] **Step 1: 마이그레이션 파일 작성**

```sql
-- AF-99 하나은행 회차별 고시환율 — 운영 Neon 1회성 마이그레이션
-- 실행: /opt/homebrew/opt/libpq/bin/psql "<NEON_DB_URL>" -f 2026-08-12-hana-fx-quote.sql
-- 반드시 백엔드 배포 "전"에 실행 (ddl-auto: none). 신규 테이블이라 기존 백엔드엔 무해. 멱등.

CREATE TABLE IF NOT EXISTS hana_fx_quote (
    id            UUID          NOT NULL,
    base_date     DATE          NOT NULL,   -- 하나은행이 준 기준일. 조회일자가 아니다
    round_no      INT           NOT NULL,   -- 고시 회차
    currency      VARCHAR(10)   NOT NULL,   -- ISO 3자리
    base_rate     NUMERIC(18,4) NOT NULL,   -- 매매기준율
    cash_buy      NUMERIC(18,4),            -- 현찰 사실 때
    cash_sell     NUMERIC(18,4),            -- 현찰 파실 때
    remit_send    NUMERIC(18,4),            -- 송금 보낼 때
    remit_receive NUMERIC(18,4),            -- 송금 받을 때
    collected_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_hana_fx_quote PRIMARY KEY (id),
    CONSTRAINT uk_hana_fx_quote UNIQUE (base_date, round_no, currency)
);

-- 평가 경로가 쓰는 "그 통화의 가장 최근 고시" 한 건 조회
CREATE INDEX IF NOT EXISTS idx_hana_fx_quote_latest
    ON hana_fx_quote (currency, base_date DESC, round_no DESC);

-- 검증: 테이블만 생성되고 데이터는 어드민 수집 API로 채운다
SELECT COUNT(*) AS rows FROM hana_fx_quote;
```

- [ ] **Step 2: init.sql 맨 끝에 추가**

`allfolio-backend/infra/postgres/init.sql` 끝에 이어 붙인다(앞에 빈 줄 하나):

```sql

-- ── hana_fx_quote ──────────────────────────────────────────────
-- AF-99 하나은행 회차별 고시환율. ECOS 일별 확정 종가(fx_rate_daily)와 섞지 않는다 —
-- 저쪽은 하루 한 건, 이쪽은 하루 안 여러 회차 × 통화별 6개 환율이다.
-- 키가 (기준일, 회차, 통화)인 이유: 하나은행은 주말·공휴일에 조회하면 직전 영업일 고시를
-- 돌려준다. 조회일자를 키로 쓰면 연휴 사흘 동안 같은 고시가 세 번 들어간다.
CREATE TABLE IF NOT EXISTS hana_fx_quote (
    id            UUID          NOT NULL,
    base_date     DATE          NOT NULL,
    round_no      INT           NOT NULL,
    currency      VARCHAR(10)   NOT NULL,
    base_rate     NUMERIC(18,4) NOT NULL,
    cash_buy      NUMERIC(18,4),
    cash_sell     NUMERIC(18,4),
    remit_send    NUMERIC(18,4),
    remit_receive NUMERIC(18,4),
    collected_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_hana_fx_quote PRIMARY KEY (id),
    CONSTRAINT uk_hana_fx_quote UNIQUE (base_date, round_no, currency)
);

CREATE INDEX IF NOT EXISTS idx_hana_fx_quote_latest
    ON hana_fx_quote (currency, base_date DESC, round_no DESC);
```

- [ ] **Step 3: 커밋**

```bash
git add docs/superpowers/migrations/2026-08-12-hana-fx-quote.sql allfolio-backend/infra/postgres/init.sql
git commit -m "feat(fx): hana_fx_quote 테이블 스키마 + Neon 마이그레이션"
```

---

## Task 3: 엔티티와 리포지토리

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/entity/HanaFxQuoteEntity.kt`
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/jpa/HanaFxQuoteJpaRepository.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/infrastructure/jpa/HanaFxQuoteJpaRepositoryTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.HanaFxQuoteEntity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.test.context.ContextConfiguration
import jakarta.persistence.EntityManager
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 평가 경로는 "그 통화의 가장 최근 고시" 한 건만 본다.
 * 같은 날 여러 회차가 쌓이므로 기준일뿐 아니라 회차까지 내림차순이어야 한다.
 */
@DataJpaTest
@ContextConfiguration(classes = [HanaFxQuoteJpaRepositoryTest.TestConfig::class])
class HanaFxQuoteJpaRepositoryTest {

    @Autowired private lateinit var repository: HanaFxQuoteJpaRepository
    @Autowired private lateinit var entityManager: EntityManager

    private val friday = LocalDate.of(2026, 8, 7)
    private val monday = LocalDate.of(2026, 8, 10)

    @Test
    fun `같은 날 여러 회차가 있으면 회차가 큰 것을 준다`() {
        save(friday, 1, "USD", "1380.0000")
        save(friday, 32, "USD", "1390.5000")
        save(friday, 12, "USD", "1385.0000")

        val found = repository.findTopByCurrencyOrderByBaseDateDescRoundNoDesc("USD")

        assertThat(found?.roundNo).isEqualTo(32)
        assertThat(found?.baseRate).isEqualByComparingTo("1390.5")
    }

    @Test
    fun `기준일이 더 최근이면 회차가 작아도 그것을 준다`() {
        save(friday, 32, "USD", "1390.5000")
        save(monday, 1, "USD", "1400.0000")

        val found = repository.findTopByCurrencyOrderByBaseDateDescRoundNoDesc("USD")

        assertThat(found?.baseDate).isEqualTo(monday)
        assertThat(found?.roundNo).isEqualTo(1)
    }

    @Test
    fun `다른 통화의 고시는 섞이지 않는다`() {
        save(friday, 32, "JPY", "9.5000")

        assertThat(repository.findTopByCurrencyOrderByBaseDateDescRoundNoDesc("USD")).isNull()
    }

    @Test
    fun `고시가 하나도 없으면 null을 준다`() {
        assertThat(repository.findTopByCurrencyOrderByBaseDateDescRoundNoDesc("USD")).isNull()
    }

    @Test
    fun `회차 단위 조회는 그 회차의 통화만 준다`() {
        save(friday, 32, "USD", "1390.5000")
        save(friday, 32, "JPY", "9.5000")
        save(friday, 31, "USD", "1389.0000")

        val rows = repository.findAllByBaseDateAndRoundNo(friday, 32)

        assertThat(rows.map { it.currency }).containsExactlyInAnyOrder("USD", "JPY")
    }

    @Test
    fun `같은 기준일 회차 통화는 두 번 들어갈 수 없다`() {
        save(friday, 32, "USD", "1390.5000")

        assertThatThrownBy {
            repository.saveAndFlush(
                entity(UUID.randomUUID(), friday, 32, "USD", "1391.0000"),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `조회해 온 행을 고쳐 다시 저장하면 행이 늘지 않고 값만 바뀐다`() {
        val id = UUID.randomUUID()
        repository.saveAndFlush(entity(id, friday, 32, "USD", "1390.5000"))
        entityManager.clear()

        val loaded = repository.findAllByBaseDateAndRoundNo(friday, 32).single()
        loaded.baseRate = BigDecimal("1391.2500")
        repository.saveAll(listOf(loaded))
        entityManager.flush(); entityManager.clear()

        assertThat(repository.count()).isEqualTo(1)
        assertThat(repository.findAllByBaseDateAndRoundNo(friday, 32).single().baseRate)
            .isEqualByComparingTo("1391.25")
    }

    private fun save(date: LocalDate, round: Int, currency: String, rate: String) {
        repository.saveAndFlush(entity(UUID.randomUUID(), date, round, currency, rate))
        entityManager.clear()
    }

    private fun entity(id: UUID, date: LocalDate, round: Int, currency: String, rate: String) =
        HanaFxQuoteEntity(
            id = id, baseDate = date, roundNo = round, currency = currency,
            baseRate = BigDecimal(rate), cashBuy = null, cashSell = null,
            remitSend = null, remitReceive = null, collectedAt = LocalDateTime.now(),
        )

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = [HanaFxQuoteEntity::class])
    @EnableJpaRepositories(basePackageClasses = [HanaFxQuoteJpaRepository::class])
    class TestConfig
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests '*HanaFxQuoteJpaRepositoryTest*' --rerun-tasks`
Expected: 컴파일 실패 — `Unresolved reference: HanaFxQuoteEntity`

- [ ] **Step 3: 엔티티 작성**

```kotlin
package com.allfolio.unifiedasset.infrastructure.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 하나은행 회차별 고시환율 (AF-99).
 *
 * ECOS 일별 확정 종가([HistoricalFxRateEntity])와 성격이 다르다 — 저쪽은 하루 한 건,
 * 이쪽은 하루 안 여러 회차 × 통화별 6개 환율이다. 섞지 않는다.
 *
 * baseDate는 **하나은행이 응답에 담아 준 기준일**이지 우리가 요청한 조회일자가 아니다.
 * 주말·공휴일에 조회하면 직전 영업일 고시가 돌아오므로, 조회일자를 키로 쓰면
 * 연휴 사흘 동안 같은 고시가 세 번 들어간다.
 *
 * 환율 필드가 var인 이유: 같은 회차를 다시 수집하면 값만 덮기 때문.
 */
@Entity
@Table(
    name = "hana_fx_quote",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_hana_fx_quote", columnNames = ["base_date", "round_no", "currency"]),
    ],
)
class HanaFxQuoteEntity(
    @Id val id: UUID,
    @Column(name = "base_date", nullable = false) val baseDate: LocalDate,
    @Column(name = "round_no", nullable = false) val roundNo: Int,
    @Column(name = "currency", nullable = false, length = 10) val currency: String,
    @Column(name = "base_rate", nullable = false, precision = 18, scale = 4) var baseRate: BigDecimal,
    @Column(name = "cash_buy", precision = 18, scale = 4) var cashBuy: BigDecimal?,
    @Column(name = "cash_sell", precision = 18, scale = 4) var cashSell: BigDecimal?,
    @Column(name = "remit_send", precision = 18, scale = 4) var remitSend: BigDecimal?,
    @Column(name = "remit_receive", precision = 18, scale = 4) var remitReceive: BigDecimal?,
    @Column(name = "collected_at", nullable = false) val collectedAt: LocalDateTime,
)
```

- [ ] **Step 4: 리포지토리 작성**

```kotlin
package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.HanaFxQuoteEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

interface HanaFxQuoteJpaRepository : JpaRepository<HanaFxQuoteEntity, UUID> {

    /**
     * 그 통화의 가장 최근 고시 한 건. 평가 경로가 쓴다.
     * 같은 날 여러 회차가 쌓이므로 기준일 다음에 회차까지 내림차순이어야 한다.
     */
    fun findTopByCurrencyOrderByBaseDateDescRoundNoDesc(currency: String): HanaFxQuoteEntity?

    /** 수집 시 같은 회차의 기존 행을 한 번에 읽어 덮어쓸 대상을 가려낸다 */
    fun findAllByBaseDateAndRoundNo(baseDate: LocalDate, roundNo: Int): List<HanaFxQuoteEntity>
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests '*HanaFxQuoteJpaRepositoryTest*' --rerun-tasks`
Expected: PASS (7 tests)

- [ ] **Step 6: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/entity/HanaFxQuoteEntity.kt allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/jpa/HanaFxQuoteJpaRepository.kt allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/infrastructure/jpa/HanaFxQuoteJpaRepositoryTest.kt
git commit -m "feat(fx): hana_fx_quote 엔티티 + 최신 고시 조회 리포지토리"
```

---

## Task 4: HTML 파서

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/hana/HanaFxParser.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/hana/HanaFxParserTest.kt`

파싱이 이 기능에서 가장 잘 깨지는 부분이다. HTTP와 분리해 네트워크 없이 검증한다.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
package com.allfolio.fx.hana

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

class HanaFxParserTest {

    private val parser = HanaFxParser()

    /** 11개 컬럼: 통화·현찰사실때(환율,스프레드)·현찰파실때(환율,스프레드)·송금보낼때·송금받을때·외화수표파실때·매매기준율·환가료율·미화환산율 */
    private fun row(name: String, vararg cells: String) =
        "<tr><td>$name</td>" + cells.joinToString("") { "<td>$it</td>" } + "</tr>"

    private fun page(meta: String, vararg rows: String) = """
        <html><body>
          <div>$meta</div>
          <table><tbody>${rows.joinToString("")}</tbody></table>
        </body></html>
    """.trimIndent()

    private val usdRow = row("미국 USD",
        "1,414.50", "1.75", "1,365.50", "1.75", "1,404.00", "1,376.00", "1,375.00",
        "1,390.00", "2.5", "1.0")

    @Test
    fun `기준일과 회차를 뽑는다`() {
        val result = parser.parse(page("기준일 : 2026년08월11일 (32회차)", usdRow))

        assertThat(result.baseDate).isEqualTo(LocalDate.of(2026, 8, 11))
        assertThat(result.roundNo).isEqualTo(32)
    }

    @Test
    fun `통화명에서 3자리 코드를 뽑고 환율 여섯 개를 읽는다`() {
        val result = parser.parse(page("기준일 : 2026년08월11일 (32회차)", usdRow))

        val usd = result.rows.single()
        assertThat(usd.currency).isEqualTo("USD")
        assertThat(usd.baseRate).isEqualByComparingTo("1390.00")
        assertThat(usd.cashBuy).isEqualByComparingTo("1414.50")
        assertThat(usd.cashSell).isEqualByComparingTo("1365.50")
        assertThat(usd.remitSend).isEqualByComparingTo("1404.00")
        assertThat(usd.remitReceive).isEqualByComparingTo("1376.00")
        assertThat(result.skipped).isZero()
    }

    @Test
    fun `100단위 통화는 환율만 100으로 나눈다`() {
        // 일본 JPY(100): 매매기준율 950 → 1엔당 9.5. 스프레드·환가료율·미화환산율은 그대로
        val jpy = row("일본 JPY(100)",
            "966.00", "1.75", "934.00", "1.75", "959.00", "941.00", "940.00",
            "950.00", "2.5", "0.68")

        val result = parser.parse(page("기준일 : 2026년08월11일 (32회차)", jpy))

        val row = result.rows.single()
        assertThat(row.currency).isEqualTo("JPY")
        assertThat(row.baseRate).isEqualByComparingTo("9.50")
        assertThat(row.cashBuy).isEqualByComparingTo("9.66")
        assertThat(row.remitSend).isEqualByComparingTo("9.59")
    }

    @Test
    fun `컬럼 수가 11이 아닌 행은 버리고 센다`() {
        val short = "<tr><td>미국 USD</td><td>1,390.00</td></tr>"

        val result = parser.parse(page("기준일 : 2026년08월11일 (32회차)", usdRow, short))

        assertThat(result.rows).hasSize(1)
        assertThat(result.skipped).isEqualTo(1)
    }

    @Test
    fun `통화 코드를 못 뽑는 행은 버리고 센다`() {
        val noCode = row("합계",
            "1,414.50", "1.75", "1,365.50", "1.75", "1,404.00", "1,376.00", "1,375.00",
            "1,390.00", "2.5", "1.0")

        val result = parser.parse(page("기준일 : 2026년08월11일 (32회차)", usdRow, noCode))

        assertThat(result.rows).hasSize(1)
        assertThat(result.skipped).isEqualTo(1)
    }

    @Test
    fun `매매기준율이 숫자가 아닌 행은 버리고 센다`() {
        val dash = row("영국 GBP",
            "-", "-", "-", "-", "-", "-", "-", "-", "-", "-")

        val result = parser.parse(page("기준일 : 2026년08월11일 (32회차)", usdRow, dash))

        assertThat(result.rows).hasSize(1)
        assertThat(result.skipped).isEqualTo(1)
    }

    @Test
    fun `테이블이 비면 빈 결과를 준다`() {
        val result = parser.parse(page("기준일 : 2026년08월11일 (32회차)"))

        assertThat(result.rows).isEmpty()
    }

    @Test
    fun `기준일을 못 읽으면 예외로 올린다`() {
        assertThatThrownBy { parser.parse(page("점검 중입니다", usdRow)) }
            .isInstanceOf(HanaFxParseException::class.java)
            .hasMessageContaining("기준일")
    }

    @Test
    fun `회차를 못 읽으면 예외로 올린다`() {
        assertThatThrownBy { parser.parse(page("기준일 : 2026년08월11일", usdRow)) }
            .isInstanceOf(HanaFxParseException::class.java)
            .hasMessageContaining("회차")
    }

    @Test
    fun `테이블이 아예 없으면 예외로 올린다`() {
        assertThatThrownBy { parser.parse("<html><body>기준일 : 2026년08월11일 (32회차)</body></html>") }
            .isInstanceOf(HanaFxParseException::class.java)
            .hasMessageContaining("테이블")
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*HanaFxParserTest*' --rerun-tasks`
Expected: 컴파일 실패 — `Unresolved reference: HanaFxParser`

- [ ] **Step 3: 파서 작성**

```kotlin
package com.allfolio.fx.hana

import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** 고시 한 행. 환율은 모두 통화 1단위 기준으로 정규화된 값이다. */
data class HanaFxRow(
    val currency: String,
    val baseRate: BigDecimal,
    val cashBuy: BigDecimal?,
    val cashSell: BigDecimal?,
    val remitSend: BigDecimal?,
    val remitReceive: BigDecimal?,
)

/**
 * @param skipped 컬럼 수·통화코드·숫자 파싱에 실패해 버린 행 수.
 *                조용히 삼키지 않고 호출자에게 보고한다.
 */
data class HanaFxSnapshot(
    val baseDate: LocalDate,
    val roundNo: Int,
    val rows: List<HanaFxRow>,
    val skipped: Int,
)

class HanaFxParseException(message: String) : RuntimeException("하나은행 응답 파싱 실패: $message")

/**
 * 하나은행 고시환율 화면 파서.
 *
 * 공식 API가 아니라 마크업이 바뀌면 예외가 아니라 조용히 빈 테이블이 온다.
 * 그래서 "구조가 아예 다르다"(기준일·회차·테이블 부재)는 예외로 올리고,
 * "행 하나가 이상하다"는 버리되 센다. 둘을 섞으면 전체 실패와 부분 실패를 구분할 수 없다.
 *
 * 컬럼 순서(11개): 통화 · 현찰사실때(환율, 스프레드) · 현찰파실때(환율, 스프레드) ·
 * 송금보낼때 · 송금받을때 · 외화수표파실때 · 매매기준율 · 환가료율 · 미화환산율
 */
@Component
class HanaFxParser {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val COLUMN_COUNT = 11
        private val BASE_DATE = Regex("""기준일\s*:\s*(\d{4})년\s*(\d{2})월\s*(\d{2})일""")
        private val ROUND_NO = Regex("""\((\d+)회차\)""")
        private val CURRENCY_CODE = Regex("""([A-Z]{3})""")
        /** 통화명에 (100)이 붙으면 100단위 고시다 — JPY·IDR·VND 등 */
        private val PER_HUNDRED = Regex("""\(\s*100\s*\)""")

        // 컬럼 인덱스 (0 = 통화)
        private const val CASH_BUY = 1
        private const val CASH_SELL = 3
        private const val REMIT_SEND = 5
        private const val REMIT_RECEIVE = 6
        private const val BASE_RATE = 8
    }

    fun parse(html: String): HanaFxSnapshot {
        val doc = Jsoup.parse(html)
        val text = doc.text()

        val dateMatch = BASE_DATE.find(text)
            ?: throw HanaFxParseException("기준일을 찾지 못했습니다")
        val roundMatch = ROUND_NO.find(text)
            ?: throw HanaFxParseException("고시 회차를 찾지 못했습니다")

        val baseDate = LocalDate.of(
            dateMatch.groupValues[1].toInt(),
            dateMatch.groupValues[2].toInt(),
            dateMatch.groupValues[3].toInt(),
        )
        val roundNo = roundMatch.groupValues[1].toInt()

        val table = doc.selectFirst("table")
            ?: throw HanaFxParseException("환율 테이블을 찾지 못했습니다")

        var skipped = 0
        val rows = table.select("tr").mapNotNull { tr ->
            val cells = tr.select("td").map { it.text().trim() }
            if (cells.size != COLUMN_COUNT) {
                if (cells.isNotEmpty()) skipped++
                return@mapNotNull null
            }
            toRow(cells) ?: run { skipped++; null }
        }

        if (skipped > 0) log.warn("[하나은행] 버린 행 {}건 baseDate={} round={}", skipped, baseDate, roundNo)
        return HanaFxSnapshot(baseDate, roundNo, rows, skipped)
    }

    private fun toRow(cells: List<String>): HanaFxRow? {
        val name = cells[0]
        val code = CURRENCY_CODE.find(name)?.groupValues?.get(1) ?: return null
        val divisor = if (PER_HUNDRED.containsMatchIn(name)) BigDecimal(100) else BigDecimal.ONE

        // 매매기준율이 없으면 그 행은 쓸모가 없다 — 평가·화면 양쪽이 이 값을 쓴다
        val baseRate = number(cells[BASE_RATE], divisor) ?: return null

        return HanaFxRow(
            currency = code,
            baseRate = baseRate,
            cashBuy = number(cells[CASH_BUY], divisor),
            cashSell = number(cells[CASH_SELL], divisor),
            remitSend = number(cells[REMIT_SEND], divisor),
            remitReceive = number(cells[REMIT_RECEIVE], divisor),
        )
    }

    /** 스프레드·환가료율·미화환산율에는 쓰지 않는다 — %·비율이라 100으로 나누면 안 된다 */
    private fun number(raw: String, divisor: BigDecimal): BigDecimal? =
        runCatching { BigDecimal(raw.replace(",", "")) }
            .getOrNull()
            ?.takeIf { it > BigDecimal.ZERO }
            ?.divide(divisor, 4, RoundingMode.HALF_UP)
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*HanaFxParserTest*' --rerun-tasks`
Expected: PASS (10 tests)

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/hana/HanaFxParser.kt allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/hana/HanaFxParserTest.kt
git commit -m "feat(fx): 하나은행 고시환율 HTML 파서 (100단위 정규화 + 이상 행 스킵)"
```

---

## Task 5: 안전장치 판정

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/hana/HanaFxGuards.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/hana/HanaFxGuardsTest.kt`

순수 함수로 분리한다 — DB도 HTTP도 없이 판정만 검증할 수 있어야 한다.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
package com.allfolio.fx.hana

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * 하나은행은 마크업이 바뀌면 예외가 아니라 조용히 빈/부분 테이블을 준다.
 * 판정은 "저장을 막을 것인가"만 결정하고, 저장 자체는 서비스가 한다.
 */
class HanaFxGuardsTest {

    private val guards = HanaFxGuards()

    private fun rows(vararg pairs: Pair<String, String>) =
        pairs.map { (c, r) -> HanaFxRow(c, BigDecimal(r), null, null, null, null) }

    @Test
    fun `정상이면 아무것도 걸리지 않는다`() {
        val anomalies = guards.check(
            rows = rows("USD" to "1390", "JPY" to "9.5"),
            previousRates = mapOf("USD" to BigDecimal("1385")),
            previousRowCount = 2,
            force = false,
        )

        assertThat(anomalies).isEmpty()
    }

    @Test
    fun `USD가 없으면 걸린다`() {
        val anomalies = guards.check(
            rows = rows("JPY" to "9.5"),
            previousRates = emptyMap(),
            previousRowCount = null,
            force = false,
        )

        assertThat(anomalies).anyMatch { it.contains("USD") }
    }

    @Test
    fun `행 수가 직전의 절반 미만이면 걸린다`() {
        val anomalies = guards.check(
            rows = rows("USD" to "1390"),
            previousRates = emptyMap(),
            previousRowCount = 58,
            force = false,
        )

        assertThat(anomalies).anyMatch { it.contains("행 수") }
    }

    @Test
    fun `행 수가 직전의 절반이면 걸리지 않는다`() {
        val anomalies = guards.check(
            rows = rows("USD" to "1390", "JPY" to "9.5"),
            previousRates = emptyMap(),
            previousRowCount = 4,
            force = false,
        )

        assertThat(anomalies).isEmpty()
    }

    @Test
    fun `직전 값 대비 2퍼센트를 넘게 움직이면 걸린다`() {
        val anomalies = guards.check(
            rows = rows("USD" to "1420"),           // 1385 → 1420 = +2.53%
            previousRates = mapOf("USD" to BigDecimal("1385")),
            previousRowCount = 1,
            force = false,
        )

        assertThat(anomalies).anyMatch { it.contains("USD") && it.contains("변동") }
    }

    @Test
    fun `2퍼센트 이내면 걸리지 않는다`() {
        val anomalies = guards.check(
            rows = rows("USD" to "1410"),           // 1385 → 1410 = +1.80%
            previousRates = mapOf("USD" to BigDecimal("1385")),
            previousRowCount = 1,
            force = false,
        )

        assertThat(anomalies).isEmpty()
    }

    @Test
    fun `force는 변동 가드만 뚫고 USD 부재는 못 뚫는다`() {
        val moved = guards.check(
            rows = rows("USD" to "1420"),
            previousRates = mapOf("USD" to BigDecimal("1385")),
            previousRowCount = 1,
            force = true,
        )
        assertThat(moved).isEmpty()

        val missing = guards.check(
            rows = rows("JPY" to "9.5"),
            previousRates = emptyMap(),
            previousRowCount = null,
            force = true,
        )
        assertThat(missing).anyMatch { it.contains("USD") }
    }

    @Test
    fun `force는 행 수 급감도 못 뚫는다`() {
        val anomalies = guards.check(
            rows = rows("USD" to "1390"),
            previousRates = emptyMap(),
            previousRowCount = 58,
            force = true,
        )

        assertThat(anomalies).anyMatch { it.contains("행 수") }
    }

    @Test
    fun `비교 대상이 없는 첫 수집은 통과시킨다`() {
        // 직전 값도 직전 행 수도 없다. 여기서 막으면 수집을 영영 시작할 수 없다
        val anomalies = guards.check(
            rows = rows("USD" to "1390"),
            previousRates = emptyMap(),
            previousRowCount = null,
            force = false,
        )

        assertThat(anomalies).isEmpty()
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*HanaFxGuardsTest*' --rerun-tasks`
Expected: 컴파일 실패 — `Unresolved reference: HanaFxGuards`

- [ ] **Step 3: 판정 작성**

```kotlin
package com.allfolio.fx.hana

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 저장 전 안전장치. 하나은행은 공식 API가 아니라 마크업이 바뀌면 예외가 아니라
 * 조용히 빈/부분 테이블을 돌려준다.
 *
 * 판정만 하고 저장은 하지 않는다 — DB도 HTTP도 없이 검증할 수 있어야 하기 때문이다.
 * 반환이 비어 있으면 저장해도 좋다는 뜻이다.
 */
@Component
class HanaFxGuards {

    companion object {
        /** 평가 경로가 USD를 쓰므로 USD 없는 수집은 쓸모가 없다 */
        private const val REQUIRED = "USD"
        private val MIN_ROW_RATIO = BigDecimal("0.5")
        private val MAX_CHANGE_RATIO = BigDecimal("0.02")
    }

    /**
     * @param previousRates   통화별 직전 고시 매매기준율. 없는 통화는 변동 검사를 건너뛴다
     * @param previousRowCount 직전 수집의 통화 수. null이면 첫 수집이라 검사를 건너뛴다
     * @param force           2% 변동 가드만 무시한다. 실제로 2% 넘게 움직인 날 영구히 막히는 걸 푸는 용도
     * @return 걸린 항목. 비어 있으면 저장 가능
     */
    fun check(
        rows: List<HanaFxRow>,
        previousRates: Map<String, BigDecimal>,
        previousRowCount: Int?,
        force: Boolean,
    ): List<String> {
        val anomalies = mutableListOf<String>()

        if (rows.none { it.currency == REQUIRED }) {
            anomalies += "필수 통화 $REQUIRED 가 응답에 없습니다 (통화 ${rows.size}개)"
        }

        // 첫 수집이면 비교 대상이 없다. 여기서 막으면 수집을 시작할 수 없다
        if (previousRowCount != null) {
            val threshold = BigDecimal(previousRowCount).multiply(MIN_ROW_RATIO)
            if (BigDecimal(rows.size) < threshold) {
                anomalies += "행 수가 직전 수집의 절반 미만입니다 (${rows.size} < $previousRowCount)"
            }
        }

        if (!force) {
            rows.forEach { row ->
                val previous = previousRates[row.currency] ?: return@forEach
                if (previous <= BigDecimal.ZERO) return@forEach
                val change = (row.baseRate - previous).abs()
                    .divide(previous, 6, RoundingMode.HALF_UP)
                if (change > MAX_CHANGE_RATIO) {
                    anomalies += "${row.currency} 변동이 2%를 넘습니다 ($previous → ${row.baseRate})"
                }
            }
        }

        return anomalies
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*HanaFxGuardsTest*' --rerun-tasks`
Expected: PASS (9 tests)

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/hana/HanaFxGuards.kt allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/hana/HanaFxGuardsTest.kt
git commit -m "feat(fx): 하나은행 수집 안전장치 판정 (USD 부재·행 급감·2% 변동)"
```

---

## Task 6: HTTP 클라이언트

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/hana/HanaFxClient.kt`

HTTP 호출부는 단위 테스트하지 않는다 — 파싱은 Task 4에서, 수집 로직은 Task 7에서 fake로 검증한다. 이 클래스에 남는 건 폼 조립뿐이다.

- [ ] **Step 1: 클라이언트 작성**

```kotlin
package com.allfolio.fx.hana

import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientException
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

interface HanaFxClient {
    /** 지정일 고시 화면 HTML. 실패하면 예외를 던진다 — 호출자가 기존 값을 지키도록. */
    fun fetch(date: LocalDate): String
}

/**
 * 하나은행 고시환율 조회.
 *
 * 원본 `hana_fx_scraper.py`의 폼 파라미터를 그대로 옮겼다. 공식 API가 아니므로
 * `User-Agent`와 `Referer`가 없으면 응답이 달라질 수 있다.
 *
 * `pbldDvCd`: 오늘이면 3(현재고시), 과거면 0(최종고시).
 * 오늘 조회는 장중에 회차가 계속 올라가고, 과거 조회는 그날의 마지막 회차가 온다.
 */
@Component
class HanaFxWebClient : HanaFxClient {

    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(BASE_URL)
            .defaultHeader("User-Agent", USER_AGENT)
            .defaultHeader("Referer", REFERER)
            .codecs { it.defaultCodecs().maxInMemorySize(4 * 1024 * 1024) }
            .build()
    }

    companion object {
        private const val BASE_URL = "https://www.kebhana.com"
        private const val PATH = "/cms/rate/wpfxd651_01i_01.do"
        private const val REFERER = "https://www.kebhana.com/cms/rate/wpfxd651_01i.do"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
        private val TIMEOUT = Duration.ofSeconds(20)
        private val KST = ZoneId.of("Asia/Seoul")
        private val COMPACT = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val DASHED = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }

    override fun fetch(date: LocalDate): String {
        val isToday = date == LocalDate.now(KST)
        val form = LinkedMultiValueMap<String, String>().apply {
            add("ajax", "true")
            add("curCd", "")
            add("tmpInqStrDt", date.format(DASHED))
            add("pbldDvCd", if (isToday) "3" else "0")
            add("pbldSqn", "")
            add("hid_key_data", "")
            add("inqStrDt", date.format(COMPACT))
            add("inqKindCd", "1")
            add("hid_enc_data", "")
            add("requestTarget", "searchContentDiv")
        }

        log.info("[하나은행] 고시 조회 date={} 구분={}", date, if (isToday) "현재고시" else "최종고시")

        return try {
            webClient.post()
                .uri(PATH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(String::class.java)
                .block(TIMEOUT)
                ?: throw HanaFxParseException("응답 본문이 비어 있습니다")
        } catch (e: HanaFxParseException) {
            throw e
        } catch (e: WebClientException) {
            log.warn("[하나은행] 호출 실패 date={} reason={}", date, e.javaClass.simpleName)
            throw HanaFxParseException("하나은행 호출에 실패했습니다")
        }
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/hana/HanaFxClient.kt
git commit -m "feat(fx): 하나은행 고시환율 조회 클라이언트"
```

---

## Task 7: 수집 서비스

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/hana/HanaFxCollectService.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/hana/HanaFxCollectServiceTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
package com.allfolio.fx.hana

import com.allfolio.unifiedasset.infrastructure.entity.HanaFxQuoteEntity
import com.allfolio.unifiedasset.infrastructure.jpa.HanaFxQuoteJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class HanaFxCollectServiceTest {

    private val requested = LocalDate.of(2026, 8, 9)     // 토요일
    private val friday = LocalDate.of(2026, 8, 7)

    @Test
    fun `응답이 말하는 기준일로 저장한다 — 조회일자가 아니다`() {
        // 토요일에 조회하면 하나은행은 금요일 고시를 돌려준다.
        // 조회일자로 저장하면 연휴 사흘 동안 같은 고시가 세 번 들어간다
        val repo = FakeRepo()
        val summary = service(repo, snapshot(friday, 32, "USD" to "1390")).collect(requested, force = false)

        assertThat(summary.baseDate).isEqualTo(friday)
        assertThat(repo.saved.single().baseDate).isEqualTo(friday)
    }

    @Test
    fun `수집 결과를 저장하고 요약을 반환한다`() {
        val repo = FakeRepo()

        val summary = service(repo, snapshot(friday, 32, "USD" to "1390", "JPY" to "9.5"))
            .collect(requested, force = false)

        assertThat(summary.roundNo).isEqualTo(32)
        assertThat(summary.currencies).isEqualTo(2)
        assertThat(summary.inserted).isEqualTo(2)
        assertThat(summary.updated).isZero()
        assertThat(summary.anomalies).isEmpty()
    }

    @Test
    fun `같은 회차를 다시 수집하면 새 행을 만들지 않고 값을 덮는다`() {
        val existing = entity(friday, 32, "USD", "1385")
        val repo = FakeRepo(existing)

        val summary = service(repo, snapshot(friday, 32, "USD" to "1390")).collect(requested, force = false)

        assertThat(summary.inserted).isZero()
        assertThat(summary.updated).isEqualTo(1)
        assertThat(repo.saved.single().id).isEqualTo(existing.id)
        assertThat(repo.saved.single().baseRate).isEqualByComparingTo("1390")
    }

    @Test
    fun `값이 같으면 무변화로 센다`() {
        val repo = FakeRepo(entity(friday, 32, "USD", "1390.0000"))

        val summary = service(repo, snapshot(friday, 32, "USD" to "1390")).collect(requested, force = false)

        assertThat(summary.unchanged).isEqualTo(1)
        assertThat(summary.updated).isZero()
    }

    @Test
    fun `안전장치에 걸리면 아무것도 쓰지 않고 실패한다`() {
        val repo = FakeRepo()

        assertThatThrownBy {
            service(repo, snapshot(friday, 32, "JPY" to "9.5")).collect(requested, force = false)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("USD")

        assertThat(repo.saved).isEmpty()
    }

    @Test
    fun `force는 변동 가드를 뚫는다`() {
        val repo = FakeRepo(entity(friday, 31, "USD", "1385"))
        val snapshot = snapshot(friday, 32, "USD" to "1420")   // +2.53%

        assertThatThrownBy { service(repo, snapshot).collect(requested, force = false) }
            .isInstanceOf(IllegalStateException::class.java)

        val summary = service(repo, snapshot).collect(requested, force = true)
        assertThat(summary.inserted).isEqualTo(1)
    }

    @Test
    fun `파서가 버린 행 수를 요약에 싣는다`() {
        val repo = FakeRepo()
        val withSkips = HanaFxSnapshot(friday, 32, listOf(row("USD", "1390")), skipped = 3)

        val summary = service(repo, withSkips).collect(requested, force = false)

        assertThat(summary.skipped).isEqualTo(3)
    }

    @Test
    fun `클라이언트 예외는 그대로 올려보낸다`() {
        val failing = object : HanaFxClient {
            override fun fetch(date: LocalDate): String = throw HanaFxParseException("점검 중")
        }
        val service = HanaFxCollectService(failing, FixedParser(snapshot(friday, 32, "USD" to "1390")),
            HanaFxGuards(), FakeRepo())

        assertThatThrownBy { service.collect(requested, force = false) }
            .isInstanceOf(HanaFxParseException::class.java)
    }

    // ── helpers ──────────────────────────────────────────────────

    private fun service(repo: HanaFxQuoteJpaRepository, snapshot: HanaFxSnapshot) =
        HanaFxCollectService(
            client = object : HanaFxClient {
                override fun fetch(date: LocalDate): String = "<html/>"
            },
            parser = FixedParser(snapshot),
            guards = HanaFxGuards(),
            repository = repo,
        )

    private fun snapshot(date: LocalDate, round: Int, vararg pairs: Pair<String, String>) =
        HanaFxSnapshot(date, round, pairs.map { (c, r) -> row(c, r) }, skipped = 0)

    private fun row(currency: String, rate: String) =
        HanaFxRow(currency, BigDecimal(rate), null, null, null, null)

    private fun entity(date: LocalDate, round: Int, currency: String, rate: String) =
        HanaFxQuoteEntity(
            id = UUID.randomUUID(), baseDate = date, roundNo = round, currency = currency,
            baseRate = BigDecimal(rate), cashBuy = null, cashSell = null,
            remitSend = null, remitReceive = null, collectedAt = LocalDateTime.now(),
        )

    private class FixedParser(private val snapshot: HanaFxSnapshot) : HanaFxParser() {
        override fun parse(html: String): HanaFxSnapshot = snapshot
    }

    private class FakeRepo(
        private vararg val existing: HanaFxQuoteEntity,
    ) : HanaFxQuoteJpaRepository by mock(HanaFxQuoteJpaRepository::class.java) {
        val saved = mutableListOf<HanaFxQuoteEntity>()

        override fun findAllByBaseDateAndRoundNo(baseDate: LocalDate, roundNo: Int) =
            existing.filter { it.baseDate == baseDate && it.roundNo == roundNo }

        // Pair는 Comparable이 아니므로 maxByOrNull에 Pair를 넘기면 컴파일되지 않는다
        override fun findTopByCurrencyOrderByBaseDateDescRoundNoDesc(currency: String) =
            existing.filter { it.currency == currency }
                .maxWithOrNull(compareBy({ it.baseDate }, { it.roundNo }))

        override fun <S : HanaFxQuoteEntity> saveAll(entities: MutableIterable<S>): MutableList<S> {
            entities.forEach { saved.add(it) }
            return entities.toMutableList()
        }
    }
}
```

**`FixedParser`가 상속으로 동작하는 이유**: 이 리포는 `kotlin("plugin.spring")`을 쓰고, allopen이 `@Component`·`@Service` 클래스와 그 멤버를 열어 준다(AF-100 Task 11 리뷰에서 `javap`로 확인됨 — `FxRateBackfillService`가 `final`이 아니었다). `HanaFxParser`가 `@Component`이므로 별도 `open` 키워드 없이 상속·오버라이드가 된다. 만약 컴파일이 안 되면 allopen 설정이 예상과 다른 것이므로, 임의로 고치지 말고 무엇이 달랐는지 보고해라.

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*HanaFxCollectServiceTest*' --rerun-tasks`
Expected: 컴파일 실패 — `Unresolved reference: HanaFxCollectService`

- [ ] **Step 3: 서비스 작성**

```kotlin
package com.allfolio.fx.hana

import com.allfolio.unifiedasset.infrastructure.entity.HanaFxQuoteEntity
import com.allfolio.unifiedasset.infrastructure.jpa.HanaFxQuoteJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * @param baseDate  하나은행이 응답에 담아 준 기준일. 요청한 조회일자가 아니다
 * @param skipped   파싱 단계에서 버린 행 수. 안전장치에 걸려 저장이 막힌 것과는 다르다
 * @param anomalies 안전장치에 걸린 항목. 비어 있어야 저장된다
 */
data class HanaCollectSummary(
    val requestedDate: LocalDate,
    val baseDate: LocalDate,
    val roundNo: Int,
    val currencies: Int,
    val inserted: Int,
    val updated: Int,
    val unchanged: Int,
    val skipped: Int,
    val anomalies: List<String>,
)

/**
 * 하나은행 고시환율 수집 (AF-99).
 *
 * `@Transactional`을 붙이지 않는다 — 20초까지 걸리는 HTTP 호출이 트랜잭션 안에 들어가면
 * 그동안 Neon 커넥션을 쥐고 앉아 있게 된다. `saveAll`은 Spring Data 리포지토리 레벨에서
 * 이미 트랜잭션이라 배치 원자성은 확보된다. 별도 빈으로 쪼개면 AF-90에서 물린
 * 자기호출 프록시 함정이 되살아나므로, 그냥 붙이지 않는 쪽이 맞다.
 */
@Service
class HanaFxCollectService(
    private val client: HanaFxClient,
    private val parser: HanaFxParser,
    private val guards: HanaFxGuards,
    private val repository: HanaFxQuoteJpaRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 연속 실패 횟수. 프로세스 메모리에만 둔다 —
     * 하는 일이 "로그 레벨을 올린다"뿐이라 테이블을 늘릴 값어치가 없고,
     * 재시작으로 0이 되는 것도 손해가 아니다(재시작 자체가 이미 조사할 사건이다).
     */
    private val consecutiveFailures = AtomicInteger(0)

    companion object {
        private const val FAILURE_ALERT_THRESHOLD = 3
    }

    fun collect(date: LocalDate, force: Boolean): HanaCollectSummary {
        val snapshot = try {
            parser.parse(client.fetch(date))
        } catch (e: Exception) {
            recordFailure(date, e.javaClass.simpleName)
            throw e
        }

        // 조회일자가 아니라 응답이 말하는 기준일·회차로 저장한다.
        // 주말·공휴일에 조회하면 직전 영업일 고시가 돌아오기 때문이다
        val existing = repository.findAllByBaseDateAndRoundNo(snapshot.baseDate, snapshot.roundNo)
            .associateBy { it.currency }

        // 비교 기준은 "가장 최근에 저장된 회차 통째"다. 현재 스냅샷의 통화로만 직전 값을 모으면
        // previousRates 크기가 스냅샷 크기를 절대 넘지 못해 행 수 비율이 항상 1.0이 되고,
        // 급감 가드가 영원히 안 걸린다. USD는 안전장치가 강제하므로 어느 저장된 회차에나 있다
        val previousRound = repository.findTopByCurrencyOrderByBaseDateDescRoundNoDesc("USD")
        val previousRows = previousRound
            ?.let { repository.findAllByBaseDateAndRoundNo(it.baseDate, it.roundNo) }
            ?: emptyList()
        val previousRates = previousRows.associate { it.currency to it.baseRate }
        val previousRowCount = previousRows.size.takeIf { it > 0 }

        val anomalies = guards.check(snapshot.rows, previousRates, previousRowCount, force)
        if (anomalies.isNotEmpty()) {
            recordFailure(date, anomalies.joinToString("; "))
            throw IllegalStateException(
                "안전장치에 걸려 저장하지 않았습니다: ${anomalies.joinToString("; ")}",
            )
        }

        var inserted = 0; var updated = 0; var unchanged = 0
        val rows = snapshot.rows.map { row ->
            val prev = existing[row.currency]
            when {
                prev == null -> { inserted++; toEntity(snapshot, row) }
                prev.baseRate.compareTo(row.baseRate) != 0 -> { updated++; prev.apply { overwrite(row) } }
                else -> { unchanged++; prev.apply { overwrite(row) } }
            }
        }
        repository.saveAll(rows)
        consecutiveFailures.set(0)

        val summary = HanaCollectSummary(
            requestedDate = date, baseDate = snapshot.baseDate, roundNo = snapshot.roundNo,
            currencies = snapshot.rows.size,
            inserted = inserted, updated = updated, unchanged = unchanged,
            skipped = snapshot.skipped, anomalies = emptyList(),
        )
        log.info("[하나은행] 수집 완료 {}", summary)
        return summary
    }

    private fun HanaFxQuoteEntity.overwrite(row: HanaFxRow) {
        baseRate = row.baseRate
        cashBuy = row.cashBuy
        cashSell = row.cashSell
        remitSend = row.remitSend
        remitReceive = row.remitReceive
    }

    private fun toEntity(snapshot: HanaFxSnapshot, row: HanaFxRow) = HanaFxQuoteEntity(
        id = UUID.randomUUID(),
        baseDate = snapshot.baseDate,
        roundNo = snapshot.roundNo,
        currency = row.currency,
        baseRate = row.baseRate,
        cashBuy = row.cashBuy,
        cashSell = row.cashSell,
        remitSend = row.remitSend,
        remitReceive = row.remitReceive,
        collectedAt = LocalDateTime.now(),
    )

    private fun recordFailure(date: LocalDate, reason: String) {
        val count = consecutiveFailures.incrementAndGet()
        if (count >= FAILURE_ALERT_THRESHOLD) {
            log.error("[하나은행] 연속 {}회 실패 date={} reason={}", count, date, reason)
        } else {
            log.warn("[하나은행] 수집 실패({}회) date={} reason={}", count, date, reason)
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*HanaFxCollectServiceTest*' --rerun-tasks`
Expected: PASS (8 tests)

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/hana/HanaFxCollectService.kt allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/hana/HanaFxCollectServiceTest.kt
git commit -m "feat(fx): 하나은행 수집 서비스 (응답 기준일 저장 + 안전장치 + 멱등)"
```

---

## Task 8: `getUsdToKrw()` 포트 추가

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/FxRateService.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/FxRateServiceDefaultTest.kt`

**왜 default 구현인가:** 이 인터페이스를 구현하는 테스트 fake가 5곳이다 — `CurrencyConverterTest`, `SecurityConfigAdminTest`, `SecurityConfigErrorDispatchTest`, `UnifiedAssetFxConverterAdapterTest`, `FxRateBackfillServiceTest`. default 없이 메서드를 추가하면 그 변경 하나로 컴파일이 무너진다. 여기서는 default 값이 곧 현행 동작이라 의미도 정확하다.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
package com.allfolio.fx

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * 하나은행 고시를 모르는 구현체(테스트 fake 포함)는 USDT 환율로 근사한다 — 현행 동작이다.
 */
class FxRateServiceDefaultTest {

    private val service = object : FxRateService {
        override fun getUsdtToKrw(): BigDecimal = BigDecimal("1400")
        override fun setUsdtToKrw(rate: BigDecimal) {}
        override fun getCryptoToKrw(symbol: String): BigDecimal = BigDecimal.ZERO
        override fun setCryptoToKrw(symbol: String, rate: BigDecimal) {}
    }

    @Test
    fun `default는 USDT 환율을 그대로 준다`() {
        assertThat(service.getUsdToKrw()).isEqualByComparingTo("1400")
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*FxRateServiceDefaultTest*' --rerun-tasks`
Expected: 컴파일 실패 — `Unresolved reference: getUsdToKrw`

- [ ] **Step 3: 포트에 메서드 추가**

`FxRateService.kt`의 `setCryptoToKrw` 선언 뒤, 인터페이스 닫는 `}` 앞에 추가:

```kotlin

    /**
     * 공식 원/미국달러 매매기준율 (AF-99).
     *
     * 자산 평가의 USD 환산이 쓴다. USDT는 이걸 쓰지 않는다 —
     * Binance USDT/KRW에는 김치 프리미엄이 섞여 있고, 그건 부정확이 아니라
     * 거래소에 실제 USDT를 들고 있는 사용자에게 실현 가능한 값이다.
     *
     * default는 하나은행 고시를 모르는 구현체를 위한 것으로, 현행 동작(USDT 환율로 근사)이다.
     */
    fun getUsdToKrw(): BigDecimal = getUsdtToKrw()
```

- [ ] **Step 4: 테스트 통과 + 회귀 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*FxRateServiceDefaultTest*' --rerun-tasks`
Expected: PASS

Run: `cd allfolio-backend && ./gradlew :unified-asset:test :backend-app:test --rerun-tasks`
Expected: BUILD SUCCESSFUL — fake 5곳이 default 덕분에 그대로 컴파일된다

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/FxRateService.kt allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/FxRateServiceDefaultTest.kt
git commit -m "feat(fx): FxRateService에 getUsdToKrw 추가 (default = 현행 동작)"
```

---

## Task 9: USD/USDT 분기 분리

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/CurrencyConverter.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/UnifiedAssetFxConverterAdapter.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/CurrencyConverterTest.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/UnifiedAssetFxConverterAdapterTest.kt`

**이 태스크가 AF-100에서 만든 것을 한 군데 되돌린다.** 어댑터 `toKrw`가 `canonical()`로 `USDT → USD`를 접는데, 그대로 두면 USDT가 `CurrencyConverter`에 닿기 전에 USD가 되어 분리가 무효가 된다.

- [ ] **Step 1: 실패하는 테스트 작성**

`CurrencyConverterTest.kt`의 fake에 `getUsdToKrw` 오버라이드를 추가하고, `usd converts like usdt` 테스트를 아래로 교체한다:

```kotlin
    private val fxRates = object : FxRateService {
        override fun getUsdtToKrw(): BigDecimal = BigDecimal("1400")
        override fun getUsdToKrw(): BigDecimal = BigDecimal("1390")
        override fun setUsdtToKrw(rate: BigDecimal) {}
        override fun getCryptoToKrw(symbol: String): BigDecimal = when (symbol.uppercase()) {
            "BTC" -> BigDecimal("90000000")
            "ETH" -> BigDecimal("4500000")
            else  -> throw IllegalArgumentException(symbol)
        }
        override fun setCryptoToKrw(symbol: String, rate: BigDecimal) {}
    }
```

```kotlin
    @Test
    fun `usd는 공식 매매기준율로 환산한다`() {
        // AF-99: USD는 하나은행 고시(1390), USDT는 Binance(1400) — 소스가 다르다
        assertEquals(0, BigDecimal("139000").compareTo(converter.toKrw(BigDecimal("100"), "USD")))
    }

    @Test
    fun `usdt는 거래소 시세를 유지한다`() {
        // 김치 프리미엄은 부정확이 아니라 거래소 보유자에게 실현 가능한 값이다
        assertEquals(0, BigDecimal("140000").compareTo(converter.toKrw(BigDecimal("100"), "USDT")))
    }
```

`UnifiedAssetFxConverterAdapterTest.kt`의 `StubFxRateService`에 오버라이드를 추가하고 테스트 두 건을 넣는다:

```kotlin
    private class StubFxRateService : FxRateService {
        override fun getUsdtToKrw(): BigDecimal = BigDecimal("1350")
        override fun getUsdToKrw(): BigDecimal = BigDecimal("1340")
        override fun setUsdtToKrw(rate: BigDecimal) = Unit
        override fun getCryptoToKrw(symbol: String): BigDecimal = BigDecimal("90000000")
        override fun setCryptoToKrw(symbol: String, rate: BigDecimal) = Unit
    }
```

```kotlin
    @Test
    fun `현재 환율 경로에서 USDT는 USD로 접히지 않는다`() {
        // AF-99: 두 경로가 의도적으로 다른 규칙을 쓴다 — 현재가는 USDT를 별개 자산으로 본다
        val adapter = adapter(FakeRepo())

        assertThat(adapter.toKrw(BigDecimal("100"), " usdt ")).isEqualByComparingTo("135000")
        assertThat(adapter.toKrw(BigDecimal("100"), "USD")).isEqualByComparingTo("134000")
    }

    @Test
    fun `과거 환율 경로에서는 USDT를 USD 시계열로 접는다`() {
        // 과거 USDT 시계열은 존재하지 않으므로 USD로 근사하는 게 맞다
        val repo = FakeRepo(row(date, "1390.200000"))

        val result = adapter(repo).toKrwOn(BigDecimal("100"), "usdt", date)

        assertThat(result.amountKrw).isEqualByComparingTo("139020")
        assertThat(result.estimated).isFalse()
    }
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*CurrencyConverterTest*' --tests '*UnifiedAssetFxConverterAdapterTest*' --rerun-tasks`
Expected: FAIL — USD가 1400(USDT 환율)로 환산되고, 어댑터에서 `" usdt "`가 USD로 접혀 134000이 나온다

실제 실패 출력을 보고에 붙여라.

- [ ] **Step 3: `CurrencyConverter` 분기 분리**

`CurrencyConverter.kt`의 `when` 블록에서 `"USDT", "USD" -> { ... }` 갈래를 아래 둘로 나눈다:

```kotlin
            // AF-99: 법정통화 USD는 하나은행 공식 매매기준율.
            // 1:1(원화 취급) 폴백은 달러 자산을 1/1400로 축소하는 버그였다
            "USD" -> {
                val rate = fxRateService.getUsdToKrw()
                (amount * rate).setScale(0, RoundingMode.HALF_UP)
            }
            // 스테이블코인은 거래소 시세를 유지한다 — 김치 프리미엄은 부정확이 아니라
            // 거래소에 실제 USDT를 들고 있는 사용자에게 실현 가능한 값이다
            "USDT" -> {
                val rate = fxRateService.getUsdtToKrw()
                (amount * rate).setScale(0, RoundingMode.HALF_UP)
            }
```

클래스 KDoc의 `지원 통화: KRW (1:1), USDT (Redis 캐시 환율 적용)` 줄도 실제와 맞게 고친다:

```
 * 지원 통화: KRW(1:1) · USD(하나은행 매매기준율) · USDT(거래소 시세) · BTC/ETH(코인 시세)
```

- [ ] **Step 4: 어댑터 `toKrw`에서 USDT 매핑 제거**

`UnifiedAssetFxConverterAdapter.kt`의 `toKrw`를 아래로 교체한다:

```kotlin
    /**
     * 현재 환율 환산 — 자산 평가액용.
     *
     * [canonical]이 아니라 [normalized]를 쓴다. **의도적이다.**
     * AF-99부터 USD는 하나은행 공식 매매기준율, USDT는 거래소 시세로 갈렸다.
     * 여기서 USDT를 USD로 접으면 그 분리가 무효가 된다.
     * 과거 환율([toKrwOn])은 반대로 접는다 — 과거 USDT 시계열이 존재하지 않기 때문이다.
     *
     * Account.reconstruct는 DB 값을 재정규화 없이 되살리므로 Currencies.normalize를 우회한 코드가
     * 그대로 도달한다. 정규화 없이 넘기면 " usdt "가 1:1로 떨어져 100 USDT가 100원이 된다
     */
    override fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
        currencyConverter.toKrw(amount, normalized(currency))
```

그리고 `canonical` 옆에 `normalized`를 추가한다:

```kotlin
    /** 공백·대소문자만 정리한다. 통화 별칭은 접지 않는다 — 현재 환율 경로용. */
    private fun normalized(currency: String): String = currency.trim().uppercase()

    /**
     * 별칭까지 접는다 — 과거 환율 경로용.
     * USDT는 과거 시계열이 없으므로 USD로 근사한다. 현재 환율 경로([toKrw])는
     * AF-99부터 둘을 구분하므로 이 함수를 쓰지 않는다. 통일하지 말 것.
     */
    private fun canonical(currency: String): String =
        when (val code = normalized(currency)) {
            "USDT" -> "USD"
            else -> code
        }
```

- [ ] **Step 5: 테스트 통과 + 전 모듈 회귀 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*CurrencyConverterTest*' --tests '*UnifiedAssetFxConverterAdapterTest*' --rerun-tasks`
Expected: PASS

Run: `cd allfolio-backend && ./gradlew test --rerun-tasks`
Expected: BUILD SUCCESSFUL — 평가 경로는 NAV·리포트·대시보드 25개 호출부가 쓰므로 전 모듈을 돌린다

- [ ] **Step 6: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/CurrencyConverter.kt allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/UnifiedAssetFxConverterAdapter.kt allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/CurrencyConverterTest.kt allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/UnifiedAssetFxConverterAdapterTest.kt
git commit -m "feat(fx): USD는 공식 고시·USDT는 거래소 시세로 분리"
```

---

## Task 10: `HanaFxRateService`

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/HanaFxRateService.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/HanaFxRateServiceTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
package com.allfolio.fx

import com.allfolio.unifiedasset.infrastructure.entity.HanaFxQuoteEntity
import com.allfolio.unifiedasset.infrastructure.jpa.HanaFxQuoteJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 하나은행 고시가 있으면 그것을, 없거나 조회가 실패하면 기존 구현에 위임한다.
 * 수집을 한 번도 안 돌린 상태에서도 오늘과 똑같이 굴러가야 한다.
 */
class HanaFxRateServiceTest {

    @Test
    fun `하나은행 고시가 있으면 그 매매기준율을 준다`() {
        val service = service(FakeRepo(quote("1390.5000")))

        assertThat(service.getUsdToKrw()).isEqualByComparingTo("1390.5")
    }

    @Test
    fun `고시가 없으면 기존 구현에 위임한다`() {
        val service = service(FakeRepo())

        assertThat(service.getUsdToKrw()).isEqualByComparingTo("1350")
    }

    @Test
    fun `조회가 실패해도 예외를 던지지 않고 위임한다`() {
        val service = service(ExplodingRepo())

        assertThat(service.getUsdToKrw()).isEqualByComparingTo("1350")
    }

    @Test
    fun `USDT와 코인은 기존 구현에 그대로 위임한다`() {
        val service = service(FakeRepo(quote("1390.5000")))

        assertThat(service.getUsdtToKrw()).isEqualByComparingTo("1350")
        assertThat(service.getCryptoToKrw("BTC")).isEqualByComparingTo("90000000")
    }

    @Test
    fun `반복 조회해도 DB는 한 번만 친다`() {
        val repo = FakeRepo(quote("1390.5000"))
        val service = service(repo)

        repeat(5) { service.getUsdToKrw() }

        assertThat(repo.callCount).isEqualTo(1)
    }

    // ── helpers ──────────────────────────────────────────────────

    private fun service(repo: HanaFxQuoteJpaRepository) = HanaFxRateService(StubDelegate(), repo)

    private fun quote(rate: String) = HanaFxQuoteEntity(
        id = UUID.randomUUID(), baseDate = LocalDate.of(2026, 8, 7), roundNo = 32,
        currency = "USD", baseRate = BigDecimal(rate), cashBuy = null, cashSell = null,
        remitSend = null, remitReceive = null, collectedAt = LocalDateTime.now(),
    )

    private class StubDelegate : FxRateService {
        override fun getUsdtToKrw(): BigDecimal = BigDecimal("1350")
        override fun setUsdtToKrw(rate: BigDecimal) = Unit
        override fun getCryptoToKrw(symbol: String): BigDecimal = BigDecimal("90000000")
        override fun setCryptoToKrw(symbol: String, rate: BigDecimal) = Unit
    }

    private open class FakeRepo(
        private val stored: HanaFxQuoteEntity? = null,
    ) : HanaFxQuoteJpaRepository by mock(HanaFxQuoteJpaRepository::class.java) {
        var callCount = 0

        override fun findTopByCurrencyOrderByBaseDateDescRoundNoDesc(currency: String): HanaFxQuoteEntity? {
            callCount++
            return stored?.takeIf { it.currency == currency }
        }
    }

    private class ExplodingRepo : FakeRepo() {
        override fun findTopByCurrencyOrderByBaseDateDescRoundNoDesc(currency: String): HanaFxQuoteEntity? =
            throw RuntimeException("DB down")
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*HanaFxRateServiceTest*' --rerun-tasks`
Expected: 컴파일 실패 — `Unresolved reference: HanaFxRateService`

- [ ] **Step 3: 서비스 작성**

```kotlin
package com.allfolio.fx

import com.allfolio.unifiedasset.infrastructure.jpa.HanaFxQuoteJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicReference

/**
 * 공식 원/미국달러 매매기준율을 하나은행 고시에서 읽는다 (AF-99).
 *
 * `getUsdToKrw()`만 오버라이드하고 나머지는 [RedisFxRateService]에 위임한다.
 * 고시가 없거나 조회가 실패하면 default 동작(USDT 환율 근사)으로 떨어지므로,
 * **수집을 한 번도 안 돌린 상태에서도 오늘과 똑같이 굴러간다.**
 *
 * 신선도 제한을 두지 않는다. 주말이면 금요일 최종고시를 쓰게 되는데 그게 실제 시장과 맞는다 —
 * 주말엔 환전도 그 값으로 된다. "N시간 넘으면 폴백" 같은 규칙을 두면 연휴에 정상인데도
 * 폴백이 돌아 환율이 튄다.
 */
@Service
@Primary
class HanaFxRateService(
    private val delegate: RedisFxRateService,
    private val quotes: HanaFxQuoteJpaRepository,
) : FxRateService by delegate {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * NAV 계산 한 번에 toKrw가 수십 번 불린다. 매번 DB를 치면 낭비라 짧게 캐싱한다.
     * Redis를 쓰지 않는 이유: 설계 원칙이 "Postgres가 진실, Redis는 가속"이고
     * 이 경로는 프로세스 내 캐시로 충분하다.
     */
    private val cached = AtomicReference<Pair<Long, BigDecimal>?>(null)

    companion object {
        private const val CURRENCY = "USD"
        private const val TTL_MILLIS = 60_000L
    }

    override fun getUsdToKrw(): BigDecimal {
        val now = System.currentTimeMillis()
        cached.get()?.let { (at, rate) -> if (now - at < TTL_MILLIS) return rate }

        val rate = runCatching {
            quotes.findTopByCurrencyOrderByBaseDateDescRoundNoDesc(CURRENCY)?.baseRate
        }.getOrElse { e ->
            log.error("[하나은행] 고시 조회 실패 — USDT 환율로 근사한다: {}", e.message)
            null
        }

        if (rate == null) return delegate.getUsdtToKrw()

        cached.set(now to rate)
        return rate
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests '*HanaFxRateServiceTest*' --rerun-tasks`
Expected: PASS (5 tests)

Run: `cd allfolio-backend && ./gradlew :unified-asset:test :backend-app:test --rerun-tasks`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/HanaFxRateService.kt allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/HanaFxRateServiceTest.kt
git commit -m "feat(fx): USD 현재가를 하나은행 매매기준율로 전환 (없으면 기존 경로 위임)"
```

---

## Task 11: 어드민 엔드포인트 + 전 모듈 검증 + PR

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/api/admin/FxRateAdminController.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/api/admin/FxRateAdminHanaControllerTest.kt`

- [ ] **Step 1: 엔드포인트 추가**

`FxRateAdminController` 생성자에 `hanaCollectService: HanaFxCollectService`를 추가하고, `getUsdtKrw` 뒤에 아래 둘을 넣는다:

```kotlin
    /**
     * GET /api/admin/fx/usdkrw — 평가 경로가 실제로 쓰는 USD 환율 (AF-99)
     *
     * usdtkrw만 있으면 하나은행 전환 후 무엇이 쓰이는지 확인할 방법이 없다.
     */
    @GetMapping("/usdkrw")
    fun getUsdKrw(): ResponseEntity<FxRateResponse> =
        ResponseEntity.ok(FxRateResponse(fxRateService.getUsdToKrw()))

    /**
     * POST /api/admin/fx/hana/collect — 하나은행 고시환율 수집 (어드민 전용, AF-99)
     *
     * date 생략 시 오늘(현재고시). 과거 날짜는 그날 최종고시.
     * force=true는 2% 변동 가드만 뚫는다 — 실제로 크게 움직인 날 영구히 막히는 걸 푸는 용도다.
     */
    @PostMapping("/hana/collect")
    fun collectHana(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?,
        @RequestParam(defaultValue = "false") force: Boolean,
    ): ResponseEntity<HanaCollectSummary> {
        val target = date ?: LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
        return try {
            ResponseEntity.ok(hanaCollectService.collect(target, force))
        } catch (e: IllegalStateException) {
            // 안전장치가 막은 것 — 우리가 판단해서 안 쓴 것이지 하나은행 잘못이 아니다
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.message)
        } catch (e: HanaFxParseException) {
            // 응답이 없거나 마크업이 바뀐 것 — 하나은행 쪽 문제다
            log.warn("[하나은행] 수집 실패 date={} reason={}", target, e.javaClass.simpleName)
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, e.message)
        } catch (e: DataIntegrityViolationException) {
            log.error("[하나은행] 제약 위반 date={}", target, e)
            throw ResponseStatusException(HttpStatus.CONFLICT, "동시 실행이 감지되었습니다. 다시 실행해주세요.")
        }
    }
```

임포트에 추가:

```kotlin
import com.allfolio.fx.hana.HanaCollectSummary
import com.allfolio.fx.hana.HanaFxCollectService
import com.allfolio.fx.hana.HanaFxParseException
```

**상태 코드 선택 이유**: 안전장치가 막은 경우는 502(하나은행 문제)가 아니다 — 응답은 정상적으로 왔고 우리가 검사해서 거부한 것이다. 422가 맞다. 502는 응답 자체를 못 받았거나 파싱이 안 되는 경우다.

- [ ] **Step 2: 컨트롤러 테스트 작성**

`allfolio-backend/backend-app/src/test/kotlin/com/allfolio/api/admin/FxRateAdminHanaControllerTest.kt`:

```kotlin
package com.allfolio.api.admin

import com.allfolio.auth.JwtTokenService
import com.allfolio.auth.UserEntity
import com.allfolio.auth.UserRole
import com.allfolio.config.GlobalExceptionHandler
import com.allfolio.config.JwtUserIdFilter
import com.allfolio.config.SecurityConfig
import com.allfolio.config.SseTokenFilter
import com.allfolio.fx.FxRateBackfillService
import com.allfolio.fx.FxRateService
import com.allfolio.fx.hana.HanaCollectSummary
import com.allfolio.fx.hana.HanaFxCollectService
import com.allfolio.fx.hana.HanaFxParseException
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * POST /api/admin/fx/hana/collect 의 상태 코드 매핑 회귀 방지 (AF-99).
 *
 * 손대지 않으면 안전장치 거부(우리 판단)·하나은행 장애(외부)·제약 위반(경합)이 전부 같은 500으로
 * 뭉개져 운영자가 다음에 뭘 할지 알 수 없다. 특히 422와 502를 가르는 게 핵심이다 —
 * 안전장치가 막은 건 응답이 정상적으로 왔는데 우리가 검사해서 거부한 것이라 하나은행 잘못이 아니다.
 */
@WebMvcTest(controllers = [FxRateAdminController::class])
@ContextConfiguration(classes = [FxRateAdminHanaControllerTest.TestApplication::class])
@Import(
    FxRateAdminController::class,
    SecurityConfig::class,
    SseTokenFilter::class,
    JwtUserIdFilter::class,
    JwtTokenService::class,
    GlobalExceptionHandler::class,
)
@TestPropertySource(
    properties = [
        "allfolio.cors.allowed-origins=http://localhost:3000",
        "allfolio.auth.jwt-secret=01234567890123456789012345678901",
        "allfolio.auth.access-token-minutes=60",
    ]
)
class FxRateAdminHanaControllerTest {

    @SpringBootConfiguration
    class TestApplication

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jwtTokenService: JwtTokenService

    @MockBean private lateinit var fxRateService: FxRateService
    @MockBean private lateinit var backfillService: FxRateBackfillService
    @MockBean private lateinit var hanaCollectService: HanaFxCollectService

    private val friday = LocalDate.of(2026, 8, 7)

    private fun adminToken(): String =
        jwtTokenService.issue(
            UserEntity(
                id = UUID.randomUUID(),
                email = "${UUID.randomUUID()}@example.com",
                passwordHash = "hash",
                displayName = "Test Admin",
                role = UserRole.ADMIN,
            ),
        )

    private fun summary() = HanaCollectSummary(
        requestedDate = friday, baseDate = friday, roundNo = 32, currencies = 58,
        inserted = 50, updated = 6, unchanged = 2, skipped = 1, anomalies = emptyList(),
    )

    @Test
    fun `정상 수집은 200과 요약을 준다`() {
        `when`(hanaCollectService.collect(any(), anyBoolean())).thenReturn(summary())

        mockMvc.post("/api/admin/fx/hana/collect?date=2026-08-07") {
            header("Authorization", "Bearer ${adminToken()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.baseDate") { value("2026-08-07") }
            jsonPath("$.roundNo") { value(32) }
            jsonPath("$.currencies") { value(58) }
            jsonPath("$.inserted") { value(50) }
            jsonPath("$.updated") { value(6) }
            jsonPath("$.unchanged") { value(2) }
            jsonPath("$.skipped") { value(1) }
        }
    }

    @Test
    fun `안전장치가 막으면 422와 실제 사유를 준다`() {
        // 하나은행 잘못이 아니라 우리가 검사해서 거부한 것이다 — 502가 아니다
        `when`(hanaCollectService.collect(any(), anyBoolean()))
            .thenThrow(IllegalStateException("안전장치에 걸려 저장하지 않았습니다: 필수 통화 USD 가 응답에 없습니다 (통화 3개)"))

        mockMvc.post("/api/admin/fx/hana/collect") {
            header("Authorization", "Bearer ${adminToken()}")
        }.andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.error") { value(org.hamcrest.Matchers.containsString("USD")) }
        }
    }

    @Test
    fun `하나은행 응답을 못 읽으면 502를 준다`() {
        `when`(hanaCollectService.collect(any(), anyBoolean()))
            .thenThrow(HanaFxParseException("기준일을 찾지 못했습니다"))

        mockMvc.post("/api/admin/fx/hana/collect") {
            header("Authorization", "Bearer ${adminToken()}")
        }.andExpect { status { isBadGateway() } }
    }

    @Test
    fun `동시 실행은 409를 준다`() {
        `when`(hanaCollectService.collect(any(), anyBoolean()))
            .thenThrow(DataIntegrityViolationException("uk_hana_fx_quote"))

        mockMvc.post("/api/admin/fx/hana/collect") {
            header("Authorization", "Bearer ${adminToken()}")
        }.andExpect {
            status { isConflict() }
            jsonPath("$.error") { value(org.hamcrest.Matchers.containsString("다시 실행")) }
        }
    }

    @Test
    fun `date를 생략하면 오늘로 수집한다`() {
        `when`(hanaCollectService.collect(any(), anyBoolean())).thenReturn(summary())
        val dateCaptor = ArgumentCaptor.forClass(LocalDate::class.java)

        mockMvc.post("/api/admin/fx/hana/collect") {
            header("Authorization", "Bearer ${adminToken()}")
        }.andExpect { status { isOk() } }

        verify(hanaCollectService).collect(dateCaptor.capture(), anyBoolean())
        org.assertj.core.api.Assertions.assertThat(dateCaptor.value)
            .isEqualTo(LocalDate.now(ZoneId.of("Asia/Seoul")))
    }

    @Test
    fun `force는 서비스로 그대로 전달된다`() {
        `when`(hanaCollectService.collect(any(), anyBoolean())).thenReturn(summary())
        val forceCaptor = ArgumentCaptor.forClass(Boolean::class.java)

        mockMvc.post("/api/admin/fx/hana/collect?force=true") {
            header("Authorization", "Bearer ${adminToken()}")
        }.andExpect { status { isOk() } }

        verify(hanaCollectService).collect(any(), forceCaptor.capture())
        org.assertj.core.api.Assertions.assertThat(forceCaptor.value).isTrue()
    }

    @Test
    fun `usdkrw는 평가 경로가 쓰는 값을 준다`() {
        `when`(fxRateService.getUsdToKrw()).thenReturn(BigDecimal("1390.5"))

        mockMvc.get("/api/admin/fx/usdkrw") {
            header("Authorization", "Bearer ${adminToken()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.usdtKrw") { value(1390.5) }
        }
    }

    @Test
    fun `어드민이 아니면 403이다`() {
        val userToken = jwtTokenService.issue(
            UserEntity(
                id = UUID.randomUUID(), email = "${UUID.randomUUID()}@example.com",
                passwordHash = "hash", displayName = "Test User", role = UserRole.USER,
            ),
        )

        mockMvc.post("/api/admin/fx/hana/collect") {
            header("Authorization", "Bearer $userToken")
        }.andExpect { status { isForbidden() } }
    }
}
```

**주의 두 가지**:
1. `FxRateResponse`의 필드명이 `usdtKrw`다. `/usdkrw`가 그 DTO를 재사용하면 JSON 키가 `usdtKrw`로 나가 헷갈린다. 위 테스트는 재사용을 전제로 `$.usdtKrw`를 단언한다 — 만약 `/usdkrw` 전용 DTO(`UsdRateResponse(usdKrw)`)를 만들기로 하면 이 단언도 함께 고쳐라. **둘 중 하나를 골라 일관되게 하고 무엇을 골랐는지 보고해라.**
2. Mockito의 `any()`는 Kotlin non-null 파라미터에서 NPE를 낼 수 있다. 위 코드가 그대로 안 되면 `ArgumentMatchers.any(LocalDate::class.java)`로 바꾸거나 `org.mockito.kotlin`이 클래스패스에 있는지 확인해라(없으면 전자로).

- [ ] **Step 3: 전 모듈 검증**

Run: `cd allfolio-backend && ./gradlew test --rerun-tasks`
Expected: BUILD SUCCESSFUL

**`--rerun-tasks`가 필수다** — 없으면 전부 UP-TO-DATE로 아무것도 실행되지 않는다.

`SecurityConfigAdminTest`·`SecurityConfigErrorDispatchTest`가 `FxRateAdminController`를 직접 생성하므로 새 생성자 인자 때문에 깨진다. `@MockBean HanaFxCollectService`를 추가해 고쳐라(AF-100 Task 11에서 같은 일을 겪었다).

- [ ] **Step 4: 커밋 + PR**

```bash
git add -A && git commit -m "feat(fx): 하나은행 수집 어드민 엔드포인트 + USD 환율 조회"
git push -u origin feat/af-99-hana-fx-collector
```

PR 본문은 실제 커밋 로그(`git log --oneline main..HEAD`)를 보고 직접 써라. 반드시 담을 것:

- **무엇/왜**: 로컬 스크래퍼를 서버로. 값어치는 스크래핑이 아니라 안전장치에 있다
- **저장 키가 `(기준일, 회차, 통화)`인 이유**: 하나은행이 주말 조회에 직전 영업일 고시를 돌려주므로 조회일자를 키로 쓰면 연휴에 같은 고시가 반복 저장된다
- **USD/USDT 분리**: 김치 프리미엄은 부정확이 아니라 거래소 보유자에게 실현 가능한 값이다. 그러려면 어댑터 `toKrw`의 `USDT→USD` 정규화를 빼야 했고, `toKrwOn`은 유지했다 — **두 경로가 의도적으로 다른 규칙을 쓴다**
- **2% 가드에 `force`가 있는 이유**: 실제로 2% 넘게 움직이는 날 영구히 얼어붙는 걸 막는다
- **배포 순서**: ① Neon에 `2026-08-12-hana-fx-quote.sql` 실행(배포 前) ② 머지·배포 ③ `POST /api/admin/fx/hana/collect`. **③을 안 해도 회귀 없음** — 고시가 없으면 `getUsdToKrw()`가 기존 경로로 위임한다
- **범위 밖**: 스케줄(AF-103), 시장 화면(AF-104), 출처 표기(AF-105), 전일대비·등락률

끝에 붙일 것:
```
🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

---

## 배포 체크리스트 (머지 후, 사용자 작업)

- [ ] Neon에 `docs/superpowers/migrations/2026-08-12-hana-fx-quote.sql` 실행 — **배포 전에**
- [ ] 머지·배포
- [ ] `POST /api/admin/fx/hana/collect` 실행 (인증 필요, 새 환경변수는 없음)
- [ ] 응답의 `baseDate`가 **오늘이 맞는지** 확인 — 주말이면 직전 영업일이 정상이다
- [ ] `GET /api/admin/fx/usdkrw`로 평가 경로가 하나은행 값을 쓰는지 확인
- [ ] `SELECT base_date, round_no, COUNT(*) FROM hana_fx_quote GROUP BY 1,2 ORDER BY 1 DESC, 2 DESC;`
- [ ] 노션 AF-99 상태를 '완료'로 변경
