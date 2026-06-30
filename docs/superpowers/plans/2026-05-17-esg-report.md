# ESG Report 모듈 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 비어있는 `esg/`, `report/` 모듈에 실제 소스를 추가하고, `/api/reports/esg` API와 `/unified/reports/esg` 프론트엔드 페이지를 완성한다.

**Architecture:** `esg/`는 DB/Spring 없는 순수 계산 엔진(`RiskEngine` 패턴 동일), `report/`는 도메인 데이터 클래스만 담는다. `EsgReportService`는 `unified-asset` 모듈에 두어 순환 의존성을 회피하고, 기존 `ReportController`에 엔드포인트를 추가한다.

**Tech Stack:** Kotlin, Spring Boot, JUnit 5, Mockito, Next.js 14, TanStack Query, TypeScript, Tailwind CSS

---

## 파일 맵

| 파일 | 작업 |
|------|------|
| `allfolio-backend/esg/build.gradle.kts` | 수정 — `:common` 의존성 추가 |
| `allfolio-backend/esg/src/main/kotlin/com/allfolio/esg/domain/EsgException.kt` | 신규 |
| `allfolio-backend/esg/src/main/kotlin/com/allfolio/esg/domain/EsgScore.kt` | 신규 |
| `allfolio-backend/esg/src/main/kotlin/com/allfolio/esg/domain/EsgEngine.kt` | 신규 |
| `allfolio-backend/esg/src/test/kotlin/com/allfolio/esg/domain/EsgEngineTest.kt` | 신규 |
| `allfolio-backend/report/src/main/kotlin/com/allfolio/report/domain/AssetEsgRow.kt` | 신규 |
| `allfolio-backend/report/src/main/kotlin/com/allfolio/report/domain/EsgReport.kt` | 신규 |
| `allfolio-backend/unified-asset/build.gradle.kts` | 수정 — `:esg`, `:report` 추가 |
| `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/EsgReportService.kt` | 신규 |
| `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/EsgReportServiceTest.kt` | 신규 |
| `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/api/ReportController.kt` | 수정 — `/esg` 엔드포인트 추가 |
| `frontend/allfolio_app/types/report.ts` | 수정 — ESG 타입 추가 |
| `frontend/allfolio_app/lib/report-api.ts` | 수정 — `esg()` 메서드 추가 |
| `frontend/allfolio_app/app/unified/reports/esg/page.tsx` | 신규 |
| `frontend/allfolio_app/app/unified/reports/page.tsx` | 수정 — ESG 카드 추가 |

---

## Task 1: esg 모듈 — build.gradle + 도메인 모델

**Files:**
- Modify: `allfolio-backend/esg/build.gradle.kts`
- Create: `allfolio-backend/esg/src/main/kotlin/com/allfolio/esg/domain/EsgException.kt`
- Create: `allfolio-backend/esg/src/main/kotlin/com/allfolio/esg/domain/EsgScore.kt`

- [ ] **Step 1: esg/build.gradle.kts 수정**

```kotlin
// allfolio-backend/esg/build.gradle.kts
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
}

dependencies {
    implementation(project(":common"))
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
```

- [ ] **Step 2: EsgException.kt 생성**

```kotlin
// allfolio-backend/esg/src/main/kotlin/com/allfolio/esg/domain/EsgException.kt
package com.allfolio.esg.domain

import com.allfolio.common.domain.DomainException

class EsgException(
    errorCode: String,
    message: String,
) : DomainException(errorCode, message) {

    companion object {
        fun emptyAssets() = EsgException(
            "ESG_EMPTY_ASSETS",
            "자산 목록이 비어있어 ESG 점수를 계산할 수 없습니다",
        )
    }
}
```

- [ ] **Step 3: EsgScore.kt 생성**

```kotlin
// allfolio-backend/esg/src/main/kotlin/com/allfolio/esg/domain/EsgScore.kt
package com.allfolio.esg.domain

import java.math.BigDecimal

data class EsgScore(
    val environmental: BigDecimal,  // 0~100
    val social: BigDecimal,         // 0~100
    val governance: BigDecimal,     // 0~100
    val total: BigDecimal,          // 가중 평균 (E×0.35 + S×0.30 + G×0.35)
    val rating: String,             // "A+", "A", "B+", "B", "C+", "C"
)
```

- [ ] **Step 4: 빌드 확인**

```bash
cd allfolio-backend
./gradlew :esg:compileKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/esg/
git commit -m "feat(esg): add EsgScore and EsgException domain models"
```

---

## Task 2: EsgEngine — scoreOf() 구현 (TDD)

**Files:**
- Create: `allfolio-backend/esg/src/test/kotlin/com/allfolio/esg/domain/EsgEngineTest.kt`
- Create: `allfolio-backend/esg/src/main/kotlin/com/allfolio/esg/domain/EsgEngine.kt`

- [ ] **Step 1: 테스트 파일 작성 (scoreOf 부분만)**

