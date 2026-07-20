# 리포트 공통 기반 (report_archive + as-of 생성 프레임) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** R-01~R-07 기관급 리포트가 공유하는 기반 계층 — `report_archive` 테이블 + as-of 생성 프레임(Generator/ValidationGate/Archive 포트 + UseCase) + 계좌 sync 검증 게이트 + 아카이브 REST API.

**Architecture:** `report` 모듈에 domain/application/infrastructure 3계층 프레임을 추가하고(스캔 자동: `com.allfolio` 전체), 사용자 컨텍스트가 필요한 게이트 구현·컨트롤러는 `unified-asset`에 둔다. 리포트 엔진들은 `ReportBodyGenerator` 스프링 빈으로 등록만 하면 규율 4종(as-of 고정·보관·검증 게이트·표준 양식)을 얻는다.

**Tech Stack:** Kotlin 1.9.25 / Spring Boot 3.2.5 / JPA(Hibernate 6.4 `@JdbcTypeCode(SqlTypes.JSON)`) / PostgreSQL JSONB / JUnit5

**Spec:** `docs/superpowers/specs/2026-07-16-report-foundation-design.md`

---

### Task 1: DDL — report_archive

**Files:**
- Modify: `allfolio-backend/infra/postgres/init.sql` (파일 끝에 추가)

- [x] **Step 1: init.sql 끝에 DDL 추가**

```sql
-- ── report_archive ─────────────────────────────────────────────
-- 기관급 리포트 아카이브 (R1 #32): as-of 고정된 본문 JSON을 기간 단위로 보관
CREATE TABLE IF NOT EXISTS report_archive (
    id            UUID         PRIMARY KEY,
    user_id       UUID         NOT NULL,
    report_type   VARCHAR(30)  NOT NULL,
    period_start  DATE         NOT NULL,
    period_end    DATE         NOT NULL,
    as_of_date    DATE         NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    warnings      JSONB        NOT NULL DEFAULT '[]',
    body          JSONB        NOT NULL,
    pdf           BYTEA,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_report_archive UNIQUE (user_id, report_type, period_start, period_end)
);

CREATE INDEX IF NOT EXISTS idx_report_archive_user
    ON report_archive (user_id, report_type, period_end DESC);
```

- [x] **Step 2: Commit**

```bash
git add allfolio-backend/infra/postgres/init.sql
git commit -m "feat(report): report_archive 테이블 DDL"
```

### Task 2: report 모듈 의존성 + 도메인 (TDD)

**Files:**
- Modify: `allfolio-backend/report/build.gradle.kts`
- Create: `allfolio-backend/report/src/main/kotlin/com/allfolio/report/domain/archive/ReportType.kt`
- Create: `allfolio-backend/report/src/main/kotlin/com/allfolio/report/domain/archive/ReportPeriod.kt`
- Create: `allfolio-backend/report/src/main/kotlin/com/allfolio/report/domain/archive/ReportArchive.kt` (ReportStatus·ReportWarning 포함)
- Test: `allfolio-backend/report/src/test/kotlin/com/allfolio/report/domain/archive/ReportPeriodTest.kt`
- Test: `allfolio-backend/report/src/test/kotlin/com/allfolio/report/domain/archive/ReportArchiveTest.kt`

