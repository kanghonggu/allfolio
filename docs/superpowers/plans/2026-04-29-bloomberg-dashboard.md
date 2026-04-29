# Bloomberg Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 계층형 순자산 대시보드를 구현한다 — 투자 포트폴리오(수익률·배분·리스크 기관 지표) + 실물자산(전세 만기) 분리.

**Architecture:** `backend-app`에 새 `DashboardController`를 추가해 `unified-asset`(자산 조회)과 `snapshot`(지표 히스토리) 양쪽을 집계한다. `userId == portfolioId == tenantId` 는 기존 관례를 따른다. 기존 `performance_daily` · `risk_daily` 테이블을 최대한 재활용하고, 벤치마크 비교를 위한 `benchmark_daily` 테이블만 신규 추가한다.

**Tech Stack:** Kotlin 21 · Spring Boot 3 · JPA · PostgreSQL · Next.js 14 (App Router) · TanStack Query · Tailwind CSS

---

## File Map

### 신규 파일
| 경로 | 역할 |
|---|---|
| `unified-asset/.../domain/asset/AssetLiquidityType.kt` | LIQUID/ILLIQUID 유동성 구분 enum |
| `snapshot/.../entity/BenchmarkDailyEntity.kt` | KOSPI·BTC 일별 종가 엔티티 |
| `snapshot/.../repository/BenchmarkDailyJpaRepository.kt` | 벤치마크 JPA 레포지터리 |
| `backend-app/.../dashboard/DashboardResponse.kt` | API 응답 DTO 모음 |
| `backend-app/.../dashboard/MetricsCalculator.kt` | 지표값→등급·별점 변환 순수 함수 |
| `backend-app/.../dashboard/GetDashboardUseCase.kt` | 대시보드 데이터 집계 유스케이스 |
| `backend-app/.../dashboard/DashboardController.kt` | `GET /api/unified/dashboard` |
| `backend-app/.../market/BenchmarkCollector.kt` | 벤치마크 데이터 스케줄 수집 |
| `backend-app/.../market/MaturityAlertScheduler.kt` | 전세 만기 알림 스케줄러 |
| `frontend/.../types/dashboard.ts` | 대시보드 TypeScript 타입 |
| `frontend/.../lib/dashboard-api.ts` | 대시보드 API 클라이언트 |
| `frontend/.../components/dashboard/NetWorthBar.tsx` | 순자산 바 컴포넌트 |
| `frontend/.../components/dashboard/MetricCard.tsx` | 지표 카드 컴포넌트 |
| `frontend/.../components/dashboard/PositionTable.tsx` | 포지션 테이블 컴포넌트 |
| `frontend/.../components/dashboard/RealAssetCard.tsx` | 실물자산 카드 컴포넌트 |
| `frontend/.../components/dashboard/AllocationBar.tsx` | 자산배분 바 컴포넌트 |

### 수정 파일
| 경로 | 변경 내용 |
|---|---|
| `unified-asset/.../domain/asset/AssetType.kt` | JEONSE 추가 |
| `unified-asset/.../domain/asset/Asset.kt` | maturityDate, liquidityType 필드 추가 |
| `unified-asset/.../infrastructure/entity/AssetEntity.kt` | 동일 컬럼 추가 |
| `unified-asset/.../api/AccountController.kt` | maturityDate 요청 필드 + liquidityType 자동 설정 |
| `infra/postgres/init.sql` | ua_assets 컬럼 추가 + benchmark_daily 테이블 |
| `backend-app/.../config/SecurityConfig.kt` | /api/unified/dashboard 엔드포인트 허용 |
| `frontend/.../app/unified/page.tsx` | 대시보드 페이지 전면 교체 |
| `frontend/.../lib/unified-api.ts` | dashboard.get() 추가 |

---

## Phase 1 — Foundation + 수익률 지표

---

### Task 1: DB 스키마 추가

**Files:**
- Modify: `allfolio-backend/infra/postgres/init.sql`

- [ ] **Step 1: init.sql 하단에 아래 SQL 추가**

```sql
-- ── Bloomberg Dashboard: ua_assets 컬럼 추가 ─────────────────────
ALTER TABLE ua_assets ADD COLUMN IF NOT EXISTS maturity_date   DATE;
ALTER TABLE ua_assets ADD COLUMN IF NOT EXISTS liquidity_type  VARCHAR(20) NOT NULL DEFAULT 'LIQUID';

-- 기존 비유동 자산 백필
UPDATE ua_assets SET liquidity_type = 'ILLIQUID'
WHERE type IN ('REAL_ESTATE', 'JEONSE', 'VEHICLE');

-- ── benchmark_daily ───────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS benchmark_daily (
    index_type   VARCHAR(10)     NOT NULL,  -- KOSPI / BTC
    date         DATE            NOT NULL,
    close_value  NUMERIC(30, 10) NOT NULL,
    created_at   TIMESTAMP       NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_benchmark_daily PRIMARY KEY (index_type, date)
);

CREATE INDEX IF NOT EXISTS idx_benchmark_daily_type_date
    ON benchmark_daily (index_type, date DESC);
```

- [ ] **Step 2: Docker로 로컬 DB에 적용 (이미 컨테이너 실행 중인 경우)**

```bash
docker exec -i allfolio-postgres psql -U postgres -d allfolio < allfolio-backend/infra/postgres/init.sql
```

Expected: 오류 없이 완료 (IF NOT EXISTS이므로 멱등 실행 가능)

- [ ] **Step 3: 컬럼 확인**

```bash
docker exec -i allfolio-postgres psql -U postgres -d allfolio -c "\d ua_assets"
```

Expected: `maturity_date`, `liquidity_type` 컬럼이 보임

- [ ] **Step 4: 커밋**

```bash
git add allfolio-backend/infra/postgres/init.sql
git commit -m "feat: add maturity_date, liquidity_type to ua_assets and benchmark_daily table"
```

---

### Task 2: AssetLiquidityType enum 신규 생성

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/domain/asset/AssetLiquidityType.kt`

- [ ] **Step 1: 파일 생성**

```kotlin
package com.allfolio.unifiedasset.domain.asset

enum class AssetLiquidityType {
    LIQUID,    // 주식·코인 — 기관 지표 계산 대상
    ILLIQUID,  // 전세·부동산·차량 — Net Worth 합산 전용
}
```

- [ ] **Step 2: AssetType.kt에 JEONSE 추가**

파일: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/domain/asset/AssetType.kt`

```kotlin
package com.allfolio.unifiedasset.domain.asset

enum class AssetType {
    STOCK,        // 주식
    CRYPTO,       // 암호화폐
    REAL_ESTATE,  // 부동산 (소유)
    JEONSE,       // 전세보증금 (반환 청구권)
    VEHICLE,      // 자동차
    GOLD,         // 금
    CASH,         // 현금
    ETC,          // 기타
}
```

- [ ] **Step 3: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/domain/asset/AssetLiquidityType.kt
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/domain/asset/AssetType.kt
git commit -m "feat: add AssetLiquidityType enum and JEONSE to AssetType"
```

---

### Task 3: Asset 도메인에 maturityDate·liquidityType 추가

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/domain/asset/Asset.kt`

- [ ] **Step 1: Asset.kt 전체 교체**

