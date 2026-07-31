# R-07 배제 프리셋 큐레이션 (ADMIN) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development.

**Goal:** 하드코딩 `EsgExclusionPreset` → DB 테이블 + ADMIN CRUD로 이관, 소비처를 리포지토리 기반으로 스왑.

**Architecture:** #51 세율마스터 템플릿(도메인·포트·엔티티·JPA·impl·서비스·ADMIN 컨트롤러·마이그레이션·FE 어드민). 소비처 2곳(ExclusionListService·EsgScreeningReportGenerator)이 `ExclusionPresetRepository.findAll()` 사용.

**Tech Stack:** Kotlin/Spring(unified-asset·backend-app), JPA, JUnit, Next.js/React/TS.

Spec: `docs/superpowers/specs/2026-07-31-exclusion-preset-curation-design.md`
Base: `main`(cca9c64+). gradle=`/Users/hong9/IdeaProjects/allfolio/allfolio-backend`, FE=`/Users/hong9/IdeaProjects/allfolio/frontend/allfolio_app`.

---

## Task 1: 도메인 + 포트 + 영속화 + 마이그레이션

**Files (신규):**
- `unified-asset/.../domain/exclusion/ExclusionPreset.kt`
- `unified-asset/.../application/port/ExclusionPresetRepository.kt`
- `unified-asset/.../infrastructure/entity/ExclusionPresetEntity.kt`
- `unified-asset/.../infrastructure/jpa/ExclusionPresetJpaRepository.kt`
- `unified-asset/.../infrastructure/repository/ExclusionPresetRepositoryImpl.kt`
- `allfolio-backend/infra/postgres/init.sql`(수정)
- `docs/superpowers/migrations/2026-07-31-exclusion-presets.sql`(신규)

- [ ] **Step 1: 도메인** — `ExclusionPreset.kt`(spec §3.1 data class). package `com.allfolio.unifiedasset.domain.exclusion`.

- [ ] **Step 2: 포트** — `ExclusionPresetRepository.kt`(spec §3.2). package `com.allfolio.unifiedasset.application.port`.

- [ ] **Step 3: 엔티티/JPA/impl** — 기존 배제리스트/세율 엔티티(`infrastructure/entity/TaxRateEntity.kt`, `ExclusionListEntity` 등)를 참고해 동일 스타일:
  - `ExclusionPresetEntity`: `@Entity @Table(name="exclusion_presets")`, 컬럼 id·symbol(unique는 DB 제약)·list_name·reason·updated_by·created_at·updated_at. `toDomain()`/`from(domain)`.
  - `ExclusionPresetJpaRepository : JpaRepository<ExclusionPresetEntity, UUID>` + `fun findBySymbol(symbol: String): ExclusionPresetEntity?`.
  - `ExclusionPresetRepositoryImpl(@Repository)(private val jpa: ExclusionPresetJpaRepository)` — findAll/findBySymbol/save(from→save→toDomain)/delete(deleteById).

- [ ] **Step 4: init.sql** — `exclusion_presets` CREATE TABLE(IF NOT EXISTS) + UNIQUE(symbol) + 시드 2건(EXCL-COAL-01 "예시 프리셋"/"석탄", EXCL-WEAPON-01 "예시 프리셋"/"논란무기"). 배제리스트 테이블 근처에 배치.

- [ ] **Step 5: 마이그레이션** — `2026-07-31-exclusion-presets.sql`(자립형·멱등):
```sql
-- R-07 배제 프리셋 큐레이션 — 운영 Neon 1회성 (백엔드 배포 "전" 실행). 자립형·멱등.
CREATE TABLE IF NOT EXISTS exclusion_presets (
    id          UUID         PRIMARY KEY,
    symbol      VARCHAR(40)  NOT NULL,
    list_name   VARCHAR(100) NOT NULL,
    reason      VARCHAR(200) NOT NULL,
    updated_by  UUID,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_exclusion_presets_symbol UNIQUE (symbol)
);
INSERT INTO exclusion_presets (id, symbol, list_name, reason) VALUES
  (gen_random_uuid(), 'EXCL-COAL-01',   '예시 프리셋', '석탄'),
  (gen_random_uuid(), 'EXCL-WEAPON-01', '예시 프리셋', '논란무기')
ON CONFLICT (symbol) DO NOTHING;
SELECT symbol, list_name, reason FROM exclusion_presets ORDER BY symbol;
```

