# 동기화 상태 API + 화면 (AF-9 · AF-10) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 계좌별 동기화 이력(시각·트리거·결과·수집건수·실패사유)을 `ua_sync_logs`에 영속화하고, 요약/이력 API와 동기화 상태 화면을 제공한다.

**Architecture:** unified-asset 헥사고날 패턴 그대로 — domain/sync + port `SyncLogRepository` + infrastructure(entity/jpa/impl). 기록은 단일 초크포인트 `SyncAccountUseCase.execute`(스케줄·수동 공통)에서. FE는 `/unified/accounts/sync` 전용 페이지.

**Tech Stack:** Kotlin/Spring(JPA), JUnit5 + 수기 fake, Next.js(App Router) + react-query, Postgres(운영 Neon — ddl-auto:none, 자립형 마이그레이션 필수).

**스펙:** `docs/superpowers/specs/2026-07-31-sync-status-design.md`

---

## PR A — `feat/sync-status-api` (BE, 스키마 변경 有)

### Task 0: 브랜치 생성

- [ ] `git checkout -b feat/sync-status-api main`

### Task 1: 도메인 — SyncLog

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/domain/sync/SyncLog.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/domain/sync/SyncLogTest.kt`

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
package com.allfolio.unifiedasset.domain.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class SyncLogTest {

    @Test
    fun `에러 메시지는 500자로 절단된다`() {
        val log = SyncLog.create(
            accountId = UUID.randomUUID(), userId = UUID.randomUUID(),
            trigger = SyncTrigger.MANUAL, status = SyncLogStatus.ERROR,
            syncedCount = 0, errorMessage = "x".repeat(600),
        )
        assertEquals(500, log.errorMessage!!.length)
    }

    @Test
    fun `성공 로그는 에러 메시지가 없다`() {
        val log = SyncLog.create(
            accountId = UUID.randomUUID(), userId = UUID.randomUUID(),
            trigger = SyncTrigger.SCHEDULED, status = SyncLogStatus.SUCCESS,
            syncedCount = 7, errorMessage = null,
        )
        assertEquals(7, log.syncedCount)
        assertNull(log.errorMessage)
    }
}
```

- [ ] **Step 2: 실패 확인** — `cd allfolio-backend && ./gradlew :unified-asset:test --tests "*.SyncLogTest"` → 컴파일 실패 예상
- [ ] **Step 3: 구현**

```kotlin
package com.allfolio.unifiedasset.domain.sync

import java.time.LocalDateTime
import java.util.UUID

enum class SyncTrigger { SCHEDULED, MANUAL }
enum class SyncLogStatus { SUCCESS, ERROR }

/** 계좌 동기화 1회 실행 기록. 스케줄·수동 공통. */
class SyncLog private constructor(
    val id: UUID,
    val accountId: UUID,
    val userId: UUID,
    val trigger: SyncTrigger,
    val status: SyncLogStatus,
    val syncedCount: Int,
    val errorMessage: String?,
    val createdAt: LocalDateTime,
) {
    companion object {
        private const val MAX_ERROR_LENGTH = 500

        fun create(
            accountId: UUID, userId: UUID, trigger: SyncTrigger,
            status: SyncLogStatus, syncedCount: Int, errorMessage: String?,
        ) = SyncLog(
            id = UUID.randomUUID(), accountId = accountId, userId = userId,
            trigger = trigger, status = status, syncedCount = syncedCount,
            errorMessage = errorMessage?.take(MAX_ERROR_LENGTH),
            createdAt = LocalDateTime.now(),
        )

        fun reconstruct(
            id: UUID, accountId: UUID, userId: UUID, trigger: SyncTrigger,
            status: SyncLogStatus, syncedCount: Int, errorMessage: String?, createdAt: LocalDateTime,
        ) = SyncLog(id, accountId, userId, trigger, status, syncedCount, errorMessage, createdAt)
    }
}
```

- [ ] **Step 4: 테스트 통과 확인** — 같은 명령 → PASS
- [ ] **Step 5: Commit** — `feat(sync): SyncLog 도메인 (AF-9)`