```kotlin
package com.allfolio.unifiedasset.domain.asset

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class Asset private constructor(
    val id: UUID,
    val userId: UUID,
    val accountId: UUID,
    val category: AssetCategory,
    val type: AssetType,
    val sourceType: AssetSourceType,
    val name: String,
    val symbol: String?,
    val quantity: BigDecimal,
    val purchasePrice: BigDecimal,
    val currentValue: BigDecimal,
    val currency: String,
    val valuationMethod: ValuationMethod,
    val confidenceLevel: ConfidenceLevel,
    val lastUpdatedAt: LocalDateTime,
    val createdAt: LocalDateTime,
    val memo: String?,
    val subType: String?,
    val loanAmount: BigDecimal?,
    val maturityDate: LocalDate?,
    val liquidityType: AssetLiquidityType,
) {
    fun totalPurchaseCost(): BigDecimal = quantity.multiply(purchasePrice)
    fun unrealizedPnl(): BigDecimal = currentValue.subtract(totalPurchaseCost())
    fun returnRate(): BigDecimal {
        val cost = totalPurchaseCost()
        if (cost <= BigDecimal.ZERO) return BigDecimal.ZERO
        return unrealizedPnl().divide(cost, 6, java.math.RoundingMode.HALF_UP)
            .multiply(BigDecimal(100))
    }
    fun netEquity(): BigDecimal = currentValue.subtract(loanAmount ?: BigDecimal.ZERO)

    companion object {
        private val ILLIQUID_TYPES = setOf(
            AssetType.REAL_ESTATE, AssetType.JEONSE, AssetType.VEHICLE,
        )

        fun create(
            userId: UUID,
            accountId: UUID,
            category: AssetCategory,
            type: AssetType,
            sourceType: AssetSourceType,
            name: String,
            symbol: String?,
            quantity: BigDecimal,
            purchasePrice: BigDecimal,
            currentValue: BigDecimal,
            currency: String,
            valuationMethod: ValuationMethod,
            memo: String? = null,
            subType: String? = null,
            loanAmount: BigDecimal? = null,
            maturityDate: LocalDate? = null,
        ): Asset {
            require(name.isNotBlank()) { "자산명은 필수입니다" }
            require(quantity >= BigDecimal.ZERO) { "수량은 0 이상이어야 합니다" }
            require(currentValue >= BigDecimal.ZERO) { "현재 가치는 0 이상이어야 합니다" }

            val confidence = when (valuationMethod) {
                ValuationMethod.MARKET_PRICE -> ConfidenceLevel.HIGH
                ValuationMethod.BALANCE      -> ConfidenceLevel.HIGH
                ValuationMethod.USER_INPUT   -> ConfidenceLevel.LOW
            }
            val now = LocalDateTime.now()
            return Asset(
                id              = UUID.randomUUID(),
                userId          = userId,
                accountId       = accountId,
                category        = category,
                type            = type,
                sourceType      = sourceType,
                name            = name.trim(),
                symbol          = symbol?.trim()
                    ?.let { if (type == AssetType.CRYPTO || type == AssetType.STOCK) it.uppercase() else it },
                quantity        = quantity,
                purchasePrice   = purchasePrice,
                currentValue    = currentValue,
                currency        = currency.uppercase(),
                valuationMethod = valuationMethod,
                confidenceLevel = confidence,
                lastUpdatedAt   = now,
                createdAt       = now,
                memo            = memo?.trim(),
                subType         = subType?.trim()?.uppercase(),
                loanAmount      = loanAmount,
                maturityDate    = maturityDate,
                liquidityType   = if (type in ILLIQUID_TYPES) AssetLiquidityType.ILLIQUID
                                  else AssetLiquidityType.LIQUID,
            )
        }

        fun reconstruct(
            id: UUID, userId: UUID, accountId: UUID, category: AssetCategory, type: AssetType,
            sourceType: AssetSourceType, name: String, symbol: String?, quantity: BigDecimal,
            purchasePrice: BigDecimal, currentValue: BigDecimal, currency: String,
            valuationMethod: ValuationMethod, confidenceLevel: ConfidenceLevel,
            lastUpdatedAt: LocalDateTime, createdAt: LocalDateTime, memo: String?,
            subType: String? = null, loanAmount: BigDecimal? = null,
            maturityDate: LocalDate? = null,
            liquidityType: AssetLiquidityType = AssetLiquidityType.LIQUID,
        ) = Asset(
            id, userId, accountId, category, type, sourceType, name, symbol,
            quantity, purchasePrice, currentValue, currency, valuationMethod,
            confidenceLevel, lastUpdatedAt, createdAt, memo, subType, loanAmount,
            maturityDate, liquidityType,
        )
    }
}
```

- [ ] **Step 2: 빌드 확인**

```bash
cd allfolio-backend && ./gradlew :unified-asset:compileKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/domain/asset/Asset.kt
git commit -m "feat: add maturityDate and liquidityType to Asset domain"
```

---

### Task 4: AssetEntity 컬럼 추가

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/entity/AssetEntity.kt`

- [ ] **Step 1: AssetEntity.kt 전체 교체**

```kotlin
package com.allfolio.unifiedasset.infrastructure.entity

import com.allfolio.unifiedasset.domain.asset.*
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "ua_assets")
class AssetEntity(
    @Id @Column(columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    val userId: UUID,

    @Column(name = "account_id", nullable = false, columnDefinition = "uuid")
    val accountId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val category: AssetCategory,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val type: AssetType,

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    val sourceType: AssetSourceType,

    @Column(nullable = false)
    val name: String,

    @Column(length = 200)
    val symbol: String?,

    @Column(nullable = false, precision = 30, scale = 10)
    val quantity: BigDecimal,

    @Column(name = "purchase_price", nullable = false, precision = 30, scale = 10)
    val purchasePrice: BigDecimal,

    @Column(name = "current_value", nullable = false, precision = 30, scale = 10)
    val currentValue: BigDecimal,

    @Column(nullable = false, length = 10)
    val currency: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "valuation_method", nullable = false, length = 20)
    val valuationMethod: ValuationMethod,

    @Enumerated(EnumType.STRING)
    @Column(name = "confidence_level", nullable = false, length = 10)
    val confidenceLevel: ConfidenceLevel,

    @Column(name = "last_updated_at", nullable = false)
    val lastUpdatedAt: LocalDateTime,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime,

    @Column(length = 500)
    val memo: String?,

    @Column(name = "sub_type", length = 30)
    val subType: String?,

    @Column(name = "loan_amount", precision = 30, scale = 10)
    val loanAmount: BigDecimal?,

    @Column(name = "maturity_date")
    val maturityDate: LocalDate?,

    @Enumerated(EnumType.STRING)
    @Column(name = "liquidity_type", nullable = false, length = 20)
    val liquidityType: AssetLiquidityType = AssetLiquidityType.LIQUID,
) {
    fun toDomain() = Asset.reconstruct(
        id, userId, accountId, category, type, sourceType, name, symbol,
        quantity, purchasePrice, currentValue, currency, valuationMethod,
        confidenceLevel, lastUpdatedAt, createdAt, memo, subType, loanAmount,
        maturityDate, liquidityType,
    )

    companion object {
        fun fromDomain(a: Asset) = AssetEntity(
            a.id, a.userId, a.accountId, a.category, a.type, a.sourceType,
            a.name, a.symbol, a.quantity, a.purchasePrice, a.currentValue,
            a.currency, a.valuationMethod, a.confidenceLevel,
            a.lastUpdatedAt, a.createdAt, a.memo, a.subType, a.loanAmount,
            a.maturityDate, a.liquidityType,
        )
    }
}
```

- [ ] **Step 2: 빌드 확인**

```bash
cd allfolio-backend && ./gradlew :unified-asset:compileKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/entity/AssetEntity.kt
git commit -m "feat: add maturityDate and liquidityType to AssetEntity"
```

---

### Task 5: AccountController — maturityDate 요청 필드 추가

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/api/AccountController.kt`

- [ ] **Step 1: CreateManualAssetRequest에 maturityDate 추가**

`CreateManualAssetRequest` 데이터 클래스를 아래로 교체:

```kotlin
data class CreateManualAssetRequest(
    @field:NotBlank val name: String,
    val symbol: String?,
    val type: com.allfolio.unifiedasset.domain.asset.AssetType,
    val subType: String? = null,
    val quantity: java.math.BigDecimal,
    val purchasePrice: java.math.BigDecimal,
    val currentValue: java.math.BigDecimal,
    val loanAmount: java.math.BigDecimal? = null,
    val currency: String = "KRW",
    val memo: String?,
    val maturityDate: java.time.LocalDate? = null,
)
```

- [ ] **Step 2: addManualAsset 핸들러의 Asset.create() 호출에 maturityDate 전달**

`addManualAsset` 안의 `Asset.create(...)` 호출을 아래로 교체:

```kotlin
val asset = com.allfolio.unifiedasset.domain.asset.Asset.create(
    userId          = userId,
    accountId       = id,
    category        = category,
    type            = req.type,
    sourceType      = com.allfolio.unifiedasset.domain.asset.AssetSourceType.MANUAL,
    name            = req.name,
    symbol          = req.symbol,
    quantity        = req.quantity,
    purchasePrice   = req.purchasePrice,
    currentValue    = req.currentValue,
    currency        = req.currency,
    valuationMethod = com.allfolio.unifiedasset.domain.asset.ValuationMethod.USER_INPUT,
    memo            = req.memo,
    subType         = req.subType,
    loanAmount      = req.loanAmount,
    maturityDate    = req.maturityDate,
)
```

- [ ] **Step 3: AssetResponse에 maturityDate, liquidityType 추가**

`AssetResponse` 데이터 클래스에 두 필드 추가:

```kotlin
data class AssetResponse(
    val id: UUID,
    val accountId: UUID,
    val name: String,
    val symbol: String?,
    val type: String,
    val subType: String?,
    val category: String,
    val sourceType: String,
    val quantity: java.math.BigDecimal,
    val purchasePrice: java.math.BigDecimal,
    val currentValue: java.math.BigDecimal,
    val loanAmount: java.math.BigDecimal?,
    val netEquity: java.math.BigDecimal,
    val currency: String,
    val valuationMethod: String,
    val confidenceLevel: String,
    val unrealizedPnl: java.math.BigDecimal,
    val returnRate: java.math.BigDecimal,
    val memo: String?,
    val lastUpdatedAt: LocalDateTime,
    val maturityDate: java.time.LocalDate?,
    val liquidityType: String,
)
```

`Asset.toResponse()` 확장 함수 마지막에 두 줄 추가:

```kotlin
fun Asset.toResponse() = AssetResponse(
    id               = id,
    accountId        = accountId,
    name             = name,
    symbol           = symbol,
    type             = type.name,
    subType          = subType,
    category         = category.name,
    sourceType       = sourceType.name,
    quantity         = quantity,
    purchasePrice    = purchasePrice,
    currentValue     = currentValue,
    loanAmount       = loanAmount,
    netEquity        = netEquity(),
    currency         = currency,
    valuationMethod  = valuationMethod.name,
    confidenceLevel  = confidenceLevel.name,
    unrealizedPnl    = unrealizedPnl(),
    returnRate       = returnRate(),
    memo             = memo,
    lastUpdatedAt    = lastUpdatedAt,
    maturityDate     = maturityDate,
    liquidityType    = liquidityType.name,
)
```

- [ ] **Step 4: 빌드 확인**

