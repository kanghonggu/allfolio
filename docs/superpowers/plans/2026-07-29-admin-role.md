# ADMIN role (P0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ADMIN role을 실제로 동작시켜 `/api/admin/**`를 ADMIN에게만 열고, 프론트에 role/`isAdmin`을 노출해 후속 admin 기능을 언블록한다.

**Architecture:** `app_users`에 단일 `role` enum 컬럼(USER/ADMIN)을 추가하고, JWT에 `role` claim을 실어 `JwtUserIdFilter`가 `ROLE_*` authority를 부여한다. `SecurityConfig`에서 `/api/admin/**`를 `denyAll()`→`hasRole('ADMIN')`으로 바꾸고 `@EnableMethodSecurity`를 켠다. role claim이 없는 기존 토큰은 `USER`로 폴백해 하위호환을 지킨다. 프론트는 `AuthContext`에 role/`isAdmin`과 `useRequireAdmin()` 가드 유틸을 추가한다.

**Tech Stack:** Kotlin, Spring Boot 6, Spring Security 6, JJWT(HS256), JPA/Hibernate, JUnit5 + MockMvc, PostgreSQL(init.sql), Next.js(App Router)/React/TypeScript.

**Spec:** `docs/superpowers/specs/2026-07-29-admin-role-design.md`

---

## Reference: 현재 상태 (구현 전 사실)

- `JwtUserIdFilter`(`config/JwtUserIdFilter.kt:42`)는 `PreAuthenticatedAuthenticationToken(userId, token, emptyList())` — authorities 비어 있음.
- `JwtTokenService`(`auth/JwtTokenService.kt`)는 `issue`(role claim 없음)와 `parseUserId`만 있음. `parseUserId` 유일 호출처는 filter.
- `SecurityConfig`(`config/SecurityConfig.kt:61`)에 `.requestMatchers("/api/admin/**").denyAll()`. admin 경로 미인증 시 403 entryPoint(:37-44), accessDeniedHandler 403(:45-47).
- `UserEntity`(`auth/UserEntity.kt`)에 role 필드 없음. `AuthDtos.kt`의 `AuthUserResponse`에 role 없음. `AuthService.toResponse()`(`auth/AuthService.kt:126`)에서 매핑.
- `init.sql`(`infra/postgres/init.sql`) `app_users` CREATE TABLE에 role 컬럼 없음(정본은 루트 `allfolio-backend/infra/postgres/init.sql`, 550줄).
- FE `contexts/AuthContext.tsx` — `AuthState`/`AuthApiResponse`/`toAuthState`/초기값/`logout` reset에 role 없음.
- 유일 admin 컨트롤러 `api/admin/FxRateAdminController.kt` — `GET/PUT /api/admin/fx/usdtkrw`.
- 테스트 `test/.../config/SecurityConfigAdminTest.kt` — "유효 토큰도 403" 4개(오늘의 denyAll 계약).

**공통 규칙:** 모든 경로는 리포지토리 루트(`/Users/hong9/IdeaProjects/allfolio`) 기준. 백엔드 테스트 실행은 `allfolio-backend/` 에서 `./gradlew :backend-app:test --tests '<FQCN>'`. FE는 `frontend/allfolio_app/` 에서 `npm test`(설정 존재 시).

---

## File Structure

**Backend (신규)**
- `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/UserRole.kt` — role enum.
- `docs/superpowers/migrations/2026-07-29-admin-role.sql` — 운영 Neon용 1회성 SQL.

**Backend (수정)**
- `auth/UserEntity.kt` — `role` 필드.
- `auth/JwtTokenService.kt` — role claim + `JwtPrincipal`/`parsePrincipal`.
- `config/JwtUserIdFilter.kt` — `parsePrincipal` 사용, authority 부여.
- `config/SecurityConfig.kt` — `@EnableMethodSecurity`, admin 매처.
- `auth/AuthDtos.kt` — `AuthUserResponse.role`.
- `auth/AuthService.kt` — `toResponse()`에 role.
- `infra/postgres/init.sql` — `app_users` role 컬럼.
- `test/.../config/SecurityConfigAdminTest.kt` — 계약 재작성.
- (신규 테스트) `test/.../auth/JwtTokenServiceRoleTest.kt`, `test/.../config/JwtUserIdFilterRoleTest.kt`.