- [x] **Step 1: build.gradle.kts에 JPA 의존성 추가** (snapshot 모듈과 동일 스타일)

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
```

- [x] **Step 2: 실패하는 도메인 테스트 작성**

ReportPeriodTest: `monthly() 팩토리가 월초~월말 생성`, `start가 end 이후면 예외`.
ReportArchiveTest: `경고 없으면 FINAL`, `경고 있으면 WARNING`, `본문 공백이면 예외`.

```kotlin
package com.allfolio.report.domain.archive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ReportPeriodTest {

    @Test
    fun `monthly creates first to last day of month`() {
        val period = ReportPeriod.monthly(2026, 6)
        assertEquals(LocalDate.of(2026, 6, 1), period.start)
        assertEquals(LocalDate.of(2026, 6, 30), period.end)
    }

    @Test
    fun `start after end is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReportPeriod(LocalDate.of(2026, 7, 2), LocalDate.of(2026, 7, 1))
        }
    }
}
```

```kotlin
package com.allfolio.report.domain.archive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class ReportArchiveTest {

    private val userId = UUID.randomUUID()
    private val period = ReportPeriod.monthly(2026, 6)

    @Test
    fun `no warnings means FINAL`() {
        val archive = ReportArchive.create(
            userId = userId, type = ReportType.RETURNS, period = period,
            asOfDate = LocalDate.of(2026, 6, 30), warnings = emptyList(), bodyJson = """{"a":1}""",
        )
        assertEquals(ReportStatus.FINAL, archive.status)
    }

    @Test
    fun `warnings mean WARNING status`() {
        val archive = ReportArchive.create(
            userId = userId, type = ReportType.RETURNS, period = period,
            asOfDate = LocalDate.of(2026, 6, 30),
            warnings = listOf(ReportWarning("SYNC_ERROR", "계좌 동기화 실패")), bodyJson = """{"a":1}""",
        )
        assertEquals(ReportStatus.WARNING, archive.status)
        assertEquals(1, archive.warnings.size)
    }

    @Test
    fun `blank body is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReportArchive.create(
                userId = userId, type = ReportType.RETURNS, period = period,
                asOfDate = LocalDate.of(2026, 6, 30), warnings = emptyList(), bodyJson = " ",
            )
        }
    }
}
```

- [x] **Step 3: 테스트 실패 확인**

Run: `cd allfolio-backend && ./gradlew :report:test`
Expected: 컴파일 실패 (클래스 미존재)

- [x] **Step 4: 도메인 구현**

```kotlin
// ReportType.kt
package com.allfolio.report.domain.archive

/** 기관급 리포트 7종 (리포트명세서 R-01~R-07) */
enum class ReportType {
    MONTHLY_REPORT,     // R-01 월간 운용보고서
    RETURNS,            // R-02 수익률
    DIVIDEND_INTEREST,  // R-03 배당·이자
    COST,               // R-04 비용
    HOLDINGS,           // R-05 월말 보유 명세
    CASHFLOW,           // R-06 현금흐름
    ESG_SCREENING,      // R-07 투자배제·ESG
}
```

```kotlin
// ReportPeriod.kt
package com.allfolio.report.domain.archive

import java.time.LocalDate
import java.time.YearMonth

data class ReportPeriod(val start: LocalDate, val end: LocalDate) {
    init {
        require(!start.isAfter(end)) { "기간 시작일이 종료일 이후일 수 없습니다: $start > $end" }
    }

    companion object {
        fun monthly(year: Int, month: Int): ReportPeriod {
            val ym = YearMonth.of(year, month)
            return ReportPeriod(ym.atDay(1), ym.atEndOfMonth())
        }
    }
}
```

```kotlin
// ReportArchive.kt
package com.allfolio.report.domain.archive

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

enum class ReportStatus { FINAL, WARNING }

data class ReportWarning(val code: String, val message: String)

class ReportArchive private constructor(
    val id: UUID,
    val userId: UUID,
    val type: ReportType,
    val period: ReportPeriod,
    val asOfDate: LocalDate,
    val status: ReportStatus,
    val warnings: List<ReportWarning>,
    val bodyJson: String,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun create(
            userId: UUID,
            type: ReportType,
            period: ReportPeriod,
            asOfDate: LocalDate,
            warnings: List<ReportWarning>,
            bodyJson: String,
        ): ReportArchive {
            require(bodyJson.isNotBlank()) { "리포트 본문은 비어 있을 수 없습니다" }
            return ReportArchive(
                id        = UUID.randomUUID(),
                userId    = userId,
                type      = type,
                period    = period,
                asOfDate  = asOfDate,
                status    = if (warnings.isEmpty()) ReportStatus.FINAL else ReportStatus.WARNING,
                warnings  = warnings,
                bodyJson  = bodyJson,
                createdAt = LocalDateTime.now(),
            )
        }

        fun reconstruct(
            id: UUID, userId: UUID, type: ReportType, period: ReportPeriod, asOfDate: LocalDate,
            status: ReportStatus, warnings: List<ReportWarning>, bodyJson: String, createdAt: LocalDateTime,
        ) = ReportArchive(id, userId, type, period, asOfDate, status, warnings, bodyJson, createdAt)
    }
}
```

- [x] **Step 5: 테스트 통과 확인**

Run: `cd allfolio-backend && ./gradlew :report:test`
Expected: BUILD SUCCESSFUL, 5 tests pass

- [x] **Step 6: Commit**

```bash
git add allfolio-backend/report
git commit -m "feat(report): 리포트 아카이브 도메인 (ReportType·Period·Archive)"
```

### Task 3: 생성 프레임 유스케이스 (TDD)

**Files:**
- Create: `allfolio-backend/report/src/main/kotlin/com/allfolio/report/application/ReportBodyGenerator.kt` (GeneratedReport 포함)
- Create: `allfolio-backend/report/src/main/kotlin/com/allfolio/report/application/ReportValidationGate.kt`
- Create: `allfolio-backend/report/src/main/kotlin/com/allfolio/report/application/ReportArchiveRepository.kt`
- Create: `allfolio-backend/report/src/main/kotlin/com/allfolio/report/application/GenerateReportUseCase.kt` (UnsupportedReportTypeException 포함)
- Test: `allfolio-backend/report/src/test/kotlin/com/allfolio/report/application/GenerateReportUseCaseTest.kt`

- [x] **Step 1: 실패하는 유스케이스 테스트 작성** (fake 포트 3종 인라인 정의)

케이스: ①정상 생성 → FINAL 저장+asOf 전달 ②게이트 경고 → WARNING ③미지원 타입 → UnsupportedReportTypeException ④같은 기간 재생성 → upsert 1건 유지.

```kotlin
package com.allfolio.report.application