```bash
cd allfolio-backend && ./gradlew :unified-asset:compileKotlin :backend-app:compileKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/api/AccountController.kt
git commit -m "feat: add maturityDate to CreateManualAssetRequest and AssetResponse"
```

---

### Task 6: BenchmarkDailyEntity + JPA Repository (snapshot 모듈)

**Files:**
- Create: `allfolio-backend/snapshot/src/main/kotlin/com/allfolio/snapshot/infrastructure/entity/BenchmarkDailyEntity.kt`
- Create: `allfolio-backend/snapshot/src/main/kotlin/com/allfolio/snapshot/infrastructure/repository/BenchmarkDailyJpaRepository.kt`

- [ ] **Step 1: BenchmarkDailyEntity 생성**

```kotlin
package com.allfolio.snapshot.infrastructure.entity

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(name = "benchmark_daily")
class BenchmarkDailyEntity(
    @EmbeddedId
    val id: BenchmarkDailyId,

    @Column(name = "close_value", nullable = false, precision = 30, scale = 10)
    val closeValue: BigDecimal,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)

@Embeddable
data class BenchmarkDailyId(
    @Column(name = "index_type", length = 10)
    val indexType: String,
    val date: LocalDate,
) : java.io.Serializable
```

- [ ] **Step 2: BenchmarkDailyJpaRepository 생성**

```kotlin
package com.allfolio.snapshot.infrastructure.repository

import com.allfolio.snapshot.infrastructure.entity.BenchmarkDailyEntity
import com.allfolio.snapshot.infrastructure.entity.BenchmarkDailyId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface BenchmarkDailyJpaRepository : JpaRepository<BenchmarkDailyEntity, BenchmarkDailyId> {

    fun findTopByIdIndexTypeOrderByIdDateDesc(indexType: String): BenchmarkDailyEntity?

    fun findByIdIndexTypeAndIdDateBetween(
        indexType: String,
        from: LocalDate,
        to: LocalDate,
    ): List<BenchmarkDailyEntity>

    @Query(
        "SELECT b FROM BenchmarkDailyEntity b " +
        "WHERE b.id.indexType = :type AND b.id.date <= :before " +
        "ORDER BY b.id.date DESC"
    )
    fun findLatestOnOrBefore(
        @Param("type") indexType: String,
        @Param("before") before: LocalDate,
    ): List<BenchmarkDailyEntity>
}
```

- [ ] **Step 3: 빌드 확인**

```bash
cd allfolio-backend && ./gradlew :snapshot:compileKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add allfolio-backend/snapshot/src/main/kotlin/com/allfolio/snapshot/infrastructure/entity/BenchmarkDailyEntity.kt
git add allfolio-backend/snapshot/src/main/kotlin/com/allfolio/snapshot/infrastructure/repository/BenchmarkDailyJpaRepository.kt
git commit -m "feat: add BenchmarkDailyEntity and JPA repository for KOSPI/BTC benchmark data"
```

---

### Task 7: DashboardResponse + MetricsCalculator (backend-app)

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dashboard/DashboardResponse.kt`
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dashboard/MetricsCalculator.kt`

- [ ] **Step 1: DashboardResponse.kt 생성**

```kotlin
package com.allfolio.dashboard

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class DashboardResponse(
    val netWorth: NetWorthDto,
    val portfolio: PortfolioDto,
    val realAssets: List<RealAssetDto>,
)

data class NetWorthDto(
    val total: BigDecimal,
    val liquid: BigDecimal,
    val illiquid: BigDecimal,
    val debt: BigDecimal,
    val change30d: BigDecimal,
    val changeRate30d: BigDecimal,
)

data class PortfolioDto(
    val totalValue: BigDecimal,
    val currency: String,
    val metrics: MetricsDto,
    val allocation: List<AllocationDto>,
    val positions: List<PositionDto>,
)

data class MetricsDto(
    val returnYtd: MetricValueDto?,
    val return1m: MetricValueDto?,
    val return3m: MetricValueDto?,
    val mdd: MetricValueDto?,
)

data class MetricValueDto(
    val value: BigDecimal,
    val grade: String,
    val stars: Int,
    val benchmarkVsKospi: BigDecimal?,
    val benchmarkVsBtc: BigDecimal?,
    val dataWarning: String?,
)

data class AllocationDto(
    val type: String,
    val ratio: BigDecimal,
    val value: BigDecimal,
    val grade: String,
)

data class PositionDto(
    val id: UUID,
    val name: String,
    val symbol: String?,
    val type: String,
    val currentValue: BigDecimal,
    val returnRate: BigDecimal,
    val weight: BigDecimal,
    val currency: String,
)

data class RealAssetDto(
    val id: UUID,
    val name: String,
    val type: String,
    val value: BigDecimal,
    val currency: String,
    val maturityDate: LocalDate?,
    val daysUntilMaturity: Long?,
)
```

- [ ] **Step 2: MetricsCalculator.kt 생성**

```kotlin
package com.allfolio.dashboard

import java.math.BigDecimal
import java.math.RoundingMode

enum class MetricGrade { EXCELLENT, GOOD, WARN, BAD }

object MetricsCalculator {

    fun returnToGrade(pct: BigDecimal): MetricGrade = when {
        pct >= BigDecimal("15") -> MetricGrade.EXCELLENT
        pct >= BigDecimal("5")  -> MetricGrade.GOOD
        pct >= BigDecimal.ZERO  -> MetricGrade.WARN
        else                    -> MetricGrade.BAD
    }

    fun returnToStars(pct: BigDecimal): Int = when {
        pct >= BigDecimal("20") -> 5
        pct >= BigDecimal("10") -> 4
        pct >= BigDecimal("3")  -> 3
        pct >= BigDecimal.ZERO  -> 2
        else                    -> 1
    }

    fun mddToGrade(mdd: BigDecimal): MetricGrade = when {
        mdd >= BigDecimal("-5")  -> MetricGrade.EXCELLENT
        mdd >= BigDecimal("-15") -> MetricGrade.GOOD
        mdd >= BigDecimal("-30") -> MetricGrade.WARN
        else                     -> MetricGrade.BAD
    }

    fun mddToStars(mdd: BigDecimal): Int = when {
        mdd >= BigDecimal("-5")  -> 5
        mdd >= BigDecimal("-10") -> 4
        mdd >= BigDecimal("-20") -> 3
        mdd >= BigDecimal("-30") -> 2
        else                     -> 1
    }

    fun concentrationToGrade(ratio: BigDecimal): MetricGrade = when {
        ratio <= BigDecimal("0.30") -> MetricGrade.EXCELLENT
        ratio <= BigDecimal("0.50") -> MetricGrade.GOOD
        ratio <= BigDecimal("0.70") -> MetricGrade.WARN
        else                        -> MetricGrade.BAD
    }

    fun dataWarning(dataDays: Int): String? =
        if (dataDays < 30) "단기 데이터 기반 (${dataDays}일)" else null

    fun pctDiff(portfolio: BigDecimal, benchmark: BigDecimal): BigDecimal =
        portfolio.subtract(benchmark).setScale(4, RoundingMode.HALF_UP)

    fun weightOf(value: BigDecimal, total: BigDecimal): BigDecimal =
        if (total <= BigDecimal.ZERO) BigDecimal.ZERO
        else value.divide(total, 4, RoundingMode.HALF_UP)
}
```

- [ ] **Step 3: 빌드 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:compileKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dashboard/DashboardResponse.kt
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dashboard/MetricsCalculator.kt
git commit -m "feat: add DashboardResponse DTOs and MetricsCalculator"
```

---

### Task 8: GetDashboardUseCase

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dashboard/GetDashboardUseCase.kt`

- [ ] **Step 1: GetDashboardUseCase.kt 생성**