**Frontend (수정/신규)**
- `frontend/allfolio_app/contexts/AuthContext.tsx` — role/isAdmin.
- (신규) `frontend/allfolio_app/lib/useRequireAdmin.ts` — 가드 유틸.

---

## Task 1: UserRole enum + UserEntity role 필드

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/UserRole.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/UserEntity.kt`

- [ ] **Step 1: UserRole enum 생성**

Create `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/UserRole.kt`:
```kotlin
package com.allfolio.auth

enum class UserRole { USER, ADMIN }
```

- [ ] **Step 2: UserEntity에 role 필드 추가**

Modify `auth/UserEntity.kt`. import 추가:
```kotlin
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
```
`updatedAt` 프로퍼티 아래(마지막 파라미터 다음)에 필드 추가 — 기존 마지막 파라미터의 트레일링 콤마 뒤:
```kotlin
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    var role: UserRole = UserRole.USER,
```

- [ ] **Step 3: 컴파일 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:compileKotlin -q`
Expected: BUILD SUCCESSFUL (기존 UserEntity 생성부는 role 기본값 USER라 수정 불필요).

- [ ] **Step 4: Commit**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/UserRole.kt \
        allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/UserEntity.kt
git commit -m "feat: add UserRole enum and role field on UserEntity"
```

---

## Task 2: JwtTokenService — role claim + parsePrincipal (TDD)

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/JwtTokenService.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/auth/JwtTokenServiceRoleTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

Create `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/auth/JwtTokenServiceRoleTest.kt`:
```kotlin
package com.allfolio.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Date

class JwtTokenServiceRoleTest {

    private val secret = "test-secret-test-secret-test-secret-1234"
    private val service = JwtTokenService(secret, 15)

    private fun user(role: UserRole) = UserEntity(
        email = "u@example.com",
        passwordHash = "hash",
        displayName = null,
        role = role,
    )

    @Test
    fun `issue는 role claim을 싣고 parsePrincipal이 되읽는다 - ADMIN`() {
        val (token, _) = service.issue(user(UserRole.ADMIN))
        val principal = service.parsePrincipal(token)
        assertThat(principal.role).isEqualTo(UserRole.ADMIN)
    }

    @Test
    fun `issue는 role claim을 싣고 parsePrincipal이 되읽는다 - USER`() {
        val (token, _) = service.issue(user(UserRole.USER))
        val principal = service.parsePrincipal(token)
        assertThat(principal.role).isEqualTo(UserRole.USER)
    }

    @Test
    fun `parsePrincipal은 subject를 userId로 되읽는다`() {
        val u = user(UserRole.USER)
        val (token, _) = service.issue(u)
        val principal = service.parsePrincipal(token)
        assertThat(principal.userId).isEqualTo(u.id)
    }

    @Test
    fun `role claim 없는 기존 토큰은 USER로 폴백된다`() {
        val key = Keys.hmacShaKeyFor(secret.toByteArray().copyOf(32))
        val legacyToken = Jwts.builder()
            .subject("11111111-1111-1111-1111-111111111111")
            .claim("email", "u@example.com")
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + 60_000))
            .signWith(key)
            .compact()
        val principal = service.parsePrincipal(legacyToken)
        assertThat(principal.role).isEqualTo(UserRole.USER)
    }
}
```
- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests 'com.allfolio.auth.JwtTokenServiceRoleTest' -q`
Expected: 컴파일 에러 또는 FAIL (`parsePrincipal`/`role` 파라미터 미존재).

- [ ] **Step 3: JwtTokenService 구현**

