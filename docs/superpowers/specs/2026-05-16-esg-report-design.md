# ESG Report 모듈 설계

**날짜:** 2026-05-16
**상태:** 승인됨

---

## 요약

비어있는 `esg/`, `report/` Gradle 모듈에 실제 소스를 추가한다. `risk/` 모듈 패턴을 따라 `esg/`는 순수 도메인 엔진(DB/Spring 없음)으로 구성하고, `report/`는 Spring `@Service`로 엔진을 조합해 포트폴리오 레벨 ESG 보고서를 생성한다. 기존 `ReportController`에 API 엔드포인트를 추가하고, 프론트엔드에 `/unified/reports/esg` 페이지를 신설한다.

---

## 아키텍처

```
esg 모듈 (순수 도메인)
  EsgEngine.calculate(assets) → EsgScore

report 모듈 (Spring @Service)
  EsgReportService
    ├── AssetRepository.findByUserId()
    └── EsgEngine.calculate()
    → EsgReport

backend-app / unified-asset
  ReportController
    GET /api/reports/esg → EsgReportService.generate(userId)

프론트엔드
  /unified/reports/esg/page.tsx
  lib/report-api.ts → esg()
```

---

## ESG 모듈 (`esg/`)

### 파일 구조

```
esg/src/main/kotlin/com/allfolio/esg/domain/
  ├── EsgScore.kt
  ├── EsgEngine.kt
  └── EsgException.kt
```

### 점수 체계

자산 타입별 기본 E/S/G 점수 (0~100):

| 타입 | E | S | G | 근거 |
|------|---|---|---|------|
| CRYPTO | 20 | 50 | 40 | 채굴 전력 소비 |
| STOCK | 60 | 65 | 65 | 업종 불명 시 중립 |
| REAL_ESTATE | 55 | 70 | 65 | 사회적 주거 가치 |
| JEONSE | 65 | 80 | 70 | 서민 주거 지원 |
| VEHICLE | 35 | 60 | 55 | 탄소 배출 |
| GOLD | 45 | 55 | 55 | 채굴 환경 영향 |
| CASH | 80 | 75 | 80 | 중립 자산 |
| ETC | 60 | 60 | 60 | 기본값 |

**총점 = E×0.35 + S×0.30 + G×0.35**

환경(E)과 지배구조(G)에 사회(S)보다 소폭 높은 가중치를 둔다.

**등급:**

| 등급 | 총점 범위 |
|------|----------|
| A+   | 85 이상  |
| A    | 75 ~ 84  |
| B+   | 65 ~ 74  |
| B    | 55 ~ 64  |
| C+   | 45 ~ 54  |
| C    | 44 이하  |

### EsgScore

```kotlin
data class EsgScore(
    val environmental: BigDecimal,  // 0~100
    val social: BigDecimal,         // 0~100
    val governance: BigDecimal,     // 0~100
    val total: BigDecimal,          // 가중 평균
    val rating: String,             // "A+", "A", "B+", "B", "C+", "C"
)
```

### EsgEngine

```kotlin
object EsgEngine {
    data class AssetInput(val type: String, val currentValue: BigDecimal)

    fun scoreOf(type: String): Triple<Int, Int, Int>   // (E, S, G)
    fun calculate(assets: List<AssetInput>): EsgScore  // 가중 평균
    fun rating(total: BigDecimal): String
}
```

- `assets`가 비어있으면 `EsgException.emptyAssets()` throw
- 가중치는 `currentValue` 비율

### build.gradle.kts 변경

`esg/build.gradle.kts`에 `:common` 의존성 추가.

---

## Report 모듈 (`report/`)

`report → unified-asset → report` 순환 의존성을 피하기 위해 `report/`는 **도메인 모델만** 담는다. `EsgReportService`는 `unified-asset` 모듈에 위치하여 기존 `ReportService`와 동일한 패턴을 따른다.

### 파일 구조

```
report/src/main/kotlin/com/allfolio/report/domain/
  ├── EsgReport.kt      ← 도메인 모델만, Spring 없음
  └── AssetEsgRow.kt

unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/
  └── EsgReportService.kt  ← 기존 ReportService 옆에 위치
```

### 도메인 모델 (`report/`)