- [ ] **Step 6: 컴파일** — Run: `./gradlew :unified-asset:compileKotlin` → BUILD SUCCESSFUL.
- [ ] **Step 7: 커밋** — `git add` 신규 6파일 + init.sql + 마이그레이션 → `git commit -m "feat(esg): exclusion_presets table + domain/repo (R-07)"`.

---

## Task 2: ExclusionPresetService + 테스트

**Files:**
- Create: `unified-asset/.../application/usecase/ExclusionPresetService.kt`
- Test: `unified-asset/.../application/usecase/ExclusionPresetServiceTest.kt`

- [ ] **Step 1: 실패 테스트** — `ExclusionPresetServiceTest.kt`(assertj), fake `ExclusionPresetRepository`(in-memory map by symbol):
  - upsert 신규 → insert(symbol 대문자 정규화 "excl-x"→"EXCL-X"); 동일 symbol 재upsert → update(listName/reason 갱신, id 유지); 빈 symbol/listName → IllegalArgumentException; delete → 제거; list → 전체.
- [ ] **Step 2: 실패 확인** — `./gradlew :unified-asset:test --tests "*ExclusionPresetServiceTest*"` → compile FAIL.
- [ ] **Step 3: 구현** — `ExclusionPresetService.kt`(spec §3.4).
- [ ] **Step 4: 통과 확인** — 동일 명령 → PASS.
- [ ] **Step 5: 커밋** — `git commit -m "feat(esg): ExclusionPresetService upsert/list/delete (TDD)"`.

---

## Task 3: ADMIN 컨트롤러

**Files:**
- Create: `backend-app/src/main/kotlin/com/allfolio/api/admin/ExclusionPresetAdminController.kt`

- [ ] **Step 1: 구현** — `TaxRateAdminController.kt`(같은 패키지 `com.allfolio.api.admin`) 골격 복제:
  - `@RestController @RequestMapping("/api/admin/exclusion-presets")`, ctor `(exclusionPresetService: ExclusionPresetService)`.
  - `GET` list→`List<ExclusionPresetResponse>`; `POST`(`@RequestHeader("X-User-Id") adminId`, body `UpsertPresetRequest{symbol,listName,reason}`)→upsert→Response; `DELETE /{id}`→noContent.
  - `ExclusionPresetResponse(id, symbol, listName, reason, updatedBy, updatedAt)` + `toResponse()`.
- [ ] **Step 2: 컴파일** — `./gradlew :backend-app:compileKotlin` → SUCCESSFUL.
- [ ] **Step 3: 커밋** — `git commit -m "feat(esg): ADMIN exclusion-presets CRUD controller"`.

---

## Task 4: 소비처 스왑 + 하드코딩 제거 + 테스트 조정

**Files:**
- Modify: `ExclusionListService.kt`, `EsgScreeningReportGenerator.kt`
- Delete: `EsgExclusionPreset.kt`
- Modify tests: `ExclusionListServiceTest.kt`, `EsgScreeningReportGeneratorTest.kt`

- [ ] **Step 1: 소비처 수정**
  - `ExclusionListService` 생성자에 `private val exclusionPresetRepository: ExclusionPresetRepository` 추가. `presets()` → `exclusionPresetRepository.findAll().groupBy { it.listName }.map { (ln, ps) -> PresetView(ln, ps.map { PresetSymbol(it.symbol, it.reason) }) }`.
  - `EsgScreeningReportGenerator` 생성자에 `private val exclusionPresetRepository: ExclusionPresetRepository` 추가(6번째). line 62 `EsgExclusionPreset.entries.forEach { ... }` → `exclusionPresetRepository.findAll().forEach { lookup[it.symbol] = it.listName to it.reason }`.
