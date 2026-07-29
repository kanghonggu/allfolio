# ADMIN role (P0) — Design Spec

- **Date**: 2026-07-29
- **Status**: Approved (design), pending implementation
- **Priority**: P0 — prerequisite that unblocks report follow-ups (R-03 세율 마스터, R-07 배제리스트 관리, 기타 admin-gated SCR)
- **Scope**: Backend authorization (full) + Frontend role exposure & guard util. **Out of scope**: 승격 API, FX 어드민 화면, 풀 RBAC/permission 계층.

## 1. Background & Problem

현재 인증은 커스텀 stateless JWT(jjwt HS256)이고, **role 개념이 코드 어디에도 없다**:

- `JwtUserIdFilter`는 `PreAuthenticatedAuthenticationToken(userId, token, emptyList())` — authorities가 항상 비어 있음.
- `app_users` 테이블에 role 컬럼 없음, `UserEntity`에 role 필드 없음, JWT에 role claim 없음.
- `SecurityConfig`에서 `/api/admin/**` 는 `.denyAll()` — 아무도 접근 불가. 유일한 admin 컨트롤러 `FxRateAdminController`가 사실상 죽어 있음.
- `@EnableMethodSecurity` 미적용 → `@PreAuthorize`는 지금 붙여도 무시됨.

이 때문에 admin-gated 후속 기능들을 만들 수 없다. 이 작업은 **ADMIN을 실제로 동작시켜 후속을 언블록**하는 최소 변경이다.

## 2. Decisions (확정)

| 항목 | 결정 |
|---|---|
| Role 모델 | `app_users.role` **단일 enum 컬럼** (`USER` / `ADMIN`). 풀 RBAC/permission 미도입 (YAGNI) |
| 부트스트랩 | `init.sql` 정본 편집(신규/로컬 DB) + 운영 Neon엔 **1회성 수동 SQL**로 `rkdghd123@naver.com` 승격 |
| Enforcement | URL 기반(`/api/admin/**` → `hasRole('ADMIN')`) **+** `@EnableMethodSecurity` 병행(후속 `@PreAuthorize` 대비) |
| ADMIN 계정 | 기존 유일 실계정 `rkdghd123@naver.com` 승격 |
| FE 범위 | `AuthContext`에 role 노출 + `isAdmin` + `useRequireAdmin()` 가드 유틸 스캐폴딩. 실제 admin 라우트는 미생성(후속) |

## 3. Backend Design

### 3.1 Role enum
신규 파일 `com.allfolio.auth.UserRole`:
```kotlin
enum class UserRole { USER, ADMIN }
```

### 3.2 UserEntity + schema
`auth/UserEntity.kt` — role 필드 추가:
```kotlin
@Enumerated(EnumType.STRING)
@Column(name = "role", nullable = false, length = 20)
var role: UserRole = UserRole.USER,
```
- 기본값 `USER`로 신규 유저·기존 코드 경로 안전.
- `AuthService.register`는 별도 수정 불필요(엔티티 기본값이 `USER`). 단, 명시성을 위해 생성 시점에 의존하지 말고 기본값에 의존한다.

`infra/postgres/init.sql` — `app_users` CREATE TABLE(정본, 550줄 파일)에 컬럼 추가:
```sql
role VARCHAR(20) NOT NULL DEFAULT 'USER',
```

### 3.3 운영 DB 1회성 마이그레이션 (수동)
마이그레이션 러너 없음(Flyway/Liquibase 부재). `init.sql`은 docker `initdb.d`로 fresh DB에만 실행됨. 운영은 Neon → 로컬 psql로 수동 적용.

산출물: `docs/superpowers/migrations/2026-07-29-admin-role.sql` (아래 내용), 사용자가 Neon에 `/opt/homebrew/opt/libpq/bin/psql`로 실행:
```sql
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'USER';
UPDATE app_users SET role = 'ADMIN' WHERE email = 'rkdghd123@naver.com';
```
- `ADD COLUMN IF NOT EXISTS` + `DEFAULT 'USER'` → 기존 행 모두 안전하게 `USER`로 채워짐.
- **배포 순서 주의**: 백엔드 배포 전/직후에 이 SQL을 반드시 실행해야 함. 컬럼이 없는 상태로 새 엔티티가 `role`을 SELECT하면 실패. (`ddl-auto: none`이라 자동 생성 안 됨.) spec의 Rollout 섹션 참고.

### 3.4 JWT 파이프라인
`auth/JwtTokenService.kt`:
- `issue`에 role claim 추가:
  ```kotlin
  .claim("role", user.role.name)
  ```
- 신규 `parsePrincipal(token): JwtPrincipal` 도입 (userId + role 동시 파싱, 서명 1회 검증):
  ```kotlin
  data class JwtPrincipal(val userId: UUID, val role: UserRole)

  fun parsePrincipal(token: String): JwtPrincipal {
      val claims = Jwts.parser().verifyWith(key).build()
          .parseSignedClaims(token).payload
      val userId = try { UUID.fromString(claims.subject) }
                   catch (e: IllegalArgumentException) { throw JwtException("Invalid subject", e) }
      val role = claims["role"]?.toString()
          ?.let { runCatching { UserRole.valueOf(it) }.getOrNull() }
          ?: UserRole.USER   // 하위호환: role claim 없는 기존 토큰 = USER
      return JwtPrincipal(userId, role)
  }
  ```
- `parseUserId`는 유지(현재 유일 호출처는 filter이며 아래에서 교체되지만, 기존 테스트 호환 위해 남겨둔다). 삭제하지 않는다.

