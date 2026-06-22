# Lightweight Auth And Free Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove Keycloak from Allfolio and make the app deployable on a low-cost/free stack: static frontend, one lightweight Spring Boot backend, and managed Postgres.

**Architecture:** Replace Keycloak with first-party email/password authentication inside `backend-app`, issuing signed JWT access tokens and opaque refresh tokens. Keep the existing `Authorization: Bearer ... -> X-User-Id` boundary so most portfolio/report controllers remain unchanged. Deploy frontend to Vercel or Cloudflare Pages, backend to Render Free, and Postgres to Neon Free.

**Tech Stack:** Kotlin, Spring Boot 3, Spring Security, jjwt or Nimbus JOSE JWT, BCrypt, PostgreSQL, Next.js, TanStack Query, Render, Neon, Vercel/Cloudflare Pages.

---

## File Map

- Modify: `allfolio-backend/backend-app/build.gradle.kts`  
  Add dependencies for JWT signing/verification if current OAuth resource-server dependency is removed or insufficient.
- Modify: `allfolio-backend/backend-app/src/main/resources/application.yml`  
  Add `allfolio.auth.jwt-secret`, token TTLs, CORS origins, and lightweight profile defaults.
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/UserEntity.kt`  
  Persist local application users.
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/UserRepository.kt`  
  Find users by email and id.
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/RefreshTokenEntity.kt`  
  Persist hashed refresh tokens for rotation/revocation.
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/RefreshTokenRepository.kt`  
  Find and revoke refresh tokens.
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/AuthDtos.kt`  
  Request/response DTOs for register, login, refresh, logout, and me.
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/JwtTokenService.kt`  
  Sign and validate access tokens.
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/AuthService.kt`  
  Register users, verify passwords, rotate refresh tokens.
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/AuthController.kt`  
  Expose `/api/auth/register`, `/api/auth/login`, `/api/auth/refresh`, `/api/auth/logout`, `/api/auth/me`.
- Replace: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/config/JwtUserIdFilter.kt`  
  Verify local JWT and inject `X-User-Id`.
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/config/SecurityConfig.kt`  
  Remove Keycloak resource-server config and permit auth endpoints.
- Modify: `allfolio-backend/infra/postgres/init.sql`  
  Add `app_users` and `app_refresh_tokens`; remove Keycloak schema dependency from active path.
- Modify: `frontend/allfolio_app/contexts/AuthContext.tsx`  
  Replace Keycloak token endpoint calls with Allfolio `/api/auth/*` calls.
- Delete or stop using: `frontend/allfolio_app/lib/keycloak.ts`, `frontend/allfolio_app/lib/auth.ts`  
  Remove Keycloak and NextAuth leftovers after migration.
- Modify: `frontend/allfolio_app/package.json`  
  Remove `keycloak-js` and unused `next-auth` dependency if no route imports it.
- Modify: `docker-compose.yml`, `docker-compose.prod.yml`, `allfolio-backend/docker-compose.yml`  
  Remove Keycloak service and environment variables.
- Create: `render.yaml`  
  Render backend blueprint for `backend-app`.
- Create: `docs/DEPLOY_FREE.md`  
  Step-by-step deployment guide for Neon + Render + Vercel/Cloudflare Pages.

---

## Task 1: Backend Auth Schema

**Files:**
- Modify: `allfolio-backend/infra/postgres/init.sql`
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/UserEntity.kt`
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/UserRepository.kt`
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/RefreshTokenEntity.kt`
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/RefreshTokenRepository.kt`

- [ ] **Step 1: Add auth tables to `init.sql`**

Add after the Keycloak schema comment or near other app-owned tables:

```sql
CREATE TABLE IF NOT EXISTS app_users (
    id            UUID          NOT NULL,
    email         VARCHAR(255)  NOT NULL,
    password_hash VARCHAR(255)  NOT NULL,
    display_name  VARCHAR(100),
    created_at    TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_app_users PRIMARY KEY (id),
    CONSTRAINT uk_app_users_email UNIQUE (email)
);

CREATE TABLE IF NOT EXISTS app_refresh_tokens (
    id           UUID         NOT NULL,
    user_id      UUID         NOT NULL,
    token_hash   VARCHAR(255) NOT NULL,
    expires_at   TIMESTAMP    NOT NULL,
    revoked_at   TIMESTAMP,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_app_refresh_tokens PRIMARY KEY (id),
    CONSTRAINT fk_app_refresh_tokens_user FOREIGN KEY (user_id)
        REFERENCES app_users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_app_refresh_tokens_user
    ON app_refresh_tokens (user_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_app_refresh_tokens_hash
    ON app_refresh_tokens (token_hash);
```

- [ ] **Step 2: Create `UserEntity.kt`**