import com.allfolio.report.domain.archive.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class GenerateReportUseCaseTest {

    private val userId = UUID.randomUUID()
    private val period = ReportPeriod.monthly(2026, 6)
    private val asOf = LocalDate.of(2026, 6, 30)

    private class FakeGenerator(override val type: ReportType, private val asOf: LocalDate) : ReportBodyGenerator {
        override fun generate(userId: UUID, period: ReportPeriod) =
            GeneratedReport(asOfDate = asOf, bodyJson = """{"nav":100}""")
    }

    private class FakeGate(private val warnings: List<ReportWarning> = emptyList()) : ReportValidationGate {
        override fun check(userId: UUID, period: ReportPeriod) = warnings
    }

    private class InMemoryRepo : ReportArchiveRepository {
        val stored = mutableMapOf<String, ReportArchive>()
        override fun upsert(archive: ReportArchive): ReportArchive {
            stored["${archive.userId}-${archive.type}-${archive.period}"] = archive
            return archive
        }
        override fun findById(id: UUID) = stored.values.firstOrNull { it.id == id }
        override fun findAll(userId: UUID, type: ReportType?) =
            stored.values.filter { it.userId == userId && (type == null || it.type == type) }
    }

    @Test
    fun `generates FINAL report and archives it`() {
        val repo = InMemoryRepo()
        val useCase = GenerateReportUseCase(listOf(FakeGenerator(ReportType.RETURNS, asOf)), FakeGate(), repo)

        val result = useCase.generate(userId, ReportType.RETURNS, period)

        assertEquals(ReportStatus.FINAL, result.status)
        assertEquals(asOf, result.asOfDate)
        assertEquals(1, repo.stored.size)
    }

    @Test
    fun `gate warnings produce WARNING report`() {
        val repo = InMemoryRepo()
        val gate = FakeGate(listOf(ReportWarning("SYNC_ERROR", "동기화 실패")))
        val useCase = GenerateReportUseCase(listOf(FakeGenerator(ReportType.RETURNS, asOf)), gate, repo)

        val result = useCase.generate(userId, ReportType.RETURNS, period)

        assertEquals(ReportStatus.WARNING, result.status)
    }

    @Test
    fun `unsupported type throws`() {
        val useCase = GenerateReportUseCase(emptyList(), FakeGate(), InMemoryRepo())
        assertThrows(UnsupportedReportTypeException::class.java) {
            useCase.generate(userId, ReportType.COST, period)
        }
    }

    @Test
    fun `regenerating same period keeps single archive`() {
        val repo = InMemoryRepo()
        val useCase = GenerateReportUseCase(listOf(FakeGenerator(ReportType.RETURNS, asOf)), FakeGate(), repo)

        useCase.generate(userId, ReportType.RETURNS, period)
        useCase.generate(userId, ReportType.RETURNS, period)

        assertEquals(1, repo.stored.size)
    }
}
```

- [x] **Step 2: 테스트 실패 확인**

Run: `cd allfolio-backend && ./gradlew :report:test`
Expected: 컴파일 실패

- [x] **Step 3: 포트 + 유스케이스 구현**

```kotlin
// ReportBodyGenerator.kt
package com.allfolio.report.application