```kotlin
// allfolio-backend/esg/src/test/kotlin/com/allfolio/esg/domain/EsgEngineTest.kt
package com.allfolio.esg.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class EsgEngineTest {

    // ── scoreOf ──────────────────────────────────────────────

    @Test
    fun `CRYPTO 점수 - E20 S50 G40`() {
        val (e, s, g) = EsgEngine.scoreOf("CRYPTO")
        assertEquals(20, e)
        assertEquals(50, s)
        assertEquals(40, g)
    }

    @Test
    fun `STOCK 점수 - E60 S65 G65`() {
        val (e, s, g) = EsgEngine.scoreOf("STOCK")
        assertEquals(60, e)
        assertEquals(65, s)
        assertEquals(65, g)
    }

    @Test
    fun `REAL_ESTATE 점수 - E55 S70 G65`() {
        val (e, s, g) = EsgEngine.scoreOf("REAL_ESTATE")
        assertEquals(55, e)
        assertEquals(70, s)
        assertEquals(65, g)
    }

    @Test
    fun `JEONSE 점수 - E65 S80 G70`() {
        val (e, s, g) = EsgEngine.scoreOf("JEONSE")
        assertEquals(65, e)
        assertEquals(80, s)
        assertEquals(70, g)
    }

    @Test
    fun `VEHICLE 점수 - E35 S60 G55`() {
        val (e, s, g) = EsgEngine.scoreOf("VEHICLE")
        assertEquals(35, e)
        assertEquals(60, s)
        assertEquals(55, g)
    }

    @Test
    fun `GOLD 점수 - E45 S55 G55`() {
        val (e, s, g) = EsgEngine.scoreOf("GOLD")
        assertEquals(45, e)
        assertEquals(55, s)
        assertEquals(55, g)
    }

    @Test
    fun `CASH 점수 - E80 S75 G80`() {
        val (e, s, g) = EsgEngine.scoreOf("CASH")
        assertEquals(80, e)
        assertEquals(75, s)
        assertEquals(80, g)
    }

    @Test
    fun `ETC 점수 - E60 S60 G60`() {
        val (e, s, g) = EsgEngine.scoreOf("ETC")
        assertEquals(60, e)
        assertEquals(60, s)
        assertEquals(60, g)
    }

    @Test
    fun `알 수 없는 타입은 ETC 기본값 반환`() {
        val (e, s, g) = EsgEngine.scoreOf("UNKNOWN_TYPE")
        assertEquals(60, e)
        assertEquals(60, s)
        assertEquals(60, g)
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
./gradlew :esg:test --tests "com.allfolio.esg.domain.EsgEngineTest" 2>&1 | tail -20
```

Expected: `FAILED` (EsgEngine 없음)

- [ ] **Step 3: EsgEngine.kt 생성 — scoreOf()만 구현**

