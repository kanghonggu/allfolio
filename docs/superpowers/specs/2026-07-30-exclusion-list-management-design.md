# R-07 배제리스트 관리 (SCR-RPT-11, Phase A) — Design Spec

- **Date**: 2026-07-30
- **Status**: Approved (design), pending implementation
- **Scope (Phase A)**: 사용자 배제리스트 CRUD(리스트+종목) + CSV 가져오기 + 활성 토글 + 프리셋 복제 + **EsgScreeningReportGenerator 통합** + FE 관리 화면. **USER-scoped** (ADMIN 무관).
- **Depends on**: 없음 — `main`에서 분기. (ADMIN role #50과 무관; SCR-RPT-11은 사용자 본인 리스트 관리.)
- **Out of scope (후속)**: ISIN 자동완성·ISIN 우선/티커+거래소 정밀매칭, 위반이력 타임라인·신규매수 감시로그·알림(SCR-RPT-10), 내장 프리셋 ADMIN 큐레이션(하드코딩→DB), 유효기간 버저닝(언제부터 배제).

## 1. Background

R-07 ESG 스크리닝 v1(#42)은 배제를 **하드코딩 `object EsgExclusionPreset`**(심볼→사유 2건: `EXCL-COAL-01`, `EXCL-WEAPON-01`)로만 처리하고, "사용자 배제리스트·관리(SCR-RPT-11)·CSV 반입"을 명시 제외했다. `EsgScreeningReportGenerator`가 `EsgExclusionPreset.lookup(a.symbol)`로 위반을 판정한다.

이 spec은 **사용자가 자기 배제리스트를 관리**(SCR-RPT-11)하고, 스크리닝이 그 리스트를 반영하게 한다. abor 근거: 전범기업 모니터링 `warCrmCmp`(식별자 매칭·종목명·국가·평가액). 철학(처리규칙 #2): **표시·알림이지 매매 차단 아님**(개인 서비스). 명세 근거: Notion 「R-07 화면 항목 정의서」 SCR-RPT-11 (권한 = USER 본인 리스트).

## 2. Decisions (확정)

| 항목 | 결정 |
|---|---|
| 권한 | **USER** (본인 리스트). `X-User-Id` 소유권 검증(GoalController 패턴) |
| 모듈 | `unified-asset` (ESG 생성기·프리셋 동거). 컨트롤러 `unified-asset/api` |
| 데이터 | `ua_exclusion_lists` + `ua_exclusion_items`(FK ON DELETE CASCADE) |
| 내장 프리셋 | 기존 하드코딩 `EsgExclusionPreset` 유지(읽기전용) + "복제 후 편집" |
| CSV | JSON `{csv:"..."}` 서버 파싱(개행/콤마 분리·trim·중복/기존중복 skip). multipart 미사용 |
| 매칭 | `symbol` 기준(현 프리셋과 동일). ISIN 정밀매칭은 후속 |
| category | 자유 문자열(사용자지정 허용), enum 아님 |
| 버저닝 | item `added_at`만 저장. 유효기간 버저닝·위반이력은 후속 |

## 3. Backend Design (module: `unified-asset`)

### 3.1 Domain — `domain/exclusion/`
```kotlin
// ExclusionList.kt
data class ExclusionList(
    val id: UUID,
    val userId: UUID,
    val name: String,
    val category: String,          // 사유 카테고리 (자유 문자열)
    val description: String?,
    val active: Boolean,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val items: List<ExclusionItem>, // 조회 시 채움(집계/상세). 저장은 별도 경로
)

// ExclusionItem.kt
data class ExclusionItem(
    val id: UUID,
    val listId: UUID,
    val symbol: String,
    val memo: String?,
    val addedAt: LocalDateTime,
)
```

### 3.2 Persistence
`infrastructure/entity/ExclusionListEntity.kt`, `ExclusionItemEntity.kt` (GoalEntity 패턴: `@Table("ua_exclusion_lists")` 등, `columnDefinition="uuid"`, `toDomain()`/`from()`). Items는 별도 엔티티(부모 리스트 없이 list_id로 저장).
`infrastructure/jpa/`:
- `ExclusionListJpaRepository : JpaRepository<ExclusionListEntity, UUID>` — `findByUserIdOrderByCreatedAtDesc(userId)`, `findByUserIdAndActiveTrue(userId)`
- `ExclusionItemJpaRepository : JpaRepository<ExclusionItemEntity, UUID>` — `findByListId(listId)`, `findByListIdIn(listIds)`, `existsByListIdAndSymbol(listId, symbol)`, `deleteByListId(listId)`(cascade와 별개 명시 삭제 필요 시)
`infrastructure/repository/ExclusionListRepositoryImpl.kt` — 포트 구현, 리스트+종목 조합 매핑.

### 3.3 Port — `application/port/ExclusionListRepository.kt`
```kotlin
interface ExclusionListRepository {
    fun findByUser(userId: UUID): List<ExclusionList>              // 종목 포함
    fun findActiveByUser(userId: UUID): List<ExclusionList>        // 종목 포함, active만 (생성기용)
    fun findById(id: UUID): ExclusionList?
    fun saveList(list: ExclusionList): ExclusionList               // 리스트 메타(생성/수정)
    fun deleteList(id: UUID)                                       // items cascade
    fun addItem(item: ExclusionItem): ExclusionItem
    fun deleteItem(itemId: UUID)
    fun existsItem(listId: UUID, symbol: String): Boolean
}
```

### 3.4 Service — `application/usecase/ExclusionListService.kt`
- `list(userId)`, `presets()`(EsgExclusionPreset → read-only DTO), `create(userId, cmd)`, `update(userId, id, cmd)`, `delete(userId, id)`, `addItem(userId, listId, symbol, memo)`, `deleteItem(userId, listId, itemId)`, `importCsv(userId, listId, csv)`, `clonePreset(userId, presetKey)`.
- **소유권**: id로 리소스 조회 → 없으면 `ResponseStatusException(NOT_FOUND)`, `userId` 불일치면 `FORBIDDEN`. (GoalService의 `require`/`error` 대신 명시적 HTTP 코드.)
- **검증**: name 비공백, symbol 비공백(정규화 `trim().uppercase()` — 매칭 일관). CSV: 개행/콤마 split → trim/uppercase → 빈값·중복·기존보유(existsItem) 제거 후 bulk add; 결과로 추가 개수 반환.
- **clonePreset**: 프리셋을 새 리스트(name="{프리셋명} (복제)", category=사유, source 개념은 별도 필드 없이 일반 사용자 리스트로 생성)로 만들고 프리셋 심볼을 items로 복사.

### 3.5 REST — `unified-asset/api/ExclusionListController.kt`
`@RequestMapping("/api/exclusion-lists")`, 모든 엔드포인트 `@RequestHeader("X-User-Id") userId: UUID`:
| 메서드 | 경로 | 동작 |
|---|---|---|
| GET | `` | 내 리스트(+종목) |
| GET | `/presets` | 내장 프리셋(읽기전용) |
| POST | `` | 생성 `{name, category, description}` → 201 |
| PUT | `/{id}` | 수정 `{name, category, description, active}` |
| DELETE | `/{id}` | 삭제(종목 cascade) → 204 |
| POST | `/{id}/items` | 종목 추가 `{symbol, memo}` |
| DELETE | `/{id}/items/{itemId}` | 종목 삭제 → 204 |
| POST | `/{id}/items/import` | CSV 반입 `{csv}` → `{added: n}` |
| POST | `/presets/{key}/clone` | 프리셋 복제 → 새 리스트 |

DTO: `ExclusionListResponse`(id, name, category, description, active, itemCount, items[], updatedAt), `ExclusionItemResponse`(id, symbol, memo, addedAt), `PresetResponse`(key, listName, reason, symbols[]), 요청 `CreateListRequest`/`UpdateListRequest`/`AddItemRequest`/`ImportCsvRequest{csv}`. LocalDateTime/enum은 Spring MVC Jackson으로 정상 직렬화(리포트 본문 jacksonObjectMapper 함정 무관).

### 3.6 Generator 통합 — `EsgScreeningReportGenerator`
- 생성자에 `exclusionRepo: ExclusionListRepository` 추가.
- 위반 판정 lookup 구성: **내장 프리셋(symbol→listName/reason) 을 base 로, 유저 active 리스트 종목을 overlay**(같은 symbol이면 사용자 리스트가 우선). `LinkedHashMap<String, Pair<listName, reason>>`.
- `violated = valued.mapNotNull { (a,v) -> lookup[a.symbol]?.let { Triple(a,v,it) } }` — 기존 로직 대체. 리포트 본문 형태(violations: name/symbol/listName/reason/valueKrw/weight, screening 집계) 유지.
- 빈 리스트/타유저/ inactive는 자동 무시(active·userId 쿼리). 총평가액 0 가드 유지.

### 3.7 Schema & Seed
`init.sql`에 두 테이블 추가(ua_ 구역):
```sql
CREATE TABLE IF NOT EXISTS ua_exclusion_lists (
    id          UUID          NOT NULL,
    user_id     UUID          NOT NULL,
    name        VARCHAR(100)  NOT NULL,
    category    VARCHAR(30)   NOT NULL,
    description VARCHAR(500),
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_ua_exclusion_lists PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_ua_exclusion_lists_user ON ua_exclusion_lists (user_id);

CREATE TABLE IF NOT EXISTS ua_exclusion_items (
    id       UUID         NOT NULL,
    list_id  UUID         NOT NULL,
    symbol   VARCHAR(40)  NOT NULL,
    memo     VARCHAR(300),
    added_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_ua_exclusion_items PRIMARY KEY (id),
    CONSTRAINT fk_ua_exclusion_items_list FOREIGN KEY (list_id)
        REFERENCES ua_exclusion_lists(id) ON DELETE CASCADE,
    CONSTRAINT uk_ua_exclusion_items UNIQUE (list_id, symbol)
);
CREATE INDEX IF NOT EXISTS idx_ua_exclusion_items_list ON ua_exclusion_items (list_id);
```
운영 Neon 1회성: `docs/superpowers/migrations/2026-07-30-exclusion-list.sql` — 위 DDL(`CREATE TABLE/INDEX IF NOT EXISTS`) + 헤더 주석(BE 배포 전 수동, 신규 테이블이라 무해). 시드 없음(사용자 데이터).

## 4. Frontend Design

- **API 클라이언트** `lib/exclusion-list-api.ts` (axios+Bearer, report-archive-api 패턴): `list()`, `presets()`, `create(body)`, `update(id, body)`, `remove(id)`, `addItem(id, body)`, `removeItem(id, itemId)`, `importCsv(id, csv)`, `clonePreset(key)`.
- **타입** `types/exclusion-list.ts`: `ExclusionList`, `ExclusionItem`, `Preset`, 요청 타입.
- **화면** `app/unified/reports/esg-screening/lists/page.tsx` (authenticated, `useAuth().accessToken`):
  ① 리스트 목록(이름·종목수·활성 토글·수정일) + 내장 프리셋 배지(복제 버튼) ② 선택 리스트 종목 편집그리드(심볼·메모·추가일, 추가/삭제) ③ 리스트 생성/편집 폼(이름·카테고리·설명) ④ CSV 가져오기(파일 선택→FileReader 텍스트→importCsv). 삭제 확인.
- **진입점**: `app/unified/reports/esg-screening/page.tsx`에 "배제리스트 관리" 링크 추가 → `/unified/reports/esg-screening/lists`.

## 5. Tests

**Backend (unified-asset)**
- `ExclusionListServiceTest` (fake repo): 생성/수정/삭제·**소유권**(타유저 id → FORBIDDEN, 없는 id → NOT_FOUND)·종목 추가/삭제·**CSV 파싱**(개행+콤마 혼합, 빈줄/중복/기존중복 제거, added 개수)·프리셋 복제(심볼 복사).
- `EsgScreeningReportGeneratorTest` 확장(기존 프리셋 테스트 유지 + 신규): 유저 active 리스트가 보유 심볼을 위반 판정 / inactive 리스트 무시 / 타유저 리스트 무시 / 프리셋+유저리스트 공존. 생성기 생성자 변경으로 기존 테스트는 빈 fake exclusionRepo 주입.
- `ExclusionListControllerTest`(선택, MockMvc 슬라이스): 소유권 403/404, 기본 CRUD 흐름.

**Frontend**: `npx tsc --noEmit` clean. (러너 있으면 CSV 파싱 트리거/토글 상호작용.)

## 6. Rollout / 배포 순서
1. `docs/superpowers/migrations/2026-07-30-exclusion-list.sql`을 **운영 Neon에 BE 배포 전 수동 실행**(신규 테이블 2개, 무해·멱등).
2. main 병합 → Render 자동배포(BE) → FE 배포.
3. 검증: 로그인 → ESG 스크리닝 화면 "배제리스트 관리" → 리스트 생성/종목추가/CSV/활성토글/프리셋 복제 → 보유 심볼을 리스트에 넣고 ESG 리포트 재생성 시 violations 반영 확인.

## 7. Affected Files (요약)

**Backend — unified-asset (신규)**
- `domain/exclusion/{ExclusionList,ExclusionItem}.kt`
- `infrastructure/entity/{ExclusionListEntity,ExclusionItemEntity}.kt`
- `infrastructure/jpa/{ExclusionListJpaRepository,ExclusionItemJpaRepository}.kt`
- `infrastructure/repository/ExclusionListRepositoryImpl.kt`
- `application/port/ExclusionListRepository.kt`
- `application/usecase/ExclusionListService.kt` (+commands)
- `api/ExclusionListController.kt` (+DTOs)

**Backend — 수정**
- `application/usecase/EsgScreeningReportGenerator.kt` (exclusionRepo 주입 + lookup 통합)
- `infra/postgres/init.sql` (2 테이블)
- (신규) `docs/superpowers/migrations/2026-07-30-exclusion-list.sql`

**Frontend (신규/수정)**
- `types/exclusion-list.ts`, `lib/exclusion-list-api.ts`
- `app/unified/reports/esg-screening/lists/page.tsx`
- `app/unified/reports/esg-screening/page.tsx` (관리 링크 추가)

**Tests**
- `unified-asset/.../ExclusionListServiceTest.kt`
- `unified-asset/.../EsgScreeningReportGeneratorTest.kt` (확장)
- (선택) `unified-asset/.../ExclusionListControllerTest.kt`

## 8. Out of Scope (후속)
- ISIN/티커 자동완성, ISIN 우선 정밀매칭·"미확인" 분류.
- 위반이력 타임라인·신규매수 감시로그·알림(SCR-RPT-10 영역).
- 내장 프리셋 ADMIN 큐레이션(하드코딩 EsgExclusionPreset → DB), 실제 공개 배제리스트 반입.
- 유효기간 버저닝(편입일 vs 등록일 배지, "언제부터 배제대상") — 위반 판정 시점 기준.
- 일일 마감 워크플로우 자동 스크리닝 편입(P3 연계).