### Task 2: 포트 — SyncLogRepository

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/port/SyncLogRepository.kt`

- [ ] **Step 1: 인터페이스 작성** (테스트는 사용처 유스케이스 테스트가 커버)

```kotlin
package com.allfolio.unifiedasset.application.port

import com.allfolio.unifiedasset.domain.sync.SyncLog
import java.util.UUID

interface SyncLogRepository {
    fun save(log: SyncLog): SyncLog
    /** created_at 내림차순 최대 limit건. */
    fun findByAccountId(accountId: UUID, limit: Int): List<SyncLog>
    /** 사용자의 계좌별 최신 로그 1건. key=accountId. */
    fun findLatestByUserId(userId: UUID): Map<UUID, SyncLog>
    fun deleteByAccountId(accountId: UUID)
}
```

- [ ] **Step 2: Commit** — `feat(sync): SyncLogRepository 포트 (AF-9)`

### Task 3: SyncAccountUseCase 기록 + AccountSyncRunner 트리거 확장

**Files:**
- Modify: `AccountSyncRunner.kt` — `execute(accountId, trigger = MANUAL)`
- Modify: `SyncAccountUseCase.kt` — 전 종료 경로에서 SyncLog 저장
- Create: `.../usecase/SyncAccountUseCaseLoggingTest.kt`
- Modify: `SyncAccountUseCaseNavTest.kt` · `SyncAccountUseCaseSensitiveDataTest.kt` — 생성자에 fake 로그 리포지토리 추가

- [ ] **Step 1: 실패 테스트 작성** — `SyncAccountUseCaseLoggingTest`

```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.*
import com.allfolio.unifiedasset.domain.account.*
import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.sync.SyncLog
import com.allfolio.unifiedasset.domain.sync.SyncLogStatus
import com.allfolio.unifiedasset.domain.sync.SyncTrigger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.util.UUID

class SyncAccountUseCaseLoggingTest {

