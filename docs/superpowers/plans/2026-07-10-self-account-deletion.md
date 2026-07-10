# 자기 계정 삭제 API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 인증된 사용자가 `DELETE /api/auth/me`(현재 비밀번호 재확인)로 자기 계정과 소유한 모든 데이터를 완전 삭제한다.

**Architecture:** backend-app(모든 모듈에 의존하는 조립 루트)에 격리된 네이티브 삭제 리포지토리(`AccountPurgeRepository`)와 오케스트레이터(`AccountDeletionService`)를 둔다. `trade_raw` 삭제는 이 리포지토리에만 존재해 "원장 삭제 금지" 불변식을 인터페이스 수준에서 보존한다. `AuthService.deleteAccount`가 비밀번호를 검증한 뒤 오케스트레이터에 위임한다.

**Tech Stack:** Kotlin, Spring Boot 3.2, Spring Data JPA(`@Modifying @Query nativeQuery`), JUnit5 + Mockito. 신규 라이브러리 없음.

**Spec:** `docs/superpowers/specs/2026-07-10-self-account-deletion-design.md`

**작업 브랜치:** `feat/self-account-deletion` (origin/main 기준, 스펙 커밋 포함)

**빌드/테스트 루트:** `/Users/hong9/IdeaProjects/allfolio/allfolio-backend`

**참고 — 기존 사실:**
- `AuthService`(`com.allfolio.auth`) 생성자: `(userRepository, refreshTokenRepository, passwordEncoder: PasswordEncoder, jwtTokenService, refreshTokenDays: Long)`. `me(userId)`는 없는 유저면 `ResponseStatusException(NOT_FOUND, "사용자를 찾을 수 없습니다.")`.
- `UserRepository : JpaRepository<UserEntity, UUID>`; `UserEntity.passwordHash`.
- `PositionCacheService`(`com.allfolio.pnl`) 생성자 `(redisTemplate: StringRedisTemplate, objectMapper: ObjectMapper)`, `private val log`, `private fun positionKey(portfolioId) = "pnl:positions:$portfolioId"`.
- `@Modifying @Query` 스타일은 `BrokerAuthRepository` 참고(명시적 `@Param` 없이 `:userId` 이름 매칭).
- 테이블: `broker_auth`(user_id), `ua_ai_configs`(user_id PK), `ua_goals`(user_id), `ua_accounts`(user_id; FK cascade→ua_assets/ua_stock_trades), `risk_daily`/`performance_daily`/`position_daily`(portfolio_id), `broker_sync_state`(portfolio_id), `trade_raw`(portfolio_id), `portfolios`(user_id), `app_users`(id; FK cascade→app_refresh_tokens).
- 웹 테스트 하네스는 `SecurityConfigAdminTest` 스타일(@SpringBootTest 최소 클래스 + MockMvc + DataSource/JPA autoconfig 제외).
- `.gradle/*`는 절대 stage 금지.

---

### Task 1: PositionCacheService.evictPortfolio + 테스트

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/pnl/PositionCacheService.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/pnl/PositionCacheServiceEvictTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
package com.allfolio.pnl

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID

class PositionCacheServiceEvictTest {

    @Test
    fun `evictPortfolio deletes the whole position hash key`() {
        val redis = mock(StringRedisTemplate::class.java)
        val service = PositionCacheService(redis, mock(ObjectMapper::class.java))
        val portfolioId = UUID.randomUUID()

        service.evictPortfolio(portfolioId)

        verify(redis).delete("pnl:positions:$portfolioId")
    }
}
```

- [ ] **Step 2: RED 확인**

Run: `cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.pnl.PositionCacheServiceEvictTest"`
Expected: FAIL — `evictPortfolio` unresolved reference (compile failure).

- [ ] **Step 3: evictPortfolio 추가**

`PositionCacheService.kt`에서 `costBasis(...)` 함수 아래, `private fun positionKey(...)` 위에 추가:

```kotlin
    /** 포트폴리오 포지션 캐시 해시 전체 삭제 (계정 파기 등). */
    fun evictPortfolio(portfolioId: UUID) {
        runCatching { redisTemplate.delete(positionKey(portfolioId)) }
            .onFailure { e -> log.warn("[PositionCache] evict failed portfolioId={}: {}", portfolioId, e.message) }
    }
```

- [ ] **Step 4: GREEN 확인**

Run: `./gradlew :backend-app:test --tests "com.allfolio.pnl.PositionCacheServiceEvictTest"`
Expected: BUILD SUCCESSFUL, 1 test passed.