```kotlin
package com.allfolio.dashboard

import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.domain.asset.AssetLiquidityType
import com.allfolio.snapshot.infrastructure.repository.PerformanceDailyJpaRepository
import com.allfolio.snapshot.infrastructure.repository.RiskDailyJpaRepository
import com.allfolio.snapshot.infrastructure.repository.BenchmarkDailyJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
@Transactional(readOnly = true)
class GetDashboardUseCase(
    private val assetRepository: AssetRepository,
    private val performanceRepo: PerformanceDailyJpaRepository,
    private val riskRepo: RiskDailyJpaRepository,
    private val benchmarkRepo: BenchmarkDailyJpaRepository,
) {
    fun execute(userId: UUID): DashboardResponse {
        val assets = assetRepository.findByUserId(userId)
        val liquidAssets   = assets.filter { it.liquidityType == AssetLiquidityType.LIQUID }
        val illiquidAssets = assets.filter { it.liquidityType == AssetLiquidityType.ILLIQUID }

        val liquidValue   = liquidAssets.sumOf { it.currentValue }
        val illiquidValue = illiquidAssets.sumOf { it.currentValue }
        val debtValue     = assets.sumOf { it.loanAmount ?: BigDecimal.ZERO }
        val totalNow      = liquidValue.add(illiquidValue).subtract(debtValue)

        // 30일 전 NAV (Net Worth 변화량)
        val today    = LocalDate.now()
        val date30d  = today.minusDays(30)
        val perf30d  = performanceRepo
            .findTopByIdPortfolioIdAndIdDateBeforeOrderByIdDateDesc(userId, date30d.plusDays(1))
        val nav30d   = perf30d?.nav ?: BigDecimal.ZERO
        val change30d    = if (nav30d > BigDecimal.ZERO) totalNow.subtract(nav30d) else BigDecimal.ZERO
        val changeRate30d = if (nav30d > BigDecimal.ZERO)
            change30d.divide(nav30d, 4, RoundingMode.HALF_UP).multiply(BigDecimal(100))
        else BigDecimal.ZERO

        // 수익률 히스토리 조회
        val ytdStart    = LocalDate.of(today.year, 1, 1)
        val perfHistory = performanceRepo.findByIdPortfolioIdAndIdDateBetween(userId, ytdStart, today)
        val dataDays    = perfHistory.size

        val latestPerf  = perfHistory.maxByOrNull { it.id.date }
        val ytdStartPerf = perfHistory.minByOrNull { it.id.date }

        val returnYtd = if (ytdStartPerf != null && ytdStartPerf.nav > BigDecimal.ZERO)
            latestPerf!!.nav.subtract(ytdStartPerf.nav)
                .divide(ytdStartPerf.nav, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
        else null

        val perf1mRef = performanceRepo
            .findTopByIdPortfolioIdAndIdDateBeforeOrderByIdDateDesc(userId, today.minusDays(29))
        val return1m  = if (perf1mRef != null && perf1mRef.nav > BigDecimal.ZERO && latestPerf != null)
            latestPerf.nav.subtract(perf1mRef.nav)
                .divide(perf1mRef.nav, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
        else null

        val perf3mRef = performanceRepo
            .findTopByIdPortfolioIdAndIdDateBeforeOrderByIdDateDesc(userId, today.minusDays(89))
        val return3m  = if (perf3mRef != null && perf3mRef.nav > BigDecimal.ZERO && latestPerf != null)
            latestPerf.nav.subtract(perf3mRef.nav)
                .divide(perf3mRef.nav, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
        else null

        // MDD
        val latestRisk = riskRepo.findTopByIdPortfolioIdOrderByIdDateDesc(userId)
        val mdd = latestRisk?.maxDrawdown?.multiply(BigDecimal(100))

        // 벤치마크 (YTD)
        val kospiNow   = benchmarkRepo.findTopByIdIndexTypeOrderByIdDateDesc("KOSPI")?.closeValue
        val kospiStart = benchmarkRepo.findLatestOnOrBefore("KOSPI", ytdStart.plusDays(5))
            .firstOrNull()?.closeValue
        val kospiYtd   = if (kospiNow != null && kospiStart != null && kospiStart > BigDecimal.ZERO)
            kospiNow.subtract(kospiStart).divide(kospiStart, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
        else null

        fun buildMetric(value: BigDecimal?, grade: MetricGrade, stars: Int,
                        vsKospi: BigDecimal? = null): MetricValueDto? {
            value ?: return null
            return MetricValueDto(
                value            = value.setScale(2, RoundingMode.HALF_UP),
                grade            = grade.name,
                stars            = stars,
                benchmarkVsKospi = vsKospi?.setScale(2, RoundingMode.HALF_UP),
                benchmarkVsBtc   = null,
                dataWarning      = MetricsCalculator.dataWarning(dataDays),
            )
        }

        val metrics = MetricsDto(
            returnYtd = buildMetric(
                returnYtd,
                returnYtd?.let { MetricsCalculator.returnToGrade(it) } ?: MetricGrade.WARN,
                returnYtd?.let { MetricsCalculator.returnToStars(it) } ?: 1,
                vsKospi = if (returnYtd != null && kospiYtd != null)
                    MetricsCalculator.pctDiff(returnYtd, kospiYtd) else null,
            ),
            return1m = buildMetric(
                return1m,
                return1m?.let { MetricsCalculator.returnToGrade(it) } ?: MetricGrade.WARN,
                return1m?.let { MetricsCalculator.returnToStars(it) } ?: 1,
            ),
            return3m = buildMetric(
                return3m,
                return3m?.let { MetricsCalculator.returnToGrade(it) } ?: MetricGrade.WARN,
                return3m?.let { MetricsCalculator.returnToStars(it) } ?: 1,
            ),
            mdd = buildMetric(
                mdd,
                mdd?.let { MetricsCalculator.mddToGrade(it) } ?: MetricGrade.WARN,
                mdd?.let { MetricsCalculator.mddToStars(it) } ?: 1,
            ),
        )

        // 자산 배분 (LIQUID만)
        val totalLiquid = liquidValue.takeIf { it > BigDecimal.ZERO } ?: BigDecimal.ONE
        val allocation  = liquidAssets
            .groupBy { it.type.name }
            .map { (type, list) ->
                val typeValue = list.sumOf { it.currentValue }
                val ratio = MetricsCalculator.weightOf(typeValue, totalLiquid)
                AllocationDto(
                    type  = type,
                    ratio = ratio,
                    value = typeValue,
                    grade = MetricsCalculator.concentrationToGrade(ratio).name,
                )
            }
            .sortedByDescending { it.value }

        // 포지션 테이블 (LIQUID만)
        val positions = liquidAssets
            .sortedByDescending { it.currentValue }
            .map { a ->
                PositionDto(
                    id           = a.id,
                    name         = a.name,
                    symbol       = a.symbol,
                    type         = a.type.name,
                    currentValue = a.currentValue,
                    returnRate   = a.returnRate().setScale(2, RoundingMode.HALF_UP),
                    weight       = MetricsCalculator.weightOf(a.currentValue, totalLiquid)
                        .setScale(4, RoundingMode.HALF_UP),
                    currency     = a.currency,
                )
            }

        // 실물자산
        val realAssets = illiquidAssets
            .sortedByDescending { it.currentValue }
            .map { a ->
                RealAssetDto(
                    id               = a.id,
                    name             = a.name,
                    type             = a.type.name,
                    value            = a.currentValue,
                    currency         = a.currency,
                    maturityDate     = a.maturityDate,
                    daysUntilMaturity = a.maturityDate?.let {
                        ChronoUnit.DAYS.between(today, it).takeIf { d -> d >= 0 }
                    },
                )
            }

        return DashboardResponse(
            netWorth = NetWorthDto(
                total         = totalNow,
                liquid        = liquidValue,
                illiquid      = illiquidValue,
                debt          = debtValue,
                change30d     = change30d,
                changeRate30d = changeRate30d.setScale(2, RoundingMode.HALF_UP),
            ),
            portfolio = PortfolioDto(
                totalValue = liquidValue,
                currency   = "KRW",
                metrics    = metrics,
                allocation = allocation,
                positions  = positions,
            ),
            realAssets = realAssets,
        )
    }
}
```

- [ ] **Step 2: 빌드 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:compileKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dashboard/GetDashboardUseCase.kt
git commit -m "feat: add GetDashboardUseCase aggregating net worth, metrics, and real assets"
```

---

### Task 9: DashboardController + SecurityConfig

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dashboard/DashboardController.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/config/SecurityConfig.kt`

- [ ] **Step 1: DashboardController.kt 생성**

```kotlin
package com.allfolio.dashboard

import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/unified/dashboard")
class DashboardController(private val useCase: GetDashboardUseCase) {

    @GetMapping
    fun get(@RequestHeader("X-User-Id") userId: UUID): DashboardResponse =
        useCase.execute(userId)
}
```

- [ ] **Step 2: SecurityConfig에서 `/api/unified/dashboard` 허용 확인**

`allfolio-backend/backend-app/src/main/kotlin/com/allfolio/config/SecurityConfig.kt` 를 열어 `/api/unified/**` 패턴이 이미 인증 필요로 설정되어 있는지 확인. 기존 패턴이 이미 커버하고 있으면 변경 불필요.

만약 `/api/unified/dashboard` 가 명시적으로 차단되어 있을 경우에만 허용 패턴 추가.

- [ ] **Step 3: 앱 실행 후 엔드포인트 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:bootRun &
# 앱 기동 후 (약 15초)
curl -s -o /dev/null -w "%{http_code}" \
  -H "X-User-Id: 00000000-0000-0000-0000-000000000001" \
  http://localhost:8090/api/unified/dashboard
```

Expected: `200` 또는 `401` (인증 미통과 시) — `404`가 아니면 성공

- [ ] **Step 4: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dashboard/DashboardController.kt
git commit -m "feat: add DashboardController GET /api/unified/dashboard"
```

---

### Task 10: BenchmarkCollector 스케줄러

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/BenchmarkCollector.kt`

- [ ] **Step 1: BenchmarkCollector.kt 생성**

```kotlin
package com.allfolio.market

import com.allfolio.snapshot.infrastructure.entity.BenchmarkDailyEntity
import com.allfolio.snapshot.infrastructure.entity.BenchmarkDailyId
import com.allfolio.snapshot.infrastructure.repository.BenchmarkDailyJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate

