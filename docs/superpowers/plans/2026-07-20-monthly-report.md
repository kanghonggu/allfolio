# 월간 운용보고서 생성 엔진 (R-01) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `MonthlyReportGenerator`(type=MONTHLY_REPORT)를 #32 프레임에 등록 — 월간 성과(R-02+BM 재사용)·변동성·Top10 보유·익스포저·계좌별·입출금 분해를 담은 본문 JSON을 생성해 아카이브한다.

**Architecture:** unified-asset usecase 하나로 조립. 성과는 `GetReturnsAnalysisUseCase.analyze()` 재사용(월간+표준기간), 보유·익스포저·계좌는 AssetRepository/AccountRepository + NavCalculator KRW 헬퍼. 데이터 부족 기간은 키 생략, 월간 NAV 부족은 InsufficientDataException(프레임 관례 400).

**Tech Stack:** Kotlin/Spring · 기존 포트 재사용 (신규 DDL 없음)

**Spec:** `docs/superpowers/specs/2026-07-20-monthly-report-design.md`

---

### Task 1: MonthlyReportGenerator (TDD)

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/MonthlyReportGenerator.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/MonthlyReportGeneratorTest.kt`

- [ ] **Step 1 (RED)**: 테스트 — fake NavHistorySource/CashFlowRepository/UserBenchmarkLookup/BenchmarkDailyStore/AssetRepository/AccountRepository/FxConverter로 생성기를 조립. 케이스: ①5개 섹션 존재+월간 twr ②Top10 정렬·weight 합≈100 ③표준기간 데이터 부족 시 키 생략 ④월간 NAV<2 → InsufficientDataException ⑤BM 설정 시 performance.month.benchmark 존재
- [ ] **Step 2 (GREEN)**: 구현 — `GetReturnsAnalysisUseCase`를 주입받아 월간+표준기간 analyze 호출(표준기간 실패는 runCatching으로 생략), 변동성=월간 navSeries 구간 수익률 std×√252(관측<3 → null), 자산·계좌 섹션은 KRW 환산 정렬·비중, jacksonObjectMapper로 bodyJson 직렬화, asOfDate=월간 마지막 관측일
- [ ] **Step 3**: `./gradlew :unified-asset:test` 전체 통과 → 커밋 `feat(monthly): 월간 운용보고서 생성 엔진 — R-01 v1 (#32 프레임 등록)`

### Task 2: 스모크 + 마무리

- [ ] 로컬 기동 → 유저+NAV 시드+자산/계좌 시드+BM 설정 → `POST /api/reports/archive/generate {type: MONTHLY_REPORT}` → 본문 섹션·수치 검산, 재생성 upsert, `GET /{id}` 조회 → 정리
- [ ] push, PR, 노션 #36 진행 업데이트