```kotlin
package com.allfolio.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "app_users")
class UserEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false, unique = true)
    val email: String,

    @Column(name = "password_hash", nullable = false)
    var passwordHash: String,

    @Column(name = "display_name")
    var displayName: String?,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
```

- [ ] **Step 3: Create `UserRepository.kt`**

```kotlin
package com.allfolio.auth

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserRepository : JpaRepository<UserEntity, UUID> {
    fun findByEmail(email: String): UserEntity?
    fun existsByEmail(email: String): Boolean
}
```

- [ ] **Step 4: Create refresh token persistence**

```kotlin
package com.allfolio.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "app_refresh_tokens")
class RefreshTokenEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "token_hash", nullable = false, unique = true)
    val tokenHash: String,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: LocalDateTime,

    @Column(name = "revoked_at")
    var revokedAt: LocalDateTime? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    fun isActive(now: LocalDateTime = LocalDateTime.now()): Boolean =
        revokedAt == null && expiresAt.isAfter(now)

    fun revoke(now: LocalDateTime = LocalDateTime.now()) {
        revokedAt = now
    }
}
```

```kotlin
package com.allfolio.auth

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface RefreshTokenRepository : JpaRepository<RefreshTokenEntity, UUID> {
    fun findByTokenHash(tokenHash: String): RefreshTokenEntity?
    fun findByUserId(userId: UUID): List<RefreshTokenEntity>
}
```

- [ ] **Step 5: Commit**

```bash
git add allfolio-backend/infra/postgres/init.sql allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth
git commit -m "feat: add local auth persistence"
```

---

## Task 2: JWT Service And Auth API

**Files:**
- Modify: `allfolio-backend/backend-app/build.gradle.kts`
- Modify: `allfolio-backend/backend-app/src/main/resources/application.yml`
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/AuthDtos.kt`
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/JwtTokenService.kt`
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/AuthService.kt`
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/auth/AuthController.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/auth/AuthServiceTest.kt`

- [ ] **Step 1: Add dependencies**

Use Spring Security crypto for BCrypt and either `io.jsonwebtoken:jjwt-*` or Nimbus already available through Spring Security. Prefer Nimbus if dependency is already present; otherwise add:

```kotlin
implementation("io.jsonwebtoken:jjwt-api:0.12.6")
runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
```

- [ ] **Step 2: Add auth config**

```yaml
allfolio:
  auth:
    jwt-secret: ${ALLFOLIO_JWT_SECRET:dev-only-change-me-dev-only-change-me}
    access-token-minutes: ${ACCESS_TOKEN_MINUTES:15}
    refresh-token-days: ${REFRESH_TOKEN_DAYS:30}
```

- [ ] **Step 3: Write DTOs**

```kotlin
package com.allfolio.auth

import java.util.UUID

data class RegisterRequest(val email: String, val password: String, val displayName: String?)
data class LoginRequest(val email: String, val password: String)
data class RefreshRequest(val refreshToken: String)
data class LogoutRequest(val refreshToken: String)

data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val user: AuthUserResponse,
)

data class AuthUserResponse(
    val id: UUID,
    val email: String,
    val displayName: String?,
)
```

- [ ] **Step 4: Implement token service**

```kotlin
package com.allfolio.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

@Service
class JwtTokenService(
    @Value("\${allfolio.auth.jwt-secret}") secret: String,
    @Value("\${allfolio.auth.access-token-minutes}") private val accessTokenMinutes: Long,
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(secret.toByteArray().copyOf(32))

    fun issue(user: UserEntity): Pair<String, Long> {
        val expiresAt = Instant.now().plusSeconds(accessTokenMinutes * 60)
        val token = Jwts.builder()
            .subject(user.id.toString())
            .claim("email", user.email)
            .claim("name", user.displayName ?: user.email)
            .issuedAt(Date.from(Instant.now()))
            .expiration(Date.from(expiresAt))
            .signWith(key)
            .compact()
        return token to accessTokenMinutes * 60
    }

    fun parseUserId(token: String): UUID {
        val subject = Jwts.parser().verifyWith(key).build()
            .parseSignedClaims(token)
            .payload
            .subject
        return UUID.fromString(subject)
    }
}
```

- [ ] **Step 5: Implement auth service**

Use `BCryptPasswordEncoder`, normalize emails with `trim().lowercase()`, reject passwords shorter than 8 characters, hash refresh tokens before storing them, and rotate refresh tokens on every refresh.

- [ ] **Step 6: Implement controller**

Expose:

```text
POST /api/auth/register
POST /api/auth/login
POST /api/auth/refresh
POST /api/auth/logout
GET  /api/auth/me
```

The response shape must match `AuthResponse` because the frontend task depends on it.

- [ ] **Step 7: Test**

```bash
cd allfolio-backend
./gradlew :backend-app:test --tests 'com.allfolio.auth.*'
```

Expected: auth tests pass.

- [ ] **Step 8: Commit**