    private val fx = object : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String): BigDecimal = amount
    }

    private class InMemorySyncLogRepository : SyncLogRepository {
        val saved = mutableListOf<SyncLog>()
        var failOnSave = false
        override fun save(log: SyncLog): SyncLog {
            if (failOnSave) throw RuntimeException("log db down")
            saved += log; return log
        }
        override fun findByAccountId(accountId: UUID, limit: Int): List<SyncLog> =
            saved.filter { it.accountId == accountId }.sortedByDescending { it.createdAt }.take(limit)
        override fun findLatestByUserId(userId: UUID): Map<UUID, SyncLog> =
            saved.filter { it.userId == userId }.groupBy { it.accountId }
                .mapValues { (_, v) -> v.maxBy { it.createdAt } }
        override fun deleteByAccountId(accountId: UUID) { saved.removeAll { it.accountId == accountId } }
    }

    private class FixedAccountRepository(private val account: Account?) : AccountRepository {
        override fun save(account: Account): Account = account
        override fun findById(id: UUID): Account? = account
        override fun findByUserId(userId: UUID): List<Account> = listOfNotNull(account)
        override fun findByProviders(providers: Collection<AccountProvider>): List<Account> = emptyList()
        override fun delete(id: UUID) = Unit
        override fun updateStatus(id: UUID, status: AccountStatus) = Unit
    }

    private class EmptyAssetRepository : AssetRepository {
        override fun save(asset: Asset): Asset = asset
        override fun saveAll(assets: List<Asset>): List<Asset> = assets
        override fun findById(id: UUID): Asset? = null
        override fun findByUserId(userId: UUID): List<Asset> = emptyList()
        override fun findByAccountId(accountId: UUID): List<Asset> = emptyList()
        override fun deleteByAccountId(accountId: UUID) = Unit
        override fun delete(id: UUID) = Unit
    }

    private class FixedSyncAdapter(
        override val supportedProvider: AccountProvider,
        private val result: () -> List<Asset>,
    ) : SyncAdapter {
        override fun sync(account: Account): List<Asset> = result()
    }

    private fun account(provider: AccountProvider = AccountProvider.BINANCE) = Account.create(
        userId = UUID.randomUUID(), provider = provider,
        accountType = AccountType.EXCHANGE, accountName = "t",
    )

    private fun useCase(
        account: Account?, logs: InMemorySyncLogRepository,
        adapter: SyncAdapter? = account?.let { FixedSyncAdapter(it.provider) { emptyList() } },
    ) = SyncAccountUseCase(
        accountRepository = FixedAccountRepository(account),
        assetRepository = EmptyAssetRepository(),
        adapters = listOfNotNull(adapter),
        snapshotService = mock(PerformanceSnapshotService::class.java),
        fx = fx,
        syncLogRepository = logs,
    )

    @Test
    fun `성공 시 SUCCESS 로그가 트리거·건수와 함께 남는다`() {
        val acct = account()
        val logs = InMemorySyncLogRepository()
        useCase(acct, logs).execute(acct.id, SyncTrigger.SCHEDULED)

        val log = logs.saved.single()
        assertEquals(SyncLogStatus.SUCCESS, log.status)
        assertEquals(SyncTrigger.SCHEDULED, log.trigger)
        assertEquals(0, log.syncedCount)
        assertEquals(acct.userId, log.userId)
    }

    @Test
    fun `어댑터 예외 시 ERROR 로그에 실패 사유가 남는다`() {
        val acct = account()
        val logs = InMemorySyncLogRepository()
        val throwing = FixedSyncAdapter(acct.provider) { throw IllegalStateException("api key expired") }
        useCase(acct, logs, throwing).execute(acct.id)

        val log = logs.saved.single()
        assertEquals(SyncLogStatus.ERROR, log.status)
        assertEquals(SyncTrigger.MANUAL, log.trigger)
        assertEquals("api key expired", log.errorMessage)
    }

    @Test
    fun `어댑터 미지원 계좌도 ERROR 로그가 남는다`() {
        val acct = account(AccountProvider.MANUAL)
        val logs = InMemorySyncLogRepository()
        useCase(acct, logs, FixedSyncAdapter(AccountProvider.BINANCE) { emptyList() }).execute(acct.id)

        assertEquals(SyncLogStatus.ERROR, logs.saved.single().status)
    }

    @Test
    fun `계좌가 없으면 로그를 남기지 않는다`() {
        val logs = InMemorySyncLogRepository()
        val result = useCase(null, logs).execute(UUID.randomUUID())
        assertEquals(AccountStatus.ERROR, result.status)
        assertTrue(logs.saved.isEmpty())
    }

    @Test
    fun `로그 저장 실패가 동기화 결과에 영향을 주지 않는다`() {
        val acct = account()
        val logs = InMemorySyncLogRepository().apply { failOnSave = true }
        val result = useCase(acct, logs).execute(acct.id)
        assertEquals(AccountStatus.ACTIVE, result.status)
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew :unified-asset:test --tests "*.SyncAccountUseCaseLoggingTest"` → 컴파일 실패(생성자·시그니처)
- [ ] **Step 3: 구현**

`AccountSyncRunner.kt`:
```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.domain.sync.SyncTrigger
import java.util.UUID

/**
 * 단일 계좌 동기화 실행 seam.
 * DailyAccountSyncer가 concrete SyncAccountUseCase 대신 이 인터페이스에 의존해
 * 테스트에서 fake로 대체 가능하게 한다. 유일 구현체는 SyncAccountUseCase.
 */
interface AccountSyncRunner {
    fun execute(accountId: UUID, trigger: SyncTrigger = SyncTrigger.MANUAL): SyncResult
}
```

`SyncAccountUseCase.kt` 변경 요지:
- 생성자에 `private val syncLogRepository: SyncLogRepository` 추가.
- `execute(accountId: UUID, trigger: SyncTrigger): SyncResult`로 시그니처 변경.
- 계좌가 확인된 이후의 모든 종료 경로에서 저장(계좌 자체가 없으면 userId를 몰라 로그 불가 — 스킵):

```kotlin
private fun record(account: Account, trigger: SyncTrigger, result: SyncResult) {
    runCatching {
        syncLogRepository.save(
            SyncLog.create(
                accountId = account.id, userId = account.userId, trigger = trigger,
                status = if (result.status == AccountStatus.ACTIVE) SyncLogStatus.SUCCESS else SyncLogStatus.ERROR,
                syncedCount = result.synced, errorMessage = result.error,
            )
        )
    }.onFailure { e -> log.warn("sync log save failed accountId={}", account.id, e) }
}
```
  - 어댑터 없음 경로: `record(account, trigger, result)` 후 반환.
  - 성공 경로: `SyncResult(...)` 만들어 `record` 후 반환.
  - catch 경로: 동일.
  - 민감정보 재연결(findById 예외) 경로: account 객체가 없으므로 로그 스킵(현행 유지).
- [ ] **Step 4: 기존 테스트 수정** — `SyncAccountUseCaseNavTest`·`SyncAccountUseCaseSensitiveDataTest` 생성자에 no-op fake 추가:

```kotlin
private class NoopSyncLogRepository : SyncLogRepository {
    override fun save(log: SyncLog): SyncLog = log
    override fun findByAccountId(accountId: UUID, limit: Int): List<SyncLog> = emptyList()
    override fun findLatestByUserId(userId: UUID): Map<UUID, SyncLog> = emptyMap()
    override fun deleteByAccountId(accountId: UUID) = Unit
}
```

- [ ] **Step 5: 전체 통과 확인** — `./gradlew :unified-asset:test --tests "*.SyncAccountUseCase*"` → PASS
- [ ] **Step 6: Commit** — `feat(sync): SyncAccountUseCase 동기화 이력 기록 (AF-9)`

### Task 4: DailyAccountSyncer → SCHEDULED 트리거

**Files:**
- Modify: `DailyAccountSyncer.kt` — `syncRunner.execute(account.id, SyncTrigger.SCHEDULED)`
- Modify: `DailyAccountSyncerTest.kt` — RecordingSyncRunner 시그니처 + 트리거 캡처 검증

- [ ] **Step 1: 테스트 수정(실패 먼저)** — RecordingSyncRunner에 `val triggers = mutableListOf<SyncTrigger>()` 추가, `execute(accountId, trigger)` 오버라이드, 기존 테스트에 `assertTrue(runner.triggers.all { it == SyncTrigger.SCHEDULED })` 추가
- [ ] **Step 2: 구현 후 통과** — `./gradlew :unified-asset:test --tests "*.DailyAccountSyncerTest"` → PASS
- [ ] **Step 3: Commit** — `feat(sync): 배치 동기화 SCHEDULED 트리거 표기 (AF-9)`

### Task 5: 인프라 — entity/jpa/impl

**Files:**
- Create: `infrastructure/entity/SyncLogEntity.kt`
- Create: `infrastructure/jpa/SyncLogJpaRepository.kt`
- Create: `infrastructure/repository/SyncLogRepositoryImpl.kt`

- [ ] **Step 1: 구현** (리포지토리 임플은 컴파일 확인 — DB 통합테스트 컨벤션 없음)

`SyncLogEntity.kt`:
```kotlin
package com.allfolio.unifiedasset.infrastructure.entity

import com.allfolio.unifiedasset.domain.sync.SyncLog
import com.allfolio.unifiedasset.domain.sync.SyncLogStatus
import com.allfolio.unifiedasset.domain.sync.SyncTrigger
import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "ua_sync_logs")
class SyncLogEntity(
    @Id @Column(columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "account_id", nullable = false, columnDefinition = "uuid")
    val accountId: UUID,

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    val userId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    val triggerType: SyncTrigger,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val status: SyncLogStatus,

    @Column(name = "synced_count", nullable = false)
    val syncedCount: Int,

    @Column(name = "error_message", length = 500)
    val errorMessage: String?,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime,
) {
    fun toDomain() = SyncLog.reconstruct(id, accountId, userId, triggerType, status, syncedCount, errorMessage, createdAt)

    companion object {
        fun fromDomain(l: SyncLog) = SyncLogEntity(
            l.id, l.accountId, l.userId, l.trigger, l.status, l.syncedCount, l.errorMessage, l.createdAt,
        )
    }
}
```

`SyncLogJpaRepository.kt`:
```kotlin
package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.SyncLogEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

