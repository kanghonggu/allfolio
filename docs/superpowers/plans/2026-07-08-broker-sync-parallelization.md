# 브로커 동기화 병렬화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `BrokerSyncScheduler`가 서로 다른 브로커를 병렬로, 같은 브로커의 계좌는 순차로 동기화하게 한다.

**Architecture:** 스케줄러가 콘크리트 `BrokerFacade` 대신 새 인터페이스 `BrokerAccountSyncer`에 의존하고, 브로커 타입별로 그룹핑한 태스크를 전용 고정 스레드풀(`brokerSyncExecutor`, 크기=브로커 수)에 제출한 뒤 `invokeAll`로 join한다. `fixedDelay` 무겹침·계좌 단위 실패 격리·rate limit skip 시맨틱은 그대로 보존한다.

**Tech Stack:** Kotlin, Spring Boot 3.2, `java.util.concurrent`(신규 의존성 없음), JUnit5 + Mockito(리포지토리 인터페이스 mock 전용) + 손으로 쓴 fake.

**Spec:** `docs/superpowers/specs/2026-07-08-broker-sync-parallelization-design.md`

**작업 브랜치:** `feat/broker-sync-parallelization` (origin/main 기준, 스펙 커밋 포함)

**빌드/테스트 실행 위치:** `/Users/hong9/IdeaProjects/allfolio/allfolio-backend` (Gradle 멀티모듈 루트)

---

### Task 1: `BrokerAccountSyncer` 인터페이스 절단면

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/broker/BrokerAccountSyncer.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/broker/BrokerFacade.kt` (클래스 선언 1줄 + `syncAccount`에 `override`)

- [ ] **Step 1: 인터페이스 생성**

```kotlin
package com.allfolio.broker

import java.util.UUID

/**
 * 브로커 계좌 1개의 증분 동기화 진입점.
 * BrokerSyncScheduler가 BrokerFacade에 직접 묶이지 않도록 하는 절단면 —
 * 테스트에서 fake로 대체해 병렬 실행 시맨틱을 검증한다.
 */