```kotlin
data class AssetEsgRow(
    val name: String,
    val type: String,
    val currentValue: BigDecimal,
    val weight: BigDecimal,         // 포트폴리오 내 비중 (0~1)
    val environmental: BigDecimal,
    val social: BigDecimal,
    val governance: BigDecimal,
    val total: BigDecimal,
    val rating: String,
)

data class EsgReport(
    val userId: UUID,
    val generatedAt: LocalDateTime,
    val rating: String,
    val totalScore: BigDecimal,
    val environmentalScore: BigDecimal,
    val socialScore: BigDecimal,
    val governanceScore: BigDecimal,
    val assetBreakdown: List<AssetEsgRow>,  // 전체, total 내림차순
    val topAssets: List<AssetEsgRow>,       // 상위 3개 (ESG 우수)
    val bottomAssets: List<AssetEsgRow>,    // 하위 3개 (개선 필요)
)
```

### EsgReportService (`unified-asset/`)

```kotlin
@Service
class EsgReportService(
    private val assetRepository: AssetRepository,
) {
    fun generate(userId: UUID): EsgReport
}
```

1. `AssetRepository.findByUserId(userId)` 호출
2. 자산 없으면 HTTP 404 (`ResponseStatusException(HttpStatus.NOT_FOUND)`)
3. 자산별 `EsgEngine.scoreOf(asset.type.name)` 호출 → `AssetEsgRow` 구성
4. `EsgEngine.calculate(inputs)` → 포트폴리오 `EsgScore`
5. `EsgReport` 반환

### build.gradle.kts 변경

**`report/build.gradle.kts`** — 도메인 모델만이므로 의존성 최소화:
```kotlin
dependencies {
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
```

**`unified-asset/build.gradle.kts`** — esg, report 추가:
```kotlin
implementation(project(":esg"))
implementation(project(":report"))
```

---

## API 엔드포인트

기존 `unified-asset/api/ReportController.kt`에 추가:

```kotlin
GET /api/reports/esg
```

- JWT에서 userId 추출 (기존 패턴 동일)
- `EsgReportService.generate(userId)` 호출
- 자산 없으면 404 또는 빈 응답 (기존 에러 핸들러 활용)

`BackendApplication`이 `scanBasePackages = ["com.allfolio"]`로 전체 패키지를 스캔하므로 별도 설정 불필요.

---

## 프론트엔드

### 신규/수정 파일

| 파일 | 변경 |
|------|------|
| `app/unified/reports/esg/page.tsx` | 신규 |
| `lib/report-api.ts` | `esg()` 메서드 추가 |
| `app/unified/reports/page.tsx` | ESG 카드 링크 추가 |

### ESG 페이지 레이아웃

```
┌─────────────────────────────────────┐
│ ESG 점수                   등급: A  │
│                                     │
│  🌿 환경  ████████░░  72.0         │
│  🤝 사회  █████████░  80.0         │
│  🏛 지배  ███████░░░  65.0         │
│                                     │
│ [ 자산별 ESG 테이블 ]               │
│  이름      타입    ESG    등급       │
│  삼성전자  STOCK  72.0   B+        │
│  비트코인  CRYPTO 36.5   C         │
│  아파트    RE     63.0   B         │
│                                     │
│ ✅ 우수 자산 (상위 3)               │
│ ⚠️  개선 필요 (하위 3)              │
└─────────────────────────────────────┘
```

---

## 테스트

| 테스트 파일 | 대상 |
|------------|------|
| `esg/.../EsgEngineTest.kt` | 타입별 점수, 가중 평균, 등급 경계값, 빈 자산 예외 |
| `unified-asset/.../EsgReportServiceTest.kt` | AssetRepository mock, topAssets/bottomAssets 정렬 |

기존 `DividendReportServiceTest` 패턴 동일 — Mockito `@Mock` 사용.

---

## 제외 범위

- 실제 ESG 데이터 API 연동 (MSCI, Sustainalytics 등) — 추후 교체 가능한 구조로 설계
- 주식 종목별 세부 업종 매핑 — 타입 기반 기본값으로 충분
- ESG 점수 DB 저장/캐시 — 매 요청마다 계산 (자산 수 제한적)
- PDF/CSV 내보내기