Modify `auth/JwtTokenService.kt`. `issue`의 `.claim("name", ...)` 다음 줄에 role claim 추가:
```kotlin
            .claim("role", user.role.name)
```
그리고 클래스 안에 `JwtPrincipal`과 `parsePrincipal` 추가(기존 `parseUserId`는 유지):
```kotlin
    data class JwtPrincipal(val userId: java.util.UUID, val role: UserRole)

    fun parsePrincipal(token: String): JwtPrincipal {
        val claims = Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload
        val userId = try {
            UUID.fromString(claims.subject)
        } catch (e: IllegalArgumentException) {
            throw JwtException("Invalid subject", e)
        }
        val role = claims["role"]?.toString()
            ?.let { runCatching { UserRole.valueOf(it) }.getOrNull() }
            ?: UserRole.USER
        return JwtPrincipal(userId, role)
    }
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests 'com.allfolio.auth.JwtTokenServiceRoleTest' -q`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/JwtTokenService.kt \
        allfolio-backend/backend-app/src/test/kotlin/com/allfolio/auth/JwtTokenServiceRoleTest.kt
git commit -m "feat: carry role claim in JWT and add parsePrincipal with USER fallback"
```

---

## Task 3: JwtUserIdFilter — authority 부여 (TDD)

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/config/JwtUserIdFilter.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/config/JwtUserIdFilterRoleTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

Create `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/config/JwtUserIdFilterRoleTest.kt`:
```kotlin
package com.allfolio.config

import com.allfolio.auth.JwtTokenService
import com.allfolio.auth.UserEntity
import com.allfolio.auth.UserRole
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder

class JwtUserIdFilterRoleTest {

    private val jwt = JwtTokenService("test-secret-test-secret-test-secret-1234", 15)
    private val filter = JwtUserIdFilter(jwt)

    @AfterEach
    fun tearDown() = SecurityContextHolder.clearContext()

    private fun tokenFor(role: UserRole): String =
        jwt.issue(UserEntity(email = "u@example.com", passwordHash = "h", displayName = null, role = role)).first

    private fun authoritiesAfterFilter(role: UserRole): Set<String> {
        val req = MockHttpServletRequest().apply {
            addHeader("Authorization", "Bearer ${tokenFor(role)}")
        }
        val res = MockHttpServletResponse()
        val chain = FilterChain { _, _ -> }
        filter.doFilter(req, res, chain)
        return SecurityContextHolder.getContext().authentication.authorities.map { it.authority }.toSet()
    }

    @Test
    fun `ADMIN 토큰은 ROLE_ADMIN authority를 부여한다`() {
        assertThat(authoritiesAfterFilter(UserRole.ADMIN)).contains("ROLE_ADMIN")
    }