import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.archive.ReportType
import java.time.LocalDate
import java.util.UUID

/** 리포트 엔진(#33 수익률, #36 월간 등)이 구현하는 생성기 포트. 스프링 빈으로 등록만 하면 프레임에 꽂힌다. */
interface ReportBodyGenerator {
    val type: ReportType
    fun generate(userId: UUID, period: ReportPeriod): GeneratedReport
}

data class GeneratedReport(
    val asOfDate: LocalDate,   // 생성에 사용된 스냅샷 최종일 — 아카이브에 고정
    val bodyJson: String,      // 구조화 본문 (웹/PDF 공용)
)
```

```kotlin
// ReportValidationGate.kt
package com.allfolio.report.application

import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.archive.ReportWarning
import java.util.UUID

/** 검증 게이트 (명세 §0): 신뢰할 수 없는 데이터로 생성되는 보고서에 경고를 부여한다. */
interface ReportValidationGate {
    fun check(userId: UUID, period: ReportPeriod): List<ReportWarning>
}
```

```kotlin
// ReportArchiveRepository.kt
package com.allfolio.report.application

import com.allfolio.report.domain.archive.ReportArchive
import com.allfolio.report.domain.archive.ReportType
import java.util.UUID

interface ReportArchiveRepository {
    /** (userId, type, period) 유니크 — 재생성 시 덮어쓴다 */
    fun upsert(archive: ReportArchive): ReportArchive
    fun findById(id: UUID): ReportArchive?
    fun findAll(userId: UUID, type: ReportType? = null): List<ReportArchive>
}
```

```kotlin
// GenerateReportUseCase.kt
package com.allfolio.report.application

import com.allfolio.report.domain.archive.ReportArchive
import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.archive.ReportType
import org.springframework.stereotype.Service
import java.util.UUID

class UnsupportedReportTypeException(type: ReportType) :
    RuntimeException("생성기가 등록되지 않은 리포트 타입입니다: $type")

@Service
class GenerateReportUseCase(
    generators: List<ReportBodyGenerator>,
    private val gate: ReportValidationGate,
    private val repository: ReportArchiveRepository,
) {
    private val generatorsByType: Map<ReportType, ReportBodyGenerator> =
        generators.associateBy { it.type }.also {
            require(it.size == generators.size) { "리포트 타입당 생성기는 하나여야 합니다" }
        }

    fun generate(userId: UUID, type: ReportType, period: ReportPeriod): ReportArchive {
        val generator = generatorsByType[type] ?: throw UnsupportedReportTypeException(type)
        val warnings = gate.check(userId, period)
        val generated = generator.generate(userId, period)
        return repository.upsert(
            ReportArchive.create(
                userId   = userId,
                type     = type,
                period   = period,
                asOfDate = generated.asOfDate,
                warnings = warnings,
                bodyJson = generated.bodyJson,
            )
        )
    }
}
```

- [x] **Step 4: 테스트 통과 확인**

Run: `cd allfolio-backend && ./gradlew :report:test`
Expected: BUILD SUCCESSFUL, 9 tests pass

- [x] **Step 5: Commit**

```bash
git add allfolio-backend/report
git commit -m "feat(report): as-of 생성 프레임 — Generator·Gate·Repository 포트 + GenerateReportUseCase"
```

### Task 4: JPA 인프라 (report_archive 영속)

**Files:**
- Create: `allfolio-backend/report/src/main/kotlin/com/allfolio/report/infrastructure/entity/ReportArchiveEntity.kt`
- Create: `allfolio-backend/report/src/main/kotlin/com/allfolio/report/infrastructure/repository/ReportArchiveJpaRepository.kt`
- Create: `allfolio-backend/report/src/main/kotlin/com/allfolio/report/infrastructure/repository/ReportArchiveRepositoryImpl.kt`

- [x] **Step 1: 엔티티 + JPA 리포지토리 + 어댑터 구현** (씬 어댑터 — 단위 테스트 없이 컴파일·기존 테스트로 확인)

```kotlin
// ReportArchiveEntity.kt
package com.allfolio.report.infrastructure.entity

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "report_archive")
class ReportArchiveEntity(
    @Id
    val id: UUID,

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "report_type", nullable = false, length = 30)
    val reportType: String,

    @Column(name = "period_start", nullable = false)
    val periodStart: LocalDate,

    @Column(name = "period_end", nullable = false)
    val periodEnd: LocalDate,

    @Column(name = "as_of_date", nullable = false)
    val asOfDate: LocalDate,

    @Column(name = "status", nullable = false, length = 20)
    val status: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "warnings", nullable = false, columnDefinition = "jsonb")
    val warnings: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "body", nullable = false, columnDefinition = "jsonb")
    val body: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime,
)
```

```kotlin
// ReportArchiveJpaRepository.kt
package com.allfolio.report.infrastructure.repository