@Component
class BenchmarkCollector(
    private val benchmarkRepo: BenchmarkDailyJpaRepository,
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 평일 오후 4시 30분 — KRX 마감 후 KOSPI 종가 수집
    @Scheduled(cron = "0 30 16 * * MON-FRI")
    fun collectKospi() {
        val today = LocalDate.now()
        if (benchmarkRepo.existsById(BenchmarkDailyId("KOSPI", today))) return

        // market_price_tick에서 KOSPI 지수 대용으로 삼성전자(005930) 종가를 임시 사용
        // TODO: KIS API 지수 조회 엔드포인트 연동 후 교체
        val price: BigDecimal? = jdbc.query(
            """SELECT price FROM market_price_tick
               WHERE exchange = 'KIS' AND symbol = '005930'
                 AND tick_timestamp >= ?::date AND tick_timestamp < (?::date + INTERVAL '1 day')
               ORDER BY tick_timestamp DESC LIMIT 1""",
            { rs, _ -> rs.getBigDecimal("price") },
            today, today,
        ).firstOrNull()

        if (price == null) {
            log.warn("BenchmarkCollector: KOSPI data not available for $today")
            return
        }

        benchmarkRepo.save(
            BenchmarkDailyEntity(BenchmarkDailyId("KOSPI", today), price)
        )
        log.info("BenchmarkCollector: KOSPI saved $today=$price")
    }

    // 매일 00:30 — BTC UTC 전일 종가 수집 (market_price_tick 활용)
    @Scheduled(cron = "0 30 0 * * *")
    fun collectBtc() {
        val yesterday = LocalDate.now().minusDays(1)
        if (benchmarkRepo.existsById(BenchmarkDailyId("BTC", yesterday))) return

        val price: BigDecimal? = jdbc.query(
            """SELECT price FROM market_price_tick
               WHERE exchange = 'BINANCE' AND symbol = 'BTCUSDT'
                 AND tick_timestamp >= ?::date AND tick_timestamp < (?::date + INTERVAL '1 day')
               ORDER BY tick_timestamp DESC LIMIT 1""",
            { rs, _ -> rs.getBigDecimal("price") },
            yesterday, yesterday,
        ).firstOrNull()

        if (price == null) {
            log.warn("BenchmarkCollector: BTC data not available for $yesterday")
            return
        }

        benchmarkRepo.save(
            BenchmarkDailyEntity(BenchmarkDailyId("BTC", yesterday), price)
        )
        log.info("BenchmarkCollector: BTC saved $yesterday=$price")
    }
}
```

- [ ] **Step 2: 빌드 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:compileKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/BenchmarkCollector.kt
git commit -m "feat: add BenchmarkCollector scheduler for KOSPI and BTC daily close"
```

---

### Task 11: 프론트엔드 타입 + API 클라이언트

**Files:**
- Create: `frontend/allfolio_app/types/dashboard.ts`
- Modify: `frontend/allfolio_app/lib/unified-api.ts`

- [ ] **Step 1: dashboard.ts 타입 생성**

```typescript
// frontend/allfolio_app/types/dashboard.ts

export type MetricGrade = 'EXCELLENT' | 'GOOD' | 'WARN' | 'BAD'

export interface MetricValue {
  value: number
  grade: MetricGrade
  stars: number
  benchmarkVsKospi: number | null
  benchmarkVsBtc: number | null
  dataWarning: string | null
}

export interface AllocationItem {
  type: string
  ratio: number
  value: number
  grade: MetricGrade
}

export interface Position {
  id: string
  name: string
  symbol: string | null
  type: string
  currentValue: number
  returnRate: number
  weight: number
  currency: string
}

export interface RealAsset {
  id: string
  name: string
  type: string
  value: number
  currency: string
  maturityDate: string | null
  daysUntilMaturity: number | null
}

export interface DashboardMetrics {
  returnYtd: MetricValue | null
  return1m: MetricValue | null
  return3m: MetricValue | null
  mdd: MetricValue | null
}

export interface DashboardResponse {
  netWorth: {
    total: number
    liquid: number
    illiquid: number
    debt: number
    change30d: number
    changeRate30d: number
  }
  portfolio: {
    totalValue: number
    currency: string
    metrics: DashboardMetrics
    allocation: AllocationItem[]
    positions: Position[]
  }
  realAssets: RealAsset[]
}
```

- [ ] **Step 2: unified-api.ts에 dashboard 추가**

`createUnifiedApi` 반환 객체에 아래 블록 추가:

```typescript
dashboard: {
  get: async (): Promise<DashboardResponse> =>
    (await api.get<DashboardResponse>('/dashboard')).data,
},
```

그리고 상단 import에 `DashboardResponse` 추가:

```typescript
import type { DashboardResponse } from '@/types/dashboard'
```

- [ ] **Step 3: 타입 체크**

```bash
cd frontend/allfolio_app && npx tsc --noEmit
```

Expected: 오류 없음

- [ ] **Step 4: 커밋**

```bash
git add frontend/allfolio_app/types/dashboard.ts frontend/allfolio_app/lib/unified-api.ts
git commit -m "feat: add DashboardResponse types and dashboard API client"
```

---

### Task 12: NetWorthBar 컴포넌트

**Files:**
- Create: `frontend/allfolio_app/components/dashboard/NetWorthBar.tsx`

- [ ] **Step 1: 파일 생성**

```tsx
// frontend/allfolio_app/components/dashboard/NetWorthBar.tsx
'use client'

interface NetWorthBarProps {
  total: number
  liquid: number
  illiquid: number
  debt: number
  change30d: number
  changeRate30d: number
  currency?: string
}

function fmt(n: number) {
  if (Math.abs(n) >= 100_000_000)
    return `${(n / 100_000_000).toFixed(1)}억`
  if (Math.abs(n) >= 10_000)
    return `${Math.round(n / 10_000).toLocaleString('ko-KR')}만`
  return n.toLocaleString('ko-KR')
}

function fmtFull(n: number) {
  return `₩${n.toLocaleString('ko-KR')}`
}

export default function NetWorthBar({
  total, liquid, illiquid, debt, change30d, changeRate30d,
}: NetWorthBarProps) {
  const isUp = changeRate30d >= 0

  return (
    <div className="rounded-xl border border-gray-700 bg-gray-900 px-6 py-5">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        {/* 왼쪽: 순자산 총액 */}
        <div>
          <p className="text-xs font-medium uppercase tracking-widest text-gray-500">
            총 순자산 (Net Worth)
          </p>
          <p className="mt-1 text-3xl font-bold tabular-nums text-white">
            {fmtFull(total)}
          </p>
          <p className={`mt-1 text-sm tabular-nums ${isUp ? 'text-emerald-400' : 'text-red-400'}`}>
            {isUp ? '+' : ''}{fmtFull(change30d)} ({isUp ? '+' : ''}{changeRate30d.toFixed(2)}%)
            <span className="ml-1 text-xs text-gray-600">30일 전 대비</span>
          </p>
        </div>

        {/* 오른쪽: 구성 */}
        <div className="flex gap-6">
          <div className="text-center">
            <p className="text-lg font-semibold tabular-nums text-emerald-400">
              {fmt(liquid)}
            </p>
            <p className="mt-0.5 text-xs text-gray-500">투자자산</p>
          </div>
          <div className="text-gray-700 text-xl font-light">·</div>
          <div className="text-center">
            <p className="text-lg font-semibold tabular-nums text-blue-400">
              {fmt(illiquid)}
            </p>
            <p className="mt-0.5 text-xs text-gray-500">실물자산</p>
          </div>
          <div className="text-gray-700 text-xl font-light">·</div>
          <div className="text-center">
            <p className="text-lg font-semibold tabular-nums text-gray-500">
              -{fmt(debt)}
            </p>
            <p className="mt-0.5 text-xs text-gray-500">부채</p>
          </div>
        </div>
      </div>
    </div>
  )
}
```

- [ ] **Step 2: 커밋**

```bash
git add frontend/allfolio_app/components/dashboard/NetWorthBar.tsx
git commit -m "feat: add NetWorthBar component"
```

---

### Task 13: MetricCard 컴포넌트

**Files:**
- Create: `frontend/allfolio_app/components/dashboard/MetricCard.tsx`

- [ ] **Step 1: 파일 생성**