interface SyncLogJpaRepository : JpaRepository<SyncLogEntity, UUID> {
    fun findByAccountIdOrderByCreatedAtDesc(accountId: UUID, pageable: Pageable): List<SyncLogEntity>

    /** 계좌별 최신 1건 (Postgres DISTINCT ON). */
    @Query(
        value = "SELECT DISTINCT ON (account_id) * FROM ua_sync_logs WHERE user_id = :userId ORDER BY account_id, created_at DESC",
        nativeQuery = true,
    )
    fun findLatestPerAccountByUserId(userId: UUID): List<SyncLogEntity>

    @Modifying @Transactional
    fun deleteByAccountId(accountId: UUID)
}
```

`SyncLogRepositoryImpl.kt`:
```kotlin
package com.allfolio.unifiedasset.infrastructure.repository

import com.allfolio.unifiedasset.application.port.SyncLogRepository
import com.allfolio.unifiedasset.domain.sync.SyncLog
import com.allfolio.unifiedasset.infrastructure.entity.SyncLogEntity
import com.allfolio.unifiedasset.infrastructure.jpa.SyncLogJpaRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class SyncLogRepositoryImpl(private val jpa: SyncLogJpaRepository) : SyncLogRepository {
    override fun save(log: SyncLog): SyncLog = jpa.save(SyncLogEntity.fromDomain(log)).toDomain()
    override fun findByAccountId(accountId: UUID, limit: Int): List<SyncLog> =
        jpa.findByAccountIdOrderByCreatedAtDesc(accountId, PageRequest.of(0, limit)).map { it.toDomain() }
    override fun findLatestByUserId(userId: UUID): Map<UUID, SyncLog> =
        jpa.findLatestPerAccountByUserId(userId).associate { it.accountId to it.toDomain() }
    override fun deleteByAccountId(accountId: UUID) = jpa.deleteByAccountId(accountId)
}
```

- [ ] **Step 2: 컴파일 확인** — `./gradlew :unified-asset:compileKotlin` → BUILD SUCCESSFUL
- [ ] **Step 3: Commit** — `feat(sync): ua_sync_logs 영속화 어댑터 (AF-9)`

### Task 6: GetSyncStatusUseCase (요약 조합)

**Files:**
- Create: `application/usecase/GetSyncStatusUseCase.kt`
- Test: `application/usecase/GetSyncStatusUseCaseTest.kt`

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.SyncLogRepository
import com.allfolio.unifiedasset.domain.account.*
import com.allfolio.unifiedasset.domain.sync.SyncLog
import com.allfolio.unifiedasset.domain.sync.SyncLogStatus
import com.allfolio.unifiedasset.domain.sync.SyncTrigger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class GetSyncStatusUseCaseTest {

    private class FixedAccountRepository(private val accounts: List<Account>) : AccountRepository {
        override fun save(account: Account): Account = account
        override fun findById(id: UUID): Account? = accounts.find { it.id == id }
        override fun findByUserId(userId: UUID): List<Account> = accounts.filter { it.userId == userId }
        override fun findByProviders(providers: Collection<AccountProvider>): List<Account> = emptyList()
        override fun delete(id: UUID) = Unit
        override fun updateStatus(id: UUID, status: AccountStatus) = Unit
    }

    private class FixedSyncLogRepository(private val latest: Map<UUID, SyncLog>) : SyncLogRepository {
        override fun save(log: SyncLog): SyncLog = log
        override fun findByAccountId(accountId: UUID, limit: Int): List<SyncLog> = emptyList()
        override fun findLatestByUserId(userId: UUID): Map<UUID, SyncLog> = latest
        override fun deleteByAccountId(accountId: UUID) = Unit
    }

    @Test
    fun `계좌별 최신 로그와 syncable 여부를 조합한다`() {
        val userId = UUID.randomUUID()
        val kis = Account.create(userId = userId, provider = AccountProvider.KIS,
            accountType = AccountType.STOCK, accountName = "kis", currency = "KRW")
        val manual = Account.create(userId = userId, provider = AccountProvider.MANUAL,
            accountType = AccountType.MANUAL, accountName = "manual", currency = "KRW")
        val log = SyncLog.create(kis.id, userId, SyncTrigger.SCHEDULED, SyncLogStatus.ERROR, 0, "expired")

        val result = GetSyncStatusUseCase(
            FixedAccountRepository(listOf(kis, manual)),
            FixedSyncLogRepository(mapOf(kis.id to log)),
        ).execute(userId)

        assertEquals(2, result.size)
        val kisRow = result.first { it.accountId == kis.id }
        assertTrue(kisRow.syncable)
        assertEquals("expired", kisRow.lastLog?.errorMessage)
        assertEquals("SCHEDULED", kisRow.lastLog?.trigger)
        val manualRow = result.first { it.accountId == manual.id }
        assertFalse(manualRow.syncable)
        assertNull(manualRow.lastLog)
    }
}
```