```kotlin
// allfolio-backend/esg/src/main/kotlin/com/allfolio/esg/domain/EsgEngine.kt
package com.allfolio.esg.domain

import java.math.BigDecimal
import java.math.RoundingMode

object EsgEngine {

    data class AssetInput(val type: String, val currentValue: BigDecimal)

    private val SCALE = 2
    private val ROUNDING = RoundingMode.HALF_UP

    private val SCORES: Map<String, Triple<Int, Int, Int>> = mapOf(
        "CRYPTO"      to Triple(20, 50, 40),
        "STOCK"       to Triple(60, 65, 65),
        "REAL_ESTATE" to Triple(55, 70, 65),
        "JEONSE"      to Triple(65, 80, 70),
        "VEHICLE"     to Triple(35, 60, 55),
        "GOLD"        to Triple(45, 55, 55),
        "CASH"        to Triple(80, 75, 80),
        "ETC"         to Triple(60, 60, 60),
    )

    private val DEFAULT_SCORE = Triple(60, 60, 60)

    fun scoreOf(type: String): Triple<Int, Int, Int> =
        SCORES[type] ?: DEFAULT_SCORE

    fun rating(total: BigDecimal): String = when {
        total >= BigDecimal("85") -> "A+"
        total >= BigDecimal("75") -> "A"
        total >= BigDecimal("65") -> "B+"
        total >= BigDecimal("55") -> "B"
        total >= BigDecimal("45") -> "C+"
        else                      -> "C"
    }

    fun calculate(assets: List<AssetInput>): EsgScore {
        if (assets.isEmpty()) throw EsgException.emptyAssets()

        val totalValue = assets.sumOf { it.currentValue }
        if (totalValue <= BigDecimal.ZERO) throw EsgException.emptyAssets()

        var eSum = BigDecimal.ZERO
        var sSum = BigDecimal.ZERO
        var gSum = BigDecimal.ZERO

        for (asset in assets) {
            val weight = asset.currentValue.divide(totalValue, 10, ROUNDING)
            val (e, s, g) = scoreOf(asset.type)
            eSum = eSum.add(weight.multiply(BigDecimal(e)))
            sSum = sSum.add(weight.multiply(BigDecimal(s)))
            gSum = gSum.add(weight.multiply(BigDecimal(g)))
        }

        val e = eSum.setScale(SCALE, ROUNDING)
        val s = sSum.setScale(SCALE, ROUNDING)
        val g = gSum.setScale(SCALE, ROUNDING)

        val total = e.multiply(BigDecimal("0.35"))
            .add(s.multiply(BigDecimal("0.30")))
            .add(g.multiply(BigDecimal("0.35")))
            .setScale(SCALE, ROUNDING)

        return EsgScore(
            environmental = e,
            social        = s,
            governance    = g,
            total         = total,
            rating        = rating(total),
        )
    }
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

```bash
./gradlew :esg:test --tests "com.allfolio.esg.domain.EsgEngineTest" 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/esg/
git commit -m "feat(esg): implement EsgEngine with scoreOf and rating"
```

---

## Task 3: EsgEngine — calculate() + rating() 테스트 추가 (TDD)

**Files:**
- Modify: `allfolio-backend/esg/src/test/kotlin/com/allfolio/esg/domain/EsgEngineTest.kt`

- [ ] **Step 1: calculate 및 rating 테스트 추가**

기존 `EsgEngineTest.kt`의 클래스 끝 닫는 `}` 앞에 아래 테스트를 추가한다:

```kotlin
    // ── rating ────────────────────────────────────────────────

    @Test
    fun `총점 85 이상 - A+`() = assertEquals("A+", EsgEngine.rating(bd("85")))

    @Test
    fun `총점 75 - A`() = assertEquals("A", EsgEngine.rating(bd("75")))

    @Test
    fun `총점 65 - B+`() = assertEquals("B+", EsgEngine.rating(bd("65")))

    @Test
    fun `총점 55 - B`() = assertEquals("B", EsgEngine.rating(bd("55")))

    @Test
    fun `총점 45 - C+`() = assertEquals("C+", EsgEngine.rating(bd("45")))

    @Test
    fun `총점 44 - C`() = assertEquals("C", EsgEngine.rating(bd("44")))

    // ── calculate ─────────────────────────────────────────────

    @Test
    fun `자산 없으면 EsgException`() {
        assertThrows(EsgException::class.java) {
            EsgEngine.calculate(emptyList())
        }
    }

    @Test
    fun `CASH 단일 자산 - 총점 78_5 등급 A`() {
        // E=80, S=75, G=80 → total = 80×0.35 + 75×0.30 + 80×0.35 = 28 + 22.5 + 28 = 78.5
        val result = EsgEngine.calculate(listOf(
            EsgEngine.AssetInput("CASH", bd("1000000"))
        ))
        assertEquals(bd("80.00"), result.environmental)
        assertEquals(bd("75.00"), result.social)
        assertEquals(bd("80.00"), result.governance)
        assertEquals(0, bd("78.50").compareTo(result.total))
        assertEquals("A", result.rating)
    }

    @Test
    fun `CRYPTO 단일 자산 - 총점 36_0 등급 C`() {
        // E=20, S=50, G=40 → total = 20×0.35 + 50×0.30 + 40×0.35 = 7 + 15 + 14 = 36
        val result = EsgEngine.calculate(listOf(
            EsgEngine.AssetInput("CRYPTO", bd("1000000"))
        ))
        assertEquals(0, bd("36.00").compareTo(result.total))
        assertEquals("C", result.rating)
    }

    @Test
    fun `두 자산 동일 비중 - 가중 평균 계산`() {
        // CASH(1000): E=80, S=75, G=80
        // CRYPTO(1000): E=20, S=50, G=40
        // 가중: E=(80+20)/2=50, S=(75+50)/2=62.5, G=(80+40)/2=60
        // total = 50×0.35 + 62.5×0.30 + 60×0.35 = 17.5 + 18.75 + 21.0 = 57.25 → B
        val result = EsgEngine.calculate(listOf(
            EsgEngine.AssetInput("CASH",   bd("1000")),
            EsgEngine.AssetInput("CRYPTO", bd("1000")),
        ))
        assertEquals(0, bd("50.00").compareTo(result.environmental))
        assertEquals(0, bd("57.25").compareTo(result.total))
        assertEquals("B", result.rating)
    }

    @Test
    fun `비중이 다른 두 자산 - 큰 자산이 점수에 더 많이 반영`() {
        // CASH(9000, 90%): E=80
        // CRYPTO(1000, 10%): E=20
        // E_portfolio = 80×0.9 + 20×0.1 = 72 + 2 = 74
        val result = EsgEngine.calculate(listOf(
            EsgEngine.AssetInput("CASH",   bd("9000")),
            EsgEngine.AssetInput("CRYPTO", bd("1000")),
        ))
        assertEquals(0, bd("74.00").compareTo(result.environmental))
    }

    private fun bd(s: String) = BigDecimal(s)
