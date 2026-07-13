# 일 시세갱신 + NAV 스냅샷 통합 배치 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 매일 자정(KST) NAV 스냅샷 직전에 자동조회 대상 계좌를 전부 재동기화해, 최신 시세 기준으로 일단위 스냅샷이 쌓이게 한다.

**Architecture:** 기존 `DailyNavScheduler`(00:00 KST)를 오케스트레이터로 확장 — 새 `DailyAccountSyncer`로 대상 계좌를 전부 재동기화(계좌별 오류 격리)한 뒤, 기존 per-currency NAV 스냅샷을 실행한다. 테스트 가능성을 위해 `SyncAccountUseCase`를 얇은 `AccountSyncRunner` 인터페이스 뒤에 둔다.

**Tech Stack:** Kotlin, Spring Boot(@Scheduled, Spring Data JPA), JUnit5 + hand-written fakes.

**참고 스펙:** [docs/superpowers/specs/2026-07-13-daily-account-sync-snapshot-design.md](../specs/2026-07-13-daily-account-sync-snapshot-design.md)

---

## File Structure

**신규 (unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/):**
- `AccountSyncRunner.kt` — 단일 계좌 sync 실행 seam(인터페이스)
- `DailyAccountSyncer.kt` — 대상 계좌 전체 재동기화 유닛 + `SyncBatchResult`

**수정:**
- `application/port/AccountRepository.kt` — `findByProviders` 추가
- `infrastructure/jpa/AccountJpaRepository.kt` — `findByProviderIn` 추가
- `infrastructure/repository/AccountRepositoryImpl.kt` — `findByProviders` 구현
- `application/usecase/SyncAccountUseCase.kt` — `AccountSyncRunner` 구현 선언
- `application/usecase/DailyNavScheduler.kt` — sync→snapshot 오케스트레이션

**테스트:**
- 신규 `.../application/usecase/DailyAccountSyncerTest.kt`
- 수정(포트 메서드 추가로 컴파일 깨짐): `SyncAccountUseCaseSensitiveDataTest.kt`, `ReportServiceTest.kt`, `SyncAccountUseCaseNavTest.kt` — fake `AccountRepository`에 `findByProviders` 추가

**Gradle:** `allfolio-backend/gradlew`. 테스트: `cd allfolio-backend && ./gradlew :unified-asset:test`

---

### Task 1: AccountRepository에 findByProviders 추가 (+ 기존 fake 갱신)

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/port/AccountRepository.kt`
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/jpa/AccountJpaRepository.kt`
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/repository/AccountRepositoryImpl.kt`
- Modify(test fakes): `SyncAccountUseCaseSensitiveDataTest.kt`, `ReportServiceTest.kt`, `SyncAccountUseCaseNavTest.kt`

- [ ] **Step 1: 포트에 메서드 추가**

`AccountRepository.kt` — import에 `AccountProvider` 추가하고 인터페이스에 메서드 추가:

```kotlin
package com.allfolio.unifiedasset.application.port

import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountStatus
import java.util.UUID

interface AccountRepository {
    fun save(account: Account): Account
    fun findById(id: UUID): Account?
    fun findByUserId(userId: UUID): List<Account>
    fun findByProviders(providers: Collection<AccountProvider>): List<Account>
    fun delete(id: UUID)
    fun updateStatus(id: UUID, status: AccountStatus)
}
```

- [ ] **Step 2: JPA 인터페이스에 파생 쿼리 추가**

`AccountJpaRepository.kt` — `findByUserId` 아래에 추가:

```kotlin
    fun findByProviderIn(providers: Collection<com.allfolio.unifiedasset.domain.account.AccountProvider>): List<AccountEntity>
```

- [ ] **Step 3: 구현체에 추가**

`AccountRepositoryImpl.kt` — import에 `AccountProvider` 추가하고 `findByUserId` 아래에:

```kotlin
    override fun findByProviders(providers: Collection<com.allfolio.unifiedasset.domain.account.AccountProvider>): List<Account> =
        jpa.findByProviderIn(providers).map { it.toDomain() }