- [ ] **Step 2: 실패 확인** → 컴파일 실패
- [ ] **Step 3: 구현**

```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.SyncLogRepository
import com.allfolio.unifiedasset.domain.sync.SyncLog
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.UUID

data class SyncLogView(
    val id: UUID,
    val trigger: String,
    val status: String,
    val syncedCount: Int,
    val errorMessage: String?,
    val createdAt: LocalDateTime,
)

fun SyncLog.toView() = SyncLogView(id, trigger.name, status.name, syncedCount, errorMessage, createdAt)

data class AccountSyncStatus(
    val accountId: UUID,
    val accountName: String,
    val provider: String,
    val status: String,
    val lastSyncedAt: LocalDateTime?,
    val syncable: Boolean,
    val lastLog: SyncLogView?,
)

/** 계좌 목록 + 계좌별 최신 동기화 로그 요약 (AF-9 완료 조건). */
@Service
class GetSyncStatusUseCase(
    private val accountRepository: AccountRepository,
    private val syncLogRepository: SyncLogRepository,
) {
    fun execute(userId: UUID): List<AccountSyncStatus> {
        val latest = syncLogRepository.findLatestByUserId(userId)
        return accountRepository.findByUserId(userId).map { account ->
            AccountSyncStatus(
                accountId = account.id,
                accountName = account.accountName,
                provider = account.provider.name,
                status = account.status.name,
                lastSyncedAt = account.lastSyncedAt,
                syncable = account.provider in DailyAccountSyncer.SYNC_ELIGIBLE_PROVIDERS,
                lastLog = latest[account.id]?.toView(),
            )
        }
    }
}
```

- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: Commit** — `feat(sync): 동기화 상태 요약 유스케이스 (AF-9)`

### Task 7: 컨트롤러 — 엔드포인트 2개 + 삭제 정리

**Files:**
- Modify: `api/AccountController.kt`

- [ ] **Step 1: 구현** — 생성자에 `getSyncStatusUseCase: GetSyncStatusUseCase`, `syncLogRepository: SyncLogRepository` 추가 후:

```kotlin
@GetMapping("/sync-status")
fun syncStatus(@RequestHeader("X-User-Id") userId: UUID): List<AccountSyncStatus> =
    getSyncStatusUseCase.execute(userId)

@GetMapping("/{id}/sync-logs")
fun syncLogs(
    @RequestHeader("X-User-Id") userId: UUID,
    @PathVariable id: UUID,
    @RequestParam(defaultValue = "20") limit: Int,
): List<SyncLogView> {
    authorizationService.requireOwnedAccount(userId, id)
    return syncLogRepository.findByAccountId(id, limit.coerceIn(1, 100)).map { it.toView() }
}
```

`delete(...)`에 `syncLogRepository.deleteByAccountId(id)` 추가 (assetRepository.deleteByAccountId 다음 줄).
`sync(...)`는 `syncAccountUseCase.execute(id, SyncTrigger.MANUAL)`로 명시.

주의: `/sync-status`는 `/{id}` 경로들과 충돌하지 않는다(UUID 컨버전 실패 대상이 아님 — 구체 경로가 우선 매칭).

- [ ] **Step 2: 컴파일 + 전체 테스트** — `./gradlew :unified-asset:test :backend-app:compileKotlin` → PASS
- [ ] **Step 3: Commit** — `feat(sync): 동기화 상태·이력 조회 API (AF-9)`

### Task 8: 스키마 — init.sql + 운영 마이그레이션

**Files:**
- Modify: `allfolio-backend/infra/postgres/init.sql` — ua_goals 정의 근처에 추가
- Create: `docs/superpowers/migrations/2026-07-31-sync-logs.sql`

- [ ] **Step 1: init.sql에 추가**