```

- [ ] **Step 2: 테스트 실행 — 통과 확인**

```bash
./gradlew :esg:test 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` (구현이 이미 완성되어 있으므로 즉시 통과)

- [ ] **Step 3: 커밋**

```bash
git add allfolio-backend/esg/src/test/
git commit -m "test(esg): add calculate and rating boundary tests to EsgEngineTest"
```

---

## Task 4: report 모듈 — 도메인 모델

**Files:**
- Create: `allfolio-backend/report/src/main/kotlin/com/allfolio/report/domain/AssetEsgRow.kt`
- Create: `allfolio-backend/report/src/main/kotlin/com/allfolio/report/domain/EsgReport.kt`

- [ ] **Step 1: AssetEsgRow.kt 생성**

```kotlin
// allfolio-backend/report/src/main/kotlin/com/allfolio/report/domain/AssetEsgRow.kt
package com.allfolio.report.domain

import java.math.BigDecimal

data class AssetEsgRow(
    val name: String,
    val type: String,
    val currentValue: BigDecimal,
    val weight: BigDecimal,         // 포트폴리오 내 비중 (0~1, 소수)
    val environmental: BigDecimal,
    val social: BigDecimal,
    val governance: BigDecimal,
    val total: BigDecimal,
    val rating: String,
)
```

- [ ] **Step 2: EsgReport.kt 생성**

```kotlin
// allfolio-backend/report/src/main/kotlin/com/allfolio/report/domain/EsgReport.kt
package com.allfolio.report.domain

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

data class EsgReport(
    val userId: UUID,
    val generatedAt: LocalDateTime,
    val rating: String,
    val totalScore: BigDecimal,
    val environmentalScore: BigDecimal,
    val socialScore: BigDecimal,
    val governanceScore: BigDecimal,
    val assetBreakdown: List<AssetEsgRow>,  // 전체 자산, total 내림차순
    val topAssets: List<AssetEsgRow>,       // 상위 3개 (ESG 우수)
    val bottomAssets: List<AssetEsgRow>,    // 하위 3개 (개선 필요)
)
```

- [ ] **Step 3: 빌드 확인**

```bash
./gradlew :report:compileKotlin 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 커밋**

```bash
git add allfolio-backend/report/
git commit -m "feat(report): add EsgReport and AssetEsgRow domain models"
```

---

## Task 5: EsgReportService 구현 (TDD)

**Files:**
- Modify: `allfolio-backend/unified-asset/build.gradle.kts`
- Create: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/EsgReportServiceTest.kt`
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/EsgReportService.kt`

- [ ] **Step 1: unified-asset/build.gradle.kts 수정 — `:esg`, `:report` 추가**

현재 파일의 `dependencies { ... }` 블록에 두 줄 추가:

```kotlin
// 기존 의존성들 사이에 추가
implementation(project(":esg"))
implementation(project(":report"))
```

전체 파일은 다음과 같다:

```kotlin
// allfolio-backend/unified-asset/build.gradle.kts
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
}

dependencies {
    implementation(project(":common"))
    implementation(project(":esg"))
    implementation(project(":report"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.opencsv:opencsv:5.9")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
```

- [ ] **Step 2: 테스트 파일 작성**

