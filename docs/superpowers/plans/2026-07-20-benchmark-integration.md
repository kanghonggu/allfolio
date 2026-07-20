# 벤치마크 연동 + 사용자 BM 설정 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Yahoo 차트 API로 SPX·KOSPI·BTC 일별 종가를 benchmark_daily에 백필·일일 수집하고, 사용자 BM 설정(user_benchmark)과 R-02 분석 응답의 BM 비교(수익률·초과수익·정규화 시계열)를 붙여 수익률 화면의 BM 카드·선을 활성화한다.

**Architecture:** 수집·설정·비교 전부 unified-asset. benchmark_daily/user_benchmark 접근은 JdbcTemplate(스냅샷 모듈 비의존 관례). 기존 BenchmarkCollector(티크 프록시)는 삭제. FE는 returns 화면에 BM 셀렉트·카드·라인 추가.

**Tech Stack:** Kotlin/Spring · Yahoo Finance chart API(무인증) · JdbcTemplate · Next.js/recharts

**Spec:** `docs/superpowers/specs/2026-07-20-benchmark-integration-design.md`

---

### Task 1: DDL — user_benchmark + 계정 파기 (TDD)

- [x] init.sql 끝에 추가:

```sql
-- ── user_benchmark ─────────────────────────────────────────────
-- 사용자 벤치마크 설정 (R1 #35): R-01/R-02 "BM 대비"의 기준 지수
CREATE TABLE IF NOT EXISTS user_benchmark (
    user_id     UUID        NOT NULL,
    index_type  VARCHAR(20) NOT NULL,
    updated_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_user_benchmark PRIMARY KEY (user_id)
);
```

- [x] AccountDeletionServiceTest 두 테스트에 `deleteUserBenchmark(userId)` 검증 추가(RED) → AccountPurgeRepository에 `DELETE FROM user_benchmark WHERE user_id = :userId` + AccountDeletionService 호출 추가(GREEN) → 테스트 → 커밋 `feat(benchmark): user_benchmark DDL + 계정 파기 연결`

### Task 2: 수집 — BenchmarkType·Yahoo 히스토리·BenchmarkSyncService (TDD)

**Files:** unified-asset에
- Create: `domain/benchmark/BenchmarkType.kt` — `enum class BenchmarkType(val yahooTicker: String, val label: String) { SPX("^GSPC","S&P 500"), KOSPI("^KS11","KOSPI"), BTC("BTC-USD","Bitcoin") }`
- Modify: `infrastructure/adapter/YahooFinanceClient.kt` — `getDailyHistory(ticker: String, range: String): List<Pair<LocalDate, BigDecimal>>` (chart API의 `timestamp[]` + `indicators.quote[0].close[]` 파싱, null close 스킵)
- Create: `application/port/BenchmarkHistoryClient.kt` — 포트 `fun dailyHistory(type: BenchmarkType, range: String): List<Pair<LocalDate, BigDecimal>>` (+ Yahoo 구현 `YahooBenchmarkHistoryClient`)
- Create: `application/port/BenchmarkDailyStore.kt` — 포트 `fun latestDate(type): LocalDate?` / `fun upsert(type, rows)` / `fun series(type, from, to): List<Pair<LocalDate, BigDecimal>>` (+ Jdbc 구현)
- Create: `application/usecase/BenchmarkSyncService.kt`:

```kotlin
@Service
class BenchmarkSyncService(
    private val historyClient: BenchmarkHistoryClient,
    private val store: BenchmarkDailyStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun onStartup() = runCatching { syncAll() }.onFailure { log.warn("benchmark startup sync failed: ${it.message}") }

    @Scheduled(cron = "0 10 1 * * *")
    fun daily() = syncAll()

    fun syncAll() {
        BenchmarkType.entries.forEach { type ->
            val latest = store.latestDate(type)
            val range = if (latest == null || latest.isBefore(LocalDate.now().minusDays(30))) "1y" else "1mo"
            val rows = historyClient.dailyHistory(type, range)
            if (rows.isEmpty()) { log.warn("benchmark sync: no data for $type"); return@forEach }
            store.upsert(type, rows)
            log.info("benchmark sync: $type +${rows.size} rows (range=$range)")
        }
    }
}
```

- 삭제: `backend-app/src/main/kotlin/com/allfolio/market/BenchmarkCollector.kt`
- Test(RED→GREEN): `BenchmarkSyncServiceTest` — ①빈 store → range=1y ②최신 store → 1mo ③빈 응답 → upsert 안 함
- 커밋 `feat(benchmark): Yahoo 히스토리 기반 벤치마크 수집기 (티크 프록시 대체)`

### Task 3: 사용자 BM 설정 API

- Create: `application/usecase/UserBenchmarkService.kt` — JdbcTemplate로 `get(userId): BenchmarkType?` / `set(userId, type?)` (null=DELETE, 아니면 UPSERT)
- Create: `api/BenchmarkConfigController.kt` — `GET /api/benchmark-config` → `{"indexType": "SPX"|null, "available": [{type,label}...]}`, `PUT` body `{"indexType": ...}` (X-User-Id 관례)
- 빌드 확인 → 커밋 `feat(benchmark): 사용자 BM 설정 API`

### Task 4: R-02 분석 BM 비교 (TDD)

- `GetReturnsAnalysisUseCase`에 `UserBenchmarkService`·`BenchmarkDailyStore` 주입, `ReturnsAnalysis.benchmark: BenchmarkComparison?` 추가:

```kotlin
data class BenchmarkComparison(
    val indexType: String,
    val label: String,
    val periodReturn: BigDecimal?,
    val excessReturn: BigDecimal?,
    val series: List<NavPoint>,
)
```

- 로직: BM 미설정 → null. 설정 시 `store.series(type, from, to)` — 2건 미만 → null. 아니면 periodReturn=(last/first−1), excess=twr−periodReturn(twr null이면 null), series=startNav×(close/close_first)
- Test: BM 설정+데이터 → 값 검증 / 미설정 → null / 데이터 1건 → null
- 커밋 `feat(benchmark): 수익률 분석에 BM 비교(수익률·초과·정규화 시계열)`

### Task 5: FE — BM 셀렉트·카드·라인

- `types/returns.ts`: `BenchmarkComparison` + `ReturnsAnalysis.benchmark?` + `BenchmarkConfig` 타입
- `lib/benchmark-api.ts` + `useBenchmarkApi()`: get/set config
- returns 화면: 기간 선택바에 BM 셀렉트(미설정/사용 가능 목록, 변경 시 PUT→invalidate), benchmark 있으면 요약 카드 뒤에 `BM 수익률`·`초과수익(%p)` 카드 2개, NAV 차트에 `bm` 데이터 병합 후 회색 점선 Line
- `tsc --noEmit` + `next build` → 커밋 `feat(benchmark-fe): 수익률 화면 BM 셀렉트·카드·비교선`

### Task 6: 스모크 + 마무리

- 로컬 기동(도커 PG + user_benchmark DDL) → 실 Yahoo로 벤치마크 백필 확인 → NAV 시드 유저로 BM 설정 → 화면에서 BM 카드·선 확인 → 정리
- push, PR, 노션 #35 진행 업데이트