```tsx
// frontend/allfolio_app/components/dashboard/MetricCard.tsx
'use client'

import type { MetricGrade, MetricValue } from '@/types/dashboard'

const GRADE_STYLES: Record<MetricGrade, { badge: string; label: string }> = {
  EXCELLENT: { badge: 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/30', label: '우수' },
  GOOD:      { badge: 'bg-blue-500/20 text-blue-400 border border-blue-500/30',         label: '양호' },
  WARN:      { badge: 'bg-yellow-500/20 text-yellow-400 border border-yellow-500/30',   label: '주의' },
  BAD:       { badge: 'bg-red-500/20 text-red-400 border border-red-500/30',             label: '위험' },
}

const VALUE_COLORS: Record<MetricGrade, string> = {
  EXCELLENT: 'text-emerald-400',
  GOOD:      'text-blue-400',
  WARN:      'text-yellow-400',
  BAD:       'text-red-400',
}

interface MetricCardProps {
  label: string
  metric: MetricValue
  formatValue?: (v: number) => string
  benchmarkLabel?: string
  description?: string
}

function Stars({ count }: { count: number }) {
  return (
    <span className="text-sm">
      {Array.from({ length: 5 }).map((_, i) => (
        <span key={i} className={i < count ? 'text-yellow-400' : 'text-gray-700'}>★</span>
      ))}
    </span>
  )
}

export default function MetricCard({
  label, metric, formatValue, benchmarkLabel, description,
}: MetricCardProps) {
  const { badge, label: gradeLabel } = GRADE_STYLES[metric.grade]
  const valueColor = VALUE_COLORS[metric.grade]
  const displayValue = formatValue ? formatValue(metric.value) : `${metric.value >= 0 ? '+' : ''}${metric.value.toFixed(2)}%`

  return (
    <div className="rounded-xl border border-gray-700 bg-gray-900 p-5">
      <div className="flex items-start justify-between mb-3">
        <p className="text-xs font-medium uppercase tracking-wider text-gray-500">{label}</p>
        <span className={`rounded-full px-2.5 py-0.5 text-xs font-semibold ${badge}`}>
          {gradeLabel}
        </span>
      </div>

      <p className={`text-2xl font-bold tabular-nums ${valueColor}`}>{displayValue}</p>
      <Stars count={metric.stars} />

      {metric.benchmarkVsKospi != null && (
        <div className="mt-3 rounded-lg bg-gray-800 px-3 py-2">
          <p className="text-xs text-gray-500">{benchmarkLabel ?? '코스피 대비'}</p>
          <p className="text-sm font-medium">
            <span className={metric.benchmarkVsKospi >= 0 ? 'text-emerald-400' : 'text-red-400'}>
              {metric.benchmarkVsKospi >= 0 ? '+' : ''}{metric.benchmarkVsKospi.toFixed(2)}%p
            </span>
            <span className="ml-1 text-gray-500 text-xs">초과수익</span>
          </p>
        </div>
      )}

      {description && (
        <div className="mt-2 rounded-lg bg-gray-800 px-3 py-2">
          <p className="text-xs text-gray-400 leading-relaxed">{description}</p>
        </div>
      )}

      {metric.dataWarning && (
        <div className="mt-2 flex items-center gap-1.5 text-xs text-yellow-600">
          <span>⚠</span>
          <span>{metric.dataWarning}</span>
        </div>
      )}
    </div>
  )
}
```

- [ ] **Step 2: 커밋**

```bash
git add frontend/allfolio_app/components/dashboard/MetricCard.tsx
git commit -m "feat: add MetricCard component with grade badge and benchmark comparison"
```

---

### Task 14: PositionTable 컴포넌트

**Files:**
- Create: `frontend/allfolio_app/components/dashboard/PositionTable.tsx`

- [ ] **Step 1: 파일 생성**

```tsx
// frontend/allfolio_app/components/dashboard/PositionTable.tsx
'use client'

import type { Position } from '@/types/dashboard'

const TYPE_COLORS: Record<string, string> = {
  CRYPTO: '#f59e0b', STOCK: '#3b82f6', GOLD: '#eab308',
  CASH: '#6b7280', ETC: '#ec4899',
}
const TYPE_KO: Record<string, string> = {
  CRYPTO: '코인', STOCK: '주식', GOLD: '금', CASH: '현금', ETC: '기타',
}

interface PositionTableProps {
  positions: Position[]
}

export default function PositionTable({ positions }: PositionTableProps) {
  if (positions.length === 0) {
    return (
      <div className="rounded-xl border border-gray-700 bg-gray-900 py-12 text-center text-sm text-gray-500">
        투자 포지션 없음
      </div>
    )
  }

  return (
    <div className="rounded-xl border border-gray-700 bg-gray-900 overflow-hidden">
      <div className="px-6 py-4 border-b border-gray-700">
        <h3 className="text-sm font-semibold text-gray-300">포지션 ({positions.length})</h3>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-xs text-gray-500 border-b border-gray-800">
              <th className="px-6 py-3 font-medium">자산명</th>
              <th className="px-4 py-3 font-medium">유형</th>
              <th className="px-4 py-3 text-right font-medium">평가액</th>
              <th className="px-4 py-3 text-right font-medium">수익률</th>
              <th className="px-4 py-3 text-right font-medium">비중</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-800">
            {positions.map((p) => {
              const ret = p.returnRate
              const color = TYPE_COLORS[p.type] ?? '#9ca3af'
              return (
                <tr key={p.id} className="hover:bg-gray-800/50 transition-colors">
                  <td className="px-6 py-3">
                    <div className="font-medium text-gray-100">{p.name}</div>
                    {p.symbol && <div className="text-xs text-gray-500">{p.symbol}</div>}
                  </td>
                  <td className="px-4 py-3">
                    <span
                      className="rounded-full px-2 py-0.5 text-xs font-medium"
                      style={{ background: `${color}20`, color }}
                    >
                      {TYPE_KO[p.type] ?? p.type}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums text-gray-200">
                    ₩{p.currentValue.toLocaleString('ko-KR')}
                  </td>
                  <td className={`px-4 py-3 text-right tabular-nums ${ret >= 0 ? 'text-emerald-400' : 'text-red-400'}`}>
                    {ret >= 0 ? '+' : ''}{ret.toFixed(2)}%
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums text-gray-400">
                    {(p.weight * 100).toFixed(1)}%
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </div>
  )
}
```

- [ ] **Step 2: 커밋**

```bash
git add frontend/allfolio_app/components/dashboard/PositionTable.tsx
git commit -m "feat: add PositionTable component"
```

---

### Task 15: 대시보드 페이지 (Phase 1 버전)

**Files:**
- Modify: `frontend/allfolio_app/app/unified/page.tsx`

- [ ] **Step 1: unified/page.tsx 전면 교체**

```tsx
'use client'

import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useUnifiedApi } from '@/lib/useApi'
import { useLivePrices } from '@/lib/useLivePrices'
import NetWorthBar from '@/components/dashboard/NetWorthBar'
import MetricCard from '@/components/dashboard/MetricCard'
import PositionTable from '@/components/dashboard/PositionTable'
import type { DashboardResponse } from '@/types/dashboard'

const MDD_DESC = (v: number) =>
  `최근 1년 중 가장 크게 떨어졌을 때 ${v.toFixed(1)}%였어요. 낮을수록 손실 관리가 잘 된 포트폴리오예요.`

export default function UnifiedDashboard() {
  const api = useUnifiedApi()
  const { connected: liveConnected } = useLivePrices()

  const { data, isLoading, isError, error } = useQuery<DashboardResponse>({
    queryKey: ['dashboard'],
    queryFn:  () => api!.dashboard.get(),
    enabled:  !!api,
    staleTime: 60_000,
  })

  if (isLoading || !api) return <PageSkeleton />
  if (isError)   return <ErrorBox message={(error as Error).message} />
  if (!data)     return null

  const { netWorth, portfolio, realAssets } = data
  const hasMetrics = Object.values(portfolio.metrics).some(Boolean)

  return (
    <div className="space-y-8">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">통합 자산 대시보드</h1>
          <p className="mt-1 text-sm text-gray-400">모든 자산을 한눈에</p>
        </div>
        <div className="flex items-center gap-3">
          <span className="flex items-center gap-1.5 text-xs text-gray-500">
            <span className={`h-2 w-2 rounded-full ${liveConnected ? 'bg-emerald-400 animate-pulse' : 'bg-gray-600'}`} />
            {liveConnected ? '실시간' : '연결 중'}
          </span>
          <Link
            href="/unified/accounts"
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium hover:bg-blue-500 transition-colors"
          >
            계좌 관리
          </Link>
        </div>
      </div>

      {/* 순자산 바 */}
      <NetWorthBar
        total={netWorth.total}
        liquid={netWorth.liquid}
        illiquid={netWorth.illiquid}
        debt={netWorth.debt}
        change30d={netWorth.change30d}
        changeRate30d={netWorth.changeRate30d}
      />

      {/* 섹션 1: 투자 포트폴리오 */}
      <section>
        <div className="mb-4 flex items-center gap-2">
          <span className="h-4 w-1 rounded-full bg-blue-500" />
          <h2 className="text-sm font-semibold text-gray-300 uppercase tracking-wider">
            투자 포트폴리오
          </h2>
          <span className="text-xs text-gray-600">
            ₩{portfolio.totalValue.toLocaleString('ko-KR')}
          </span>
        </div>

        {/* 지표 카드 */}
        {hasMetrics ? (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4 mb-6">
            {portfolio.metrics.returnYtd && (
              <MetricCard
                label="연간 수익률 (YTD)"
                metric={portfolio.metrics.returnYtd}
                benchmarkLabel="코스피 대비"
              />
            )}
            {portfolio.metrics.return1m && (
              <MetricCard
                label="1개월 수익률"
                metric={portfolio.metrics.return1m}
              />
            )}
            {portfolio.metrics.return3m && (
              <MetricCard
                label="3개월 수익률"
                metric={portfolio.metrics.return3m}
              />
            )}
            {portfolio.metrics.mdd && (
              <MetricCard
                label="최대 낙폭 (MDD)"
                metric={portfolio.metrics.mdd}
                description={MDD_DESC(portfolio.metrics.mdd.value)}
              />
            )}
          </div>
        ) : (
          <div className="mb-6 rounded-xl border border-gray-800 bg-gray-900/50 py-8 text-center text-sm text-gray-500">
            자산을 sync하면 수익률 지표가 표시됩니다
          </div>
        )}

        <PositionTable positions={portfolio.positions} />
      </section>

      {/* 섹션 2: 실물·고정 자산 */}
      {realAssets.length > 0 && (
        <section>
          <div className="mb-4 flex items-center gap-2">
            <span className="h-4 w-1 rounded-full bg-yellow-500" />
            <h2 className="text-sm font-semibold text-gray-300 uppercase tracking-wider">
              실물·고정 자산
            </h2>
          </div>
          <div className="space-y-3">
            {realAssets.map((a) => {
              const days = a.daysUntilMaturity
              const urgent = days != null && days <= 7
              const warn   = days != null && days <= 30 && !urgent
              return (
                <div
                  key={a.id}
                  className={`rounded-xl border bg-gray-900 px-5 py-4 flex items-center justify-between
                    ${urgent ? 'border-red-700' : warn ? 'border-yellow-700' : 'border-gray-700'}`}
                >
                  <div>
                    <p className="font-medium text-gray-100">{a.name}</p>
                    <p className="text-xs text-gray-500 mt-0.5">{a.type}</p>
                  </div>
                  <div className="text-right">
                    <p className="font-semibold text-yellow-400 tabular-nums">
                      ₩{a.value.toLocaleString('ko-KR')}
                    </p>
                    {days != null && (
                      <p className={`text-xs mt-0.5 ${urgent ? 'text-red-400 font-semibold' : warn ? 'text-yellow-400' : 'text-gray-500'}`}>
                        만기 D-{days}
                        {urgent && <span className="ml-1 rounded bg-red-900 px-1 py-0.5 text-xs">만기 임박</span>}
                      </p>
                    )}
                  </div>
                </div>
              )
            })}
          </div>
        </section>
      )}
    </div>
  )
}

function PageSkeleton() {
  return (
    <div className="space-y-8">
      <div className="h-8 w-48 animate-pulse rounded bg-gray-800" />
      <div className="h-28 animate-pulse rounded-xl bg-gray-800" />
      <div className="grid gap-4 sm:grid-cols-4">
        {[1,2,3,4].map(i => <div key={i} className="h-36 animate-pulse rounded-xl bg-gray-800" />)}
      </div>
      <div className="h-48 animate-pulse rounded-xl bg-gray-800" />
    </div>
  )
}
function ErrorBox({ message }: { message: string }) {
  return (
    <div className="rounded-xl border border-red-800 bg-red-950 p-6">
      <p className="text-sm font-medium text-red-400">오류 발생</p>
      <p className="mt-1 text-sm text-red-500">{message}</p>
    </div>
  )
}
```