```kotlin
// allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/EsgReportServiceTest.kt
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.domain.asset.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class EsgReportServiceTest {

    @Mock lateinit var assetRepository: AssetRepository

    private val userId    = UUID.randomUUID()
    private val accountId = UUID.randomUUID()

    private fun svc() = EsgReportService(assetRepository)

    @Test
    fun `자산 없으면 ResponseStatusException 404`() {
        `when`(assetRepository.findByUserId(userId)).thenReturn(emptyList())

        val ex = assertThrows<ResponseStatusException> {
            svc().generate(userId)
        }
        assertEquals(404, ex.statusCode.value())
    }

    @Test
    fun `CASH 단일 자산 - ESG 보고서 반환`() {
        val asset = cashAsset(value = bd("1000000"))
        `when`(assetRepository.findByUserId(userId)).thenReturn(listOf(asset))

        val result = svc().generate(userId)

        assertEquals(userId, result.userId)
        assertEquals("A", result.rating)                          // CASH 총점 78.5 → A
        assertEquals(0, bd("78.50").compareTo(result.totalScore))
        assertEquals(1, result.assetBreakdown.size)
        assertEquals(1, result.topAssets.size)
        assertTrue(result.bottomAssets.isEmpty())                 // 자산 1개면 bottom 없음
    }

    @Test
    fun `assetBreakdown - total 내림차순 정렬`() {
        // CASH(78.5) > STOCK(62.75) > CRYPTO(36.0)
        val assets = listOf(
            cryptoAsset(value = bd("100000")),
            cashAsset(value = bd("100000")),
            stockAsset(value = bd("100000")),
        )
        `when`(assetRepository.findByUserId(userId)).thenReturn(assets)

        val result = svc().generate(userId)

        assertEquals("CASH",   result.assetBreakdown[0].type)
        assertEquals("STOCK",  result.assetBreakdown[1].type)
        assertEquals("CRYPTO", result.assetBreakdown[2].type)
    }

    @Test
    fun `topAssets - ESG 상위 3개`() {
        val assets = (1..5).map { cashAsset(value = bd("100000")) } +
                     listOf(cryptoAsset(value = bd("100000")))
        `when`(assetRepository.findByUserId(userId)).thenReturn(assets)

        val result = svc().generate(userId)

        assertTrue(result.topAssets.size <= 3)
        // topAssets 모두 bottomAssets보다 total이 높거나 같아야 함
        if (result.bottomAssets.isNotEmpty()) {
            val minTop = result.topAssets.minOf { it.total }
            val maxBottom = result.bottomAssets.maxOf { it.total }
            assertTrue(minTop >= maxBottom)
        }
    }

    @Test
    fun `weight - 포트폴리오 내 비중 합산은 1`() {
        val assets = listOf(
            cashAsset(value = bd("300000")),
            stockAsset(value = bd("700000")),
        )
        `when`(assetRepository.findByUserId(userId)).thenReturn(assets)

        val result = svc().generate(userId)

        val totalWeight = result.assetBreakdown.sumOf { it.weight }
        assertEquals(0, bd("1").compareTo(totalWeight.setScale(2)))
    }

    // ── helpers ───────────────────────────────────────────────

    private fun cashAsset(value: BigDecimal) = Asset.create(
        userId = userId, accountId = accountId,
        category = AssetCategory.FINANCIAL, type = AssetType.CASH,
        sourceType = AssetSourceType.MANUAL, name = "현금",
        symbol = null, quantity = value, purchasePrice = bd("1"),
        currentValue = value, currency = "KRW",
        valuationMethod = ValuationMethod.USER_INPUT,
    )

    private fun stockAsset(value: BigDecimal) = Asset.create(
        userId = userId, accountId = accountId,
        category = AssetCategory.FINANCIAL, type = AssetType.STOCK,
        sourceType = AssetSourceType.MANUAL, name = "삼성전자",
        symbol = "005930", quantity = bd("10"), purchasePrice = value.divide(bd("10")),
        currentValue = value, currency = "KRW",
        valuationMethod = ValuationMethod.MARKET_PRICE,
    )

    private fun cryptoAsset(value: BigDecimal) = Asset.create(
        userId = userId, accountId = accountId,
        category = AssetCategory.FINANCIAL, type = AssetType.CRYPTO,
        sourceType = AssetSourceType.MANUAL, name = "비트코인",
        symbol = "BTC", quantity = bd("0.01"), purchasePrice = value.divide(bd("0.01")),
        currentValue = value, currency = "USD",
        valuationMethod = ValuationMethod.MARKET_PRICE,
    )

    private fun bd(s: String) = BigDecimal(s)
}
```

- [ ] **Step 3: 테스트 실행 — 실패 확인**

```bash
./gradlew :unified-asset:test --tests "com.allfolio.unifiedasset.application.usecase.EsgReportServiceTest" 2>&1 | tail -15
```

Expected: `FAILED` (EsgReportService 없음)

- [ ] **Step 4: EsgReportService.kt 구현**

```kotlin
// allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/EsgReportService.kt
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.esg.domain.EsgEngine
import com.allfolio.report.domain.AssetEsgRow
import com.allfolio.report.domain.EsgReport
import com.allfolio.unifiedasset.application.port.AssetRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.util.UUID

@Service
class EsgReportService(
    private val assetRepository: AssetRepository,
) {
    fun generate(userId: UUID): EsgReport {
        val assets = assetRepository.findByUserId(userId)
        if (assets.isEmpty()) throw ResponseStatusException(HttpStatus.NOT_FOUND, "자산이 없습니다")

        val totalValue = assets.sumOf { it.currentValue }

        val inputs = assets.map { EsgEngine.AssetInput(it.type.name, it.currentValue) }
        val portfolioScore = EsgEngine.calculate(inputs)

        val breakdown = assets.map { asset ->
            val (e, s, g) = EsgEngine.scoreOf(asset.type.name)
            val assetTotal = BigDecimal(e).multiply(BigDecimal("0.35"))
                .add(BigDecimal(s).multiply(BigDecimal("0.30")))
                .add(BigDecimal(g).multiply(BigDecimal("0.35")))
                .setScale(2, RoundingMode.HALF_UP)
            val weight = if (totalValue > BigDecimal.ZERO)
                asset.currentValue.divide(totalValue, 4, RoundingMode.HALF_UP)
            else BigDecimal.ZERO

            AssetEsgRow(
                name          = asset.name,
                type          = asset.type.name,
                currentValue  = asset.currentValue,
                weight        = weight,
                environmental = BigDecimal(e),
                social        = BigDecimal(s),
                governance    = BigDecimal(g),
                total         = assetTotal,
                rating        = EsgEngine.rating(assetTotal),
            )
        }.sortedByDescending { it.total }

        return EsgReport(
            userId             = userId,
            generatedAt        = LocalDateTime.now(),
            rating             = portfolioScore.rating,
            totalScore         = portfolioScore.total,
            environmentalScore = portfolioScore.environmental,
            socialScore        = portfolioScore.social,
            governanceScore    = portfolioScore.governance,
            assetBreakdown     = breakdown,
            topAssets          = breakdown.take(3),
            bottomAssets       = if (breakdown.size > 3) breakdown.takeLast(3).reversed() else emptyList(),
        )
    }
}
```