```bash
git add allfolio-backend/backend-app
git commit -m "feat: replace keycloak token issuance with local auth api"
```

---

## Task 3: Replace Keycloak Security Filter

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/config/JwtUserIdFilter.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/config/SecurityConfig.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/config/JwtUserIdFilterTest.kt`

- [ ] **Step 1: Rewrite `JwtUserIdFilter`**

Keep the existing behavior of injecting `X-User-Id`, but parse Allfolio JWT through `JwtTokenService` instead of reading a Keycloak `JwtAuthenticationToken`.

- [ ] **Step 2: Update `SecurityConfig`**

Permit:

```kotlin
.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
.requestMatchers("/actuator/**").permitAll()
.requestMatchers("/api/auth/register", "/api/auth/login", "/api/auth/refresh").permitAll()
.requestMatchers("/api/broker/*/callback").permitAll()
```

Require auth for everything else. Add the custom filter before `UsernamePasswordAuthenticationFilter`. Remove `.oauth2ResourceServer { oauth2 -> oauth2.jwt { } }`.

- [ ] **Step 3: Test a protected endpoint**

```bash
cd allfolio-backend
./gradlew :backend-app:test
```

Expected: existing backend tests and new auth/security tests pass.

- [ ] **Step 4: Commit**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/config allfolio-backend/backend-app/src/test
git commit -m "feat: verify allfolio jwt in security filter"
```

---

## Task 4: Frontend Auth Migration

**Files:**
- Modify: `frontend/allfolio_app/contexts/AuthContext.tsx`
- Modify: `frontend/allfolio_app/app/login/page.tsx`
- Modify: `frontend/allfolio_app/package.json`
- Delete: `frontend/allfolio_app/lib/keycloak.ts`
- Delete or keep unused until final cleanup: `frontend/allfolio_app/lib/auth.ts`

- [ ] **Step 1: Change auth base URL**

In `AuthContext.tsx`, replace Keycloak constants with:

```ts
const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'
const LOGIN_URL = `${API_BASE_URL}/api/auth/login`
const REFRESH_URL = `${API_BASE_URL}/api/auth/refresh`
```

- [ ] **Step 2: Replace token fetch body**

Login must call:

```ts
await fetch(LOGIN_URL, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ email, password }),
})
```

Refresh must call:

```ts
await fetch(REFRESH_URL, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ refreshToken }),
})
```

- [ ] **Step 3: Store response**

Map backend response into the current `AuthState`:

```ts
return {
  accessToken: data.accessToken,
  refreshToken: data.refreshToken,
  expiresAt: Date.now() + data.expiresIn * 1000,
  userName: data.user.displayName ?? data.user.email,
  userEmail: data.user.email,
  userId: data.user.id,
}
```

- [ ] **Step 4: Remove Keycloak package**

```bash
cd frontend/allfolio_app
npm uninstall keycloak-js
```

If `next-auth` is only used by deleted `lib/auth.ts`, remove it too:

```bash
cd frontend/allfolio_app
npm uninstall next-auth
```

- [ ] **Step 5: Verify frontend**

```bash
cd frontend/allfolio_app
npm run lint
npm run build
```

Expected: build completes without Keycloak environment variables.

- [ ] **Step 6: Commit**

```bash
git add frontend/allfolio_app
git commit -m "feat: migrate frontend auth to allfolio api"
```

---

## Task 5: Remove Keycloak From Local And Production Compose

**Files:**
- Modify: `docker-compose.yml`
- Modify: `docker-compose.prod.yml`
- Modify: `allfolio-backend/docker-compose.yml`
- Modify: `allfolio-backend/backend-app/src/main/resources/application.yml`
- Modify: `allfolio-backend/infra/postgres/init.sql`

- [ ] **Step 1: Remove Keycloak services and volumes**

Remove `keycloak` services, `keycloak_data` volumes, and `KEYCLOAK_ISSUER_URI` / `SPRING_SECURITY_OAUTH2_RESOURCESERVER_*` environment variables.

- [ ] **Step 2: Add JWT env**

For backend services add:

```yaml
ALLFOLIO_JWT_SECRET: ${ALLFOLIO_JWT_SECRET:-dev-only-change-me-dev-only-change-me}
ACCESS_TOKEN_MINUTES: "15"
REFRESH_TOKEN_DAYS: "30"
```

- [ ] **Step 3: Keep Kafka/Redis optional**

For free deployment, ensure default local profile can start with:

```yaml
KAFKA_ENABLED: "false"
REDIS_ENABLED: "false"
```

If code requires Kafka/Redis beans unconditionally, add a follow-up task before deployment to guard those beans with properties.

- [ ] **Step 4: Verify local infra**

```bash
docker compose config
```

Expected: no `keycloak` service appears and compose config is valid.

- [ ] **Step 5: Commit**