- [ ] **Step 5: Commit**

```bash
cd /Users/hong9/IdeaProjects/allfolio
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/pnl/PositionCacheService.kt allfolio-backend/backend-app/src/test/kotlin/com/allfolio/pnl/PositionCacheServiceEvictTest.kt
git commit -m "feat: add PositionCacheService.evictPortfolio to drop a portfolio's cache"
```

---

### Task 2: AccountPurgeRepository + AccountDeletionService + 테스트

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/account/AccountPurgeRepository.kt`
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/account/AccountDeletionService.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/account/AccountDeletionServiceTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성 (오케스트레이션 순서 + evict)**

```kotlin
package com.allfolio.account

import com.allfolio.pnl.PositionCacheService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.util.UUID

class AccountDeletionServiceTest {

    private val repo = mock(AccountPurgeRepository::class.java)
    private val cache = mock(PositionCacheService::class.java)
    private val service = AccountDeletionService(repo, cache)

    private val userId = UUID.randomUUID()

    @Test
    fun `purge deletes all owned data in FK-safe order and evicts each portfolio cache`() {
        val pf1 = UUID.randomUUID()
        val pf2 = UUID.randomUUID()
        `when`(repo.findPortfolioIds(userId)).thenReturn(listOf(pf1, pf2))

        service.purge(userId)

        val ordered = inOrder(repo)
        ordered.verify(repo).findPortfolioIds(userId)
        ordered.verify(repo).deleteBrokerAuth(userId)
        ordered.verify(repo).deleteAiConfigs(userId)
        ordered.verify(repo).deleteGoals(userId)
        ordered.verify(repo).deleteUaAccounts(userId)
        ordered.verify(repo).deleteRiskDaily(userId)
        ordered.verify(repo).deletePerformanceDaily(userId)
        ordered.verify(repo).deletePositionDaily(userId)
        ordered.verify(repo).deleteBrokerSyncState(userId)
        ordered.verify(repo).deleteTradeRaw(userId)
        ordered.verify(repo).deletePortfolios(userId)
        ordered.verify(repo).deleteUser(userId)

        verify(cache).evictPortfolio(pf1)
        verify(cache).evictPortfolio(pf2)
    }

    @Test
    fun `purge with no portfolios still deletes user-scoped data and touches no cache`() {
        `when`(repo.findPortfolioIds(userId)).thenReturn(emptyList())

        service.purge(userId)

        verify(repo).deleteBrokerAuth(userId)
        verify(repo).deleteUaAccounts(userId)
        verify(repo).deleteTradeRaw(userId)
        verify(repo).deletePortfolios(userId)
        verify(repo).deleteUser(userId)
        verifyNoInteractions(cache)
    }
}
```

- [ ] **Step 2: RED 확인**

Run: `cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.account.AccountDeletionServiceTest"`
Expected: FAIL — `AccountPurgeRepository`/`AccountDeletionService` unresolved reference.

- [ ] **Step 3: AccountPurgeRepository 생성**

```kotlin
package com.allfolio.account

import com.allfolio.auth.UserEntity
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import java.util.UUID

/**
 * 계정 완전 삭제(파기) 전용 네이티브 삭제 모음.
 *
 * trade_raw 삭제 메서드가 이 인터페이스에만 존재한다 — TradeRawJpaRepository는
 * 삭제 메서드 없는 채로 유지되어 "원장 삭제 금지" 불변식을 보존한다.
 * 호출 순서는 AccountDeletionService가 FK 안전하게 관리한다.
 */
interface AccountPurgeRepository : Repository<UserEntity, UUID> {

    @Query("SELECT id FROM portfolios WHERE user_id = :userId", nativeQuery = true)
    fun findPortfolioIds(userId: UUID): List<UUID>

    @Modifying
    @Query("DELETE FROM broker_auth WHERE user_id = :userId", nativeQuery = true)
    fun deleteBrokerAuth(userId: UUID): Int

    @Modifying
    @Query("DELETE FROM ua_ai_configs WHERE user_id = :userId", nativeQuery = true)
    fun deleteAiConfigs(userId: UUID): Int

    @Modifying
    @Query("DELETE FROM ua_goals WHERE user_id = :userId", nativeQuery = true)
    fun deleteGoals(userId: UUID): Int

    /** ua_assets / ua_stock_trades 는 ua_accounts FK cascade 로 함께 삭제된다. */
    @Modifying
    @Query("DELETE FROM ua_accounts WHERE user_id = :userId", nativeQuery = true)
    fun deleteUaAccounts(userId: UUID): Int

    @Modifying
    @Query("DELETE FROM risk_daily WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = :userId)", nativeQuery = true)
    fun deleteRiskDaily(userId: UUID): Int

    @Modifying
    @Query("DELETE FROM performance_daily WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = :userId)", nativeQuery = true)
    fun deletePerformanceDaily(userId: UUID): Int

    @Modifying
    @Query("DELETE FROM position_daily WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = :userId)", nativeQuery = true)
    fun deletePositionDaily(userId: UUID): Int

    @Modifying
    @Query("DELETE FROM broker_sync_state WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = :userId)", nativeQuery = true)
    fun deleteBrokerSyncState(userId: UUID): Int

    /** 계정 파기 전용 예외 — trade_raw 는 평소 삭제 금지(@Immutable, INSERT ONLY). */
    @Modifying
    @Query("DELETE FROM trade_raw WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = :userId)", nativeQuery = true)
    fun deleteTradeRaw(userId: UUID): Int

    @Modifying
    @Query("DELETE FROM portfolios WHERE user_id = :userId", nativeQuery = true)
    fun deletePortfolios(userId: UUID): Int

    /** app_refresh_tokens 는 FK cascade 로 함께 삭제된다. */
    @Modifying
    @Query("DELETE FROM app_users WHERE id = :userId", nativeQuery = true)
    fun deleteUser(userId: UUID): Int
}
```