- [ ] **Step 5: 테스트 실행 — 통과 확인**

```bash
./gradlew :unified-asset:test 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: 커밋**

```bash
git add allfolio-backend/unified-asset/
git commit -m "feat(unified-asset): implement EsgReportService with TDD"
```

---

## Task 6: API 엔드포인트 추가

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/api/ReportController.kt`

- [ ] **Step 1: ReportController에 EsgReportService 주입 및 엔드포인트 추가**

`ReportController.kt`의 생성자와 마지막 엔드포인트 뒤를 수정한다.

생성자를:
```kotlin
class ReportController(
    private val svc: ReportService,
    private val dividendSvc: DividendReportService,
) {
```
→
```kotlin
class ReportController(
    private val svc: ReportService,
    private val dividendSvc: DividendReportService,
    private val esgSvc: EsgReportService,
) {
```

파일 맨 끝 `}` 앞에 다음을 추가:
```kotlin
    @GetMapping("/esg")
    fun esg(@RequestHeader("X-User-Id") userId: UUID): com.allfolio.report.domain.EsgReport =
        esgSvc.generate(userId)
```

- [ ] **Step 2: 백엔드 전체 컴파일 확인**

```bash
./gradlew :backend-app:compileKotlin 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/api/ReportController.kt
git commit -m "feat(api): add GET /api/reports/esg endpoint"
```

---

## Task 7: 프론트엔드 — 타입 정의 + API 메서드

**Files:**
- Modify: `frontend/allfolio_app/types/report.ts`
- Modify: `frontend/allfolio_app/lib/report-api.ts`

- [ ] **Step 1: types/report.ts에 ESG 타입 추가**

`frontend/allfolio_app/types/report.ts` 파일 끝에 추가:

```typescript
// ── ESG ────────────────────────────────────────────────────────

export interface AssetEsgRow {
  name:          string
  type:          string
  currentValue:  number
  weight:        number
  environmental: number
  social:        number
  governance:    number
  total:         number
  rating:        string
}

export interface EsgReport {
  userId:             string
  generatedAt:        string
  rating:             string
  totalScore:         number
  environmentalScore: number
  socialScore:        number
  governanceScore:    number
  assetBreakdown:     AssetEsgRow[]
  topAssets:          AssetEsgRow[]
  bottomAssets:       AssetEsgRow[]
}
```

- [ ] **Step 2: report-api.ts import 수정 + esg() 추가**

`frontend/allfolio_app/lib/report-api.ts`의 import 라인을:
```typescript
import type {
  SummaryReport, AllocationReport, PerformanceReport,
  RiskReport, PositionsReport, BenchmarkReport,
  NetWorthReport, MonthlyPnlReport,
} from '@/types/report'
```
→
```typescript
import type {
  SummaryReport, AllocationReport, PerformanceReport,
  RiskReport, PositionsReport, BenchmarkReport,
  NetWorthReport, MonthlyPnlReport, EsgReport,
} from '@/types/report'
```

`return { ... }` 블록의 `dividend:` 뒤에 추가:
```typescript
    esg: async (): Promise<EsgReport> =>
      (await api.get<EsgReport>('/esg')).data,
```

- [ ] **Step 3: TypeScript 컴파일 확인**

```bash
cd frontend/allfolio_app
npx tsc --noEmit 2>&1 | head -20
```

Expected: 에러 없음

- [ ] **Step 4: 커밋**

```bash
git add frontend/allfolio_app/types/report.ts frontend/allfolio_app/lib/report-api.ts
git commit -m "feat(frontend): add ESG types and report-api.esg() method"
```

---

## Task 8: ESG 보고서 페이지 구현

**Files:**
- Create: `frontend/allfolio_app/app/unified/reports/esg/page.tsx`

- [ ] **Step 1: ESG 페이지 생성**