```

- [ ] **Step 4: 기존 테스트 fake 3곳에 메서드 추가**

컴파일이 깨지므로, 아래 3개 파일에서 `AccountRepository`를 구현한 클래스(각 파일의 fake/stub)에 다음 override를 추가한다. 각 파일 상단 import에 `com.allfolio.unifiedasset.domain.account.AccountProvider`가 없으면 추가한다.

```kotlin
    override fun findByProviders(providers: Collection<AccountProvider>): List<Account> = emptyList()
```

대상:
- `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/SyncAccountUseCaseSensitiveDataTest.kt`
- `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/ReportServiceTest.kt`
- `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/SyncAccountUseCaseNavTest.kt`

> 각 파일에서 `: AccountRepository`로 검색해 해당 클래스를 찾은 뒤, 다른 `override fun ...` 들과 같은 위치에 추가.

- [ ] **Step 5: 컴파일 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:compileKotlin :unified-asset:compileTestKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/port/AccountRepository.kt \
        allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/jpa/AccountJpaRepository.kt \
        allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/repository/AccountRepositoryImpl.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/SyncAccountUseCaseSensitiveDataTest.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/ReportServiceTest.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/SyncAccountUseCaseNavTest.kt
git commit -m "feat(unified-asset): AccountRepository.findByProviders 추가"
```

---

### Task 2: AccountSyncRunner 인터페이스 + SyncAccountUseCase 구현

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/AccountSyncRunner.kt`
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/SyncAccountUseCase.kt`

> `DailyAccountSyncer`가 concrete `SyncAccountUseCase`에 직접 의존하지 않도록(테스트 시 fake 주입) 얇은 seam을 둔다. 동작 변경 없음.

- [ ] **Step 1: 인터페이스 생성**

`AccountSyncRunner.kt`:

```kotlin
package com.allfolio.unifiedasset.application.usecase

import java.util.UUID

/**
 * 단일 계좌 동기화 실행 seam.
 * DailyAccountSyncer가 concrete SyncAccountUseCase 대신 이 인터페이스에 의존해
 * 테스트에서 fake로 대체 가능하게 한다. 유일 구현체는 SyncAccountUseCase.
 */
interface AccountSyncRunner {
    fun execute(accountId: UUID): SyncResult
}
```

- [ ] **Step 2: SyncAccountUseCase가 구현하도록 선언**

`SyncAccountUseCase.kt` — 클래스 선언에 `: AccountSyncRunner` 추가하고 `execute`에 `override` 부여. 현재:

```kotlin
@Service
class SyncAccountUseCase(
    private val accountRepository: AccountRepository,
    private val assetRepository: AssetRepository,
    private val adapters: List<SyncAdapter>,
    private val snapshotService: PerformanceSnapshotService,
    private val fx: FxConverter,
) {
```
를

```kotlin
@Service
class SyncAccountUseCase(
    private val accountRepository: AccountRepository,
    private val assetRepository: AssetRepository,
    private val adapters: List<SyncAdapter>,
    private val snapshotService: PerformanceSnapshotService,
    private val fx: FxConverter,
) : AccountSyncRunner {
```
로 바꾸고, `@Transactional` 아래 `fun execute(accountId: UUID): SyncResult {` 를 `override fun execute(accountId: UUID): SyncResult {` 로 바꾼다. (본문 그대로)

- [ ] **Step 3: 컴파일 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/AccountSyncRunner.kt \
        allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/SyncAccountUseCase.kt