- [ ] **Step 4: AccountDeletionService 생성**

```kotlin
package com.allfolio.account

import com.allfolio.pnl.PositionCacheService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 계정 완전 삭제 오케스트레이터.
 * FK 안전 순서(자식→부모)로 사용자 소유 데이터를 모두 삭제한 뒤,
 * 각 포트폴리오의 Redis 포지션 캐시를 evict 한다.
 */
@Service
class AccountDeletionService(
    private val purgeRepository: AccountPurgeRepository,
    private val positionCacheService: PositionCacheService,
) {
    @Transactional
    fun purge(userId: UUID) {
        val portfolioIds = purgeRepository.findPortfolioIds(userId)

        purgeRepository.deleteBrokerAuth(userId)
        purgeRepository.deleteAiConfigs(userId)
        purgeRepository.deleteGoals(userId)
        purgeRepository.deleteUaAccounts(userId)
        purgeRepository.deleteRiskDaily(userId)
        purgeRepository.deletePerformanceDaily(userId)
        purgeRepository.deletePositionDaily(userId)
        purgeRepository.deleteBrokerSyncState(userId)
        purgeRepository.deleteTradeRaw(userId)
        purgeRepository.deletePortfolios(userId)
        purgeRepository.deleteUser(userId)

        portfolioIds.forEach { positionCacheService.evictPortfolio(it) }
    }
}
```

- [ ] **Step 5: GREEN 확인**

Run: `./gradlew :backend-app:test --tests "com.allfolio.account.AccountDeletionServiceTest"`
Expected: BUILD SUCCESSFUL, 2 tests passed.

- [ ] **Step 6: Commit**

```bash
cd /Users/hong9/IdeaProjects/allfolio
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/account/AccountPurgeRepository.kt allfolio-backend/backend-app/src/main/kotlin/com/allfolio/account/AccountDeletionService.kt allfolio-backend/backend-app/src/test/kotlin/com/allfolio/account/AccountDeletionServiceTest.kt
git commit -m "feat: add account purge repository and deletion orchestrator"
```

---

### Task 3: AuthService.deleteAccount + DTO + 컨트롤러 엔드포인트 + 테스트

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/AuthDtos.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/AuthService.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/AuthController.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/auth/AuthServiceDeleteAccountTest.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/auth/DeleteAccountEndpointTest.kt`

- [ ] **Step 1: 실패하는 서비스 테스트 작성**

`AuthServiceDeleteAccountTest.kt`:

```kotlin
package com.allfolio.auth

import com.allfolio.account.AccountDeletionService
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.server.ResponseStatusException
import java.util.Optional
import java.util.UUID

class AuthServiceDeleteAccountTest {

    private val userRepository = mock(UserRepository::class.java)
    private val refreshTokenRepository = mock(RefreshTokenRepository::class.java)
    private val passwordEncoder = mock(PasswordEncoder::class.java)
    private val jwtTokenService = mock(JwtTokenService::class.java)
    private val accountDeletionService = mock(AccountDeletionService::class.java)