import com.allfolio.report.infrastructure.entity.ReportArchiveEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

interface ReportArchiveJpaRepository : JpaRepository<ReportArchiveEntity, UUID> {
    fun findByUserIdAndReportTypeAndPeriodStartAndPeriodEnd(
        userId: UUID, reportType: String, periodStart: LocalDate, periodEnd: LocalDate,
    ): ReportArchiveEntity?

    fun findByUserIdOrderByPeriodEndDesc(userId: UUID): List<ReportArchiveEntity>
    fun findByUserIdAndReportTypeOrderByPeriodEndDesc(userId: UUID, reportType: String): List<ReportArchiveEntity>
}
```

```kotlin
// ReportArchiveRepositoryImpl.kt
package com.allfolio.report.infrastructure.repository

import com.allfolio.report.application.ReportArchiveRepository
import com.allfolio.report.domain.archive.*
import com.allfolio.report.infrastructure.entity.ReportArchiveEntity
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Repository
class ReportArchiveRepositoryImpl(
    private val jpa: ReportArchiveJpaRepository,
) : ReportArchiveRepository {

    private val mapper = jacksonObjectMapper()

    @Transactional
    override fun upsert(archive: ReportArchive): ReportArchive {
        // (userId, type, period) 유니크 — 기존 행이 있으면 같은 id로 덮어쓴다
        val existing = jpa.findByUserIdAndReportTypeAndPeriodStartAndPeriodEnd(
            archive.userId, archive.type.name, archive.period.start, archive.period.end,
        )
        val entity = ReportArchiveEntity(
            id          = existing?.id ?: archive.id,
            userId      = archive.userId,
            reportType  = archive.type.name,
            periodStart = archive.period.start,
            periodEnd   = archive.period.end,
            asOfDate    = archive.asOfDate,
            status      = archive.status.name,
            warnings    = mapper.writeValueAsString(archive.warnings),
            body        = archive.bodyJson,
            createdAt   = archive.createdAt,
        )
        return jpa.save(entity).toDomain()
    }

    @Transactional(readOnly = true)
    override fun findById(id: UUID): ReportArchive? =
        jpa.findById(id).orElse(null)?.toDomain()

    @Transactional(readOnly = true)
    override fun findAll(userId: UUID, type: ReportType?): List<ReportArchive> =
        (if (type == null) jpa.findByUserIdOrderByPeriodEndDesc(userId)
         else jpa.findByUserIdAndReportTypeOrderByPeriodEndDesc(userId, type.name))
            .map { it.toDomain() }

    private fun ReportArchiveEntity.toDomain() = ReportArchive.reconstruct(
        id        = id,
        userId    = userId,
        type      = ReportType.valueOf(reportType),
        period    = ReportPeriod(periodStart, periodEnd),
        asOfDate  = asOfDate,
        status    = ReportStatus.valueOf(status),
        warnings  = mapper.readValue(warnings),
        bodyJson  = body,
        createdAt = createdAt,
    )
}
```

- [x] **Step 2: 전체 빌드로 컴파일 확인**

Run: `cd allfolio-backend && ./gradlew :report:build`
Expected: BUILD SUCCESSFUL

- [x] **Step 3: Commit**

```bash
git add allfolio-backend/report
git commit -m "feat(report): report_archive JPA 영속 어댑터 (JSONB 매핑·upsert)"
```

### Task 5: 계좌 sync 검증 게이트 (unified-asset, TDD)

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/AccountSyncValidationGate.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/AccountSyncValidationGateTest.kt`

- [x] **Step 1: 실패하는 테스트 작성**

케이스: ①ERROR 계좌 → SYNC_ERROR ②syncable인데 lastSyncedAt null → NEVER_SYNCED ③lastSyncedAt이 기간말 이전 → STALE_SYNC ④기간말 이후 동기화된 정상 계좌 → 경고 없음 ⑤MANUAL 계좌는 STALE 검사 제외.