git commit -m "refactor(unified-asset): SyncAccountUseCase에 AccountSyncRunner seam 도입"
```

---

### Task 3: DailyAccountSyncer (TDD)

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/DailyAccountSyncer.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/DailyAccountSyncerTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

`DailyAccountSyncerTest.kt`:

```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountStatus
import com.allfolio.unifiedasset.domain.account.AccountType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class DailyAccountSyncerTest {

    private fun acct(provider: AccountProvider) = Account.create(
        userId = UUID.randomUUID(), provider = provider, accountType = AccountType.STOCK,
        accountName = provider.name, currency = "KRW",
    )

    /** findByProviders에 넘어온 필터를 캡처하고, 미리 지정한 계좌 목록을 반환하는 fake. */
    private class FakeAccountRepository(private val accounts: List<Account>) : AccountRepository {
        var requestedProviders: Collection<AccountProvider>? = null
        override fun findByProviders(providers: Collection<AccountProvider>): List<Account> {
            requestedProviders = providers
            return accounts
        }
        override fun save(account: Account): Account = account
        override fun findById(id: UUID): Account? = null
        override fun findByUserId(userId: UUID): List<Account> = emptyList()
        override fun delete(id: UUID) {}
        override fun updateStatus(id: UUID, status: AccountStatus) {}
    }

    /** 호출된 accountId를 기록하고, 지정된 id에서는 예외를 던지는 fake runner. */
    private class RecordingSyncRunner(private val throwOn: UUID? = null) : AccountSyncRunner {
        val calledIds = mutableListOf<UUID>()
        override fun execute(accountId: UUID): SyncResult {
            calledIds += accountId
            if (accountId == throwOn) throw RuntimeException("boom")
            return SyncResult(accountId, 1, AccountStatus.ACTIVE)
        }
    }

    @Test
    fun `자동조회 대상 provider 집합으로 계좌를 조회한다`() {
        val repo = FakeAccountRepository(emptyList())
        DailyAccountSyncer(repo, RecordingSyncRunner()).syncAll()
        assertEquals(DailyAccountSyncer.SYNC_ELIGIBLE_PROVIDERS, repo.requestedProviders?.toSet())
        // MANUAL·CSV·KIWOOM은 대상에서 제외
        assertTrue(AccountProvider.MANUAL !in DailyAccountSyncer.SYNC_ELIGIBLE_PROVIDERS)
        assertTrue(AccountProvider.CSV !in DailyAccountSyncer.SYNC_ELIGIBLE_PROVIDERS)
        assertTrue(AccountProvider.KIWOOM !in DailyAccountSyncer.SYNC_ELIGIBLE_PROVIDERS)
    }

    @Test
    fun `조회된 모든 계좌를 sync하고 한 계좌 실패가 나머지를 막지 않는다`() {
        val a1 = acct(AccountProvider.KIS)
        val a2 = acct(AccountProvider.BINANCE)
        val a3 = acct(AccountProvider.STOCK)
        val repo = FakeAccountRepository(listOf(a1, a2, a3))
        val runner = RecordingSyncRunner(throwOn = a2.id)   // 가운데 계좌 실패

        val result = DailyAccountSyncer(repo, runner).syncAll()

        // 3개 모두 시도됨 (a2 예외에도 a3 계속)
        assertEquals(listOf(a1.id, a2.id, a3.id), runner.calledIds)
        assertEquals(3, result.total)
        assertEquals(2, result.synced)
        assertEquals(1, result.failed)
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests "com.allfolio.unifiedasset.application.usecase.DailyAccountSyncerTest"`
Expected: FAIL (DailyAccountSyncer / SyncBatchResult 미존재 → 컴파일 에러)

- [ ] **Step 3: DailyAccountSyncer 구현**

`DailyAccountSyncer.kt`:

```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.domain.account.AccountProvider
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** syncAll 결과 요약. */
data class SyncBatchResult(val synced: Int, val failed: Int, val total: Int)

/**
 * 자동조회 대상 계좌(외부 API/지갑 기반)를 전부 재동기화한다.
 * 계좌별 오류 격리 — 한 계좌 실패가 다른 계좌·배치 전체에 영향 없음.
 * 실패 계좌는 ua_assets 기존 값 유지(현행 유지).
 *
 * MANUAL·CSV·KIWOOM은 라이브 시세가 없어 제외(사용자 입력값 보호).
 */
@Component
class DailyAccountSyncer(
    private val accountRepository: AccountRepository,
    private val syncRunner: AccountSyncRunner,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun syncAll(): SyncBatchResult {
        val accounts = accountRepository.findByProviders(SYNC_ELIGIBLE_PROVIDERS)
        var synced = 0
        var failed = 0
        accounts.forEach { account ->
            runCatching { syncRunner.execute(account.id) }
                .onSuccess { synced++ }
                .onFailure { e ->
                    failed++
                    log.error("[DailyAccountSyncer] sync failed accountId={} provider={}",
                        account.id, account.provider, e)
                }
        }
        log.info("[DailyAccountSyncer] synced={} failed={} total={}", synced, failed, accounts.size)
        return SyncBatchResult(synced, failed, accounts.size)
    }

    companion object {
        /** 외부 API/지갑으로 자동 시세 갱신이 가능한 provider(프론트 sync 노출 대상과 동일). */
        val SYNC_ELIGIBLE_PROVIDERS: Set<AccountProvider> = setOf(
            AccountProvider.KIS,
            AccountProvider.BINANCE,
            AccountProvider.UPBIT,
            AccountProvider.BITHUMB,
            AccountProvider.COINONE,
            AccountProvider.BYBIT,
            AccountProvider.OKX,
            AccountProvider.WALLET,
            AccountProvider.STOCK,
        )
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests "com.allfolio.unifiedasset.application.usecase.DailyAccountSyncerTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/DailyAccountSyncer.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/DailyAccountSyncerTest.kt
git commit -m "feat(unified-asset): DailyAccountSyncer — 대상 계좌 전체 재동기화(오류 격리, TDD)"
```

---

### Task 4: DailyNavScheduler 오케스트레이션 (sync → snapshot)

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/DailyNavScheduler.kt`

- [ ] **Step 1: 생성자에 DailyAccountSyncer 주입 + 스냅샷 전에 syncAll 호출**

`DailyNavScheduler.kt`를 아래로 교체(기존 per-currency 스냅샷 로직은 유지, 앞에 sync 단계 추가):

```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.FxConverter
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.UUID

/**
 * 매일 자정 KST 배치:
 *  1) 자동조회 대상 계좌를 전부 재동기화(DailyAccountSyncer) → ua_assets 최신 시세 반영
 *  2) 모든 사용자의 NAV를 performance_daily에 스냅샷(통화 혼재 → KRW 환산)
 *
 * 스냅샷 파트는 sync 결과와 무관하게 항상 실행한다. SyncAccountUseCase가 sync 성공 시
 * 이미 스냅샷을 UPSERT하지만, 여기 명시적 패스는 syncable 계좌가 없는 사용자·전부 실패한
 * 사용자까지 마지막 값으로라도 스냅샷을 보장하는 안전망이다(UPSERT라 멱등).
 */
@Component
class DailyNavScheduler(
    private val jdbc: JdbcTemplate,
    private val snapshotService: PerformanceSnapshotService,
    private val fx: FxConverter,
    private val dailyAccountSyncer: DailyAccountSyncer,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    fun recordDailySnapshots() {
        // 1) 최신 시세로 계좌 재동기화 (배치 실패해도 스냅샷은 진행)
        runCatching { dailyAccountSyncer.syncAll() }
            .onFailure { e -> log.error("[DailyNavScheduler] account sync batch failed", e) }

        // 2) 통화별로 합산한 뒤 KRW로 환산해야 통화가 섞인 사용자의 NAV가 올바르다.
        val perCurrency = jdbc.query(
            "SELECT user_id, currency, SUM(current_value) AS v FROM ua_assets GROUP BY user_id, currency"
        ) { rs, _ ->
            Triple(
                UUID.fromString(rs.getString("user_id")),
                rs.getString("currency") ?: "KRW",
                rs.getBigDecimal("v") ?: BigDecimal.ZERO,
            )
        }

        val navByUser = perCurrency
            .groupBy { it.first }
            .mapValues { (_, rows) ->
                rows.fold(BigDecimal.ZERO) { acc, (_, currency, value) -> acc + fx.toKrw(value, currency) }
            }

        if (navByUser.isEmpty()) {
            log.debug("[DailyNavScheduler] no users with assets, skipping")
            return
        }

        log.info("[DailyNavScheduler] recording snapshots for {} users", navByUser.size)
        navByUser.forEach { (userId, nav) ->
            runCatching { snapshotService.record(userId, nav) }
                .onFailure { e -> log.error("[DailyNavScheduler] failed userId={}", userId, e) }
        }
        log.info("[DailyNavScheduler] done")
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/DailyNavScheduler.kt
git commit -m "feat(unified-asset): 자정 배치를 [계좌 재동기화 → NAV 스냅샷]으로 확장"
```

---

### Task 5: 전체 빌드 + 빈 배선 검증

**Files:** 없음 (검증 태스크)

- [ ] **Step 1: unified-asset 전체 테스트**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test`
Expected: BUILD SUCCESSFUL (DailyAccountSyncerTest 2개 + 기존 테스트 전부 통과)

- [ ] **Step 2: backend-app 빌드(빈 배선 확인)**

Run: `cd allfolio-backend && ./gradlew :backend-app:test`
Expected: BUILD SUCCESSFUL — Spring 컨텍스트가 `DailyAccountSyncer`(AccountRepository + AccountSyncRunner=SyncAccountUseCase)와 확장된 `DailyNavScheduler`를 충돌 없이 주입.

> 검증 포인트: `AccountSyncRunner` 구현체가 `SyncAccountUseCase` 하나뿐이라 주입 모호성 없음. `DailyAccountSyncer`는 새 `@Component`.

- [ ] **Step 3: PR 생성 및 CI 확인**

`feat/daily-account-sync-batch` push → PR → `Build backend JAR` 체크 pass 확인.

- [ ] **Step 4: 실측 (배포 후, 선택)**

배포 후 다음 자정(KST) 이후: `rkdghd123@naver.com`의 KIS/Binance 계좌를 수동 Sync 없이 두고, 다음 날 성과/네트워스 리포트에 **당일 시세 기준** 새 스냅샷이 자동으로 찍혔는지 확인. (로그: `[DailyAccountSyncer] synced=... ` + `[DailyNavScheduler] recording snapshots for N users`)

---

## Self-Review

**1. Spec coverage:**
- 통합 단일 잡(sync→snapshot) → Task 4 ✅
- DailyAccountSyncer(대상 계좌 재동기화, 오류 격리, eligible 집합) → Task 3 ✅
- AccountRepository.findByProviders → Task 1 ✅
- MANUAL·CSV·KIWOOM 제외 → Task 3 `SYNC_ELIGIBLE_PROVIDERS` + 테스트 ✅
- 명시적 스냅샷 패스 안전망 유지(멱등) → Task 4 (기존 로직 보존 + docstring) ✅
- 플래그 없음/cron 00:00 유지 → Task 4 ✅
- 테스트(대상 필터·오류 격리) → Task 3 ✅
- 기존 fake 컴파일 깨짐 대응 → Task 1 Step 4 ✅

**2. Placeholder scan:** TODO/TBD/"적절히" 없음. 모든 코드 스텝에 완전한 코드 포함.

**3. Type consistency:**
- `AccountRepository.findByProviders(Collection<AccountProvider>): List<Account>` — Task 1 정의, Task 3 fake/구현 일치 ✅
- `AccountSyncRunner.execute(UUID): SyncResult` — Task 2 정의, Task 3 fake runner·DailyAccountSyncer 사용 일치, `SyncAccountUseCase.execute` 시그니처와 동일 ✅
- `SyncBatchResult(synced, failed, total)` — Task 3 정의, 테스트 사용 일치 ✅
- `DailyAccountSyncer.SYNC_ELIGIBLE_PROVIDERS` (companion) — Task 3 정의, 테스트 참조 일치 ✅
- `DailyAccountSyncer(accountRepository, syncRunner)` 생성자 — Task 3 정의, Task 4 주입(자동, @Component) ✅
- `SyncResult(accountId, synced, status, error=null)` — 기존 정의와 일치(테스트에서 3-arg 사용) ✅