```tsx
// frontend/allfolio_app/app/unified/reports/esg/page.tsx
'use client'

import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useReportApi } from '@/lib/useApi'
import type { AssetEsgRow, EsgReport } from '@/types/report'

const RATING_COLORS: Record<string, string> = {
  'A+': 'text-emerald-400 border-emerald-600',
  'A':  'text-green-400 border-green-600',
  'B+': 'text-blue-400 border-blue-600',
  'B':  'text-blue-300 border-blue-700',
  'C+': 'text-amber-400 border-amber-600',
  'C':  'text-red-400 border-red-600',
}

const TYPE_KO: Record<string, string> = {
  CRYPTO: '암호화폐', STOCK: '주식', REAL_ESTATE: '부동산',
  JEONSE: '전세', VEHICLE: '차량', GOLD: '금', CASH: '현금', ETC: '기타',
}

function ScoreBar({ label, score, icon }: { label: string; score: number; icon: string }) {
  const pct = Math.min(100, Math.max(0, score))
  const color = score >= 75 ? 'bg-emerald-500' : score >= 55 ? 'bg-blue-500' : 'bg-amber-500'
  return (
    <div className="space-y-1">
      <div className="flex justify-between text-sm">
        <span className="text-gray-400">{icon} {label}</span>
        <span className="tabular-nums font-semibold">{score.toFixed(1)}</span>
      </div>
      <div className="h-2 rounded-full bg-gray-700">
        <div className={`h-2 rounded-full ${color} transition-all`} style={{ width: `${pct}%` }} />
      </div>
    </div>
  )
}

function RatingBadge({ rating }: { rating: string }) {
  const cls = RATING_COLORS[rating] ?? 'text-gray-400 border-gray-600'
  return (
    <span className={`inline-flex items-center rounded-full border px-4 py-1 text-2xl font-bold ${cls}`}>
      {rating}
    </span>
  )
}

function AssetRow({ row }: { row: AssetEsgRow }) {
  const pct = (row.weight * 100).toFixed(1)
  const ratingCls = (RATING_COLORS[row.rating] ?? 'text-gray-400').split(' ')[0]
  return (
    <tr className="border-t border-gray-800">
      <td className="py-3 pr-4 text-sm text-gray-200">{row.name}</td>
      <td className="py-3 pr-4 text-xs text-gray-500">{TYPE_KO[row.type] ?? row.type}</td>
      <td className="py-3 pr-4 text-right text-xs text-gray-500 tabular-nums">{pct}%</td>
      <td className="py-3 pr-4 text-right text-sm tabular-nums">{Number(row.environmental).toFixed(0)}</td>
      <td className="py-3 pr-4 text-right text-sm tabular-nums">{Number(row.social).toFixed(0)}</td>
      <td className="py-3 pr-4 text-right text-sm tabular-nums">{Number(row.governance).toFixed(0)}</td>
      <td className="py-3 pr-4 text-right font-semibold tabular-nums">{Number(row.total).toFixed(1)}</td>
      <td className={`py-3 text-right text-sm font-bold ${ratingCls}`}>{row.rating}</td>
    </tr>
  )
}

function Skeleton() {
  return <div className="h-96 animate-pulse rounded-xl bg-gray-800" />
}

function ErrorBox() {
  return (
    <div className="rounded-xl border border-red-800 bg-red-950 p-6 text-sm text-red-400">
      ESG 보고서를 불러올 수 없습니다. 자산을 먼저 등록해주세요.
    </div>
  )
}

export default function EsgPage() {
  const reportApi = useReportApi()

  const { data, isLoading, isError } = useQuery<EsgReport>({
    queryKey: ['report', 'esg'],
    queryFn:  () => reportApi!.esg(),
    enabled:  !!reportApi,
  })

  if (isLoading) return <Skeleton />
  if (isError || !data) return <ErrorBox />

  return (
    <div className="space-y-8">
      {/* 헤더 */}
      <div className="flex items-center gap-3">
        <Link href="/unified/reports" className="text-sm text-gray-500 hover:text-gray-300">
          ← 보고서
        </Link>
        <h1 className="text-2xl font-bold">ESG 점수</h1>
      </div>
      <p className="text-xs text-gray-500">
        생성: {new Date(data.generatedAt).toLocaleString('ko-KR')}
      </p>

      {/* 등급 + 총점 */}
      <div className="flex flex-col gap-6 sm:flex-row sm:items-center">
        <div className="flex flex-col items-center gap-2">
          <RatingBadge rating={data.rating} />
          <p className="text-xs text-gray-500">포트폴리오 등급</p>
        </div>
        <div className="flex-1 space-y-3">
          <ScoreBar label="환경 (E)" score={Number(data.environmentalScore)} icon="🌿" />
          <ScoreBar label="사회 (S)" score={Number(data.socialScore)} icon="🤝" />
          <ScoreBar label="지배구조 (G)" score={Number(data.governanceScore)} icon="🏛" />
        </div>
        <div className="text-center">
          <p className="text-4xl font-bold tabular-nums">{Number(data.totalScore).toFixed(1)}</p>
          <p className="text-xs text-gray-500 mt-1">ESG 총점</p>
        </div>
      </div>

      {/* 우수 / 개선 */}
      {(data.topAssets.length > 0 || data.bottomAssets.length > 0) && (
        <div className="grid gap-4 sm:grid-cols-2">
          {data.topAssets.length > 0 && (
            <div className="rounded-xl border border-emerald-800 bg-emerald-950/30 p-4">
              <p className="mb-3 text-sm font-semibold text-emerald-400">ESG 우수 자산</p>
              <ul className="space-y-1">
                {data.topAssets.map((a, i) => (
                  <li key={i} className="flex justify-between text-sm">
                    <span className="text-gray-300">{a.name}</span>
                    <span className="text-emerald-400 font-semibold">{a.rating}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
          {data.bottomAssets.length > 0 && (
            <div className="rounded-xl border border-amber-800 bg-amber-950/30 p-4">
              <p className="mb-3 text-sm font-semibold text-amber-400">개선 필요 자산</p>
              <ul className="space-y-1">
                {data.bottomAssets.map((a, i) => (
                  <li key={i} className="flex justify-between text-sm">
                    <span className="text-gray-300">{a.name}</span>
                    <span className="text-amber-400 font-semibold">{a.rating}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}

      {/* 자산별 ESG 테이블 */}
      <div className="rounded-xl border border-gray-800 bg-gray-900 overflow-x-auto">
        <div className="px-6 py-4 border-b border-gray-800">
          <h2 className="text-sm font-semibold text-gray-300">자산별 ESG</h2>
        </div>
        <table className="w-full px-6">
          <thead>
            <tr className="text-xs text-gray-500">
              <th className="px-6 py-3 text-left">자산명</th>
              <th className="px-6 py-3 text-left">유형</th>
              <th className="px-6 py-3 text-right">비중</th>
              <th className="px-6 py-3 text-right">E</th>
              <th className="px-6 py-3 text-right">S</th>
              <th className="px-6 py-3 text-right">G</th>
              <th className="px-6 py-3 text-right">총점</th>
              <th className="px-6 py-3 text-right">등급</th>
            </tr>
          </thead>
          <tbody className="px-6">
            {data.assetBreakdown.map((row, i) => (
              <AssetRow key={i} row={row} />
            ))}
          </tbody>
        </table>
      </div>

      {/* 방법론 안내 */}
      <p className="text-xs text-gray-600">
        ESG 점수는 자산 유형별 기본값(E×35% + S×30% + G×35%)을 현재 가치 기준으로 가중 평균한 휴리스틱 점수입니다.
        실제 ESG 등급과 다를 수 있습니다.
      </p>
    </div>
  )
}
```