```sql
-- ── ua_sync_logs ──────────────────────────────────────────────
-- 계좌 동기화 실행 이력 (AF-9). trigger는 예약어 회피로 trigger_type.
CREATE TABLE IF NOT EXISTS ua_sync_logs (
    id            UUID         NOT NULL,
    account_id    UUID         NOT NULL,
    user_id       UUID         NOT NULL,
    trigger_type  VARCHAR(20)  NOT NULL,   -- SCHEDULED / MANUAL
    status        VARCHAR(20)  NOT NULL,   -- SUCCESS / ERROR
    synced_count  INT          NOT NULL DEFAULT 0,
    error_message VARCHAR(500),
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_ua_sync_logs PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_ua_sync_logs_account ON ua_sync_logs (account_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ua_sync_logs_user ON ua_sync_logs (user_id, created_at DESC);
```

- [ ] **Step 2: 마이그레이션 파일** — 동일 DDL + 헤더 주석(자립형·멱등·BE 배포 전 실행) + 검증 SELECT (`2026-07-31-cashflow-link-id.sql` 형식)
- [ ] **Step 3: Commit** — `feat(sync): ua_sync_logs 스키마 + 운영 마이그레이션 (AF-9)`

### Task 9: PR A 오픈

- [ ] **Step 1: 회귀** — `./gradlew :unified-asset:test :backend-app:compileKotlin` → PASS
- [ ] **Step 2: push + `gh pr create`** — 제목 `feat(sync): AF-9 계좌 동기화 이력·상태 API`, 본문에 스키마 변경·마이그레이션 파일 경로 명시. **머지하지 않음(사용자 게이트).**

---

## PR B — `feat/sync-status-screen` (FE, PR A 위 스택)

### Task 10: 브랜치 + API 클라이언트·타입

**Files:**
- Modify: `frontend/allfolio_app/types/unified.ts`
- Modify: `frontend/allfolio_app/lib/unified-api.ts`

- [ ] **Step 1: `git checkout -b feat/sync-status-screen feat/sync-status-api`**
- [ ] **Step 2: 타입 추가** (`types/unified.ts`)

```typescript
export interface SyncLogView {
  id: string
  trigger: 'SCHEDULED' | 'MANUAL'
  status: 'SUCCESS' | 'ERROR'
  syncedCount: number
  errorMessage: string | null
  createdAt: string
}

export interface AccountSyncStatus {
  accountId: string
  accountName: string
  provider: string
  status: string
  lastSyncedAt: string | null
  syncable: boolean
  lastLog: SyncLogView | null
}
```

- [ ] **Step 3: API 함수 추가** (`lib/unified-api.ts` accounts 객체 내)

```typescript
syncStatus: async () =>
  (await api.get<AccountSyncStatus[]>('/accounts/sync-status')).data,
syncLogs: async (id: string, limit = 20) =>
  (await api.get<SyncLogView[]>(`/accounts/${id}/sync-logs`, { params: { limit } })).data,
```

- [ ] **Step 4: Commit** — `feat(sync): 동기화 상태 API 클라이언트 (AF-10)`

### Task 11: 화면 — `/unified/accounts/sync`

**Files:**
- Create: `frontend/allfolio_app/app/unified/accounts/sync/page.tsx`
- Modify: `frontend/allfolio_app/app/unified/accounts/page.tsx` — 헤더 옆 "동기화 상태" 링크

- [ ] **Step 1: 페이지 구현** — 요구: 실패 우선 정렬 + 실패 요약 배너 + 행별 재동기화 + 펼침 이력 + 비대상 하단 그룹

핵심 구조 (기존 계좌 페이지 스타일·STATUS_STYLE 재사용):

