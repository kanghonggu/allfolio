# R-03 원천징수 세율 마스터 (SCR-RPT-06, Phase A) — Design Spec

- **Date**: 2026-07-29
- **Status**: Approved (design), pending implementation
- **Depends on**: ADMIN role (PR #50, `feat/admin-role`) — `/api/admin/**` `hasRole('ADMIN')` 게이트, FE `isAdmin`/`useRequireAdmin`. **R-03 브랜치는 #50 머지 후 main에서 분기.**
- **Scope (Phase A)**: 세율 마스터 도메인 + ADMIN CRUD API + 시드 + ADMIN 화면. **Out of scope (Phase B, 후속)**: 배당 생성기의 기대세율 조회·0.5%p 비교·⚠, 배당뷰어 기대세율 컬럼, 국가 국내/해외→실제국가 매핑 보강.

## 1. Background

기관 abor `divTax` 세율 마스터(국가×소득유형×적용기간, 유효기간 버저닝)를 이식한다. 현재 R-03 배당리포트(`DividendInterestReportGenerator`)는 **실제 원천징수액**(`DividendRecord.tax`)만 쓰고, v1에서 "세율 마스터·기대세율 비교"를 명시적으로 제외했다. 이 spec은 그 **기준값 마스터 자체**를 만든다(SCR-RPT-06, ADMIN 전용). abor 철학: 마스터는 **검증 기준값**일 뿐 계산 대체값이 아니다 → 소비(Phase B)는 항상 실제 징수액을 표시하고 기준과의 차이만 플래그.

명세 근거: Notion 「R-03 배당·이자 보고서 — 화면 항목 정의서」 SCR-RPT-06.

## 2. Decisions (확정)

| 항목 | 결정 |
|---|---|
| 모듈 배치 | `unified-asset` (도메인/엔티티/JPA/서비스). ADMIN REST 컨트롤러만 `backend-app/api/admin`. Phase B에서 생성기가 서비스 직접 주입 가능. |
| 데이터 모델 | `tax_rates` 단일 테이블, 유효기간 버저닝(effective_start/end, end null=현행) |
| 국가 표현 | ISO alpha-2 코드(`US`/`KR`/`JP`), FE가 flag+명칭 렌더 |
| 소득유형 | enum `IncomeType { DIVIDEND, INTEREST, DISTRIBUTION }` |
| API | ADMIN `GET/POST /api/admin/tax-rates`만. **USER 조회 엔드포인트 생략**(소비처는 Phase B) |
| 시드/배포 | `init.sql` 시드(신규/로컬) + 운영 Neon 1회성 마이그레이션 SQL(수동, BE 배포 전) |
| FE | `/unified/admin/tax-rates` (`useRequireAdmin` 가드), 내비에 isAdmin일 때만 진입점 |

## 3. Backend Design (module: `unified-asset` unless noted)

### 3.1 Domain
`domain/tax/IncomeType.kt`:
```kotlin
enum class IncomeType { DIVIDEND, INTEREST, DISTRIBUTION }
```
`domain/tax/TaxRate.kt` — 불변 도메인 모델:
```kotlin
data class TaxRate(
    val id: UUID,
    val country: String,          // ISO alpha-2
    val incomeType: IncomeType,
    val rate: BigDecimal,         // 퍼센트값 (예 15.315)
    val effectiveStart: LocalDate,
    val effectiveEnd: LocalDate?, // null = 현행(open)
    val updatedBy: UUID?,         // 최종수정 ADMIN userId (시드는 null)
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)
```

### 3.2 Persistence
`infrastructure/entity/TaxRateEntity.kt` — 기존 `CashFlowEntity` 패턴(`@Entity @Table`, `@Enumerated(STRING)`, `toDomain()`/`from()`):
```kotlin
@Entity
@Table(name = "tax_rates")
class TaxRateEntity(
    @Id val id: UUID,
    @Column(name = "country", nullable = false, length = 2) val country: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "income_type", nullable = false, length = 20) val incomeType: IncomeType,
    @Column(name = "rate", nullable = false, precision = 6, scale = 3) val rate: BigDecimal,
    @Column(name = "effective_start", nullable = false) val effectiveStart: LocalDate,
    @Column(name = "effective_end") val effectiveEnd: LocalDate?,
    @Column(name = "updated_by") val updatedBy: UUID?,
    @Column(name = "created_at", nullable = false) val createdAt: LocalDateTime,
    @Column(name = "updated_at", nullable = false) val updatedAt: LocalDateTime,
) { fun toDomain(): TaxRate = ...; companion object { fun from(d: TaxRate) = ... } }
```
`infrastructure/jpa/TaxRateJpaRepository.kt` (Spring Data):
```kotlin
interface TaxRateJpaRepository : JpaRepository<TaxRateEntity, UUID> {
    fun findAllByOrderByCountryAscIncomeTypeAscEffectiveStartDesc(): List<TaxRateEntity>
    fun findByCountryAndIncomeTypeAndEffectiveEndIsNull(country: String, incomeType: IncomeType): TaxRateEntity?
}
```

### 3.3 Service — `application/usecase/TaxRateService.kt`
- `list(): List<TaxRate>` — 전체(현행+이력), 국가·유형·시작일 정렬.
- `register(cmd: RegisterTaxRateCommand, adminId: UUID): TaxRate` — **버저닝**:
  1. `country`·`incomeType`의 현행 open 행 조회.
  2. open 행이 있으면: `effectiveStart` ≤ open.effectiveStart 이면 400(과거로 되돌리기 금지). 아니면 open 행을 `effectiveEnd = cmd.effectiveStart.minusDays(1)`로 마감.
  3. 신규 open 행 삽입(`effectiveEnd=null`, `updatedBy=adminId`).
  - `@Transactional`로 마감+삽입 원자성.
- `findEffectiveRate(country, incomeType, date): TaxRate?` — `start ≤ date AND (end IS NULL OR end ≥ date)` 조회. **Phase A에선 호출자 없음이나, 버저닝 정합의 핵심 계약이라 구현+테스트로 고정**(Phase B가 그대로 사용).
- 검증(`register`): rate ∈ [0, 50], country 2자 비공백, effectiveStart 필수. 위반 시 `ResponseStatusException(BAD_REQUEST)` (기존 `AuthService` 검증 스타일).

### 3.4 REST — `backend-app/api/admin/TaxRateAdminController.kt`
`@RequestMapping("/api/admin/tax-rates")` (SecurityConfig `/api/admin/**` → `hasRole('ADMIN')`로 이미 게이트). `@RequestHeader("X-User-Id") adminId: UUID`(기존 컨트롤러 관례) 사용:
- `GET` → `List<TaxRateResponse>` (전체 목록; FE가 country×type로 그룹핑해 이력 타임라인 렌더 → 별도 history 엔드포인트 불필요).
- `POST` `{country, incomeType, rate, effectiveStart}` → `TaxRateResponse` (register).
- DTO: `TaxRateResponse(id, country, incomeType, rate, effectiveStart, effectiveEnd, updatedBy, updatedAt)`, `RegisterTaxRateRequest(country, incomeType, rate, effectiveStart)`. jacksonObjectMapper JSR310 미등록 함정 주의 — 컨트롤러 응답은 Spring MVC(Jackson+JavaTimeModule 등록됨)라 LocalDate 직렬화 정상. (report 본문 JSON 아님.)

### 3.5 Schema & Seed
`infrastructure`가 아니라 스키마 파일: `allfolio-backend/infra/postgres/init.sql`에 CREATE TABLE + 시드 추가:
```sql
CREATE TABLE IF NOT EXISTS tax_rates (
    id              UUID          NOT NULL,
    country         VARCHAR(2)    NOT NULL,
    income_type     VARCHAR(20)   NOT NULL,
    rate            NUMERIC(6,3)  NOT NULL,
    effective_start DATE          NOT NULL,
    effective_end   DATE,
    updated_by      UUID,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_tax_rates PRIMARY KEY (id),
    CONSTRAINT uk_tax_rates_ver UNIQUE (country, income_type, effective_start)
);
-- 기본 시드 (effective_start 2000-01-01, open)
INSERT INTO tax_rates (id, country, income_type, rate, effective_start) VALUES
  (gen_random_uuid(), 'US', 'DIVIDEND', 15,     '2000-01-01'),
  (gen_random_uuid(), 'KR', 'DIVIDEND', 15.4,   '2000-01-01'),
  (gen_random_uuid(), 'KR', 'INTEREST', 15.4,   '2000-01-01'),
  (gen_random_uuid(), 'JP', 'DIVIDEND', 15.315, '2000-01-01')
ON CONFLICT (country, income_type, effective_start) DO NOTHING;
```
운영 Neon 1회성: `docs/superpowers/migrations/2026-07-29-tax-rate-master.sql` — 위 `CREATE TABLE IF NOT EXISTS` + `INSERT ... ON CONFLICT DO NOTHING` 동일 내용 + 헤더 주석(“BE 배포 전 수동 실행, ddl-auto:none”). 멱등.

> `uk_tax_rates_ver`가 `ON CONFLICT` 타깃이자 중복 버전 방어. “(country,type)당 open 1개”는 서비스 레벨 가드로 강제(부분 유니크 인덱스는 도입하지 않음 — YAGNI).

## 4. Frontend Design

- **API 클라이언트** `lib/tax-rate-admin-api.ts` (기존 `report-archive-api.ts`의 axios+Bearer 패턴):
  ```ts
  export function createTaxRateAdminApi(accessToken: string) {
    const api = axios.create({ baseURL: `${BASE}/api/admin/tax-rates`, headers: { Authorization: `Bearer ${accessToken}` } })
    return {
      list: async (): Promise<TaxRate[]> => (await api.get<TaxRate[]>('')).data,
      register: async (body: RegisterTaxRate): Promise<TaxRate> => (await api.post<TaxRate>('', body)).data,
    }
  }
  ```
  타입 `types/tax-rate.ts` (`TaxRate`, `RegisterTaxRate`, `IncomeType` 유니온).
- **화면** `app/unified/admin/tax-rates/page.tsx` — 최상단 `const { ready } = useRequireAdmin()`; `ready` 전엔 로딩/널 렌더. 구성:
  ① 세율 목록 그리드(국가 flag+명칭, 유형, 세율%, 적용 시작·종료, 최종수정자·수정일; open 행 강조)
  ② 등록 폼(국가 select, 유형 select, 세율 number 0~50, 시작일 date) + 저장 → 성공 시 목록 refetch
  ③ 국가×유형 변경 이력: 목록을 그룹핑해 타임라인(시작~종료, 세율) 표시.
  인쇄/스케일: 세율은 이미 퍼센트값 → `.toFixed(3)`+`%` (×100 금지).
- **내비 진입점**: 주 내비(리포트 메뉴 근처)에 `isAdmin`일 때만 “세율 마스터(ADMIN)” 링크 노출 → `/unified/admin/tax-rates`. (정확한 내비 컴포넌트/라인은 plan에서 확정.)

## 5. Tests

**Backend (unified-asset, TDD)**
- `TaxRateService` 버저닝: (a) open 없는 상태 register → open 1개 생성. (b) open 있는 상태 register(더 늦은 start) → 기존 open의 end=newStart−1로 마감 + 신규 open. (c) start ≤ 기존 open.start → 400. (d) `findEffectiveRate`가 날짜별로 올바른 버전 반환(경계: end 당일 포함, 마감 전/후).
- 검증: rate 51 → 400, rate −1 → 400, 빈 country → 400.
- (선택) 엔티티 `toDomain`/`from` 왕복.

**Backend (backend-app, 컨트롤러 인가/통합)** — `SecurityConfigAdminTest` 패턴(실제 필터체인 MockMvc):
- `GET/POST /api/admin/tax-rates` 무토큰=403, USER 토큰=403, ADMIN 토큰=200.
- POST rate 51 (ADMIN) → 400.

**Frontend**: `npx tsc --noEmit` clean. 테스트 러너 있으면 `useRequireAdmin` 가드/`isAdmin` 분기.

## 6. Rollout / 배포 순서
1. `docs/superpowers/migrations/2026-07-29-tax-rate-master.sql`을 **운영 Neon에 BE 배포 전 수동 실행**(CREATE TABLE + 시드). 멱등.
2. main 병합 → Render 자동배포(BE) → FE 배포.
3. 검증: ADMIN 계정 → `/unified/admin/tax-rates` 진입·목록(시드 4행)·신규 등록(버저닝)·이력 확인. USER → 진입 리다이렉트, `GET /api/admin/tax-rates` 403.

> ADMIN role(#50)과 동일 순서 원칙: 스키마 SQL 먼저(ddl-auto:none). 이번 테이블은 신규라 기존 백엔드에 무해.

## 7. Affected Files (요약)

**Backend — unified-asset (신규)**
- `domain/tax/IncomeType.kt`, `domain/tax/TaxRate.kt`
- `infrastructure/entity/TaxRateEntity.kt`, `infrastructure/jpa/TaxRateJpaRepository.kt`
- `application/usecase/TaxRateService.kt` (+ command/검증)

**Backend — backend-app (신규)**
- `api/admin/TaxRateAdminController.kt` (+ DTOs)

**Backend — 스키마/마이그레이션**
- `infra/postgres/init.sql` (tax_rates + 시드)
- `docs/superpowers/migrations/2026-07-29-tax-rate-master.sql` (신규)

**Frontend (신규)**
- `types/tax-rate.ts`, `lib/tax-rate-admin-api.ts`
- `app/unified/admin/tax-rates/page.tsx`
- 내비 진입점 수정(isAdmin 게이팅)

**Tests (신규)**
- `unified-asset/.../TaxRateServiceTest.kt`
- `backend-app/.../api/admin/TaxRateAdminControllerTest.kt` (또는 SecurityConfig 슬라이스 확장)

## 8. Out of Scope (후속 Phase B / 기타)
- 배당 생성기 기대세율 조회·0.5%p 차이 플래그·⚠, 배당뷰어 FE 기대세율 컬럼.
- 국가 분류 국내/해외 → 실제 상장국 매핑 보강(현 `byCountry`는 심볼 숫자여부만).
- 세율 수정 UI의 open 행 인라인 편집/삭제, 감사로그 상세, USER 조회 엔드포인트(Phase B에서 소비처와 함께).