- [ ] **Step 2: 개발 서버 실행 후 브라우저 확인**

```bash
cd frontend/allfolio_app && npm run dev
```

브라우저에서 `http://localhost:3000/unified` 접속 → 순자산 바·지표 카드·포지션 테이블이 렌더링되는지 확인.

- [ ] **Step 3: 타입 체크**

```bash
cd frontend/allfolio_app && npx tsc --noEmit
```

Expected: 오류 없음

- [ ] **Step 4: 커밋**

```bash
git add frontend/allfolio_app/app/unified/page.tsx
git commit -m "feat: replace unified dashboard with Bloomberg-style layout (Phase 1: 수익률 지표)"
```

---

## Phase 2 — 자산 배분 + 실물자산

---

### Task 16: AllocationBar 컴포넌트

**Files:**
- Create: `frontend/allfolio_app/components/dashboard/AllocationBar.tsx`

- [ ] **Step 1: 파일 생성**

```tsx
// frontend/allfolio_app/components/dashboard/AllocationBar.tsx
'use client'

import type { AllocationItem, MetricGrade } from '@/types/dashboard'

const TYPE_COLORS: Record<string, string> = {
  CRYPTO: '#f59e0b', STOCK: '#3b82f6', GOLD: '#eab308', CASH: '#6b7280', ETC: '#ec4899',
}
const TYPE_KO: Record<string, string> = {
  CRYPTO: '암호화폐', STOCK: '주식', GOLD: '금', CASH: '현금', ETC: '기타',
}
const WARN_TEXT: Record<MetricGrade, string> = {
  EXCELLENT: '분산 양호',
  GOOD: '적정 수준',
  WARN: '집중도 주의',
  BAD: '집중도 위험',
}

interface AllocationBarProps {
  allocation: AllocationItem[]
}

export default function AllocationBar({ allocation }: AllocationBarProps) {
  const topItem = allocation[0]
  const topGrade = topItem?.grade as MetricGrade | undefined

  return (
    <div className="rounded-xl border border-gray-700 bg-gray-900 p-5">
      <div className="flex items-center justify-between mb-4">
        <h3 className="text-sm font-semibold text-gray-300 uppercase tracking-wider">
          자산 배분
        </h3>
        {topGrade && (
          <span className={`text-xs font-medium ${
            topGrade === 'EXCELLENT' ? 'text-emerald-400' :
            topGrade === 'GOOD'      ? 'text-blue-400' :
            topGrade === 'WARN'      ? 'text-yellow-400' : 'text-red-400'
          }`}>
            {WARN_TEXT[topGrade]}
          </span>
        )}
      </div>

      <div className="space-y-3">
        {allocation.map((item) => {
          const color = TYPE_COLORS[item.type] ?? '#9ca3af'
          const pct = (item.ratio * 100).toFixed(1)
          return (
            <div key={item.type} className="flex items-center gap-3">
              <span className="h-3 w-3 shrink-0 rounded-full" style={{ background: color }} />
              <span className="w-16 text-sm text-gray-300">{TYPE_KO[item.type] ?? item.type}</span>
              <div className="flex-1 h-2 rounded-full bg-gray-800 overflow-hidden">
                <div
                  className="h-full rounded-full transition-all"
                  style={{ width: `${pct}%`, background: color }}
                />
              </div>
              <span className="w-12 text-right text-sm tabular-nums text-gray-300">{pct}%</span>
            </div>
          )
        })}
      </div>

      {topItem && Number(topItem.ratio) > 0.5 && (
        <div className="mt-4 rounded-lg bg-yellow-900/20 border border-yellow-700/30 px-3 py-2">
          <p className="text-xs text-yellow-400">
            {TYPE_KO[topItem.type] ?? topItem.type} 집중도가 {(Number(topItem.ratio) * 100).toFixed(0)}%로 높아요.
            단일 자산이 50% 이상이면 변동성이 커집니다.
          </p>
        </div>
      )}
    </div>
  )
}
```

- [ ] **Step 2: 커밋**

```bash
git add frontend/allfolio_app/components/dashboard/AllocationBar.tsx
git commit -m "feat: add AllocationBar component with concentration warning"
```

---

### Task 17: 대시보드 페이지에 배분 섹션 추가

**Files:**
- Modify: `frontend/allfolio_app/app/unified/page.tsx`

- [ ] **Step 1: AllocationBar import 추가**

파일 상단 import 목록에 추가:

```typescript
import AllocationBar from '@/components/dashboard/AllocationBar'
```

- [ ] **Step 2: 포지션 테이블 위에 AllocationBar 삽입**

`<PositionTable positions={portfolio.positions} />` 위에 아래 코드 삽입:

```tsx
{portfolio.allocation.length > 0 && (
  <div className="mb-6">
    <AllocationBar allocation={portfolio.allocation} />
  </div>
)}
```

- [ ] **Step 3: 브라우저 확인 + 커밋**

```bash
cd frontend/allfolio_app && npx tsc --noEmit
git add frontend/allfolio_app/app/unified/page.tsx
git commit -m "feat: add allocation section to dashboard (Phase 2)"
```

---

### Task 18: MaturityAlertScheduler

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/MaturityAlertScheduler.kt`

- [ ] **Step 1: 파일 생성**

```kotlin
package com.allfolio.market

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate

@Component
class MaturityAlertScheduler(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val ALERT_DAYS = listOf(30L, 7L, 1L)

    // 매일 오전 7시 — 만기 임박 자산 로그 출력 (추후 알림 채널 연동 확장)
    @Scheduled(cron = "0 0 7 * * *")
    fun checkMaturityAlerts() {
        val today = LocalDate.now()
        ALERT_DAYS.forEach { days ->
            val targetDate = today.plusDays(days)
            val assets = jdbc.query(
                """SELECT id, user_id, name, maturity_date
                   FROM ua_assets
                   WHERE maturity_date = ? AND liquidity_type = 'ILLIQUID'""",
                { rs, _ ->
                    Triple(
                        rs.getString("user_id"),
                        rs.getString("name"),
                        rs.getDate("maturity_date").toLocalDate()
                    )
                },
                targetDate,
            )
            assets.forEach { (userId, name, date) ->
                log.warn("MaturityAlert: userId=$userId asset='$name' matures=$date (D-$days)")
            }
        }
    }
}
```

- [ ] **Step 2: 빌드 + 커밋**

```bash
cd allfolio-backend && ./gradlew :backend-app:compileKotlin
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/MaturityAlertScheduler.kt
git commit -m "feat: add MaturityAlertScheduler for D-30/7/1 alerts"
```

---

## Phase 3 — 리스크 지표 (Sharpe Ratio)

---

### Task 19: Sharpe Ratio 계산 + API 추가

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dashboard/DashboardResponse.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dashboard/MetricsCalculator.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dashboard/GetDashboardUseCase.kt`

- [ ] **Step 1: DashboardResponse.kt의 MetricsDto에 sharpe, var95, volatility 추가**

`MetricsDto`를 아래로 교체:

```kotlin
data class MetricsDto(
    val returnYtd: MetricValueDto?,
    val return1m: MetricValueDto?,
    val return3m: MetricValueDto?,
    val mdd: MetricValueDto?,
    val sharpe: MetricValueDto?,
    val var95: MetricValueDto?,
    val volatility: MetricValueDto?,
)
```