```tsx
'use client'

import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import Link from 'next/link'
import { useUnifiedApi } from '@/lib/useApi'
import type { AccountSyncStatus, SyncLogView } from '@/types/unified'

const STATUS_STYLE: Record<string, string> = {
  ACTIVE:   'bg-emerald-900/40 text-emerald-400 border-emerald-800',
  SYNCING:  'bg-yellow-900/40 text-yellow-400 border-yellow-800',
  ERROR:    'bg-red-900/40 text-red-400 border-red-800',
  INACTIVE: 'bg-gray-800 text-gray-500 border-gray-700',
}
const TRIGGER_KO = { SCHEDULED: '자동', MANUAL: '수동' } as const

function fmt(ts: string | null | undefined) {
  return ts ? new Date(ts).toLocaleString('ko-KR') : '없음'
}

function SyncHistory({ accountId }: { accountId: string }) {
  const api = useUnifiedApi()
  const { data: logs = [], isLoading } = useQuery({
    queryKey: ['unified', 'sync-logs', accountId],
    queryFn:  () => api!.accounts.syncLogs(accountId),
    enabled:  !!api,
  })
  if (isLoading) return <div className="py-3 text-xs text-gray-500">이력 불러오는 중…</div>
  if (logs.length === 0) return <div className="py-3 text-xs text-gray-500">동기화 이력이 없습니다</div>
  return (
    <table className="mt-3 w-full text-xs">
      <thead><tr className="text-left text-gray-500">
        <th className="py-1 pr-4 font-medium">시각</th>
        <th className="py-1 pr-4 font-medium">트리거</th>
        <th className="py-1 pr-4 font-medium">결과</th>
        <th className="py-1 font-medium">상세</th>
      </tr></thead>
      <tbody>
        {logs.map((l: SyncLogView) => (
          <tr key={l.id} className="border-t border-gray-800">
            <td className="py-1.5 pr-4 text-gray-400">{fmt(l.createdAt)}</td>
            <td className="py-1.5 pr-4 text-gray-400">{TRIGGER_KO[l.trigger]}</td>
            <td className={`py-1.5 pr-4 ${l.status === 'SUCCESS' ? 'text-emerald-400' : 'text-red-400'}`}>
              {l.status === 'SUCCESS' ? '성공' : '실패'}
            </td>
            <td className="py-1.5 text-gray-400">
              {l.status === 'SUCCESS' ? `${l.syncedCount}개 자산` : (l.errorMessage ?? '-')}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
```

메인 컴포넌트: `syncStatus()` 조회 → `errored = rows.filter(r => r.status === 'ERROR')` 배너, syncable 그룹은 ERROR 우선 정렬(`[...syncableRows].sort((a, b) => Number(b.status === 'ERROR') - Number(a.status === 'ERROR'))`), 행마다: 이름·상태 배지·마지막 동기화 시각·마지막 결과(`lastLog`: 성공 `N개 자산 (자동)` / 실패 사유 빨강)·재동기화 버튼(`api.accounts.sync(id)` 후 `['unified','sync-status']`·`['unified','sync-logs',id]`·`['unified','accounts']` invalidate)·"이력" 토글(`<SyncHistory/>`). 비syncable 계좌는 "자동 동기화 대상 아님" 섹션에 이력 없이 나열. 쿼리 키: `['unified', 'sync-status']`.

- [ ] **Step 2: 계좌 페이지 링크** — `/unified/accounts` 헤더의 "+ 계좌 추가" 옆:

```tsx
<Link
  href="/unified/accounts/sync"
  className="rounded-lg border border-gray-600 px-4 py-2 text-sm font-medium hover:border-blue-500 hover:text-blue-400 transition-colors"
>
  동기화 상태
</Link>
```

- [ ] **Step 3: 타입 체크** — `cd frontend/allfolio_app && npx tsc --noEmit` → 에러 없음
- [ ] **Step 4: Commit** — `feat(sync): 동기화 상태 화면 (AF-10)`

### Task 12: PR B 오픈

- [ ] **Step 1: push + `gh pr create --base feat/sync-status-api`** — 제목 `feat(sync): AF-10 동기화 상태 화면`. 스택 주의: **PR A 머지 전 base 삭제 금지**, A 머지 시 B base를 main으로 재타겟.

---

## 검증 (전 PR 공통)

- [ ] BE: `./gradlew :unified-asset:test :backend-app:compileKotlin` 그린
- [ ] FE: `npx tsc --noEmit` 그린
- [ ] 라이브(가능 시): 로컬 스택(docker postgres/redis + bootRun 8090 + npm dev 3000) + `livetest@allfolio.dev` — 동기화 상태 페이지 로드, 수동 재동기화로 로그 행 생성 확인, 실패 케이스는 API 키 오류 계좌로 확인
- [ ] 노션 №9·№10 상태 갱신 + 메모리 갱신