    private val service = AuthService(
        userRepository, refreshTokenRepository, passwordEncoder, jwtTokenService,
        30L, accountDeletionService,
    )

    private val userId = UUID.randomUUID()
    private val user = UserEntity(id = userId, email = "u@example.com", passwordHash = "hash", displayName = null)

    @Test
    fun `deleteAccount purges when password matches`() {
        `when`(userRepository.findById(userId)).thenReturn(Optional.of(user))
        `when`(passwordEncoder.matches("pw", "hash")).thenReturn(true)

        service.deleteAccount(userId, "pw")

        verify(accountDeletionService).purge(userId)
    }

    @Test
    fun `deleteAccount rejects wrong password and purges nothing`() {
        `when`(userRepository.findById(userId)).thenReturn(Optional.of(user))
        `when`(passwordEncoder.matches("wrong", "hash")).thenReturn(false)

        assertThrows(ResponseStatusException::class.java) {
            service.deleteAccount(userId, "wrong")
        }
        verify(accountDeletionService, never()).purge(userId)
    }

    @Test
    fun `deleteAccount throws when user missing`() {
        `when`(userRepository.findById(userId)).thenReturn(Optional.empty())

        assertThrows(ResponseStatusException::class.java) {
            service.deleteAccount(userId, "pw")
        }
        verify(accountDeletionService, never()).purge(userId)
    }
}
```

- [ ] **Step 2: RED 확인**

Run: `cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.auth.AuthServiceDeleteAccountTest"`
Expected: FAIL — `AuthService` 생성자 인자 개수 불일치 / `deleteAccount` 미존재 (compile failure).

- [ ] **Step 3: DTO 추가**

`AuthDtos.kt`의 `data class LogoutRequest(...)` 아래에 추가:

```kotlin
data class DeleteAccountRequest(val password: String)
```

- [ ] **Step 4: AuthService에 의존성 + deleteAccount 추가**

`AuthService.kt` 상단 import에 추가:

```kotlin
import com.allfolio.account.AccountDeletionService
```

생성자에 파라미터 추가 (마지막 `refreshTokenDays` 뒤):

```kotlin
    @Value("\${allfolio.auth.refresh-token-days}") private val refreshTokenDays: Long,
    private val accountDeletionService: AccountDeletionService,
) {
```

`me(...)` 함수 아래에 추가:

```kotlin
    @Transactional
    fun deleteAccount(userId: UUID, password: String) {
        val user = userRepository.findById(userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다.") }
        if (!passwordEncoder.matches(password, user.passwordHash)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "비밀번호가 올바르지 않습니다.")
        }
        accountDeletionService.purge(userId)
    }
```

- [ ] **Step 5: 컨트롤러 엔드포인트 추가**

`AuthController.kt` import에 `DeleteMapping` 추가:

```kotlin
import org.springframework.web.bind.annotation.DeleteMapping
```

`me(...)` 함수 아래에 추가:

```kotlin
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteMe(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestBody request: DeleteAccountRequest,
    ) {
        authService.deleteAccount(userId, request.password)
    }
```

- [ ] **Step 6: 서비스 테스트 GREEN 확인**

Run: `./gradlew :backend-app:test --tests "com.allfolio.auth.AuthServiceDeleteAccountTest"`
Expected: BUILD SUCCESSFUL, 3 tests passed.

- [ ] **Step 7: 엔드포인트(웹 계층) 테스트 작성**

`DeleteAccountEndpointTest.kt` — `SecurityConfigAdminTest` 하네스 스타일:

```kotlin
package com.allfolio.auth

import com.allfolio.config.JwtUserIdFilter
import com.allfolio.config.SecurityConfig
import com.allfolio.config.SseTokenFilter
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import java.util.UUID

@SpringBootTest(
    classes = [
        DeleteAccountEndpointTest.TestApplication::class,
        DeleteAccountEndpointTest.TestBeans::class,
        SecurityConfig::class,
        JwtUserIdFilter::class,
        SseTokenFilter::class,
        JwtTokenService::class,
        AuthController::class,
    ],
    properties = [
        "allfolio.auth.jwt-secret=test-secret-test-secret-test-secret-1234",
        "allfolio.auth.access-token-minutes=15",
        "allfolio.cors.allowed-origins=http://localhost:3000",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
    ],
)
@AutoConfigureMockMvc
class DeleteAccountEndpointTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jwtTokenService: JwtTokenService

    @Test
    fun `delete me without token is rejected`() {
        mockMvc.delete("/api/auth/me") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"password":"pw"}"""
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `delete me with token returns 204`() {
        val user = UserEntity(id = UUID.randomUUID(), email = "u@example.com", passwordHash = "hash", displayName = null)
        val (token, _) = jwtTokenService.issue(user)

        mockMvc.delete("/api/auth/me") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"password":"pw"}"""
        }.andExpect { status { isNoContent() } }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    class TestApplication

    @TestConfiguration
    class TestBeans {
        @Bean fun authService(): AuthService = mock(AuthService::class.java)
    }
}
```