    @Test
    fun `USER 토큰은 ROLE_USER authority를 부여한다`() {
        assertThat(authoritiesAfterFilter(UserRole.USER)).contains("ROLE_USER")
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests 'com.allfolio.config.JwtUserIdFilterRoleTest' -q`
Expected: FAIL (authorities 비어 있어 `ROLE_ADMIN` 없음).

- [ ] **Step 3: 필터 구현**

Modify `config/JwtUserIdFilter.kt`. import 추가:
```kotlin
import org.springframework.security.core.authority.SimpleGrantedAuthority
```
`doFilterInternal`의 `if (token != null) { ... }` 블록을 아래로 교체(기존 `parseUserId`+`emptyList()` 부분):
```kotlin
        if (token != null) {
            val principal = try {
                jwtTokenService.parsePrincipal(token)
            } catch (e: JwtException) {
                SecurityContextHolder.clearContext()
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid access token")
                return
            }
            val userId = principal.userId.toString()
            val authorities = listOf(SimpleGrantedAuthority("ROLE_${principal.role.name}"))
            SecurityContextHolder.getContext().authentication =
                PreAuthenticatedAuthenticationToken(userId, token, authorities)
            val wrapped = UserIdInjectedRequest(request, userId)
            chain.doFilter(wrapped, response)
        } else {
            chain.doFilter(request, response)
        }
```
> `java.util.Collections` import는 내부 클래스에서 여전히 사용되므로 제거하지 않는다.

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests 'com.allfolio.config.JwtUserIdFilterRoleTest' -q`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/config/JwtUserIdFilter.kt \
        allfolio-backend/backend-app/src/test/kotlin/com/allfolio/config/JwtUserIdFilterRoleTest.kt
git commit -m "feat: grant ROLE_* authority from JWT role claim in JwtUserIdFilter"
```

---

## Task 4: SecurityConfig — admin 매처 + 메서드시큐리티, 계약 테스트 재작성 (TDD)

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/config/SecurityConfig.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/config/SecurityConfigAdminTest.kt`

- [ ] **Step 1: 계약 테스트 재작성**

Replace 전체 파일 `test/.../config/SecurityConfigAdminTest.kt`의 테스트 4개 + `validToken()` 헬퍼 부분을 아래로 교체. import에 `UserRole` 추가(`import com.allfolio.auth.UserRole`), 나머지 상단/`TestApplication`/`TestBeans`는 유지. 테스트 본문 블록(현재 `@Test`~`validToken()`)을 다음으로 대체:
```kotlin
    @Test
    fun `admin FX 조회는 토큰 없이 403으로 차단된다`() {
        mockMvc.get("/api/admin/fx/usdtkrw")
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `admin FX 변경은 토큰 없이 403으로 차단된다`() {
        mockMvc.put("/api/admin/fx/usdtkrw") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"rate":1350}"""
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `admin FX 조회는 USER 토큰이면 403으로 차단된다`() {
        mockMvc.get("/api/admin/fx/usdtkrw") {
            header("Authorization", "Bearer ${tokenFor(UserRole.USER)}")
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `admin FX 변경은 USER 토큰이면 403으로 차단된다`() {
        mockMvc.put("/api/admin/fx/usdtkrw") {
            header("Authorization", "Bearer ${tokenFor(UserRole.USER)}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"rate":1350}"""
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `admin FX 조회는 ADMIN 토큰이면 200으로 허용된다`() {
        mockMvc.get("/api/admin/fx/usdtkrw") {
            header("Authorization", "Bearer ${tokenFor(UserRole.ADMIN)}")
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `admin FX 변경은 ADMIN 토큰이면 200으로 허용된다`() {
        mockMvc.put("/api/admin/fx/usdtkrw") {
            header("Authorization", "Bearer ${tokenFor(UserRole.ADMIN)}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"rate":1350}"""
        }.andExpect { status { isOk() } }
    }

    private fun tokenFor(role: UserRole): String =
        jwtTokenService.issue(
            UserEntity(
                email = "security-test@example.com",
                passwordHash = "hash",
                displayName = null,
                role = role,
            ),
        ).first
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests 'com.allfolio.config.SecurityConfigAdminTest' -q`
Expected: FAIL — ADMIN 200 기대가 실패(현재 `denyAll()`이라 403).

- [ ] **Step 3: SecurityConfig 수정**

Modify `config/SecurityConfig.kt`:
1. import 추가:
```kotlin
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
```
2. 클래스 어노테이션에 추가(`@EnableWebSecurity` 아래):
```kotlin
@EnableMethodSecurity
```
3. admin 매처 교체:
```kotlin
                    .requestMatchers("/api/admin/**").hasRole("ADMIN")
```
(기존 `.requestMatchers("/api/admin/**").denyAll()` 대체. entryPoint/accessDeniedHandler는 그대로 둔다.)

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests 'com.allfolio.config.SecurityConfigAdminTest' -q`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/config/SecurityConfig.kt \
        allfolio-backend/backend-app/src/test/kotlin/com/allfolio/config/SecurityConfigAdminTest.kt
git commit -m "feat: gate /api/admin/** behind ROLE_ADMIN and enable method security"
```

---

## Task 5: 응답 DTO에 role 노출 (TDD)

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/AuthDtos.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/AuthService.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/auth/AuthServiceRoleResponseTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

Create `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/auth/AuthServiceRoleResponseTest.kt`:
```kotlin
package com.allfolio.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AuthServiceRoleResponseTest {

    @Test
    fun `AuthUserResponse는 role을 담는다`() {
        val resp = AuthUserResponse(
            id = java.util.UUID.randomUUID(),
            email = "u@example.com",
            displayName = null,
            role = UserRole.ADMIN,
        )
        assertThat(resp.role).isEqualTo(UserRole.ADMIN)
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests 'com.allfolio.auth.AuthServiceRoleResponseTest' -q`
Expected: 컴파일 에러 (`AuthUserResponse`에 `role` 파라미터 없음).

- [ ] **Step 3: AuthDtos 수정**

Modify `auth/AuthDtos.kt` — `AuthUserResponse`에 role 추가:
```kotlin
data class AuthUserResponse(
    val id: UUID,
    val email: String,
    val displayName: String?,
    val role: UserRole,
)
```

- [ ] **Step 4: AuthService.toResponse 수정**

Modify `auth/AuthService.kt` — 파일 하단 `toResponse()` 확장 함수에 role 매핑:
```kotlin
    private fun UserEntity.toResponse(): AuthUserResponse =
        AuthUserResponse(id = id, email = email, displayName = displayName, role = role)
```

- [ ] **Step 5: 테스트 실행 — 통과 확인 + 전체 auth 회귀**

Run: `cd allfolio-backend && ./gradlew :backend-app:test --tests 'com.allfolio.auth.*' -q`
Expected: PASS (신규 포함, 기존 auth 테스트 회귀 없음).

- [ ] **Step 6: Commit**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/AuthDtos.kt \
        allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/AuthService.kt \
        allfolio-backend/backend-app/src/test/kotlin/com/allfolio/auth/AuthServiceRoleResponseTest.kt
git commit -m "feat: expose role in AuthUserResponse"
```

---

## Task 6: 스키마 — init.sql 컬럼 + 운영 마이그레이션 SQL

**Files:**
- Modify: `allfolio-backend/infra/postgres/init.sql`
- Create: `docs/superpowers/migrations/2026-07-29-admin-role.sql`

- [ ] **Step 1: init.sql app_users에 role 컬럼 추가**

Modify `allfolio-backend/infra/postgres/init.sql` — `app_users` CREATE TABLE 안에서 `updated_at` 줄 다음, PRIMARY KEY 제약 앞에 컬럼 추가:
```sql
    role          VARCHAR(20)   NOT NULL DEFAULT 'USER',
```
(정확한 위치: `updated_at    TIMESTAMP     NOT NULL DEFAULT NOW(),` 바로 아래, `CONSTRAINT pk_app_users PRIMARY KEY (id),` 위.)

- [ ] **Step 2: 운영 마이그레이션 SQL 생성**

Create `docs/superpowers/migrations/2026-07-29-admin-role.sql`:
```sql
-- ADMIN role (P0) — 운영 Neon 1회성 마이그레이션
-- 실행: /opt/homebrew/opt/libpq/bin/psql "<NEON_DB_URL>" -f 2026-07-29-admin-role.sql
-- 반드시 백엔드 배포 "전"에 실행 (ddl-auto: none, 컬럼 부재 시 role SELECT 실패).

ALTER TABLE app_users
    ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'USER';

UPDATE app_users
    SET role = 'ADMIN'
    WHERE email = 'rkdghd123@naver.com';
```

- [ ] **Step 3: SQL 문법 로컬 검증(선택, psql 있으면)**

로컬 dev DB 또는 임시 컨테이너가 있으면 실행해 문법 확인. 없으면 스킵(문장 단순).
Run(예): `/opt/homebrew/opt/libpq/bin/psql "$LOCAL_DB_URL" -c "ALTER TABLE app_users ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'USER';"`
Expected: `ALTER TABLE` (또는 이미 존재 시 무변경).

- [ ] **Step 4: Commit**

```bash
git add allfolio-backend/infra/postgres/init.sql \
        docs/superpowers/migrations/2026-07-29-admin-role.sql
git commit -m "feat: add role column to app_users schema and prod migration SQL"
```

---

## Task 7: 백엔드 전체 회귀 + 빌드

**Files:** (없음 — 검증 태스크)

- [ ] **Step 1: 전체 백엔드 테스트**

Run: `cd allfolio-backend && ./gradlew :backend-app:test -q`
Expected: BUILD SUCCESSFUL. 특히 `SecurityConfigAdminTest`, `SecurityConfigErrorDispatchTest`, `SecurityConfigActuatorTest`, 기존 인증/소유권 테스트 회귀 없음.

- [ ] **Step 2: 실패 시 진단**

실패하면 해당 테스트 로그 확인:
`cd allfolio-backend && ./gradlew :backend-app:test --tests '<FQCN>' --info`
`emptyList()`→authorities 변경, DTO role 추가로 깨진 곳(예: 다른 곳에서 `AuthUserResponse`를 생성하는 코드)이 있으면 role 인자를 추가해 수정 후 재실행.

- [ ] **Step 3: Commit (수정 있었을 때만)**

```bash
git add -A && git commit -m "test: fix regressions from role addition"
```

---

## Task 8: Frontend — AuthContext role/isAdmin

**Files:**
- Modify: `frontend/allfolio_app/contexts/AuthContext.tsx`

- [ ] **Step 1: AuthState + 초기값에 role 추가**

Modify `contexts/AuthContext.tsx`:
1. `AuthState` 인터페이스에 추가:
```ts
  role:         string | null
```
2. `AuthContextValue`에 파생값 추가:
```ts
  isAdmin: boolean
```
3. `createContext` 기본값 객체에 `role: null,`와 `isAdmin: false,` 추가.
4. `AuthProvider` 초기 `useState<AuthState>` 객체에 `role: null,` 추가.
5. `logout`의 reset 객체에 `role: null,` 추가.

- [ ] **Step 2: AuthApiResponse + toAuthState에 role 추가**

Modify `contexts/AuthContext.tsx`:
1. `AuthApiResponse.user`에 추가:
```ts
    role: string
```
2. `toAuthState` 반환 객체에 추가:
```ts
    role:         data.user.role,
```

- [ ] **Step 3: context value에 isAdmin 노출**

Modify `AuthContext.Provider value`에 추가(`authenticated` 아래):
```ts
      isAdmin: state.role === 'ADMIN',
```

- [ ] **Step 4: 타입 체크 / 빌드**

Run: `cd frontend/allfolio_app && npx tsc --noEmit`
Expected: 에러 없음. (스크립트가 다르면 `npm run build`의 타입 단계로 확인.)

- [ ] **Step 5: Commit**

```bash
git add frontend/allfolio_app/contexts/AuthContext.tsx
git commit -m "feat: expose role and isAdmin in AuthContext"
```

---

## Task 9: Frontend — useRequireAdmin 가드 유틸

**Files:**
- Create: `frontend/allfolio_app/lib/useRequireAdmin.ts`

- [ ] **Step 1: 가드 유틸 생성**

Create `frontend/allfolio_app/lib/useRequireAdmin.ts`:
```ts
'use client'

import { useEffect } from 'react'
import { useRouter } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'

/**
 * ADMIN 전용 화면에서 최상단 호출. 비-admin/미인증이면 redirectTo로 이동.
 * ready === true 일 때만 admin 콘텐츠를 렌더링하면 된다.
 */
export function useRequireAdmin(redirectTo = '/') {
  const { initialized, authenticated, isAdmin } = useAuth()
  const router = useRouter()

  useEffect(() => {
    if (!initialized) return
    if (!authenticated || !isAdmin) router.replace(redirectTo)
  }, [initialized, authenticated, isAdmin, router, redirectTo])

  return { ready: initialized && authenticated && isAdmin }
}
```
> 주의: import 별칭 `@/contexts/AuthContext`와 `next/navigation`의 `useRouter`가 이 프로젝트 관례와 일치하는지 확인. 다르면 기존 컴포넌트의 import 스타일(상대경로/별칭)을 따른다.

- [ ] **Step 2: 타입 체크**

Run: `cd frontend/allfolio_app && npx tsc --noEmit`
Expected: 에러 없음.

- [ ] **Step 3: Commit**

```bash
git add frontend/allfolio_app/lib/useRequireAdmin.ts
git commit -m "feat: add useRequireAdmin guard hook for future admin screens"
```

---

## Task 10: 통합 검증 (수동/브라우저)

**Files:** (없음 — 검증 태스크)

- [ ] **Step 1: 로컬 DB에 role 컬럼 반영**

로컬 Postgres가 기존 데이터를 갖고 있으면(init.sql은 재실행 안 됨) 마이그레이션 SQL 적용:
Run: `/opt/homebrew/opt/libpq/bin/psql "$LOCAL_DB_URL" -f docs/superpowers/migrations/2026-07-29-admin-role.sql`
Expected: `ALTER TABLE`, `UPDATE n`. (신규 컨테이너면 init.sql이 컬럼 포함해 생성하므로 UPDATE만 의미.)
로컬에 `rkdghd123@naver.com`이 없으면, 테스트용 계정을 만들고 그 이메일로 `UPDATE app_users SET role='ADMIN' WHERE email='<local-test-email>';` 수동 실행.

- [ ] **Step 2: 백엔드 + 프론트 기동, ADMIN 로그인 검증**

`superpowers:verification-before-completion` / 프로젝트 `run`·`verify` 스킬로 앱 기동 후:
- ADMIN 계정 로그인 → `GET /api/admin/fx/usdtkrw` → **200**.
- 일반 USER 계정 로그인 → 동일 요청 → **403**.
- 무토큰 → **403**.
- 로그인 응답 JSON `user.role` 필드 존재 확인, FE `isAdmin` 파생 확인.

- [ ] **Step 2 확인 방법(예: curl)**

```bash
# ADMIN 로그인 → accessToken 추출 → admin 호출
TOKEN=$(curl -s localhost:8090/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"<admin-email>","password":"<pw>"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["accessToken"])')
curl -s -o /dev/null -w '%{http_code}\n' localhost:8090/api/admin/fx/usdtkrw -H "Authorization: Bearer $TOKEN"
# 기대: 200
```
Expected: ADMIN=200, USER=403, 무토큰=403.

- [ ] **Step 3: 검증 결과 기록 (커밋 불필요)**

결과를 요약해 보고. 문제 있으면 해당 Task로 돌아가 수정.

---

## Rollout (배포 시 — 구현과 별개, 사용자 실행)

1. `docs/superpowers/migrations/2026-07-29-admin-role.sql`을 **운영 Neon에 먼저 수동 실행**(ALTER + `rkdghd123@naver.com` 승격).
2. main 푸시 → Render 자동배포(백엔드 role 코드).
3. 프론트 배포.
4. 운영에서 `rkdghd123@naver.com` 로그인 → admin 200, 타 계정 403 확인.

> **순서 엄수**: SQL을 백엔드 배포보다 먼저. 컬럼 부재 상태로 새 코드가 role을 읽으면 실패.

---

## Notes / 주의
- `AuthUserResponse`에 role(non-null) 추가 → 다른 곳에서 이 DTO를 생성하는 코드가 있으면 컴파일 에러. Task 7에서 잡는다.
- `parseUserId`는 삭제하지 않는다(기존 테스트/호환). 신규 경로만 `parsePrincipal` 사용.
- FE 테스트 러너가 없으면 Task 8/9는 `tsc --noEmit`로 대체 검증.
- 이 플랜은 admin **게이트 인프라**만 제공. 실제 admin 화면·승격 API·개별 후속 컨트롤러는 범위 밖.