- [ ] **Step 2: MetricsCalculator.kt에 Sharpe 등급 함수 추가**

파일 끝에 아래 추가:

```kotlin
fun sharpeToGrade(v: BigDecimal): MetricGrade = when {
    v >= BigDecimal("2.0") -> MetricGrade.EXCELLENT
    v >= BigDecimal("1.0") -> MetricGrade.GOOD
    v >= BigDecimal.ZERO   -> MetricGrade.WARN
    else                   -> MetricGrade.BAD
}

fun sharpeToStars(v: BigDecimal): Int = when {
    v >= BigDecimal("2.0") -> 5
    v >= BigDecimal("1.5") -> 4
    v >= BigDecimal("1.0") -> 3
    v >= BigDecimal("0.5") -> 2
    else                   -> 1
}

fun volatilityToGrade(annualPct: BigDecimal): MetricGrade = when {
    annualPct <= BigDecimal("10")  -> MetricGrade.EXCELLENT
    annualPct <= BigDecimal("20")  -> MetricGrade.GOOD
    annualPct <= BigDecimal("40")  -> MetricGrade.WARN
    else                           -> MetricGrade.BAD
}
```

- [ ] **Step 3: GetDashboardUseCase.kt에 Sharpe·VaR·변동성 계산 추가**

`execute()` 함수 안에서 `metrics = MetricsDto(...)` 를 빌드하는 부분을 아래로 교체:

```kotlin
// Phase 3: Sharpe, VaR, 변동성 (risk_daily에서 조회)
val riskHistory = riskRepo.findByIdPortfolioIdAndIdDateBetween(userId, ytdStart, today)
val latestRisk2 = riskHistory.maxByOrNull { it.id.date }

// Sharpe = (누적수익률 연환산 - 무위험수익률) / 연환산변동성
// 무위험수익률: 한국 기준금리 3.5% 하드코딩 (분기 수동 업데이트)
val riskFreeRate = BigDecimal("3.5")
val annualVol    = latestRisk2?.annualizedVolatility?.multiply(BigDecimal(100))
val sharpe = if (returnYtd != null && annualVol != null && annualVol > BigDecimal.ZERO && riskHistory.size >= 10)
    returnYtd.subtract(riskFreeRate).divide(annualVol, 4, RoundingMode.HALF_UP)
else null

val var95Amount = latestRisk2?.var95?.multiply(liquidValue)

val metrics = MetricsDto(
    returnYtd = buildMetric(
        returnYtd,
        returnYtd?.let { MetricsCalculator.returnToGrade(it) } ?: MetricGrade.WARN,
        returnYtd?.let { MetricsCalculator.returnToStars(it) } ?: 1,
        vsKospi = if (returnYtd != null && kospiYtd != null)
            MetricsCalculator.pctDiff(returnYtd, kospiYtd) else null,
    ),
    return1m = buildMetric(
        return1m,
        return1m?.let { MetricsCalculator.returnToGrade(it) } ?: MetricGrade.WARN,
        return1m?.let { MetricsCalculator.returnToStars(it) } ?: 1,
    ),
    return3m = buildMetric(
        return3m,
        return3m?.let { MetricsCalculator.returnToGrade(it) } ?: MetricGrade.WARN,
        return3m?.let { MetricsCalculator.returnToStars(it) } ?: 1,
    ),
    mdd = buildMetric(
        mdd,
        mdd?.let { MetricsCalculator.mddToGrade(it) } ?: MetricGrade.WARN,
        mdd?.let { MetricsCalculator.mddToStars(it) } ?: 1,
    ),
    sharpe = if (sharpe != null) MetricValueDto(
        value            = sharpe.setScale(2, RoundingMode.HALF_UP),
        grade            = MetricsCalculator.sharpeToGrade(sharpe).name,
        stars            = MetricsCalculator.sharpeToStars(sharpe),
        benchmarkVsKospi = null,
        benchmarkVsBtc   = null,
        dataWarning      = MetricsCalculator.dataWarning(dataDays),
    ) else null,
    var95 = if (var95Amount != null) MetricValueDto(
        value            = var95Amount.setScale(0, RoundingMode.HALF_UP),
        grade            = MetricGrade.WARN.name,
        stars            = 2,
        benchmarkVsKospi = null,
        benchmarkVsBtc   = null,
        dataWarning      = MetricsCalculator.dataWarning(dataDays),
    ) else null,
    volatility = if (annualVol != null) MetricValueDto(
        value            = annualVol.setScale(2, RoundingMode.HALF_UP),
        grade            = MetricsCalculator.volatilityToGrade(annualVol).name,
        stars            = when (MetricsCalculator.volatilityToGrade(annualVol)) {
            MetricGrade.EXCELLENT -> 5; MetricGrade.GOOD -> 4
            MetricGrade.WARN -> 2; MetricGrade.BAD -> 1
        },
        benchmarkVsKospi = null,
        benchmarkVsBtc   = null,
        dataWarning      = MetricsCalculator.dataWarning(dataDays),
    ) else null,
)
```

- [ ] **Step 4: 빌드 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:compileKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dashboard/DashboardResponse.kt
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dashboard/MetricsCalculator.kt
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dashboard/GetDashboardUseCase.kt
git commit -m "feat: add Sharpe ratio, VaR, volatility to dashboard metrics (Phase 3)"
```

---

### Task 20: 프론트엔드 리스크 카드 추가

**Files:**
- Modify: `frontend/allfolio_app/types/dashboard.ts`
- Modify: `frontend/allfolio_app/app/unified/page.tsx`

- [ ] **Step 1: DashboardMetrics 타입에 sharpe, var95, volatility 추가**

`types/dashboard.ts`의 `DashboardMetrics`를 아래로 교체:

```typescript
export interface DashboardMetrics {
  returnYtd: MetricValue | null
  return1m: MetricValue | null
  return3m: MetricValue | null
  mdd: MetricValue | null
  sharpe: MetricValue | null
  var95: MetricValue | null
  volatility: MetricValue | null
}
```

- [ ] **Step 2: unified/page.tsx에 리스크 카드 추가**

Phase 1에서 추가한 지표 카드 grid 아래에 리스크 섹션 추가:

```tsx
{/* 리스크 지표 카드 */}
{(portfolio.metrics.sharpe || portfolio.metrics.var95 || portfolio.metrics.volatility) && (
  <div className="mt-4">
    <div className="mb-3 flex items-center gap-2">
      <span className="h-3 w-1 rounded-full bg-red-500" />
      <p className="text-xs font-medium uppercase tracking-wider text-gray-500">리스크 분석</p>
    </div>
    <div className="grid gap-4 sm:grid-cols-3">
      {portfolio.metrics.sharpe && (
        <MetricCard
          label="샤프 지수 (Sharpe)"
          metric={portfolio.metrics.sharpe}
          formatValue={(v) => v.toFixed(2)}
          description={`1.0 이상이면 리스크 대비 수익이 양호해요. (무위험수익률 3.5% 기준)`}
        />
      )}
      {portfolio.metrics.var95 && (
        <MetricCard
          label="VaR 95%"
          metric={portfolio.metrics.var95}
          formatValue={(v) => `₩${Math.abs(v).toLocaleString('ko-KR')}`}
          description="최악의 날 (5% 확률) 예상 최대 손실액이에요."
        />
      )}
      {portfolio.metrics.volatility && (
        <MetricCard
          label="연간 변동성"
          metric={portfolio.metrics.volatility}
          description="포트폴리오의 가격 변동 폭이에요. 낮을수록 안정적이에요."
        />
      )}
    </div>
  </div>
)}
```

- [ ] **Step 3: 타입 체크 + 브라우저 확인**

```bash
cd frontend/allfolio_app && npx tsc --noEmit && npm run dev
```

`http://localhost:3000/unified` 에서 리스크 카드가 나타나는지 확인.

- [ ] **Step 4: 커밋**

```bash
git add frontend/allfolio_app/types/dashboard.ts frontend/allfolio_app/app/unified/page.tsx
git commit -m "feat: add Sharpe, VaR, volatility cards to dashboard (Phase 3 complete)"
```

---

## 자체 검토 결과

**스펙 커버리지 확인:**
- [x] 계층형 순자산 대시보드 레이아웃 (Task 15)
- [x] JEONSE 타입 + maturityDate + liquidityType (Task 2~5)
- [x] benchmark_daily 테이블 (Task 1, 6)
- [x] 수익률 YTD/1M/3M + MDD + Alpha (Task 8, Phase 1)
- [x] 등급(B) + 벤치마크 비교(C) UX (Task 13)
- [x] 자산 배분 집중도 (Task 16~17)
- [x] 전세 만기 D-day 표시 (Task 15 실물자산 섹션)
- [x] 만기 알림 스케줄러 7AM (Task 18)
- [x] 배치: 주식 4PM / 벤치마크 수집 / 코인 00:30 (Task 10)
- [x] 데이터 부족 경고 표시 (Task 7, 8)
- [x] Sharpe·VaR·변동성 (Task 19~20)

**타입 일관성:** `MetricGrade`, `MetricValue`, `DashboardMetrics` 모든 Phase에서 동일 타입 사용. `buildMetric()` 헬퍼가 `MetricValueDto` 생성을 통일.