interface BrokerAccountSyncer {
    /** @return 저장 건수. -1 = rate limited */
    fun syncAccount(brokerType: BrokerType, portfolioId: UUID, accountId: String): Int
}
```

- [ ] **Step 2: BrokerFacade가 구현하도록 수정**

`BrokerFacade.kt`에서 클래스 선언을 바꾸고(마지막 생성자 파라미터 `objectMapper` 닫는 괄호 뒤):

```kotlin
// 변경 전
) {
// 변경 후
) : BrokerAccountSyncer {
```

`fun syncAccount(` 선언에 `override` 추가:

```kotlin
// 변경 전
    fun syncAccount(
// 변경 후
    override fun syncAccount(
```

본문은 변경하지 않는다.

- [ ] **Step 3: 컴파일 확인**

Run: `cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :backend-app:compileKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/broker/BrokerAccountSyncer.kt allfolio-backend/backend-app/src/main/kotlin/com/allfolio/broker/BrokerFacade.kt
git commit -m "refactor: introduce BrokerAccountSyncer seam over BrokerFacade"
```

---

### Task 2: 전용 executor 빈 + 스케줄러 DI 교체 (동작은 아직 순차)

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/broker/BrokerSyncExecutorConfig.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/broker/BrokerSyncScheduler.kt` (전체 교체)

- [ ] **Step 1: executor 설정 생성**

```kotlin
package com.allfolio.broker

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@Configuration
class BrokerSyncExecutorConfig {

    /**
     * 브로커 그룹 병렬 동기화 전용 풀.
     * 크기 = 브로커 타입 수 — 동시성 상한이 구조적으로 브로커 수를 넘지 않는다.
     */
    @Bean(destroyMethod = "shutdown")
    fun brokerSyncExecutor(): ExecutorService {
        val counter = AtomicInteger(1)
        return Executors.newFixedThreadPool(BrokerType.entries.size) { runnable ->
            Thread(runnable, "broker-sync-${counter.getAndIncrement()}").apply { isDaemon = true }
        }
    }
}
```

- [ ] **Step 2: 스케줄러 재작성 (필터 분리 + syncOne 추출, 실행은 아직 순차)**

`BrokerSyncScheduler.kt` 전체를 다음으로 교체:

```kotlin
package com.allfolio.broker

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.concurrent.ExecutorService

/**
 * 멀티 브로커 통합 Scheduler
 *
 * 보호 장치:
 * 1. BINANCE skip — BinanceSyncService(레거시)가 처리
 * 2. lastSyncedAt < 30s skip — 과도한 재실행 방지
 * 3. rate limited skip (-1) — BrokerFacade가 non-blocking 반환
 * 4. 각 계좌 오류 격리 — 한 계좌 실패가 다른 계좌 sync에 영향 없음
 *
 * broker_sync_state에 행이 있어야 동기화 실행.
 * 행 삽입은 OAuth2 callback / TossOAuthController가 처리.
 */
@Component
class BrokerSyncScheduler(
    private val accountSyncer: BrokerAccountSyncer,
    private val syncStateRepository: BrokerSyncStateRepository,
    private val brokerSyncExecutor: ExecutorService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 60_000)
    fun syncAll() {
        val states = syncStateRepository.findAll()
        if (states.isEmpty()) return

        val threshold = LocalDateTime.now().minusSeconds(SKIP_THRESHOLD_SECONDS)
        var skipped = 0

        val eligible: List<Pair<BrokerType, BrokerSyncStateEntity>> = states.mapNotNull { state ->
            val brokerType = runCatching {
                BrokerType.valueOf(state.id.brokerType)
            }.getOrElse {
                log.warn("[BrokerSyncScheduler] unknown brokerType={}", state.id.brokerType)
                return@mapNotNull null
            }

            // BINANCE는 BinanceSyncService(레거시 경로)가 처리
            if (brokerType == BrokerType.BINANCE) return@mapNotNull null

            // lastSyncedAt 30s 이내 skip — 과부하 방지
            val lastSynced = state.lastSyncedAt
            if (lastSynced != null && lastSynced.isAfter(threshold)) {
                skipped++
                return@mapNotNull null
            }

            brokerType to state
        }

        if (skipped > 0) log.debug("[BrokerSyncScheduler] skipped {} recently-synced accounts", skipped)
        if (eligible.isEmpty()) return

        eligible.forEach { (brokerType, state) -> syncOne(brokerType, state) }
    }

    private fun syncOne(brokerType: BrokerType, state: BrokerSyncStateEntity) {
        runCatching {
            val recorded = accountSyncer.syncAccount(
                brokerType  = brokerType,
                portfolioId = state.id.portfolioId,
                accountId   = state.id.accountId,
            )
            when {
                recorded > 0   -> log.info("[BrokerSyncScheduler] broker={} account={} recorded={}",
                    brokerType, state.id.accountId, recorded)
                recorded == -1 -> log.debug("[BrokerSyncScheduler] rate limited broker={} account={}",
                    brokerType, state.id.accountId)
            }
        }.onFailure { e ->
            log.error("[BrokerSyncScheduler] sync failed broker={} account={}",
                brokerType, state.id.accountId, e)
        }
    }

    companion object {
        private const val SKIP_THRESHOLD_SECONDS = 30L
    }
}
```

주: 기존의 "skip N초 전 동기화" 상세 debug 로그는 집계 로그로 대체된다(스펙의 필터 시맨틱 자체는 보존).

- [ ] **Step 3: 컴파일 + 기존 테스트 확인**

Run: `./gradlew :backend-app:test`
Expected: `BUILD SUCCESSFUL` (기존 테스트 전부 통과 — 스케줄러 동작은 아직 순차로 동일)

- [ ] **Step 4: Commit**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/broker/BrokerSyncExecutorConfig.kt allfolio-backend/backend-app/src/main/kotlin/com/allfolio/broker/BrokerSyncScheduler.kt
git commit -m "refactor: inject BrokerAccountSyncer and dedicated executor into sync scheduler"
```

---

### Task 3: characterization 테스트 3종 (보존 시맨틱 가드)

행동이 바뀌면 안 되는 것들(그룹 내 순차, 실패 격리, 필터)을 병렬화 **전에** 고정한다. 셋 다 현재(순차) 구현에서 통과해야 하며, Task 4 이후에도 계속 통과해야 한다.

**Files:**
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/broker/BrokerSyncSchedulerTest.kt`

- [ ] **Step 1: 테스트 파일 생성 (T2·T3·T4)**

```kotlin
package com.allfolio.broker

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class BrokerSyncSchedulerTest {

    private val executor: ExecutorService = Executors.newFixedThreadPool(BrokerType.entries.size)

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
    }

    private fun state(brokerType: String, accountId: String, lastSyncedAt: LocalDateTime? = null) =
        BrokerSyncStateEntity(
            id = BrokerSyncStateId(UUID.randomUUID(), brokerType, accountId),
            lastSyncedAt = lastSyncedAt,
        )

    private fun scheduler(syncer: BrokerAccountSyncer, states: List<BrokerSyncStateEntity>): BrokerSyncScheduler {
        val repo = Mockito.mock(BrokerSyncStateRepository::class.java)
        Mockito.`when`(repo.findAll()).thenReturn(states)
        return BrokerSyncScheduler(syncer, repo, executor)
    }

    @Test
    fun `같은 브로커의 계좌들은 순차 실행된다`() {
        val concurrent    = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val syncer = object : BrokerAccountSyncer {
            override fun syncAccount(brokerType: BrokerType, portfolioId: UUID, accountId: String): Int {
                val now = concurrent.incrementAndGet()
                maxConcurrent.updateAndGet { maxOf(it, now) }
                Thread.sleep(50)
                concurrent.decrementAndGet()
                return 0
            }
        }

        scheduler(syncer, listOf(state("KIS", "a1"), state("KIS", "a2"), state("KIS", "a3"))).syncAll()

        assertEquals(1, maxConcurrent.get(), "같은 브로커 계좌가 동시에 실행되면 안 된다")
    }

    @Test
    fun `한 계좌의 실패가 같은 그룹의 다음 계좌와 다른 브로커를 막지 않는다`() {
        val calls = ConcurrentHashMap.newKeySet<String>()
        val syncer = object : BrokerAccountSyncer {
            override fun syncAccount(brokerType: BrokerType, portfolioId: UUID, accountId: String): Int {
                calls.add("${brokerType.name}:$accountId")
                if (brokerType == BrokerType.KIS && accountId == "a1") throw IllegalStateException("boom")
                return 0
            }
        }

        scheduler(syncer, listOf(state("KIS", "a1"), state("KIS", "a2"), state("TOSS", "b1"))).syncAll()

        assertTrue(
            calls.containsAll(setOf("KIS:a1", "KIS:a2", "TOSS:b1")),
            "실패 이후 계좌들도 전부 호출되어야 한다. 실제 호출: $calls",
        )
    }

    @Test
    fun `BINANCE와 unknown type과 최근 동기화 계좌는 제외된다`() {
        val calls = ConcurrentHashMap.newKeySet<String>()
        val syncer = object : BrokerAccountSyncer {
            override fun syncAccount(brokerType: BrokerType, portfolioId: UUID, accountId: String): Int {
                calls.add("${brokerType.name}:$accountId")
                return 0
            }
        }

        scheduler(
            syncer,
            listOf(
                state("BINANCE", "legacy"),
                state("NOT_A_BROKER", "x1"),
                state("KIS", "recent", lastSyncedAt = LocalDateTime.now()),
                state("KIS", "due", lastSyncedAt = LocalDateTime.now().minusMinutes(5)),
                state("TOSS", "never"),
            ),
        ).syncAll()

        assertEquals(setOf("KIS:due", "TOSS:never"), calls)
    }
}
```

- [ ] **Step 2: 테스트 실행 — 전부 통과 확인 (characterization이므로 즉시 통과가 정상)**

Run: `./gradlew :backend-app:test --tests "com.allfolio.broker.BrokerSyncSchedulerTest"`
Expected: `BUILD SUCCESSFUL`, 3 tests passed

- [ ] **Step 3: Commit**

```bash
git add allfolio-backend/backend-app/src/test/kotlin/com/allfolio/broker/BrokerSyncSchedulerTest.kt
git commit -m "test: characterize broker sync scheduler invariants before parallelization"
```

---

### Task 4: 병렬 실행 (RED → GREEN)

**Files:**
- Modify: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/broker/BrokerSyncSchedulerTest.kt` (테스트 1개 추가)
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/broker/BrokerSyncScheduler.kt` (`syncAll` 마지막 부분)

- [ ] **Step 1: 실패하는 병렬성 테스트 추가**

`BrokerSyncSchedulerTest.kt`에 import 2개 추가:

```kotlin
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
```

클래스 안에 테스트 추가:

```kotlin
    @Test
    fun `서로 다른 브로커는 병렬로 실행된다`() {
        val kisStarted  = CountDownLatch(1)
        val tossStarted = CountDownLatch(1)
        val overlapped  = ConcurrentHashMap<BrokerType, Boolean>()
        val syncer = object : BrokerAccountSyncer {
            override fun syncAccount(brokerType: BrokerType, portfolioId: UUID, accountId: String): Int {
                when (brokerType) {
                    BrokerType.KIS -> {
                        kisStarted.countDown()
                        overlapped[brokerType] = tossStarted.await(3, TimeUnit.SECONDS)
                    }
                    BrokerType.TOSS -> {
                        tossStarted.countDown()
                        overlapped[brokerType] = kisStarted.await(3, TimeUnit.SECONDS)
                    }
                    else -> {}
                }
                return 0
            }
        }

        scheduler(syncer, listOf(state("KIS", "a1"), state("TOSS", "b1"))).syncAll()

        assertEquals(
            mapOf(BrokerType.KIS to true, BrokerType.TOSS to true),
            overlapped.toMap(),
            "두 브로커가 서로의 시작을 관찰해야 병렬이다",
        )
    }
```

- [ ] **Step 2: RED 확인 — 순차 구현에서는 첫 브로커가 3초 대기 후 false**

Run: `./gradlew :backend-app:test --tests "com.allfolio.broker.BrokerSyncSchedulerTest"`
Expected: FAIL — `서로 다른 브로커는 병렬로 실행된다`에서 `expected: <{KIS=true, TOSS=true}> but was: <{KIS=false, TOSS=true}>` (약 3초 소요). 나머지 3개는 통과.

- [ ] **Step 3: syncAll을 그룹 병렬로 전환**

`BrokerSyncScheduler.kt`의 import에 추가:

```kotlin
import java.util.concurrent.Callable
```

`syncAll()` 마지막 줄을 교체:

```kotlin
// 변경 전
        eligible.forEach { (brokerType, state) -> syncOne(brokerType, state) }

// 변경 후
        // 브로커 간 병렬, 브로커 내 순차 — invokeAll join으로 fixedDelay 무겹침 보장
        val groups = eligible.groupBy({ it.first }, { it.second }).toList()
        val tasks = groups.map { (brokerType, group) ->
            Callable { group.forEach { state -> syncOne(brokerType, state) } }
        }
        brokerSyncExecutor.invokeAll(tasks).forEachIndexed { index, future ->
            runCatching { future.get() }.onFailure { e ->
                log.error("[BrokerSyncScheduler] group task failed broker={}", groups[index].first, e)
            }
        }
```

- [ ] **Step 4: GREEN 확인 — 4개 전부 통과**

Run: `./gradlew :backend-app:test --tests "com.allfolio.broker.BrokerSyncSchedulerTest"`
Expected: `BUILD SUCCESSFUL`, 4 tests passed (병렬성 테스트가 3초 대기 없이 즉시 통과)

- [ ] **Step 5: Commit**

```bash
git add allfolio-backend/backend-app/src/test/kotlin/com/allfolio/broker/BrokerSyncSchedulerTest.kt allfolio-backend/backend-app/src/main/kotlin/com/allfolio/broker/BrokerSyncScheduler.kt
git commit -m "feat: sync brokers in parallel per broker-type group"
```

---

### Task 5: 전체 검증 + PR

- [ ] **Step 1: 전체 테스트**

Run: `cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew test`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Push + PR 생성**

```bash
git push -u origin feat/broker-sync-parallelization
gh pr create --title "feat: parallelize broker sync per broker-type group" --body "## Summary
- BrokerSyncScheduler가 브로커 타입별 그룹으로 나눠 병렬 동기화한다 (브로커 간 병렬, 같은 브로커 계좌는 순차 — 브로커별 rate limit과 충돌하지 않는 최소 병렬화 축).
- 새 절단면 \`BrokerAccountSyncer\`(BrokerFacade가 구현) + 전용 고정 풀 \`brokerSyncExecutor\`(크기=브로커 수, invokeAll join).
- 보존: fixedDelay 무겹침(join), 계좌 단위 실패 격리, rate limit skip(-1), BINANCE/unknown/최근동기화 필터.
- 설계 문서: docs/superpowers/specs/2026-07-08-broker-sync-parallelization-design.md

## Tests
- 브로커 간 병렬 실행 (순차 구현에서 RED 확인 후 구현)
- 같은 브로커 내 순차 실행 / 실패 격리 / 필터 보존 (characterization, 병렬화 전후 모두 통과)

🤖 Generated with [Claude Code](https://claude.com/claude-code)"
```