`config/JwtUserIdFilter.kt` — `parseUserId` → `parsePrincipal` 사용, authority 채움:
```kotlin
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
```
- **하위호환 핵심**: role claim이 없는 이미 발급된 토큰 → `parsePrincipal`이 `USER`로 폴백. 기존 세션이 crash 없이 계속 동작(단, admin 접근만 막힘).

### 3.5 SecurityConfig
`config/SecurityConfig.kt`:
- 클래스에 `@EnableMethodSecurity` 추가(후속 컨트롤러가 `@PreAuthorize("hasRole('ADMIN')")` 사용 가능).
- 매처 교체:
  ```kotlin
  .requestMatchers("/api/admin/**").hasRole("ADMIN")   // was .denyAll()
  ```
- 기존 `authenticationEntryPoint`(admin 경로 미인증 시 403)와 `accessDeniedHandler`(403)는 유지.
  - 무토큰 admin 접근 → entryPoint → **403**
  - USER 토큰 admin 접근 → accessDeniedHandler → **403**
  - ADMIN 토큰 → **통과(200)**

### 3.6 응답 DTO
`auth/AuthDtos.kt` — `AuthUserResponse`에 role 추가:
```kotlin
data class AuthUserResponse(
    val id: UUID,
    val email: String,
    val displayName: String?,
    val role: UserRole,
)
```
`AuthService.toResponse()` — `role = role` 매핑. login/register/refresh/me 응답 모두 role 포함.

## 4. Frontend Design

`contexts/AuthContext.tsx`:
- `AuthState`에 `role: string | null` 추가.
- `AuthApiResponse.user`에 `role: string` 추가.
- `toAuthState`에서 `role: data.user.role` 매핑.
- 초기 state·`logout` reset·localStorage 영속 모두 role 포함.
- context value에 파생값 `isAdmin: state.role === 'ADMIN'` 노출.

가드 유틸 — 신규 파일 `frontend/allfolio_app/lib/useRequireAdmin.ts` (또는 hooks 디렉터리 관례 따름):
```ts
// 비-admin 접근 시 리다이렉트. 후속 admin 화면이 페이지 최상단에서 호출.
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
- 실제 admin 라우트/화면은 이번 범위 밖. 유틸만 제공하여 후속 SCR이 바로 사용.

## 5. Tests

### 5.1 수정 — `test/.../config/SecurityConfigAdminTest.kt`
현재 "유효 토큰도 403" 4개 테스트가 오늘의 `denyAll()` 계약을 인코딩함. 새 계약으로 재작성:
- 무토큰 GET/PUT `/api/admin/fx/usdtkrw` → **403** (유지)
- **USER** 토큰 → **403** (신규)
- **ADMIN** 토큰 → **200** (신규, 기존 "유효 토큰도 403" 대체)
- `validToken()` 헬퍼를 `tokenFor(role: UserRole)`로 확장(`UserEntity(..., role = role)`).

### 5.2 신규 단위 테스트
- `JwtTokenService`: `issue`가 role claim을 넣고 `parsePrincipal`이 되읽는다(USER/ADMIN 왕복). **role claim 없는 토큰**(claim 누락 시나리오) → `parsePrincipal`이 `USER` 폴백.
- `JwtUserIdFilter`: ADMIN 토큰 → SecurityContext authorities에 `ROLE_ADMIN` 존재. USER 토큰 → `ROLE_USER`.
- `AuthService`(또는 엔티티): 신규 유저 기본 role = `USER`. 응답 DTO에 role 포함.

### 5.3 Frontend
테스트 셋업이 있으면 `isAdmin` 파생(ADMIN → true, USER/null → false) + `useRequireAdmin` 리다이렉트 로직 테스트. 없으면 최소 타입/빌드 통과 확인.

## 6. Rollout / 배포 순서

1. `docs/superpowers/migrations/2026-07-29-admin-role.sql`을 **운영 Neon에 먼저 수동 실행**(ALTER + UPDATE). 컬럼 default가 `USER`라 기존 백엔드에는 무해.
2. 백엔드 배포(role 읽는 새 코드). 이제 컬럼 존재하므로 정상.
3. 프론트 배포.
4. 검증: `rkdghd123@naver.com` 로그인 → JWT에 `role=ADMIN` → `GET /api/admin/fx/usdtkrw` 200. 다른(신규) USER 계정 → 동일 요청 403.

> 순서 주의: 백엔드를 먼저 배포하면 컬럼 부재로 조회 실패 가능 → **SQL 먼저**.

## 7. Affected Files (요약)

**Backend**
- 신규 `auth/UserRole.kt`
- `auth/UserEntity.kt` — role 필드
- `auth/JwtTokenService.kt` — role claim + `parsePrincipal`/`JwtPrincipal`
- `config/JwtUserIdFilter.kt` — authority 채움
- `config/SecurityConfig.kt` — `@EnableMethodSecurity`, `/api/admin/**` → `hasRole('ADMIN')`
- `auth/AuthDtos.kt` + `auth/AuthService.kt` — 응답에 role
- `infra/postgres/init.sql` — app_users role 컬럼
- 신규 `docs/superpowers/migrations/2026-07-29-admin-role.sql`
- `test/.../config/SecurityConfigAdminTest.kt` — 재작성 + 신규 role 테스트들

**Frontend**
- `contexts/AuthContext.tsx` — role/isAdmin
- 신규 `lib/useRequireAdmin.ts` — 가드 유틸

## 8. Out of Scope (후속)
- 승격 API (ADMIN이 다른 유저 role 변경)
- FX 어드민 화면(SCR) 등 실제 admin UI
- 풀 RBAC(roles/user_roles/permissions M2M)
- ADMIN role을 요구하는 개별 후속 컨트롤러(R-03/R-07 등) — 이 spec은 그 게이트 인프라만 제공