- [ ] **Step 2: 커밋**

```bash
git add frontend/allfolio_app/app/unified/reports/esg/
git commit -m "feat(frontend): add ESG report page /unified/reports/esg"
```

---

## Task 9: 보고서 인덱스 페이지에 ESG 카드 추가

**Files:**
- Modify: `frontend/allfolio_app/app/unified/reports/page.tsx`

- [ ] **Step 1: REPORTS 배열에 ESG 항목 추가**

`frontend/allfolio_app/app/unified/reports/page.tsx`의 `const REPORTS = [` 배열에서 `dividend` 항목 앞에 추가:

```typescript
  {
    href:  '/unified/reports/esg',
    title: 'ESG 점수',
    desc:  '환경·사회·지배구조 기반 포트폴리오 ESG 등급 및 자산별 분석',
    color: 'border-emerald-700 hover:border-emerald-500',
    badge: '🌱',
  },
```

- [ ] **Step 2: 빌드 확인**

```bash
cd frontend/allfolio_app
npx tsc --noEmit 2>&1 | head -20
```

Expected: 에러 없음

- [ ] **Step 3: 최종 커밋**

```bash
git add frontend/allfolio_app/app/unified/reports/page.tsx
git commit -m "feat(frontend): add ESG card to reports hub"
```

---

## Task 10: 전체 검증

- [ ] **Step 1: 백엔드 전체 테스트 실행**

```bash
cd allfolio-backend
./gradlew :esg:test :unified-asset:test 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL` (esg 13개 + unified-asset 54개 이상)

- [ ] **Step 2: 백엔드 빌드 확인**

```bash
./gradlew :backend-app:build -x test 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 프론트엔드 타입 체크**

```bash
cd frontend/allfolio_app
npx tsc --noEmit 2>&1 | head -20
```

Expected: 에러 없음

- [ ] **Step 4: 최종 커밋**

```bash
cd /Users/hong9/IdeaProjects/allfolio
git status
# 미커밋 파일이 있으면 추가
git log --oneline -8
```

---

## 셀프 리뷰 체크리스트

### 스펙 커버리지

| 스펙 요구사항 | 커버 태스크 |
|---|---|
| esg 모듈 — EsgScore, EsgEngine, EsgException | Task 1, 2, 3 |
| 자산 타입별 E/S/G 기본값 | Task 2 (SCORES 맵) |
| 총점 = E×0.35 + S×0.30 + G×0.35 | Task 2 (calculate) |
| 등급 A+ ~ C | Task 3 |
| report 모듈 — AssetEsgRow, EsgReport | Task 4 |
| EsgReportService (unified-asset) | Task 5 |
| GET /api/reports/esg | Task 6 |
| 자산 없으면 404 | Task 5 (테스트 포함) |
| 프론트 타입 정의 | Task 7 |
| report-api.esg() | Task 7 |
| /unified/reports/esg 페이지 | Task 8 |
| reports 인덱스 ESG 카드 | Task 9 |
| EsgEngineTest | Task 2, 3 |
| EsgReportServiceTest | Task 5 |
