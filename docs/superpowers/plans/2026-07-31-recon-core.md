# P2 대사·검증 엔진 코어 (#12·#13) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `reconciliation` 신규 모듈 + recon_ 4테이블 + 코드 룰 엔진·검증 룰 4종 + USER 스코프 실행/조회 API.

**Architecture:** v2 스펙(`docs/superpowers/specs/2026-07-31-reconciliation-engine-v2-design.md`) — 룰=Spring 빈 List 주입(OCP), 원천 데이터는 JdbcTemplate 읽기 전용 네이티브 쿼리(타 모듈 코드 의존 0), run/summary/detail 이력 영속.

**Tech Stack:** Kotlin/Spring(JPA+JdbcTemplate), JUnit5+Mockito(수기 fake), 자립형 마이그레이션.

**후속 PR(이 플랜 범위 밖):** #14/15 PositionReconRule(AssetIdDeriver 포함) → #16 KD → #17 Redis 락 → FE.

---

## PR A — `feat/recon-schema` (#12)

### Task 1: 모듈 골격
- [ ] `settings.gradle.kts`에 `":reconciliation"` 추가, `reconciliation/build.gradle.kts`(report 모듈과 동일: jvm/spring/jpa 플러그인 + data-jpa + jackson-kotlin + kotlin-reflect + starter-test)
- [ ] `backend-app/build.gradle.kts`에 `implementation(project(":reconciliation"))`
- [ ] 패키지: `com.allfolio.reconciliation.{domain,application,infrastructure,api는 backend-app}`

### Task 2: 스키마 + 엔티티 + 리포지토리
- [ ] `infra/postgres/init.sql`에 recon_run/recon_result_summary/recon_result_detail/recon_kd (스펙 §2 DDL 그대로) + `docs/superpowers/migrations/2026-07-31-recon.sql` 자립형·멱등
- [ ] 엔티티 4종(`infrastructure/entity`, GoalEntity 스타일 — 도메인 클래스는 run/summary/detail은 생략하고 엔티티 직접 사용, KD만 도메인 클래스(버저닝 로직 보유))
- [ ] `ReconRunJpaRepository`(findByUserIdOrderByRunDateDesc + Pageable), `ReconResultSummaryJpaRepository`(findByRunId), `ReconResultDetailJpaRepository`(findBySummaryIdIn + ruleCode/symbol 필터는 애플리케이션 계층), `ReconKdJpaRepository`(findByUserIdAndUseYnTrue)
- [ ] `./gradlew :reconciliation:compileKotlin` 그린 → Commit `feat(recon): reconciliation 모듈 + recon_ 스키마 (P2 #12)`

## PR B — `feat/recon-engine` (#13, PR A 위 스택)

### Task 3: 룰 계약 + 컨텍스트
```kotlin
enum class RuleKind { VALIDATION, RECONCILIATION }
enum class DiffType { VALUE_MISMATCH, MISSING_INTERNAL, MISSING_EXTERNAL, RULE_VIOLATION }

data class ReconContext(val userId: UUID, val runDate: LocalDate)

data class RuleDiff(
    val symbol: String?, val fieldName: String?, val diffType: DiffType,
    val internalValue: BigDecimal?, val externalValue: BigDecimal?, val diffValue: BigDecimal?,
    val extras: Map<String, String> = emptyMap(),
)
data class RuleResult(val checkedCnt: Int, val diffs: List<RuleDiff>)

interface ReconRule {
    val code: String
    val kind: RuleKind
    fun execute(ctx: ReconContext): RuleResult
}
```

### Task 4: 검증 룰 4종 (JdbcTemplate 네이티브, 각 @Component)
- [ ] `NegativeQuantityRule`(NEGATIVE_QUANTITY): `SELECT symbol, quantity FROM ua_assets WHERE user_id=? AND quantity<0` → RULE_VIOLATION(field=quantity, external=quantity). checked = user 자산 행 수
- [ ] `StaleSyncRule`(STALE_SYNC): `ua_accounts WHERE user_id=? AND provider IN (KIS,BINANCE,UPBIT,BITHUMB,COINONE,BYBIT,OKX,WALLET,STOCK)`(DailyAccountSyncer.SYNC_ELIGIBLE_PROVIDERS 복제 — KDoc 명시). status='ERROR' 또는 last_synced_at null 또는 < now-26h → RULE_VIOLATION(extras: accountName/provider/status/lastSyncedAt). 임계 26h companion 상수
- [ ] `DuplicateTradeRule`(DUPLICATE_TRADE): `trade_raw t JOIN portfolios p ON t.portfolio_id=p.id WHERE p.user_id=? AND p.deleted_at IS NULL AND t.executed_at >= runDate-7d` — (asset_id, trade_type, quantity, price, executed_at) GROUP BY HAVING count>1 → RULE_VIOLATION(extras: assetId/count/executedAt)
- [ ] `SnapshotMissingRule`(SNAPSHOT_MISSING): 유저 포트폴리오 중 `trade_raw`에 runDate 이전 거래가 있는데 `position_daily`에 runDate(정확히는 ≤runDate 최근 date) 행이 전무한 포트폴리오 → RULE_VIOLATION(extras: portfolioId). v1: runDate 당일 행 부재 기준
- [ ] 각 룰 단위 테스트: JdbcTemplate mock(query 결과 주입) → 판정·diff 매핑 검증

### Task 5: ReconEngine + 실행 유스케이스
- [ ] `ReconRunService.execute(userId, runDate, runType, trigger)`:
  run RUNNING 저장 → `rules.filter(kind)` 순차 실행 — 룰별 `runCatching`: 성공 → summary(status=PASSED/DIFF_FOUND, checked/diff cnt)+detail(상한 100, 초과분 카운트만), 예외 → summary FAILED(error_msg 500자 절단) → 전 룰 FAILED면 run FAILED 아니면 COMPLETED, finished_at 기록. `internal_as_of=runDate`, `external_as_of=min(ua_accounts.last_synced_at)` 기록
- [ ] 테스트: fake 룰 2개(성공/예외)로 격리·상태 전이·detail 절단 검증

### Task 6: API (backend-app `api/recon/ReconController`)
- [ ] `POST /api/recon/runs` {runDate, runType} → run 요약 / `GET /api/recon/runs?from&to`(최근 50) / `GET /api/recon/runs/{id}`(summary 목록+as-of) / `GET /api/recon/runs/{id}/details?ruleCode&symbol` — 전부 X-User-Id 소유권(run.userId 대조, 404 은닉 — AccountControllerSecurityTest 패턴)
- [ ] 컨트롤러 시큐리티 테스트(본인 200/타인 404)
- [ ] `./gradlew :backend-app:test :reconciliation:test` 그린 → Commit → PR

## 검증
- [ ] 회귀: `:reconciliation:test :backend-app:test :unified-asset:test` + FE 영향 없음
- [ ] 라이브: 마이그레이션 로컬 적용 → livetest로 `POST /api/recon/runs` → 검증 룰 4종 summary 확인(음수수량 시드로 RULE_VIOLATION 유발) → 노션 #12·#13 갱신