- [ ] **Step 8: 엔드포인트 테스트 GREEN + 전체 backend-app 테스트**

Run: `./gradlew :backend-app:test`
Expected: BUILD SUCCESSFUL — 새 테스트(서비스 3 + 엔드포인트 2) + 기존 전부 통과.

주: `delete me with token returns 204`는 mock된 `AuthService.deleteAccount`가 아무것도 안 하므로 204를 반환한다. 인증 필터가 토큰에서 X-User-Id를 주입하는 경로를 함께 검증한다.

- [ ] **Step 9: Commit**

```bash
cd /Users/hong9/IdeaProjects/allfolio
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/AuthDtos.kt \
  allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/AuthService.kt \
  allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/AuthController.kt \
  allfolio-backend/backend-app/src/test/kotlin/com/allfolio/auth/AuthServiceDeleteAccountTest.kt \
  allfolio-backend/backend-app/src/test/kotlin/com/allfolio/auth/DeleteAccountEndpointTest.kt
git commit -m "feat: add DELETE /api/auth/me self-account-deletion endpoint"
```

---

### Task 4: 전체 검증 + PR

- [ ] **Step 1: 전체 테스트**

Run: `cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Push + PR**

```bash
cd /Users/hong9/IdeaProjects/allfolio
git push -u origin feat/self-account-deletion
gh pr create --title "feat: self-account-deletion endpoint (DELETE /api/auth/me)" --body "## Summary
- 인증된 사용자가 현재 비밀번호 재확인과 함께 \`DELETE /api/auth/me\`로 자기 계정과 소유한 모든 데이터를 완전 삭제.
- 스모크/부하 테스트가 만든 throwaway 계정을 스스로 정리(teardown)할 수 있게 되고, 실사용자 회원 탈퇴로서 암호화된 민감정보(브로커 OAuth 토큰, AI API 키)까지 파기.

## 구현
- \`AccountPurgeRepository\`(네이티브 @Modifying 삭제 모음) + \`AccountDeletionService\`(FK 안전 순서 오케스트레이터). trade_raw 삭제는 이 리포지토리에만 존재해 \`TradeRawJpaRepository\`의 '원장 삭제 금지' 불변식을 인터페이스 수준에서 보존.
- \`PositionCacheService.evictPortfolio\`로 삭제된 포트폴리오의 Redis 캐시 evict.
- \`AuthService.deleteAccount\`가 BCrypt 비밀번호 검증 후 오케스트레이터에 위임. \`DELETE /api/auth/me\`는 기존 \`anyRequest().authenticated()\`로 이미 인증 강제(보안 설정 변경 없음).

## 삭제 범위 (FK 안전 순서)
broker_auth → ua_ai_configs → ua_goals → ua_accounts(→ua_assets/ua_stock_trades cascade) → risk/performance/position_daily → broker_sync_state → trade_raw → portfolios → app_users(→app_refresh_tokens cascade).

## Tests
- \`PositionCacheServiceEvictTest\`: 캐시 키 삭제
- \`AccountDeletionServiceTest\`: 9개 삭제 FK 안전 순서(InOrder) + 포트폴리오별 evict + 포트폴리오 0개 케이스
- \`AuthServiceDeleteAccountTest\`: 비밀번호 일치/불일치/유저 없음
- \`DeleteAccountEndpointTest\`: 무인증 401 / 인증 204
- \`./gradlew test\` 전체 통과

## 검증 한계
네이티브 삭제문의 실제 행 제거는 CI에서 자동 검증되지 않음(실 DB 통합 테스트 인프라 부재). 단, 이 DELETE들은 앞선 계정 정리 작업 때 운영 Neon DB에 성공 실행된 문장과 동일. 배포 후 실계정 스모크로 확인 가능. testcontainers 도입은 후속 과제.

설계·계획: docs/superpowers/specs/2026-07-10-self-account-deletion-design.md, docs/superpowers/plans/2026-07-10-self-account-deletion.md

🤖 Generated with [Claude Code](https://claude.com/claude-code)"
```
