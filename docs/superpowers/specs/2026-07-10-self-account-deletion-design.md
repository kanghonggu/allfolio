# 자기 계정 삭제 API 설계

- 날짜: 2026-07-10
- 상태: 승인됨 (구현 전)
- 범위: 인증된 사용자가 자기 계정과 소유한 모든 데이터를 완전 삭제(hard delete)하는
  `DELETE /api/auth/me` 엔드포인트

## 배경과 목적

계정 삭제 API가 없어서, 스모크/부하 테스트가 회원가입 API로 만든 throwaway 계정을
스스로 정리하지 못하고 운영 DB에 누적됐다(codex-smoke*/local-smoke*/k6-load-test 등).
이 기능은 두 가지를 동시에 만족한다:

- **테스트 자동 정리**: 테스트 하네스가 계정을 만들고 끝에 스스로 삭제(teardown).
- **실사용자 회원 탈퇴**: 금융 앱이므로 암호화된 민감정보(브로커 OAuth 토큰, AI API 키)를
  포함해 완전 파기(GDPR식 erasure).

## 데이터 모델 사실 (설계 근거)

- `app_users`에는 `deleted_at`이 없다 → soft delete는 스키마 변경 필요.
- FK ON DELETE CASCADE는 3개뿐: `app_refresh_tokens→app_users`,
  `ua_assets→ua_accounts`, `ua_stock_trades→ua_accounts`.
- 나머지 사용자 소유 테이블(`portfolios`, `trade_raw`, `position/performance/risk_daily`,
  `broker_sync_state`, `broker_auth`, `ua_accounts`, `ua_goals`, `ua_ai_configs`)은
  `app_users`를 FK로 참조하지 않고 `user_id`/`portfolio_id`를 UUID 컬럼으로만 들고 있다
  (모듈 경계상 느슨한 결합). → app_users 행만 지우면 refresh_token만 cascade되고
  금융 데이터는 전부 고아로 남는다.
- `trade_raw`는 `@Immutable` + INSERT-ONLY, "삭제 금지"가 도메인·엔티티·리포지토리에
  명시돼 있다(리포지토리에 삭제 메서드 없음).
- 실 DB 통합 테스트 인프라(H2/testcontainers)가 없다. Postgres는 runtime 의존뿐.

## 결정 사항

- **하드 삭제 — 계정 + 소유한 모든 데이터 완전 제거.** soft delete/최소 삭제는 "잔재 없는
  정리" 목적을 못 이룸.
- **`trade_raw`도 탈퇴 시 예외적으로 삭제.** "원장 영구보존" 불변식의 정당한 예외로 보되,
  삭제 메서드를 일반 경로에 노출하지 않고 계정-종료 전용으로 격리한다.
- **비밀번호 재확인 요구.** 되돌릴 수 없는 파기이므로 세션 탈취/CSRF 방어막을 둔다.
- **오케스트레이션은 격리된 네이티브 삭제(1안).** `trade_raw` 예외를 서비스 한 곳에만 가둬
  불변식을 인터페이스 수준에서 보존한다. cross-context FK cascade 마이그레이션(3안)은
  모듈 경계 위반·라이브 마이그레이션 위험으로 기각. 모듈별 삭제 메서드 분산(2안)은
  `TradeRawJpaRepository`에 삭제 메서드를 노출시켜 불변식을 약화하므로 기각.

## API + 인증 흐름

- **엔드포인트**: `DELETE /api/auth/me` (`AuthController`).
  `@RequestHeader("X-User-Id") userId: UUID` + `@RequestBody DeleteAccountRequest(password: String)`.
  성공 시 **204 No Content**.
- **보안 설정 변경 없음**: `/api/auth/register|login|refresh`만 permitAll이고 나머지
  `/api/**`는 `anyRequest().authenticated()` — `DELETE /api/auth/me`는 이미 JWT 인증 강제.
- **`AuthService.deleteAccount(userId, password)`** (`@Transactional`):
  1. user 조회 — 없으면 기존 `me()`와 동일한 예외.
  2. `passwordEncoder.matches(password, user.passwordHash)` 실패 시 예외(삭제 안 함).
  3. 통과 시 `accountDeletionService.purge(userId)` 위임.
  비밀번호 검증은 auth가 소유, 실제 파기는 오케스트레이터가 담당(분리).

