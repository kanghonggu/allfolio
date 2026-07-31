# R-03 원천징수 세율 마스터 (SCR-RPT-06, Phase A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ADMIN이 국가×소득유형×유효기간 버저닝으로 원천징수 세율을 관리하는 마스터(SCR-RPT-06)를 BE(도메인·CRUD API·시드)+FE(ADMIN 화면)로 구현한다.

**Architecture:** `unified-asset` 모듈에 헥사고날 패턴으로 `tax_rates` 도메인/엔티티/JPA/포트/서비스를 두고(버저닝 로직은 서비스), `backend-app/api/admin`에 REST 컨트롤러(`/api/admin/tax-rates`, 이미 `hasRole('ADMIN')` 게이트)를 둔다. FE는 `/unified/admin/tax-rates` 화면을 `useRequireAdmin`으로 가드하고 NavBar에 `isAdmin` 링크를 노출한다. 스키마는 init.sql(신규/로컬)+운영 Neon 1회성 마이그레이션.

**Tech Stack:** Kotlin, Spring Boot 6, Spring Security 6, Spring Data JPA, JUnit5(+MockMvc), PostgreSQL(init.sql), Next.js(App Router)/React/TypeScript/axios.

**Spec:** `docs/superpowers/specs/2026-07-29-tax-rate-master-design.md`
**Branch:** `feat/tax-rate-master` (main에서 분기, ADMIN role #50 머지 반영됨)

---

## Reference: 현재 상태 & 관례 (구현 전 사실)

- `unified-asset` 헥사고날: `domain/*`, `application/port`, `application/usecase`, `infrastructure/{entity,jpa,repository,adapter}`. spring-data-jpa + web 의존 있음.
- 엔티티 관례(`infrastructure/entity/CashFlowEntity.kt`): `@Entity @Table`, `@Enumerated(EnumType.STRING)`, `@Column` 명시, `toDomain()` + `companion object { fun from(domain) }`.
- 도메인 관례: `domain/cashflow/CashFlow.kt`(enum `FlowType` 동거), 값 객체.
- JPA 관례(`infrastructure/jpa/CashFlowJpaRepository.kt`): `interface X : JpaRepository<Entity, UUID>` + 파생 쿼리.
- 포트/테스트 관례: 서비스는 포트(interface) 의존, 테스트는 **Spring 없이 fake 구현**으로 단위테스트(`DividendInterestReportGeneratorTest`의 `FakeLedger`/`FakeAssetRepo`).
- ADMIN 컨트롤러 관례(`backend-app/api/admin/FxRateAdminController.kt`): `@RestController @RequestMapping("/api/admin/...")`, 서비스 주입. 슬라이스 인가 테스트 관례(`config/SecurityConfigAdminTest.kt`): `@SpringBootTest(classes=[...SecurityConfig, JwtUserIdFilter, SseTokenFilter, JwtTokenService, 컨트롤러, TestBeans...])` + `tokenFor(role)` 헬퍼(#50에서 도입).
- 검증 관례(`auth/AuthService.kt`): 위반 시 `throw ResponseStatusException(HttpStatus.BAD_REQUEST, "...")`.
- 스키마: `allfolio-backend/infra/postgres/init.sql`(정본, `CREATE TABLE IF NOT EXISTS` + 시드 `INSERT`, 예: `kr_stocks`). 운영은 Neon 수동(마이그레이션 러너 없음).
- FE: 컨트롤러 클라이언트 관례 `lib/report-archive-api.ts`(axios + Bearer). 가드 `lib/useRequireAdmin.ts`(#50, `{ ready }` 반환). 내비 `components/NavBar.tsx`(`{authenticated && (<>...Link...</>)}` 블록, `useAuth()`에 `isAdmin` 존재). `@/*` alias → `./*`.

**공통 규칙:** 경로는 리포 루트(`/Users/hong9/IdeaProjects/allfolio`) 기준. BE 테스트: `cd allfolio-backend && ./gradlew :<module>:test --tests '<FQCN>'` (module = `unified-asset` 또는 `backend-app`). FE: `cd frontend/allfolio_app && npx tsc --noEmit`.

---

## File Structure

**Backend — unified-asset (신규)**
- `domain/tax/IncomeType.kt` — enum
- `domain/tax/TaxRate.kt` — 도메인 data class
- `application/port/TaxRateRepository.kt` — 포트(interface)
- `application/usecase/TaxRateService.kt` — 버저닝/검증 (+ `RegisterTaxRateCommand`)
- `infrastructure/entity/TaxRateEntity.kt`
- `infrastructure/jpa/TaxRateJpaRepository.kt`
- `infrastructure/repository/TaxRateRepositoryImpl.kt` — 포트 어댑터
- (test) `application/usecase/TaxRateServiceTest.kt`

**Backend — backend-app (신규)**
- `api/admin/TaxRateAdminController.kt` (+ DTOs)
- (test) `api/admin/TaxRateAdminControllerTest.kt`

**Backend — 스키마/마이그레이션**
- `infra/postgres/init.sql` (수정: tax_rates + 시드)
- `docs/superpowers/migrations/2026-07-29-tax-rate-master.sql` (신규)

**Frontend (신규/수정)**
- `types/tax-rate.ts`, `lib/tax-rate-admin-api.ts`
- `app/unified/admin/tax-rates/page.tsx`
- `components/NavBar.tsx` (수정: isAdmin 링크)

---

## Task 1: 도메인 (IncomeType, TaxRate)

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/domain/tax/IncomeType.kt`
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/domain/tax/TaxRate.kt`

- [ ] **Step 1: IncomeType enum**

Create `domain/tax/IncomeType.kt`:
```kotlin
package com.allfolio.unifiedasset.domain.tax

enum class IncomeType { DIVIDEND, INTEREST, DISTRIBUTION }
```

- [ ] **Step 2: TaxRate 도메인**

Create `domain/tax/TaxRate.kt`:
```kotlin
package com.allfolio.unifiedasset.domain.tax

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/** 원천징수 세율 1버전. effectiveEnd == null 이면 현행(open). rate는 퍼센트값(예 15.315). */
data class TaxRate(
    val id: UUID,
    val country: String,          // ISO alpha-2
    val incomeType: IncomeType,
    val rate: BigDecimal,
    val effectiveStart: LocalDate,
    val effectiveEnd: LocalDate?,
    val updatedBy: UUID?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)
```

- [ ] **Step 3: 컴파일 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:compileKotlin -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/domain/tax/
git commit -m "feat(tax): add TaxRate domain and IncomeType enum"
```

---

## Task 2: 포트 + 서비스 버저닝 (TDD)

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/port/TaxRateRepository.kt`
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/TaxRateService.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/TaxRateServiceTest.kt`

- [ ] **Step 1: 포트 정의**

Create `application/port/TaxRateRepository.kt`:
```kotlin
package com.allfolio.unifiedasset.application.port

import com.allfolio.unifiedasset.domain.tax.IncomeType
import com.allfolio.unifiedasset.domain.tax.TaxRate
import java.time.LocalDate

interface TaxRateRepository {
    fun findAll(): List<TaxRate>
    fun findOpen(country: String, incomeType: IncomeType): TaxRate?
    fun findEffective(country: String, incomeType: IncomeType, date: LocalDate): TaxRate?
    fun save(taxRate: TaxRate): TaxRate
}
```

- [ ] **Step 2: 실패하는 테스트 작성**

Create `application/usecase/TaxRateServiceTest.kt`:
```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.TaxRateRepository
import com.allfolio.unifiedasset.domain.tax.IncomeType
import com.allfolio.unifiedasset.domain.tax.TaxRate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class TaxRateServiceTest {

    private class FakeRepo : TaxRateRepository {
        val store = mutableListOf<TaxRate>()
        override fun findAll() = store.toList()
        override fun findOpen(country: String, incomeType: IncomeType) =
            store.firstOrNull { it.country == country && it.incomeType == incomeType && it.effectiveEnd == null }
        override fun findEffective(country: String, incomeType: IncomeType, date: LocalDate) =
            store.firstOrNull {
                it.country == country && it.incomeType == incomeType &&
                    !it.effectiveStart.isAfter(date) && (it.effectiveEnd == null || !it.effectiveEnd.isBefore(date))
            }
        override fun save(taxRate: TaxRate): TaxRate {
            store.removeIf { it.id == taxRate.id }
            store.add(taxRate)
            return taxRate
        }
    }

    private val admin = UUID.randomUUID()

    private fun service(repo: TaxRateRepository = FakeRepo()) = TaxRateService(repo) to repo

    private fun cmd(country: String = "US", type: IncomeType = IncomeType.DIVIDEND,
                    rate: String = "15", start: LocalDate = LocalDate.of(2024, 1, 1)) =
        RegisterTaxRateCommand(country, type, BigDecimal(rate), start)

    @Test
    fun `open이 없으면 신규 open 1건을 생성한다`() {
        val (svc, repo) = service()
        val r = svc.register(cmd(), admin)
        assertThat(r.effectiveEnd).isNull()
        assertThat(r.updatedBy).isEqualTo(admin)
        assertThat(repo.findAll()).hasSize(1)
    }

    @Test
    fun `기존 open이 있으면 마감하고 신규 open을 만든다 (버저닝)`() {
        val (svc, repo) = service()
        svc.register(cmd(start = LocalDate.of(2024, 1, 1)), admin)
        svc.register(cmd(rate = "16", start = LocalDate.of(2025, 1, 1)), admin)
        val all = repo.findAll().sortedBy { it.effectiveStart }
        assertThat(all).hasSize(2)
        assertThat(all[0].effectiveEnd).isEqualTo(LocalDate.of(2024, 12, 31)) // 신규start-1
        assertThat(all[1].effectiveEnd).isNull()
    }

    @Test
    fun `findEffectiveRate는 날짜에 맞는 버전을 반환한다`() {
        val (svc, repo) = service()
        svc.register(cmd(rate = "15", start = LocalDate.of(2024, 1, 1)), admin)
        svc.register(cmd(rate = "16", start = LocalDate.of(2025, 1, 1)), admin)
        assertThat(repo.findEffective("US", IncomeType.DIVIDEND, LocalDate.of(2024, 6, 1))!!.rate).isEqualByComparingTo("15")
        assertThat(repo.findEffective("US", IncomeType.DIVIDEND, LocalDate.of(2024, 12, 31))!!.rate).isEqualByComparingTo("15")
        assertThat(repo.findEffective("US", IncomeType.DIVIDEND, LocalDate.of(2025, 1, 1))!!.rate).isEqualByComparingTo("16")
    }

    @Test
    fun `신규 시작일이 기존 open 시작일 이후가 아니면 거부한다`() {
        val (svc, _) = service()
        svc.register(cmd(start = LocalDate.of(2025, 1, 1)), admin)
        assertThatThrownBy { svc.register(cmd(start = LocalDate.of(2025, 1, 1)), admin) }
            .isInstanceOf(ResponseStatusException::class.java)
    }

    @Test
    fun `세율 범위 밖이면 거부한다`() {
        val (svc, _) = service()
        assertThatThrownBy { svc.register(cmd(rate = "51"), admin) }.isInstanceOf(ResponseStatusException::class.java)
        assertThatThrownBy { svc.register(cmd(rate = "-1"), admin) }.isInstanceOf(ResponseStatusException::class.java)
    }

    @Test
    fun `국가 형식이 틀리면 거부한다`() {
        val (svc, _) = service()
        assertThatThrownBy { svc.register(cmd(country = "USA"), admin) }.isInstanceOf(ResponseStatusException::class.java)
        assertThatThrownBy { svc.register(cmd(country = ""), admin) }.isInstanceOf(ResponseStatusException::class.java)
    }
}
```

- [ ] **Step 3: 테스트 실행 — 실패 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests 'com.allfolio.unifiedasset.application.usecase.TaxRateServiceTest' -q`
Expected: 컴파일 에러 (`TaxRateService`/`RegisterTaxRateCommand` 미존재).

- [ ] **Step 4: 서비스 구현**

Create `application/usecase/TaxRateService.kt`:
```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.TaxRateRepository
import com.allfolio.unifiedasset.domain.tax.IncomeType
import com.allfolio.unifiedasset.domain.tax.TaxRate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

data class RegisterTaxRateCommand(
    val country: String,
    val incomeType: IncomeType,
    val rate: BigDecimal,
    val effectiveStart: LocalDate,
)

@Service
class TaxRateService(
    private val repository: TaxRateRepository,
) {
    fun list(): List<TaxRate> = repository.findAll()

    fun findEffectiveRate(country: String, incomeType: IncomeType, date: LocalDate): TaxRate? =
        repository.findEffective(country, incomeType, date)

    @Transactional
    fun register(cmd: RegisterTaxRateCommand, adminId: UUID): TaxRate {
        validate(cmd)
        val now = LocalDateTime.now()
        val open = repository.findOpen(cmd.country, cmd.incomeType)
        if (open != null) {
            if (!cmd.effectiveStart.isAfter(open.effectiveStart)) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "새 적용 시작일은 기존 적용 시작일 이후여야 합니다.",
                )
            }
            repository.save(open.copy(effectiveEnd = cmd.effectiveStart.minusDays(1), updatedAt = now))
        }
        return repository.save(
            TaxRate(
                id = UUID.randomUUID(),
                country = cmd.country,
                incomeType = cmd.incomeType,
                rate = cmd.rate,
                effectiveStart = cmd.effectiveStart,
                effectiveEnd = null,
                updatedBy = adminId,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun validate(cmd: RegisterTaxRateCommand) {
        if (cmd.country.length != 2 || cmd.country.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "국가는 2자리 코드여야 합니다.")
        }
        if (cmd.rate < BigDecimal.ZERO || cmd.rate > BigDecimal(50)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "세율은 0~50% 범위여야 합니다.")
        }
    }
}
```

- [ ] **Step 5: 테스트 실행 — 통과 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests 'com.allfolio.unifiedasset.application.usecase.TaxRateServiceTest' -q`
Expected: PASS (6 tests).

- [ ] **Step 6: Commit**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/port/TaxRateRepository.kt \
        allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/TaxRateService.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/TaxRateServiceTest.kt
git commit -m "feat(tax): add TaxRateService with effective-period versioning"
```

---

## Task 3: 영속성 (Entity, JpaRepository, RepositoryImpl)

**Files:**
- Create: `.../unifiedasset/infrastructure/entity/TaxRateEntity.kt`
- Create: `.../unifiedasset/infrastructure/jpa/TaxRateJpaRepository.kt`
- Create: `.../unifiedasset/infrastructure/repository/TaxRateRepositoryImpl.kt`

- [ ] **Step 1: Entity**

Create `infrastructure/entity/TaxRateEntity.kt`:
```kotlin
package com.allfolio.unifiedasset.infrastructure.entity

import com.allfolio.unifiedasset.domain.tax.IncomeType
import com.allfolio.unifiedasset.domain.tax.TaxRate
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "tax_rates")
class TaxRateEntity(
    @Id val id: UUID,
    @Column(name = "country", nullable = false, length = 2) val country: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "income_type", nullable = false, length = 20) val incomeType: IncomeType,
    @Column(name = "rate", nullable = false, precision = 6, scale = 3) val rate: BigDecimal,
    @Column(name = "effective_start", nullable = false) val effectiveStart: LocalDate,
    @Column(name = "effective_end") val effectiveEnd: LocalDate?,
    @Column(name = "updated_by") val updatedBy: UUID?,
    @Column(name = "created_at", nullable = false) val createdAt: LocalDateTime,
    @Column(name = "updated_at", nullable = false) val updatedAt: LocalDateTime,
) {
    fun toDomain() = TaxRate(id, country, incomeType, rate, effectiveStart, effectiveEnd, updatedBy, createdAt, updatedAt)

    companion object {
        fun from(d: TaxRate) = TaxRateEntity(
            d.id, d.country, d.incomeType, d.rate, d.effectiveStart, d.effectiveEnd, d.updatedBy, d.createdAt, d.updatedAt,
        )
    }
}
```

- [ ] **Step 2: JpaRepository**

Create `infrastructure/jpa/TaxRateJpaRepository.kt`:
```kotlin
package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.domain.tax.IncomeType
import com.allfolio.unifiedasset.infrastructure.entity.TaxRateEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface TaxRateJpaRepository : JpaRepository<TaxRateEntity, UUID> {
    fun findAllByOrderByCountryAscIncomeTypeAscEffectiveStartDesc(): List<TaxRateEntity>

    fun findByCountryAndIncomeTypeAndEffectiveEndIsNull(country: String, incomeType: IncomeType): TaxRateEntity?

    @Query(
        "SELECT t FROM TaxRateEntity t WHERE t.country = :country AND t.incomeType = :incomeType " +
            "AND t.effectiveStart <= :date AND (t.effectiveEnd IS NULL OR t.effectiveEnd >= :date)",
    )
    fun findEffective(
        @Param("country") country: String,
        @Param("incomeType") incomeType: IncomeType,
        @Param("date") date: LocalDate,
    ): TaxRateEntity?
}
```

- [ ] **Step 3: RepositoryImpl (포트 어댑터)**

Create `infrastructure/repository/TaxRateRepositoryImpl.kt`:
```kotlin
package com.allfolio.unifiedasset.infrastructure.repository

import com.allfolio.unifiedasset.application.port.TaxRateRepository
import com.allfolio.unifiedasset.domain.tax.IncomeType
import com.allfolio.unifiedasset.domain.tax.TaxRate
import com.allfolio.unifiedasset.infrastructure.entity.TaxRateEntity
import com.allfolio.unifiedasset.infrastructure.jpa.TaxRateJpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
class TaxRateRepositoryImpl(
    private val jpa: TaxRateJpaRepository,
) : TaxRateRepository {
    override fun findAll(): List<TaxRate> =
        jpa.findAllByOrderByCountryAscIncomeTypeAscEffectiveStartDesc().map { it.toDomain() }

    override fun findOpen(country: String, incomeType: IncomeType): TaxRate? =
        jpa.findByCountryAndIncomeTypeAndEffectiveEndIsNull(country, incomeType)?.toDomain()

    override fun findEffective(country: String, incomeType: IncomeType, date: LocalDate): TaxRate? =
        jpa.findEffective(country, incomeType, date)?.toDomain()

    override fun save(taxRate: TaxRate): TaxRate =
        jpa.save(TaxRateEntity.from(taxRate)).toDomain()
}
```

- [ ] **Step 4: 컴파일 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:compileKotlin -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/entity/TaxRateEntity.kt \
        allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/jpa/TaxRateJpaRepository.kt \
        allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/repository/TaxRateRepositoryImpl.kt
git commit -m "feat(tax): add TaxRate JPA entity, repository, and port adapter"
```

---

## Task 4: ADMIN REST 컨트롤러 (TDD, 인가+검증)

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/api/admin/TaxRateAdminController.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/api/admin/TaxRateAdminControllerTest.kt`

- [ ] **Step 1: 실패하는 슬라이스 테스트 작성**

Create `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/api/admin/TaxRateAdminControllerTest.kt`:
```kotlin
package com.allfolio.api.admin

import com.allfolio.auth.JwtTokenService
import com.allfolio.auth.UserEntity
import com.allfolio.auth.UserRole
import com.allfolio.config.JwtUserIdFilter
import com.allfolio.config.SecurityConfig
import com.allfolio.config.SseTokenFilter
import com.allfolio.unifiedasset.application.port.TaxRateRepository
import com.allfolio.unifiedasset.application.usecase.TaxRateService
import com.allfolio.unifiedasset.domain.tax.IncomeType
import com.allfolio.unifiedasset.domain.tax.TaxRate
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.LocalDate
import java.util.UUID

@SpringBootTest(
    classes = [
        TaxRateAdminControllerTest.TestApplication::class,
        TaxRateAdminControllerTest.TestBeans::class,
        SecurityConfig::class,
        JwtUserIdFilter::class,
        SseTokenFilter::class,
        JwtTokenService::class,
        TaxRateAdminController::class,
    ],
    properties = [
        "allfolio.auth.jwt-secret=test-secret-test-secret-test-secret-1234",
        "allfolio.auth.access-token-minutes=15",
        "allfolio.cors.allowed-origins=http://localhost:3000",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
    ],
)
@AutoConfigureMockMvc
class TaxRateAdminControllerTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jwtTokenService: JwtTokenService

    private fun tokenFor(role: UserRole): String =
        jwtTokenService.issue(UserEntity(email = "t@example.com", passwordHash = "h", displayName = null, role = role)).first

    @Test
    fun `목록은 무토큰이면 403`() {
        mockMvc.get("/api/admin/tax-rates").andExpect { status { isForbidden() } }
    }

    @Test
    fun `목록은 USER 토큰이면 403`() {
        mockMvc.get("/api/admin/tax-rates") {
            header("Authorization", "Bearer ${tokenFor(UserRole.USER)}")
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `목록은 ADMIN 토큰이면 200`() {
        mockMvc.get("/api/admin/tax-rates") {
            header("Authorization", "Bearer ${tokenFor(UserRole.ADMIN)}")
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `등록은 ADMIN 토큰이면 200`() {
        mockMvc.post("/api/admin/tax-rates") {
            header("Authorization", "Bearer ${tokenFor(UserRole.ADMIN)}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"country":"US","incomeType":"DIVIDEND","rate":15,"effectiveStart":"2024-01-01"}"""
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `등록은 세율 범위 밖이면 400`() {
        mockMvc.post("/api/admin/tax-rates") {
            header("Authorization", "Bearer ${tokenFor(UserRole.ADMIN)}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"country":"US","incomeType":"DIVIDEND","rate":51,"effectiveStart":"2024-01-01"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    class TestApplication

    @TestConfiguration
    class TestBeans {
        // 인메모리 fake 포트 → 실제 TaxRateService 로직(버저닝/검증)까지 통과
        @Bean
        fun taxRateRepository(): TaxRateRepository = object : TaxRateRepository {
            val store = mutableListOf<TaxRate>()
            override fun findAll() = store.toList()
            override fun findOpen(country: String, incomeType: IncomeType) =
                store.firstOrNull { it.country == country && it.incomeType == incomeType && it.effectiveEnd == null }
            override fun findEffective(country: String, incomeType: IncomeType, date: LocalDate) = null
            override fun save(taxRate: TaxRate): TaxRate { store.removeIf { it.id == taxRate.id }; store.add(taxRate); return taxRate }
        }

        @Bean
        fun taxRateService(repo: TaxRateRepository) = TaxRateService(repo)
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests 'com.allfolio.api.admin.TaxRateAdminControllerTest' -q`
Expected: 컴파일 에러 (`TaxRateAdminController` 미존재).

- [ ] **Step 3: 컨트롤러 구현**

Create `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/api/admin/TaxRateAdminController.kt`:
```kotlin
package com.allfolio.api.admin

import com.allfolio.unifiedasset.application.usecase.RegisterTaxRateCommand
import com.allfolio.unifiedasset.application.usecase.TaxRateService
import com.allfolio.unifiedasset.domain.tax.IncomeType
import com.allfolio.unifiedasset.domain.tax.TaxRate
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@RestController
@RequestMapping("/api/admin/tax-rates")
class TaxRateAdminController(
    private val taxRateService: TaxRateService,
) {
    /** GET — 전체 세율(현행+이력). FE가 국가×유형으로 그룹핑해 이력 타임라인 렌더. */
    @GetMapping
    fun list(): ResponseEntity<List<TaxRateResponse>> =
        ResponseEntity.ok(taxRateService.list().map { it.toResponse() })

    /** POST — 등록/버저닝 (ADMIN). */
    @PostMapping
    fun register(
        @RequestHeader("X-User-Id") adminId: UUID,
        @RequestBody req: RegisterTaxRateRequest,
    ): ResponseEntity<TaxRateResponse> {
        val saved = taxRateService.register(
            RegisterTaxRateCommand(req.country, req.incomeType, req.rate, req.effectiveStart), adminId,
        )
        return ResponseEntity.ok(saved.toResponse())
    }

    private fun TaxRate.toResponse() = TaxRateResponse(
        id, country, incomeType, rate, effectiveStart, effectiveEnd, updatedBy, updatedAt,
    )
}

data class RegisterTaxRateRequest(
    val country: String,
    val incomeType: IncomeType,
    val rate: BigDecimal,
    val effectiveStart: LocalDate,
)

data class TaxRateResponse(
    val id: UUID,
    val country: String,
    val incomeType: IncomeType,
    val rate: BigDecimal,
    val effectiveStart: LocalDate,
    val effectiveEnd: LocalDate?,
    val updatedBy: UUID?,
    val updatedAt: LocalDateTime,
)
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests 'com.allfolio.api.admin.TaxRateAdminControllerTest' -q`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/api/admin/TaxRateAdminController.kt \
        allfolio-backend/backend-app/src/test/kotlin/com/allfolio/api/admin/TaxRateAdminControllerTest.kt
git commit -m "feat(tax): add ADMIN tax-rate CRUD endpoints with authz+validation tests"
```

---

## Task 5: 스키마 (init.sql) + 운영 마이그레이션 SQL

**Files:**
- Modify: `allfolio-backend/infra/postgres/init.sql`
- Create: `docs/superpowers/migrations/2026-07-29-tax-rate-master.sql`

- [ ] **Step 1: init.sql에 tax_rates + 시드 추가**

`allfolio-backend/infra/postgres/init.sql`의 참조데이터 구역(예: `kr_stocks` 블록 뒤)에 아래를 추가:
```sql
-- ── tax_rates : 원천징수 세율 마스터 (국가×유형×유효기간 버저닝) ─────────
CREATE TABLE IF NOT EXISTS tax_rates (
    id              UUID          NOT NULL,
    country         VARCHAR(2)    NOT NULL,
    income_type     VARCHAR(20)   NOT NULL,
    rate            NUMERIC(6,3)  NOT NULL,
    effective_start DATE          NOT NULL,
    effective_end   DATE,
    updated_by      UUID,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_tax_rates PRIMARY KEY (id),
    CONSTRAINT uk_tax_rates_ver UNIQUE (country, income_type, effective_start)
);
INSERT INTO tax_rates (id, country, income_type, rate, effective_start) VALUES
  (gen_random_uuid(), 'US', 'DIVIDEND', 15,     '2000-01-01'),
  (gen_random_uuid(), 'KR', 'DIVIDEND', 15.4,   '2000-01-01'),
  (gen_random_uuid(), 'KR', 'INTEREST', 15.4,   '2000-01-01'),
  (gen_random_uuid(), 'JP', 'DIVIDEND', 15.315, '2000-01-01')
ON CONFLICT (country, income_type, effective_start) DO NOTHING;
```

- [ ] **Step 2: 운영 마이그레이션 SQL 생성**

Create `docs/superpowers/migrations/2026-07-29-tax-rate-master.sql`:
```sql
-- R-03 원천징수 세율 마스터 (SCR-RPT-06) — 운영 Neon 1회성 마이그레이션
-- 실행: /opt/homebrew/opt/libpq/bin/psql "<NEON_DB_URL>" -f 2026-07-29-tax-rate-master.sql
-- 반드시 백엔드 배포 "전"에 실행 (ddl-auto: none). 신규 테이블이라 기존 백엔드엔 무해. 멱등.

CREATE TABLE IF NOT EXISTS tax_rates (
    id              UUID          NOT NULL,
    country         VARCHAR(2)    NOT NULL,
    income_type     VARCHAR(20)   NOT NULL,
    rate            NUMERIC(6,3)  NOT NULL,
    effective_start DATE          NOT NULL,
    effective_end   DATE,
    updated_by      UUID,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_tax_rates PRIMARY KEY (id),
    CONSTRAINT uk_tax_rates_ver UNIQUE (country, income_type, effective_start)
);

INSERT INTO tax_rates (id, country, income_type, rate, effective_start) VALUES
  (gen_random_uuid(), 'US', 'DIVIDEND', 15,     '2000-01-01'),
  (gen_random_uuid(), 'KR', 'DIVIDEND', 15.4,   '2000-01-01'),
  (gen_random_uuid(), 'KR', 'INTEREST', 15.4,   '2000-01-01'),
  (gen_random_uuid(), 'JP', 'DIVIDEND', 15.315, '2000-01-01')
ON CONFLICT (country, income_type, effective_start) DO NOTHING;

-- 검증: 시드 4행
SELECT country, income_type, rate, effective_start FROM tax_rates ORDER BY country, income_type;
```

- [ ] **Step 3: Commit**

```bash
git add allfolio-backend/infra/postgres/init.sql docs/superpowers/migrations/2026-07-29-tax-rate-master.sql
git commit -m "feat(tax): add tax_rates schema, seed, and prod migration SQL"
```

---

## Task 6: 백엔드 전체 회귀

**Files:** (없음 — 검증)

- [ ] **Step 1: 전체 테스트**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test :backend-app:test -q`
Expected: BUILD SUCCESSFUL. 신규 `TaxRateServiceTest`(6)·`TaxRateAdminControllerTest`(5) 포함, 기존 회귀 없음.

- [ ] **Step 2: 실패 시 진단**

`./gradlew :<module>:test --tests '<FQCN>' --info`로 원인 확인 후 수정, 재실행. (예: `@Enumerated` 매핑, 포트 빈 배선 등.)

- [ ] **Step 3: Commit (수정 있었을 때만)**

```bash
git add -A && git commit -m "test(tax): fix regressions"
```

---

## Task 7: FE — 타입 + API 클라이언트

**Files:**
- Create: `frontend/allfolio_app/types/tax-rate.ts`
- Create: `frontend/allfolio_app/lib/tax-rate-admin-api.ts`

- [ ] **Step 1: 타입**

Create `frontend/allfolio_app/types/tax-rate.ts`:
```ts
export type IncomeType = 'DIVIDEND' | 'INTEREST' | 'DISTRIBUTION'

export interface TaxRate {
  id: string
  country: string
  incomeType: IncomeType
  rate: number
  effectiveStart: string
  effectiveEnd: string | null
  updatedBy: string | null
  updatedAt: string
}

export interface RegisterTaxRate {
  country: string
  incomeType: IncomeType
  rate: number
  effectiveStart: string
}
```

- [ ] **Step 2: API 클라이언트**

Create `frontend/allfolio_app/lib/tax-rate-admin-api.ts`:
```ts
import axios from 'axios'
import type { TaxRate, RegisterTaxRate } from '@/types/tax-rate'

const BASE_URL = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'}/api/admin/tax-rates`

export function createTaxRateAdminApi(accessToken: string) {
  const api = axios.create({
    baseURL: BASE_URL,
    timeout: 30_000,
    headers: { Authorization: `Bearer ${accessToken}` },
  })

  return {
    list: async (): Promise<TaxRate[]> => (await api.get<TaxRate[]>('')).data,
    register: async (body: RegisterTaxRate): Promise<TaxRate> => (await api.post<TaxRate>('', body)).data,
  }
}
```

- [ ] **Step 3: 타입 체크**

Run: `cd frontend/allfolio_app && npx tsc --noEmit`
Expected: 에러 없음.

- [ ] **Step 4: Commit**

```bash
git add frontend/allfolio_app/types/tax-rate.ts frontend/allfolio_app/lib/tax-rate-admin-api.ts
git commit -m "feat(tax): add FE tax-rate types and admin API client"
```

---

## Task 8: FE — ADMIN 화면 + 내비 링크

**Files:**
- Create: `frontend/allfolio_app/app/unified/admin/tax-rates/page.tsx`
- Modify: `frontend/allfolio_app/components/NavBar.tsx`

- [ ] **Step 1: ADMIN 세율 마스터 화면**

Create `frontend/allfolio_app/app/unified/admin/tax-rates/page.tsx`:
```tsx
'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { useAuth } from '@/contexts/AuthContext'
import { useRequireAdmin } from '@/lib/useRequireAdmin'
import { createTaxRateAdminApi } from '@/lib/tax-rate-admin-api'
import type { IncomeType, TaxRate } from '@/types/tax-rate'

const COUNTRIES = ['US', 'KR', 'JP'] as const
const INCOME_TYPES: IncomeType[] = ['DIVIDEND', 'INTEREST', 'DISTRIBUTION']

export default function TaxRateMasterPage() {
  const { ready } = useRequireAdmin()
  const { accessToken } = useAuth()
  const [rates, setRates] = useState<TaxRate[]>([])
  const [error, setError] = useState<string | null>(null)
  const [form, setForm] = useState({ country: 'US', incomeType: 'DIVIDEND' as IncomeType, rate: '15', effectiveStart: '' })

  const api = useMemo(() => (accessToken ? createTaxRateAdminApi(accessToken) : null), [accessToken])

  const refetch = useCallback(async () => {
    if (!api) return
    try { setRates(await api.list()) } catch { setError('세율 목록을 불러오지 못했습니다.') }
  }, [api])

  useEffect(() => { if (ready) refetch() }, [ready, refetch])

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!api) return
    setError(null)
    try {
      await api.register({
        country: form.country,
        incomeType: form.incomeType,
        rate: Number(form.rate),
        effectiveStart: form.effectiveStart,
      })
      await refetch()
    } catch { setError('등록에 실패했습니다. 입력값(세율 0~50, 시작일)을 확인하세요.') }
  }

  // 국가×유형 그룹핑 이력 타임라인
  const history = useMemo(() => {
    const groups = new Map<string, TaxRate[]>()
    for (const r of rates) {
      const key = `${r.country}·${r.incomeType}`
      groups.set(key, [...(groups.get(key) ?? []), r])
    }
    return [...groups.entries()].map(([key, rs]) => ({
      key, rows: [...rs].sort((a, b) => b.effectiveStart.localeCompare(a.effectiveStart)),
    }))
  }, [rates])

  if (!ready) return <div className="p-6 text-gray-400">권한 확인 중…</div>

  return (
    <div className="mx-auto max-w-5xl space-y-8 p-6">
      <h1 className="text-2xl font-bold">원천징수 세율 마스터 <span className="text-sm text-gray-400">(ADMIN)</span></h1>
      {error && <div className="rounded bg-red-900/40 px-3 py-2 text-sm text-red-300">{error}</div>}

      {/* 등록 폼 */}
      <form onSubmit={submit} className="flex flex-wrap items-end gap-3 rounded-lg border border-gray-800 p-4">
        <label className="text-sm">국가
          <select className="ml-2 rounded bg-gray-800 px-2 py-1" value={form.country}
            onChange={e => setForm(f => ({ ...f, country: e.target.value }))}>
            {COUNTRIES.map(c => <option key={c} value={c}>{c}</option>)}
          </select>
        </label>
        <label className="text-sm">유형
          <select className="ml-2 rounded bg-gray-800 px-2 py-1" value={form.incomeType}
            onChange={e => setForm(f => ({ ...f, incomeType: e.target.value as IncomeType }))}>
            {INCOME_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
          </select>
        </label>
        <label className="text-sm">세율(%)
          <input type="number" step="0.001" min="0" max="50" required className="ml-2 w-24 rounded bg-gray-800 px-2 py-1"
            value={form.rate} onChange={e => setForm(f => ({ ...f, rate: e.target.value }))} />
        </label>
        <label className="text-sm">적용 시작일
          <input type="date" required className="ml-2 rounded bg-gray-800 px-2 py-1"
            value={form.effectiveStart} onChange={e => setForm(f => ({ ...f, effectiveStart: e.target.value }))} />
        </label>
        <button type="submit" className="rounded bg-blue-600 px-4 py-1.5 text-sm font-medium hover:bg-blue-500">저장</button>
      </form>

      {/* 세율 목록 */}
      <section>
        <h2 className="mb-2 text-lg font-semibold">현행 + 이력</h2>
        <table className="w-full text-sm">
          <thead className="text-gray-400">
            <tr className="border-b border-gray-800 text-left">
              <th className="py-2">국가</th><th>유형</th><th>세율</th><th>적용 시작</th><th>적용 종료</th><th>수정일</th>
            </tr>
          </thead>
          <tbody>
            {rates.map(r => (
              <tr key={r.id} className={`border-b border-gray-900 ${r.effectiveEnd === null ? 'font-semibold text-white' : 'text-gray-400'}`}>
                <td className="py-1.5">{r.country}</td><td>{r.incomeType}</td>
                <td>{r.rate.toFixed(3)}%</td><td>{r.effectiveStart}</td>
                <td>{r.effectiveEnd ?? '현행'}</td><td>{r.updatedAt?.slice(0, 10) ?? '-'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      {/* 국가×유형 변경 이력 */}
      <section>
        <h2 className="mb-2 text-lg font-semibold">변경 이력</h2>
        <div className="space-y-3">
          {history.map(g => (
            <div key={g.key} className="rounded border border-gray-800 p-3">
              <div className="mb-1 text-sm font-medium">{g.key}</div>
              <div className="flex flex-wrap gap-2 text-xs text-gray-400">
                {g.rows.map(r => (
                  <span key={r.id} className="rounded bg-gray-800 px-2 py-1">
                    {r.effectiveStart} ~ {r.effectiveEnd ?? '현행'} : {r.rate.toFixed(3)}%
                  </span>
                ))}
              </div>
            </div>
          ))}
        </div>
      </section>
    </div>
  )
}
```

- [ ] **Step 2: NavBar에 isAdmin 링크 추가**

Modify `frontend/allfolio_app/components/NavBar.tsx`:
1. 구조분해에 `isAdmin` 추가:
```tsx
  const { initialized, authenticated, userName, userEmail, logout, isAdmin } = useAuth()
```
2. `{authenticated && (<>...</>)}` 블록 안, `보고서` Link 다음에 추가:
```tsx
            {isAdmin && (
              <Link href="/unified/admin/tax-rates" className="text-sm text-amber-400 hover:text-amber-300 transition-colors">
                세율 마스터
              </Link>
            )}
```

- [ ] **Step 3: 타입 체크**

Run: `cd frontend/allfolio_app && npx tsc --noEmit`
Expected: 에러 없음.

- [ ] **Step 4: Commit**

```bash
git add frontend/allfolio_app/app/unified/admin/tax-rates/page.tsx frontend/allfolio_app/components/NavBar.tsx
git commit -m "feat(tax): add ADMIN tax-rate master screen and nav link"
```

---

## Task 9: 통합 검증 (실 Postgres 스키마/시드 + 버저닝)

**Files:** (없음 — 검증)

- [ ] **Step 1: init.sql을 실 Postgres 16에 적용해 시드·버저닝 확인**

일회용 컨테이너로 init.sql 전체를 부팅해 tax_rates 시드(4행)와 버저닝 UPDATE를 검증:
```bash
cd /Users/hong9/IdeaProjects/allfolio
CID=$(docker run -d --rm -e POSTGRES_DB=allfolio -e POSTGRES_USER=allfolio -e POSTGRES_PASSWORD=allfolio \
  -v "$PWD/allfolio-backend/infra/postgres/init.sql":/docker-entrypoint-initdb.d/init.sql:ro postgres:16-alpine)
for i in $(seq 1 30); do docker exec "$CID" pg_isready -U allfolio -d allfolio >/dev/null 2>&1 && break; sleep 1; done
sleep 3
echo "=== 시드 4행 ==="; docker exec "$CID" psql -U allfolio -d allfolio -c "SELECT country,income_type,rate,effective_start,effective_end FROM tax_rates ORDER BY country,income_type;"
echo "=== 버저닝: KR DIVIDEND 을 2025부터 16.5로 갱신 (기존 open 마감+신규) ==="
docker exec "$CID" psql -U allfolio -d allfolio -c "UPDATE tax_rates SET effective_end='2024-12-31' WHERE country='KR' AND income_type='DIVIDEND' AND effective_end IS NULL;"
docker exec "$CID" psql -U allfolio -d allfolio -c "INSERT INTO tax_rates(id,country,income_type,rate,effective_start) VALUES (gen_random_uuid(),'KR','DIVIDEND',16.5,'2025-01-01');"
echo "=== findEffective 개념 확인: 2024-06-01 => 15.4, 2025-06-01 => 16.5 ==="
docker exec "$CID" psql -U allfolio -d allfolio -c "SELECT rate FROM tax_rates WHERE country='KR' AND income_type='DIVIDEND' AND effective_start<='2024-06-01' AND (effective_end IS NULL OR effective_end>='2024-06-01');"
docker exec "$CID" psql -U allfolio -d allfolio -c "SELECT rate FROM tax_rates WHERE country='KR' AND income_type='DIVIDEND' AND effective_start<='2025-06-01' AND (effective_end IS NULL OR effective_end>='2025-06-01');"
docker stop "$CID" >/dev/null && echo "stopped"
```
Expected: 시드 4행 출력, 2024-06-01→15.4, 2025-06-01→16.5.

- [ ] **Step 2: 운영 마이그레이션 SQL 멱등 확인 (선택)**

동일 컨테이너 방식으로 `docs/superpowers/migrations/2026-07-29-tax-rate-master.sql`을 빈 DB에 2회 적용해도 4행 유지(ON CONFLICT) 확인. 없으면 Step 1로 충분.

- [ ] **Step 3: (앱 레벨) 컨트롤러/서비스 테스트가 인가+버저닝을 커버함을 확인**

Task 4/2의 테스트가 ADMIN 200 / USER 403 / 무토큰 403 / rate 51 → 400 / 버저닝 마감·신규를 이미 검증함(실 부팅 불필요). 결과 요약 보고.

- [ ] **Step 4: 검증 결과 기록 (커밋 불필요)**

---

## Rollout (배포 시 — 구현과 별개, 사용자 실행)
1. `docs/superpowers/migrations/2026-07-29-tax-rate-master.sql`을 **운영 Neon에 BE 배포 전 수동 실행**(CREATE TABLE + 시드). 검증 SELECT로 4행 확인. 신규 테이블이라 기존 백엔드엔 무해.
2. main 병합 → Render 자동배포(BE) → FE 배포.
3. ADMIN(`rkdghd123@naver.com`) 로그인 → NavBar "세율 마스터" 노출 → `/unified/admin/tax-rates` 목록(4행)·등록(버저닝)·이력 확인. USER → 링크 없음 + 직접 진입 시 리다이렉트, `GET /api/admin/tax-rates` 403.

---

## Notes / 주의
- `TaxRateService`는 포트(`TaxRateRepository`) 의존 → DB 없이 fake로 단위테스트(모듈 관례). 어댑터(`TaxRateRepositoryImpl`)가 JPA 배선.
- `RegisterTaxRateRequest`/`TaxRateResponse`의 LocalDate·enum은 Spring MVC Jackson(JavaTimeModule 등록)로 정상 직렬화 — report 본문 JSON(jacksonObjectMapper 직접 사용) 함정과 무관.
- `@EnableMethodSecurity`는 #50에서 이미 켜짐. 컨트롤러는 URL 매처(`/api/admin/**` → hasRole('ADMIN'))로 게이트되므로 별도 `@PreAuthorize` 불필요.
- FE `rate`는 퍼센트값 → `.toFixed(3)`+`%` (×100 금지, report 스케일 규약과 동일).
- 범위 밖(Phase B): 배당 생성기 기대세율 조회·0.5%p 비교·⚠, 배당뷰어 FE, 국가 국내/해외→실제국가 매핑.