- [ ] **Step 2: 하드코딩 삭제** — `EsgExclusionPreset.kt` 삭제(`ExclusionEntry`도 이 파일에만 있으니 함께 삭제). 삭제 후 잔여 import·참조 없는지 `grep -rn "EsgExclusionPreset\|ExclusionEntry"` 확인.
- [ ] **Step 3: 테스트 조정** — 두 테스트가 하드코딩 프리셋에 의존하므로:
  - fake `ExclusionPresetRepository`(예시 2건 또는 테스트별 프리셋 반환)를 생성자에 주입하도록 셋업 수정.
  - 기존 단언이 "예시 프리셋"/석탄·논란무기를 기대하면 fake가 동일 데이터를 공급하도록 구성 → 기존 단언 유지. (파일 스타일에 맞춰 최소 수정.)
- [ ] **Step 4: 통과 확인** — `./gradlew :unified-asset:test --tests "*EsgScreeningReportGeneratorTest*" --tests "*ExclusionListServiceTest*" --tests "*ExclusionPresetServiceTest*"` → PASS.
- [ ] **Step 5: 커밋** — `git commit -m "refactor(esg): read presets from DB repo, remove hardcoded EsgExclusionPreset"`.

---

## Task 5: 백엔드 회귀
- [ ] `./gradlew :unified-asset:test` → BUILD SUCCESSFUL.
- [ ] `./gradlew :backend-app:compileKotlin` → SUCCESSFUL(DI 무결성: ExclusionPresetRepositoryImpl 빈이 소비처에 주입).
- [ ] (실패 시) 수정 후 재실행. 커밋 불필요(그린이면).

---

## Task 6: Frontend — API + 훅 + 타입 + 페이지 + nav

**Files:**
- Create: `frontend/allfolio_app/lib/exclusion-preset-admin-api.ts`, `types/exclusion-preset.ts`, `app/unified/admin/exclusion-presets/page.tsx`
- Modify: `frontend/allfolio_app/lib/useApi.ts`, `components/NavBar.tsx`

- [ ] **Step 1: 타입 + API** — `tax-rate-admin-api.ts`를 템플릿으로 `exclusion-preset-admin-api.ts`(`createExclusionPresetAdminApi(token)`: `list()`→GET ''; `upsert(req)`→POST ''; `remove(id)`→DELETE `/${id}`; BASE `/api/admin/exclusion-presets`). `types/exclusion-preset.ts`(spec §4). `useApi.ts`에 `useExclusionPresetAdminApi()` 훅(기존 `useReportArchiveApi` 등과 동일 패턴).
- [ ] **Step 2: 페이지** — `app/unified/admin/exclusion-presets/page.tsx`. `app/unified/admin/tax-rates/page.tsx`를 템플릿(`useRequireAdmin`·react-query·다크테마)으로: 목록 표(symbol·listName·reason·수정일) + 추가/수정 폼(symbol·listName·reason, symbol 입력 시 upsert) + 삭제 버튼. 성공 시 invalidate.
- [ ] **Step 3: NavBar** — `{isAdmin && (...)}` 블록에 tax-rates 링크 옆에 `<Link href="/unified/admin/exclusion-presets" className="text-sm text-amber-400 hover:text-amber-300 transition-colors">배제 프리셋</Link>` 추가.
- [ ] **Step 4: 타입체크** — `cd /Users/hong9/IdeaProjects/allfolio/frontend/allfolio_app && npx tsc --noEmit` → no errors. (Map/Set 스프레드 금지.)
- [ ] **Step 5: 커밋** — `git add` FE 5파일 → `git commit -m "feat(esg-fe): ADMIN exclusion-presets management page + nav"`.

---

## Self-Review 체크
- [ ] 도메인·포트·엔티티·서비스·컨트롤러 시그니처 일관.
- [ ] 소비처 2곳 DB 소스 스왑·하드코딩 완전 제거(grep clean).
- [ ] 기존 테스트가 fake 프리셋으로 동일 동작 유지.
- [ ] 마이그레이션 자립형·멱등·시드. init.sql 반영.
- [ ] FE ADMIN 가드(useRequireAdmin)·nav 링크·tsc clean.

## Rollout (승인 게이트)
- **스키마 변경(신규 테이블)** → 배포 전 `docs/superpowers/migrations/2026-07-31-exclusion-presets.sql` Neon 수동 실행. 이후 main 병합.