```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.domain.account.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.UUID

class AccountSyncValidationGateTest {

    private val userId = UUID.randomUUID()
    private val period = ReportPeriod.monthly(2026, 6)

    private fun account(
        provider: AccountProvider,
        status: AccountStatus,
        lastSyncedAt: LocalDateTime?,
    ): Account = Account.reconstruct(
        id = UUID.randomUUID(), userId = userId, provider = provider,
        accountType = AccountType.STOCK, accountName = "테스트",
        externalId = null, currency = "KRW", status = status,
        lastSyncedAt = lastSyncedAt, createdAt = LocalDateTime.now(),
        apiKey = null, apiSecret = null, walletAddress = null, chain = null,
    )

    private fun gateWith(vararg accounts: Account): AccountSyncValidationGate {
        val repo = object : AccountRepository {
            override fun save(account: Account) = account
            override fun findById(id: UUID): Account? = null
            override fun findByUserId(userId: UUID) = accounts.toList()
            override fun findByProviders(providers: Collection<AccountProvider>) = emptyList<Account>()
            override fun delete(id: UUID) {}
            override fun updateStatus(id: UUID, status: AccountStatus) {}
        }
        return AccountSyncValidationGate(repo)
    }

    @Test
    fun `ERROR account produces SYNC_ERROR warning`() {
        val gate = gateWith(account(AccountProvider.KIS, AccountStatus.ERROR, LocalDateTime.now()))
        val warnings = gate.check(userId, period)
        assertEquals(listOf("SYNC_ERROR"), warnings.map { it.code })
    }

    @Test
    fun `syncable account never synced produces NEVER_SYNCED warning`() {
        val gate = gateWith(account(AccountProvider.BINANCE, AccountStatus.ACTIVE, null))
        val warnings = gate.check(userId, period)
        assertEquals(listOf("NEVER_SYNCED"), warnings.map { it.code })
    }

    @Test
    fun `syncable account synced before period end produces STALE_SYNC warning`() {
        val gate = gateWith(account(AccountProvider.KIS, AccountStatus.ACTIVE, LocalDateTime.of(2026, 6, 15, 12, 0)))
        val warnings = gate.check(userId, period)
        assertEquals(listOf("STALE_SYNC"), warnings.map { it.code })
    }

    @Test
    fun `healthy account synced after period end produces no warnings`() {
        val gate = gateWith(account(AccountProvider.KIS, AccountStatus.ACTIVE, LocalDateTime.of(2026, 7, 1, 0, 30)))
        assertTrue(gate.check(userId, period).isEmpty())
    }

    @Test
    fun `manual account is exempt from stale check`() {
        val gate = gateWith(account(AccountProvider.MANUAL, AccountStatus.ACTIVE, null))
        assertTrue(gate.check(userId, period).isEmpty())
    }
}
```

- [x] **Step 2: 테스트 실패 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests '*AccountSyncValidationGateTest*'`
Expected: 컴파일 실패

- [x] **Step 3: 게이트 구현**

```kotlin
// AccountSyncValidationGate.kt
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.application.ReportValidationGate
import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.archive.ReportWarning
import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.domain.account.AccountStatus
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 검증 게이트 v1 (명세 §0): 계좌 동기화 상태 기반.
 * P2 대사 도입 시 "대사 미해소" 검사가 이 게이트에 추가된다.
 */
@Component
class AccountSyncValidationGate(
    private val accountRepository: AccountRepository,
) : ReportValidationGate {

    override fun check(userId: UUID, period: ReportPeriod): List<ReportWarning> {
        val periodEndExclusive = period.end.plusDays(1).atStartOfDay()
        return accountRepository.findByUserId(userId).flatMap { account ->
            val syncable = account.provider in DailyAccountSyncer.SYNC_ELIGIBLE_PROVIDERS
            when {
                account.status == AccountStatus.ERROR ->
                    listOf(ReportWarning("SYNC_ERROR", "${account.accountName} 계좌가 동기화 실패 상태입니다"))
                syncable && account.lastSyncedAt == null ->
                    listOf(ReportWarning("NEVER_SYNCED", "${account.accountName} 계좌가 한 번도 동기화되지 않았습니다"))
                syncable && account.lastSyncedAt!!.isBefore(periodEndExclusive) ->
                    listOf(ReportWarning("STALE_SYNC", "${account.accountName} 계좌가 기준기간 말 이후 동기화되지 않았습니다"))
                else -> emptyList()
            }
        }
    }
}
```

- [x] **Step 4: 테스트 통과 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests '*AccountSyncValidationGateTest*'`
Expected: BUILD SUCCESSFUL, 5 tests pass