```bash
git add docker-compose.yml docker-compose.prod.yml allfolio-backend/docker-compose.yml allfolio-backend/backend-app/src/main/resources/application.yml allfolio-backend/infra/postgres/init.sql
git commit -m "chore: remove keycloak from runtime configuration"
```

---

## Task 6: Free Deployment Artifacts

**Files:**
- Create: `render.yaml`
- Create: `docs/DEPLOY_FREE.md`
- Modify: `README.md`

- [ ] **Step 1: Add `render.yaml`**

```yaml
services:
  - type: web
    name: allfolio-api
    runtime: docker
    plan: free
    dockerfilePath: ./allfolio-backend/backend-app/Dockerfile
    dockerContext: ./allfolio-backend
    healthCheckPath: /actuator/health
    envVars:
      - key: SPRING_PROFILES_ACTIVE
        value: prod
      - key: DB_URL
        sync: false
      - key: DB_USER
        sync: false
      - key: DB_PASS
        sync: false
      - key: ALLFOLIO_JWT_SECRET
        sync: false
      - key: KAFKA_ENABLED
        value: "false"
```

- [ ] **Step 2: Write `docs/DEPLOY_FREE.md`**

Include:

```markdown
# Free Deployment Guide

## Target Stack
- Frontend: Vercel or Cloudflare Pages
- Backend: Render Free Web Service
- Database: Neon Free Postgres
- Auth: Allfolio local JWT auth

## Neon
1. Create a Neon project.
2. Copy the pooled JDBC URL.
3. Run `allfolio-backend/infra/postgres/init.sql` against the Neon database.

## Render
1. Create a Web Service from this repository.
2. Use Dockerfile `allfolio-backend/backend-app/Dockerfile`.
3. Set `DB_URL`, `DB_USER`, `DB_PASS`, `ALLFOLIO_JWT_SECRET`, `KAFKA_ENABLED=false`.
4. Deploy and verify `/actuator/health`.

## Frontend
1. Set `NEXT_PUBLIC_API_BASE_URL=https://<render-service>.onrender.com`.
2. Deploy `frontend/allfolio_app`.
3. Register/login and open `/unified`.
```

- [ ] **Step 3: Update README**

Add a short deployment pointer:

```markdown
### Lightweight Free Deployment

For MVP deployment without Keycloak/Kafka/Redis, see `docs/DEPLOY_FREE.md`.
```

- [ ] **Step 4: Commit**

```bash
git add render.yaml docs/DEPLOY_FREE.md README.md
git commit -m "docs: add lightweight free deployment guide"
```

---

## Task 7: End-To-End Verification

**Files:**
- No production file changes unless verification finds defects.

- [ ] **Step 1: Backend tests**

```bash
cd allfolio-backend
./gradlew test
```

Expected: all tests pass.

- [ ] **Step 2: Frontend build**

```bash
cd frontend/allfolio_app
npm run build
```

Expected: production build succeeds.

- [ ] **Step 3: Manual auth flow**

Run backend and frontend locally. Then verify:

```text
1. Register a new user.
2. Login succeeds.
3. Dashboard API receives Authorization Bearer token.
4. Backend injects X-User-Id from JWT subject.
5. Refresh token renews access token after expiration.
6. Logout clears local storage and revokes refresh token.
```

- [ ] **Step 4: Keycloak removal scan**

```bash
rg -n "Keycloak|keycloak|KEYCLOAK|oauth2ResourceServer|NEXT_PUBLIC_KEYCLOAK" .
```

Expected: only historical docs or intentionally archived files remain. Active runtime code must not require Keycloak.

- [ ] **Step 5: Final commit**

```bash
git status --short
git commit -m "chore: verify lightweight auth deployment migration"
```

Only run the final commit if verification required additional fixes.

---

## Rollout Notes

- Existing users from Keycloak are not migrated by this plan. If production data already exists, add a migration task that creates `app_users` rows with the same UUIDs as Keycloak `sub` values.
- Store `ALLFOLIO_JWT_SECRET` as a strong 32+ byte secret in Render environment variables.
- Do not deploy broker API credentials until `broker_auth` encryption is implemented.
- For Render Free, disable Kafka, market-data, and heavy websocket services in the first deployment.
- For Neon Free, avoid high-frequency tick ingestion; keep historical price ingestion narrow until storage and compute budget are confirmed.

---

## Self-Review

- Spec coverage: The plan covers Keycloak removal, local JWT auth, frontend token migration, compose cleanup, and free deployment documentation.
- Placeholder scan: No `TBD` or vague “handle later” instructions are required for the core path; optional migration/encryption are explicitly marked as rollout notes outside the MVP path.
- Type consistency: `AuthResponse`, `AuthUserResponse`, and frontend mapping use matching names: `accessToken`, `refreshToken`, `expiresIn`, and `user`.