## 파기 오케스트레이션

### AccountPurgeRepository (신규, backend-app Spring Data 인터페이스)

테이블별 `@Modifying @Query(nativeQuery = true)` 삭제 메서드를 모은다.
**`trade_raw` 삭제 메서드가 여기에만 존재** → `TradeRawJpaRepository`는 삭제 메서드 없는
채로 유지되어 "원장 삭제 금지" 불변식이 인터페이스 수준에서 보존된다. 선언적 `@Query`라
오케스트레이션을 목으로 테스트할 수 있다.

### AccountDeletionService.purge(userId) (신규, @Transactional)

`portfolios.user_id`로 portfolioId 목록을 조회한 뒤 FK 안전 순서로 삭제:

1. `broker_auth` (user_id) — 암호화 OAuth 토큰(PII)
2. `ua_ai_configs` (user_id) — 암호화 API 키(PII)
3. `ua_goals` (user_id)
4. `ua_accounts` (user_id) — FK cascade로 `ua_assets`·`ua_stock_trades` 자동 삭제
5. `risk_daily` / `performance_daily` / `position_daily` (portfolio_id ∈ 목록)
6. `broker_sync_state` (portfolio_id ∈ 목록)
7. `trade_raw` (portfolio_id ∈ 목록) — 격리된 예외 삭제
8. `portfolios` (user_id)
9. `app_users` (id) — FK cascade로 `app_refresh_tokens` 자동 삭제

portfolioId 목록이 비면 portfolio 기반 삭제(5–7)는 빈 목록으로 안전 처리하고, user 기반
삭제(1–4, 8, 9)는 정상 수행한다.

### Redis 캐시

각 portfolioId의 `pnl:positions:{id}` 해시를 evict. `PositionCacheService.evictPortfolio(portfolioId)`
(= `redisTemplate.delete(positionKey(portfolioId))`)를 추가해 호출. 커밋 후 스테일-빈 캐시는
재조회/재부팅 시 자연 복원되므로 만에 하나 롤백돼도 무해하다.

### 참고

이 DELETE들은 계정 정리 작업 때 운영 Neon DB에 실제로 성공 실행된 바로 그 문장들이라
SQL 자체는 검증돼 있다.

## 테스트 전략

실 DB 통합 인프라가 없으므로 오케스트레이션은 목으로 검증하고, 네이티브 SQL의 실제 행
삭제는 수동 검증(운영 실행 이력)에 의존한다.

1. **AuthServiceDeleteAccountTest** (단위, 목):
   - 비밀번호 불일치 → 예외, `purge` 호출 안 됨(verify never)
   - 올바른 비밀번호 → `purge(userId)` 정확히 1회
   - 존재하지 않는 user → 기존 me()와 동일한 예외

2. **AccountDeletionServiceTest** (단위, `AccountPurgeRepository`·`PositionCacheService` 목):
   - 9개 삭제 메서드가 FK 안전 순서로 호출됨을 `InOrder`로 검증(자식이 부모보다 먼저)
   - 조회된 각 portfolioId마다 `evictPortfolio` 호출 검증
   - portfolio 0개 계정 → portfolio 기반 삭제는 빈 목록, user 기반 삭제는 정상 호출

3. **웹 계층** (기존 SecurityConfig MockMvc 스타일):
   `DELETE /api/auth/me` — 인증 없으면 401, 인증 + 서비스 목 연결 시 204.

### 검증 한계

네이티브 삭제문의 실제 행 제거는 CI에서 자동 검증되지 않는다(실 DB 인프라 부재). 배포 후
실계정 스모크로 확인하거나, testcontainers 도입을 별도 후속 과제로 남긴다.

## 커밋 분리

1. `AccountPurgeRepository` + `AccountDeletionService` + `AccountDeletionServiceTest`
2. `PositionCacheService.evictPortfolio` 추가
3. `AuthService.deleteAccount` + `DeleteAccountRequest` DTO + 컨트롤러 엔드포인트 + auth 테스트

각 단계 후 `./gradlew :backend-app:test` 통과 게이트.
