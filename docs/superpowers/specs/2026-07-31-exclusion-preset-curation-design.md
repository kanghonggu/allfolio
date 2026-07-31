# R-07 배제 프리셋 큐레이션 (ADMIN) Design Spec

- **Date**: 2026-07-31
- **Status**: Approved (design), pending implementation
- **Scope**: 하드코딩된 내장 배제 프리셋(`EsgExclusionPreset`)을 **DB 테이블 + ADMIN 관리**로 이관. 테이블/도메인/리포지토리 + ADMIN CRUD API + 소비처 스왑(ExclusionListService·EsgScreeningReportGenerator) + FE ADMIN 화면 + 마이그레이션(시드 포함).
- **Depends on**: ADMIN role(#50, 머지됨)·배제리스트(#52, 머지됨). `main`(cca9c64+)에서 분기. **패턴 템플릿 = #51 세율마스터**(DB 테이블+ADMIN 컨트롤러+FE 어드민+마이그레이션).
- **Out of scope**: 유효기간 버저닝(세율과 달리 프리셋은 현행 1건만), ISIN 매칭, 사용자별 프리셋(유저 리스트는 #52로 이미 존재).

## 1. Background

`EsgExclusionPreset`(R2 #42)은 심볼→(listName,reason) 하드코딩 Map(예시 심볼 EXCL-COAL-01·EXCL-WEAPON-01 2건). 소비처 2곳: `ExclusionListService.presets()`(프리셋 뷰), `EsgScreeningReportGenerator`(스크리닝 lookup 시드). ADMIN이 실제 배제 종목을 큐레이션하려면 DB화가 필요.

## 2. Decisions (확정)

| 항목 | 결정 |
|---|---|
| 저장 | 신규 테이블 `exclusion_presets`(symbol UNIQUE·현행 1건, 버저닝 없음) |
| 키 | symbol 대문자 정규화, UNIQUE. 심볼당 1개 (listName/reason) — 기존 Map 의미 유지 |
| ADMIN API | `/api/admin/exclusion-presets` GET/POST(upsert)/DELETE — SecurityConfig `/api/admin/**`→hasRole ADMIN(#50) |
| upsert | symbol 존재 시 update, 없으면 insert |
| 소비처 | ExclusionListService·EsgScreeningReportGenerator가 `ExclusionPresetRepository.findAll()` 사용. 하드코딩 `EsgExclusionPreset` 제거 |
| 시드 | 마이그레이션·init.sql에 기존 예시 2건 시드(동작 보존) |

## 3. Backend Design (module: `unified-asset`)

### 3.1 도메인 — `domain/exclusion/ExclusionPreset.kt`
```kotlin
data class ExclusionPreset(
    val id: UUID, val symbol: String, val listName: String, val reason: String,
    val updatedBy: UUID?, val createdAt: LocalDateTime, val updatedAt: LocalDateTime,
)
```

### 3.2 포트 — `application/port/ExclusionPresetRepository.kt`
```kotlin
interface ExclusionPresetRepository {
    fun findAll(): List<ExclusionPreset>
    fun findBySymbol(symbol: String): ExclusionPreset?
    fun save(preset: ExclusionPreset): ExclusionPreset
    fun delete(id: UUID)
}
```

### 3.3 영속화 — `infrastructure/{entity,jpa,repository}`
- `ExclusionPresetEntity`(@Table "exclusion_presets": id, symbol, list_name, reason, updated_by, created_at, updated_at).
- `ExclusionPresetJpaRepository : JpaRepository<ExclusionPresetEntity, UUID>` + `findBySymbol`.
- `ExclusionPresetRepositoryImpl(@Repository)` 도메인 매핑.
- init.sql `exclusion_presets` 테이블 + 시드 2건.
- 마이그레이션 `docs/superpowers/migrations/2026-07-31-exclusion-presets.sql`(자립형·멱등: CREATE TABLE IF NOT EXISTS + UNIQUE(symbol) + 시드 ON CONFLICT DO NOTHING).

### 3.4 서비스 — `application/usecase/ExclusionPresetService.kt`
```kotlin
data class UpsertPresetCommand(val symbol: String, val listName: String, val reason: String)
@Service
class ExclusionPresetService(private val repository: ExclusionPresetRepository) {
    fun list(): List<ExclusionPreset> = repository.findAll()
    @Transactional
    fun upsert(cmd: UpsertPresetCommand, adminId: UUID): ExclusionPreset {
        val symbol = cmd.symbol.trim().uppercase()
        require(symbol.isNotBlank()) { "심볼은 필수입니다" }
        require(cmd.listName.isNotBlank()) { "리스트명은 필수입니다" }
        val now = LocalDateTime.now()
        val existing = repository.findBySymbol(symbol)
        return repository.save(
            existing?.copy(listName = cmd.listName.trim(), reason = cmd.reason.trim(), updatedBy = adminId, updatedAt = now)
                ?: ExclusionPreset(UUID.randomUUID(), symbol, cmd.listName.trim(), cmd.reason.trim(), adminId, now, now)
        )
    }
    @Transactional fun delete(id: UUID) = repository.delete(id)
}
```

### 3.5 ADMIN 컨트롤러 — `backend-app/api/admin/ExclusionPresetAdminController.kt`
- `@RequestMapping("/api/admin/exclusion-presets")`. GET list→`List<ExclusionPresetResponse>`; POST upsert(`X-User-Id` adminId, body {symbol,listName,reason}); DELETE `/{id}`. (TaxRateAdminController와 동일 골격.)

### 3.6 소비처 스왑
- `ExclusionListService`: 생성자에 `exclusionPresetRepository: ExclusionPresetRepository` 추가. `presets()` → `exclusionPresetRepository.findAll().groupBy { it.listName }.map { (ln, ps) -> PresetView(ln, ps.map { PresetSymbol(it.symbol, it.reason) }) }`.
- `EsgScreeningReportGenerator`: 생성자에 `exclusionPresetRepository` 추가. line 62 → `exclusionPresetRepository.findAll().forEach { lookup[it.symbol] = it.listName to it.reason }`.
- **`EsgExclusionPreset` object 삭제**(및 관련 import 정리). `ExclusionEntry`가 다른 데서 쓰이면 유지, 아니면 삭제.

## 4. Frontend Design
- `lib/exclusion-preset-admin-api.ts`: `createExclusionPresetAdminApi(token)` — list()/upsert(req)/remove(id) (`/api/admin/exclusion-presets`). `useApi.ts`에 `useExclusionPresetAdminApi()` 훅 추가.
- 타입 `types/exclusion-preset.ts`: `ExclusionPreset { id; symbol; listName; reason; updatedBy: string|null; updatedAt: string }`.
- 페이지 `app/unified/admin/exclusion-presets/page.tsx`(`useRequireAdmin`): 목록 표 + 추가/수정 폼(symbol·listName·reason) + 삭제. tax-rates 어드민 페이지 룩앤필.
- NavBar: ADMIN일 때 "배제 프리셋" 링크(기존 세율 어드민 링크 옆, amber 스타일).

## 5. Tests
**Backend**: `ExclusionPresetServiceTest`(upsert insert/update·symbol 정규화·빈 값 예외·delete) with fake repo. `EsgScreeningReportGeneratorTest`·`ExclusionListServiceTest`(있으면) 확장 — fake `ExclusionPresetRepository` 주입, 프리셋 DB 소스로 스크리닝/presets 동작. 하드코딩 제거로 인한 기존 테스트 조정(프리셋을 fake로 공급).
**Frontend**: `npx tsc --noEmit` clean.

## 6. Rollout (배포 순서 — 승인 게이트)
- **스키마 변경(신규 테이블)** → 배포 전 `docs/superpowers/migrations/2026-07-31-exclusion-presets.sql` Neon 수동 실행(자립형·멱등·시드 포함). 이후 main 병합.
- 검증: `/unified/admin/exclusion-presets`에서 프리셋 추가 → ESG 스크리닝 리포트에 반영 확인.

## 7. Affected Files
**BE**: (신규) domain/exclusion/ExclusionPreset.kt · application/port/ExclusionPresetRepository.kt · infrastructure/entity/ExclusionPresetEntity.kt · infrastructure/jpa/ExclusionPresetJpaRepository.kt · infrastructure/repository/ExclusionPresetRepositoryImpl.kt · application/usecase/ExclusionPresetService.kt · backend-app/.../admin/ExclusionPresetAdminController.kt · migrations/2026-07-31-exclusion-presets.sql. (수정) ExclusionListService.kt · EsgScreeningReportGenerator.kt · init.sql · (삭제) EsgExclusionPreset.kt. (test) ExclusionPresetServiceTest(신규)·소비처 테스트 조정.
**FE**: (신규) lib/exclusion-preset-admin-api.ts · types/exclusion-preset.ts · app/unified/admin/exclusion-presets/page.tsx. (수정) lib/useApi.ts · components/NavBar.tsx.

## 8. Out of Scope (후속)
프리셋 유효기간 버저닝, ISIN/국가 매칭, CSV 대량 반입(ADMIN), 프리셋별 활성 토글, 감사 이력.