- [x] **Step 5: Commit**

```bash
git add allfolio-backend/unified-asset
git commit -m "feat(report): 계좌 sync 상태 기반 검증 게이트 v1"
```

### Task 6: 아카이브 REST API

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/api/ReportArchiveController.kt`

- [x] **Step 1: 컨트롤러 + DTO 구현** (기존 ReportController 관례: X-User-Id 헤더)

```kotlin
// ReportArchiveController.kt
package com.allfolio.unifiedasset.api

import com.allfolio.report.application.GenerateReportUseCase
import com.allfolio.report.application.ReportArchiveRepository
import com.allfolio.report.application.UnsupportedReportTypeException
import com.allfolio.report.domain.archive.ReportArchive
import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.archive.ReportType
import com.allfolio.report.domain.archive.ReportWarning
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@RestController
@RequestMapping("/api/reports/archive")
class ReportArchiveController(
    private val generateReport: GenerateReportUseCase,
    private val archiveRepository: ReportArchiveRepository,
) {

    data class GenerateRequest(val type: ReportType, val year: Int, val month: Int)

    data class ArchiveMetaResponse(
        val id: UUID,
        val type: ReportType,
        val periodStart: LocalDate,
        val periodEnd: LocalDate,
        val asOfDate: LocalDate,
        val status: String,
        val warnings: List<ReportWarning>,
        val createdAt: LocalDateTime,
    )

    data class ArchiveDetailResponse(
        val meta: ArchiveMetaResponse,
        val body: String,   // 구조화 JSON 문자열 — 프론트에서 parse
    )

    @PostMapping("/generate")
    fun generate(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestBody request: GenerateRequest,
    ): ArchiveMetaResponse =
        generateReport.generate(userId, request.type, ReportPeriod.monthly(request.year, request.month)).toMeta()

    @GetMapping
    fun list(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestParam(required = false) type: ReportType?,
    ): List<ArchiveMetaResponse> =
        archiveRepository.findAll(userId, type).map { it.toMeta() }

    @GetMapping("/{id}")
    fun detail(
        @RequestHeader("X-User-Id") userId: UUID,
        @PathVariable id: UUID,
    ): ResponseEntity<ArchiveDetailResponse> {
        val archive = archiveRepository.findById(id)
        // 소유권 검증: 남의 아카이브는 존재 여부도 노출하지 않는다
        if (archive == null || archive.userId != userId) return ResponseEntity.notFound().build()
        return ResponseEntity.ok(ArchiveDetailResponse(meta = archive.toMeta(), body = archive.bodyJson))
    }

    @ExceptionHandler(UnsupportedReportTypeException::class)
    fun unsupportedType(e: UnsupportedReportTypeException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to (e.message ?: "unsupported type")))

    private fun ReportArchive.toMeta() = ArchiveMetaResponse(
        id          = id,
        type        = type,
        periodStart = period.start,
        periodEnd   = period.end,
        asOfDate    = asOfDate,
        status      = status.name,
        warnings    = warnings,
        createdAt   = createdAt,
    )
}
```

- [x] **Step 2: 전체 빌드 + 전 모듈 테스트**

Run: `cd allfolio-backend && ./gradlew build -x :backend-app:test 2>&1 | tail -5` 후 `./gradlew test`
Expected: BUILD SUCCESSFUL

- [x] **Step 3: Commit**

```bash
git add allfolio-backend/unified-asset
git commit -m "feat(report): 리포트 아카이브 API (generate·list·detail, 소유권 검증)"
```

### Task 7: 스모크 검증 + 마무리

- [x] **Step 1: 로컬 기동 스모크** — 앱 컨텍스트가 뜨는지 확인 (게이트·유스케이스 빈 배선 검증). DB 필요 시 docker-compose postgres 사용, 불가하면 `./gradlew :backend-app:build`(컨텍스트 로드 테스트 포함 여부 확인)로 대체
- [x] **Step 2: 노션 태스크 #32 상태 → 완료(구현) 업데이트, PR 생성**
