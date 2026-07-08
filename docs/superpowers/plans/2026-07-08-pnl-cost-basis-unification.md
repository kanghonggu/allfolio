# 손익 원가 계산 통합 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** TradeRaw 기반 FIFO 원가 계산을 `trade` 모듈의 순수 코어 하나로 통합하고, pnl 초기화기가 lots를 만들지 않아 재부팅 후 `costMethod=FIFO`가 avgCost로 폴백하던 버그를 없앤다.

**Architecture:** `trade` 모듈에 상태 없는 `FifoCostEngine`(+ `CostLot`/`LotPosition`)를 두고, pnl 실시간 write path(증분 `apply`)와 초기화기·snapshot(배치 `replay`)이 모두 이 코어를 쓴다. pnl은 Redis DTO(`PositionData`)를 코어 타입과 매핑하고, snapshot은 코어 결과에서 `PositionSnapshot`을 투영한다.

**Tech Stack:** Kotlin, Gradle 멀티모듈, JUnit5. 신규 라이브러리 없음.

**Spec:** `docs/superpowers/specs/2026-07-08-pnl-cost-basis-unification-design.md`

**작업 브랜치:** `feat/pnl-cost-basis-unification` (origin/main 기준, 스펙 커밋 포함)

**빌드/테스트 루트:** `/Users/hong9/IdeaProjects/allfolio/allfolio-backend`

**참고 — 기존 사실:**
- `trade` 모듈: `com.allfolio.trade.domain`에 `TradeRaw`(val tradeType/quantity/price/fee/executedAt ...), `TradeType{BUY,SELL}`. `TradeMapper.toDomainList(entities)`로 `TradeRawEntity`→`TradeRaw` 변환.
- pnl은 `backend-app`의 `com.allfolio.pnl` 패키지. `backend-app`은 `trade`·`snapshot`에 의존.
- pnl `PositionData(portfolioId, assetId, quantity, avgCost, currency="KRW", lots: List<pnl.PositionLot> = emptyList(), updatedAt)`, `pnl.PositionLot(price, quantity, purchasedAt)`.
- snapshot `PositionEngine`는 `snapshot`의 `com.allfolio.snapshot.domain`, `snapshot`도 `trade` 의존. `snapshot.PositionLot`은 `PositionEngine`에서만 사용.
- `.gradle/*` 캐시 파일은 절대 stage하지 말 것.

---

### Task 1: 공용 코어 + 엔진 테스트 (trade 모듈, TDD)

**Files:**
- Create: `allfolio-backend/trade/src/main/kotlin/com/allfolio/trade/domain/CostLot.kt`
- Create: `allfolio-backend/trade/src/main/kotlin/com/allfolio/trade/domain/LotPosition.kt`
- Create: `allfolio-backend/trade/src/main/kotlin/com/allfolio/trade/domain/FifoCostEngine.kt`
- Test: `allfolio-backend/trade/src/test/kotlin/com/allfolio/trade/domain/FifoCostEngineTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
package com.allfolio.trade.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

class FifoCostEngineTest {

    private fun trade(type: TradeType, qty: String, price: String, fee: String = "0") =
        TradeRaw.reconstruct(
            id = TradeId.newId(),
            portfolioId = PORTFOLIO,
            assetId = ASSET,
            tradeType = type,
            quantity = BigDecimal(qty),
            price = BigDecimal(price),
            fee = BigDecimal(fee),
            tradeCurrency = "KRW",
            executedAt = LocalDateTime.now(),
            createdAt = LocalDateTime.now(),
        )

    private fun assertBd(expected: String, actual: BigDecimal) =
        assertEquals(0, BigDecimal(expected).compareTo(actual), "expected $expected but was $actual")

    @Test
    fun `buy accumulates quantity and weighted average cost`() {
        val pos = FifoCostEngine.replay(listOf(trade(TradeType.BUY, "10", "100"), trade(TradeType.BUY, "10", "200")))
        assertBd("20", pos.totalQuantity)
        assertBd("150", pos.averageCost)
        assertBd("0", pos.realizedPnl)
        assertBd("100", pos.fifoCostBasis!!)
    }

    @Test
    fun `fifo sell consumes oldest lot first and realizes pnl`() {
        val pos = FifoCostEngine.replay(
            listOf(trade(TradeType.BUY, "10", "100"), trade(TradeType.BUY, "10", "200"), trade(TradeType.SELL, "5", "300")),
        )
        assertBd("15", pos.totalQuantity)
        // 남은 lots: 5@100, 10@200 → 평균 (500+2000)/15
        assertBd("166.6666666667", pos.averageCost)
        // 실현: 5*300 - 5*100 = 1000
        assertBd("1000", pos.realizedPnl)
        assertBd("100", pos.fifoCostBasis!!)
    }

    @Test
    fun `full sell empties lots and zeroes cost`() {
        val pos = FifoCostEngine.replay(listOf(trade(TradeType.BUY, "10", "100"), trade(TradeType.SELL, "10", "150")))
        assertBd("0", pos.totalQuantity)
        assertBd("0", pos.averageCost)
        assertNull(pos.fifoCostBasis)
        assertBd("500", pos.realizedPnl)
    }

    @Test
    fun `sell fee reduces realized pnl`() {
        val pos = FifoCostEngine.replay(listOf(trade(TradeType.BUY, "10", "100"), trade(TradeType.SELL, "10", "150", fee = "70")))
        assertBd("430", pos.realizedPnl) // 500 - 70
    }

    @Test
    fun `oversell clamps to held quantity without throwing`() {
        val pos = FifoCostEngine.replay(listOf(trade(TradeType.BUY, "10", "100"), trade(TradeType.SELL, "15", "150")))
        assertBd("0", pos.totalQuantity)
        // 실제 소진 10주만: 10*150 - 10*100 = 500
        assertBd("500", pos.realizedPnl)
    }

    @Test
    fun `apply incrementally equals replay in batch`() {
        val trades = listOf(
            trade(TradeType.BUY, "10", "100"),
            trade(TradeType.BUY, "5", "200"),
            trade(TradeType.SELL, "8", "300"),
            trade(TradeType.BUY, "3", "150"),
        )
        val incremental = trades.fold(LotPosition.EMPTY) { pos, t ->
            FifoCostEngine.apply(pos, t.tradeType, t.quantity, t.price, t.fee)
        }
        val batch = FifoCostEngine.replay(trades)
        assertEquals(batch, incremental)
    }

    companion object {
        private val PORTFOLIO = UUID.randomUUID()
        private val ASSET = UUID.randomUUID()
    }
}
```

- [ ] **Step 2: RED 확인 (타입 미존재 → 컴파일 실패)**

Run: `cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :trade:test --tests "com.allfolio.trade.domain.FifoCostEngineTest"`
Expected: FAIL — `CostLot`/`LotPosition`/`FifoCostEngine` unresolved reference (컴파일 실패).

- [ ] **Step 3: CostLot 생성**

```kotlin
package com.allfolio.trade.domain

import java.math.BigDecimal

/** 원가 계산 Lot — BUY 1건 = Lot 1개. 불변. */
data class CostLot(
    val unitPrice: BigDecimal,
    val quantity: BigDecimal,
)
```

- [ ] **Step 4: LotPosition 생성**

```kotlin
package com.allfolio.trade.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * FIFO 원가 포지션 — lots(오래된 것 앞) + 누적 실현손익. 불변.
 * FifoCostEngine이 생성/갱신한다.
 */
data class LotPosition(
    val lots: List<CostLot>,
    val realizedPnl: BigDecimal,
) {
    val totalQuantity: BigDecimal
        get() = lots.fold(BigDecimal.ZERO) { acc, lot -> acc + lot.quantity }

    /** 잔여 lots 가중평균 단가 (scale 10, HALF_UP). lots 비면 ZERO. */
    val averageCost: BigDecimal
        get() {
            val qty = totalQuantity
            if (qty.signum() == 0) return BigDecimal.ZERO
            val cost = lots.fold(BigDecimal.ZERO) { acc, lot -> acc + lot.unitPrice * lot.quantity }
            return cost.divide(qty, SCALE, ROUNDING)
        }

    /** 가장 오래된 lot의 단가 (FIFO 원가). lots 비면 null. */
    val fifoCostBasis: BigDecimal?
        get() = lots.firstOrNull()?.unitPrice

    companion object {
        val EMPTY = LotPosition(emptyList(), BigDecimal.ZERO)
        private const val SCALE = 10
        private val ROUNDING = RoundingMode.HALF_UP
    }
}
```

- [ ] **Step 5: FifoCostEngine 생성**

```kotlin
package com.allfolio.trade.domain

import java.math.BigDecimal

/**
 * FIFO 원가 계산 엔진 (순수 도메인 서비스)
 *
 * - 상태 없음, side-effect 없음, DB/직렬화 의존 없음
 * - BUY: lot 추가. SELL: FIFO 소진 + 실현손익 누적.
 * - 초과매도(SELL > 보유): 보유분까지만 소진(clamp), 예외 없음.
 *   초과매도를 거부하려는 호출자는 apply 전에 사전검증한다.
 * - trades는 executedAt 오름차순 정렬 가정.
 */
object FifoCostEngine {

    /** 거래 1건을 현재 포지션에 반영한 새 포지션을 반환한다. */
    fun apply(
        position: LotPosition,
        tradeType: TradeType,
        quantity: BigDecimal,
        price: BigDecimal,
        fee: BigDecimal = BigDecimal.ZERO,
    ): LotPosition = when (tradeType) {
        TradeType.BUY  -> position.copy(lots = position.lots + CostLot(price, quantity))
        TradeType.SELL -> sell(position, quantity, price, fee)
    }

    /** 거래 목록을 EMPTY 포지션부터 순서대로 재생한다. */
    fun replay(trades: List<TradeRaw>): LotPosition =
        trades.fold(LotPosition.EMPTY) { pos, t ->
            apply(pos, t.tradeType, t.quantity, t.price, t.fee)
        }

    private fun sell(
        position: LotPosition,
        sellQty: BigDecimal,
        sellPrice: BigDecimal,
        fee: BigDecimal,
    ): LotPosition {
        var remaining = sellQty
        var consumedCost = BigDecimal.ZERO
        val newLots = ArrayList<CostLot>(position.lots.size)

        for (lot in position.lots) {
            if (remaining.signum() <= 0) {
                newLots.add(lot)
                continue
            }
            val consumed = remaining.min(lot.quantity)
            consumedCost = consumedCost + consumed * lot.unitPrice
            remaining = remaining - consumed
            val leftover = lot.quantity - consumed
            if (leftover.signum() > 0) newLots.add(CostLot(lot.unitPrice, leftover))
        }

        val soldQty = sellQty - remaining // 실제 소진량 (clamp 시 보유분)
        val realized = soldQty * sellPrice - consumedCost - fee
        return LotPosition(newLots, position.realizedPnl + realized)
    }
}
```

- [ ] **Step 6: GREEN 확인**

Run: `./gradlew :trade:test --tests "com.allfolio.trade.domain.FifoCostEngineTest"`
Expected: `BUILD SUCCESSFUL`, 6 tests passed.

- [ ] **Step 7: Commit**

```bash
cd /Users/hong9/IdeaProjects/allfolio
git add allfolio-backend/trade/src/main/kotlin/com/allfolio/trade/domain/CostLot.kt \
  allfolio-backend/trade/src/main/kotlin/com/allfolio/trade/domain/LotPosition.kt \
  allfolio-backend/trade/src/main/kotlin/com/allfolio/trade/domain/FifoCostEngine.kt \
  allfolio-backend/trade/src/test/kotlin/com/allfolio/trade/domain/FifoCostEngineTest.kt
git commit -m "feat: add shared FifoCostEngine cost-basis core in trade module"
```

---

### Task 2: pnl ↔ 코어 매핑 계층 (레거시 shim 포함, TDD)

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/pnl/PositionDataMapper.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/pnl/PositionDataMapperTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
package com.allfolio.pnl

import com.allfolio.trade.domain.CostLot
import com.allfolio.trade.domain.LotPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class PositionDataMapperTest {

    private val portfolioId = UUID.randomUUID()
    private val assetId = UUID.randomUUID()

    private fun assertBd(expected: String, actual: BigDecimal) =
        assertEquals(0, BigDecimal(expected).compareTo(actual), "expected $expected but was $actual")

    @Test
    fun `PositionData with lots maps to LotPosition preserving lot prices`() {
        val data = PositionData(
            portfolioId, assetId, quantity = BigDecimal("15"), avgCost = BigDecimal("150"),
            lots = listOf(PositionLot(BigDecimal("100"), BigDecimal("5")), PositionLot(BigDecimal("200"), BigDecimal("10"))),
        )
        val pos = PositionDataMapper.toLotPosition(data)
        assertBd("15", pos.totalQuantity)
        assertBd("100", pos.fifoCostBasis!!)
    }

    @Test
    fun `legacy lot-less PositionData synthesizes a single lot from avgCost preserving quantity`() {
        val data = PositionData(portfolioId, assetId, quantity = BigDecimal("100"), avgCost = BigDecimal("50"), lots = emptyList())
        val pos = PositionDataMapper.toLotPosition(data)
        assertBd("100", pos.totalQuantity)
        assertBd("50", pos.averageCost)
        assertBd("50", pos.fifoCostBasis!!)
    }

    @Test
    fun `LotPosition maps back to PositionData projecting quantity avgCost and lots`() {
        val pos = LotPosition(listOf(CostLot(BigDecimal("100"), BigDecimal("5")), CostLot(BigDecimal("200"), BigDecimal("10"))), BigDecimal.ZERO)
        val data = PositionDataMapper.toPositionData(pos, portfolioId, assetId, currency = "USDT")
        assertBd("15", data.quantity)
        assertBd("166.6666666667", data.avgCost)
        assertEquals(2, data.lots.size)
        assertEquals("USDT", data.currency)
        assertBd("100", data.lots[0].price)
    }

    @Test
    fun `empty PositionData maps to EMPTY position`() {
        val data = PositionData(portfolioId, assetId, quantity = BigDecimal.ZERO, avgCost = BigDecimal.ZERO, lots = emptyList())
        assertEquals(LotPosition.EMPTY, PositionDataMapper.toLotPosition(data))
    }
}
```

- [ ] **Step 2: RED 확인**

Run: `cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.pnl.PositionDataMapperTest"`
Expected: FAIL — `PositionDataMapper` unresolved reference.

- [ ] **Step 3: PositionDataMapper 구현**

```kotlin
package com.allfolio.pnl

import com.allfolio.trade.domain.CostLot
import com.allfolio.trade.domain.LotPosition
import java.math.BigDecimal
import java.util.UUID

/**
 * Redis 직렬화용 PositionData ↔ 순수 코어 LotPosition 변환.
 *
 * 레거시 shim: lots 없이 quantity만 있는 옛 캐시 데이터는
 * (avgCost, quantity) 단일 lot으로 합성해 수량 손실을 막는다.
 */
object PositionDataMapper {

    fun toLotPosition(data: PositionData): LotPosition {
        val lots: List<CostLot> = when {
            data.lots.isNotEmpty() -> data.lots.map { CostLot(it.price, it.quantity) }
            data.quantity.signum() > 0 -> listOf(CostLot(data.avgCost, data.quantity)) // 레거시 합성
            else -> emptyList()
        }
        return if (lots.isEmpty()) LotPosition.EMPTY else LotPosition(lots, BigDecimal.ZERO)
    }

    fun toPositionData(
        position: LotPosition,
        portfolioId: UUID,
        assetId: UUID,
        currency: String,
    ): PositionData = PositionData(
        portfolioId = portfolioId,
        assetId     = assetId,
        quantity    = position.totalQuantity,
        avgCost     = position.averageCost,
        currency    = currency,
        lots        = position.lots.map { PositionLot(price = it.unitPrice, quantity = it.quantity) },
    )
}
```

- [ ] **Step 4: GREEN 확인**

Run: `./gradlew :backend-app:test --tests "com.allfolio.pnl.PositionDataMapperTest"`
Expected: `BUILD SUCCESSFUL`, 4 tests passed.

- [ ] **Step 5: Commit**

```bash
cd /Users/hong9/IdeaProjects/allfolio
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/pnl/PositionDataMapper.kt \
  allfolio-backend/backend-app/src/test/kotlin/com/allfolio/pnl/PositionDataMapperTest.kt
git commit -m "feat: add PositionData <-> LotPosition mapping with legacy lot-less shim"
```

---

### Task 3: PositionCacheService를 코어로 이전 (write path #2)

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/pnl/PositionCacheService.kt`

- [ ] **Step 1: applyTrade 본문 교체**

`applyTrade` 함수 전체를 아래로 교체 (시그니처 유지):

```kotlin
    fun applyTrade(
        portfolioId: UUID,
        assetId: UUID,
        tradeType: TradeType,
        quantity: BigDecimal,
        price: BigDecimal,
        currency: String = "KRW",
    ) {
        val key      = positionKey(portfolioId)
        val field    = assetId.toString()
        val existing = getPosition(portfolioId, assetId)

        val before = existing?.let { PositionDataMapper.toLotPosition(it) } ?: LotPosition.EMPTY
        val after  = FifoCostEngine.apply(before, tradeType, quantity, price)

        if (after.totalQuantity.signum() <= 0) {
            // 포지션 청산 — Redis field 삭제
            runCatching { redisTemplate.opsForHash<String, String>().delete(key, field) }
            return
        }

        // BUY는 이번 통화, SELL은 기존 통화 유지 (기존 동작 보존)
        val effectiveCurrency = if (tradeType == TradeType.BUY) currency else (existing?.currency ?: currency)
        val updated = PositionDataMapper.toPositionData(after, portfolioId, assetId, effectiveCurrency)

        runCatching {
            redisTemplate.opsForHash<String, String>().put(key, field, objectMapper.writeValueAsString(updated))
        }.onFailure { e ->
            log.warn("[PositionCache] HSET failed portfolioId={} assetId={}: {}", portfolioId, assetId, e.message)
        }
    }
```

- [ ] **Step 2: costBasis 교체 + 죽은 private 메서드 제거**

`costBasis` 함수를 아래로 교체:

```kotlin
    /**
     * costMethod 에 따른 원가 단가 반환.
     *   AVG_COST: 잔여 lots 가중평균
     *   FIFO:     가장 오래된 lot 단가 (lots 비면 avgCost 폴백)
     */
    fun costBasis(data: PositionData, method: CostBasisMethod): BigDecimal {
        val position = PositionDataMapper.toLotPosition(data)
        return when (method) {
            CostBasisMethod.AVG_COST -> position.averageCost
            CostBasisMethod.FIFO     -> position.fifoCostBasis ?: position.averageCost
        }
    }
```

그리고 이제 쓰이지 않는 private 메서드 4개를 **삭제**한다: `applyBuy`, `applySellFifo`, `consumeFifo`, `weightedAvgCost` (파일 하단 "Private — trade logic" 섹션 전체).

- [ ] **Step 3: import 정리**

파일 상단 import에 다음을 추가:

```kotlin
import com.allfolio.trade.domain.FifoCostEngine
import com.allfolio.trade.domain.LotPosition
```

그리고 더 이상 쓰이지 않으면 `import java.math.RoundingMode`를 제거한다 (weightedAvgCost 삭제로 미사용 시). `import com.allfolio.trade.domain.TradeType`은 applyTrade에서 계속 쓰이므로 유지.

- [ ] **Step 4: 컴파일 + 기존 pnl 테스트 통과 확인**

Run: `cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :backend-app:test`
Expected: `BUILD SUCCESSFUL` (기존 테스트 전부 통과 — 동작 보존).

- [ ] **Step 5: Commit**

```bash
cd /Users/hong9/IdeaProjects/allfolio
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/pnl/PositionCacheService.kt
git commit -m "refactor: route PositionCacheService cost logic through FifoCostEngine"
```

---

### Task 4: PositionCacheInitializer를 코어로 이전 (버그 수정 #3) + 재부팅 회귀 테스트

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/pnl/PositionCacheInitializer.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/pnl/PositionCacheRebuildConsistencyTest.kt`

- [ ] **Step 1: 재부팅 회귀 테스트 작성 (버그 재현)**

이 테스트는 초기화기의 replay 결과 lots와 write-path의 증분 apply 결과 lots가 같아야 함을 고정한다. 초기화기의 순수 계산 부분(`FifoCostEngine.replay` + 매핑)을 직접 호출해 검증한다.

```kotlin
package com.allfolio.pnl

import com.allfolio.trade.domain.FifoCostEngine
import com.allfolio.trade.domain.LotPosition
import com.allfolio.trade.domain.TradeId
import com.allfolio.trade.domain.TradeRaw
import com.allfolio.trade.domain.TradeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * 재부팅 정합성 회귀 테스트.
 * 초기화기(배치 replay)로 재구축한 포지션의 lots가
 * write-path(증분 apply)로 누적한 포지션의 lots와 동일해야 한다.
 * → costMethod=FIFO 원가가 재부팅 전후로 일치.
 */
class PositionCacheRebuildConsistencyTest {

    private val portfolioId = UUID.randomUUID()
    private val assetId = UUID.randomUUID()

    private fun trade(type: TradeType, qty: String, price: String) = TradeRaw.reconstruct(
        id = TradeId.newId(), portfolioId = portfolioId, assetId = assetId, tradeType = type,
        quantity = BigDecimal(qty), price = BigDecimal(price), fee = BigDecimal.ZERO,
        tradeCurrency = "KRW", executedAt = LocalDateTime.now(), createdAt = LocalDateTime.now(),
    )

    @Test
    fun `rebuild via replay produces same lots as incremental write path`() {
        val trades = listOf(
            trade(TradeType.BUY, "10", "100"),
            trade(TradeType.BUY, "10", "200"),
            trade(TradeType.SELL, "5", "300"),
            trade(TradeType.BUY, "4", "250"),
        )

        // 초기화기 경로: 배치 replay → PositionData
        val rebuilt = PositionDataMapper.toPositionData(
            FifoCostEngine.replay(trades), portfolioId, assetId, currency = "KRW",
        )

        // write-path 경로: 증분 apply → PositionData
        val incremental = trades.fold(LotPosition.EMPTY) { pos, t ->
            FifoCostEngine.apply(pos, t.tradeType, t.quantity, t.price)
        }.let { PositionDataMapper.toPositionData(it, portfolioId, assetId, currency = "KRW") }

        assertEquals(incremental.lots, rebuilt.lots, "재부팅 재구축 lots가 증분 누적 lots와 달라 FIFO 원가가 어긋남")
        assertEquals(0, incremental.quantity.compareTo(rebuilt.quantity))
    }
}
```

- [ ] **Step 2: 테스트 실행 — 즉시 통과 확인**

Run: `cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.pnl.PositionCacheRebuildConsistencyTest"`
Expected: `BUILD SUCCESSFUL` (매핑·엔진이 이미 있으므로 통과). 이 테스트는 초기화기 재작성이 이 계산 경로를 실제로 쓰도록 만드는 것을 보증하는 가드다.

- [ ] **Step 3: initPortfolio 재작성**

`PositionCacheInitializer.kt`의 `initPortfolio` 함수를 아래로 교체:

```kotlin
    private fun initPortfolio(portfolioId: UUID) {
        val entities = tradeRepository
            .findByPortfolioIdAndExecutedAtLessThanEqualOrderByExecutedAtAsc(portfolioId, LocalDateTime.now())

        if (entities.isEmpty()) return

        val positionMap = entities
            .groupBy { it.assetId }
            .mapValues { (assetId, assetTrades) ->
                val lotPosition = FifoCostEngine.replay(TradeMapper.toDomainList(assetTrades))
                PositionDataMapper.toPositionData(lotPosition, portfolioId, assetId, currency = "KRW")
            }
            .filter { (_, data) -> data.quantity > BigDecimal.ZERO }

        positionCacheService.initPositions(portfolioId, positionMap)
    }
```

파일 하단의 `private data class MutablePositionState(...)`를 **삭제**한다.

- [ ] **Step 4: import 교체**

상단 import에서 `import com.allfolio.trade.domain.TradeType` (initPortfolio에서 더 이상 안 쓰면)와 `import java.math.RoundingMode`를 제거하고, 다음을 추가:

```kotlin
import com.allfolio.trade.domain.FifoCostEngine
import com.allfolio.trade.infrastructure.mapper.TradeMapper
```

(주: `import java.math.BigDecimal`은 `data.quantity > BigDecimal.ZERO` 필터에서 계속 쓰이므로 유지. `TradeType`이 파일의 다른 곳에서 쓰이면 유지 — 컴파일러 경고로 확인.)

- [ ] **Step 5: 컴파일 + 회귀 테스트 + 전체 pnl 테스트**

Run: `./gradlew :backend-app:test`
Expected: `BUILD SUCCESSFUL` — 회귀 테스트 포함 전부 통과.

- [ ] **Step 6: Commit**

```bash
cd /Users/hong9/IdeaProjects/allfolio
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/pnl/PositionCacheInitializer.kt \
  allfolio-backend/backend-app/src/test/kotlin/com/allfolio/pnl/PositionCacheRebuildConsistencyTest.kt
git commit -m "fix: rebuild position cache with lots so FIFO cost basis survives restart"
```

---

### Task 5: snapshot PositionEngine을 코어로 이전 (2단계, 11개 테스트 GREEN 유지)

**Files:**
- Modify: `allfolio-backend/snapshot/src/main/kotlin/com/allfolio/snapshot/domain/PositionEngine.kt`
- Delete: `allfolio-backend/snapshot/src/main/kotlin/com/allfolio/snapshot/domain/PositionLot.kt`

- [ ] **Step 1: 재작성 전 기존 11개 테스트 통과 확인 (기준선)**

Run: `cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :snapshot:test --tests "com.allfolio.snapshot.domain.PositionEngineTest"`
Expected: `BUILD SUCCESSFUL`, 11 tests passed. (재작성 후에도 이 결과가 동일해야 한다.)

- [ ] **Step 2: PositionEngine 본문 교체 (코어 위임)**

`PositionEngine.kt` 전체를 아래로 교체:

```kotlin
package com.allfolio.snapshot.domain

import com.allfolio.trade.domain.FifoCostEngine
import com.allfolio.trade.domain.TradeRaw
import com.allfolio.trade.domain.TradeType
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * FIFO 포지션 계산 엔진 (일별 스냅샷용).
 *
 * FIFO 원가·실현손익 계산은 공용 FifoCostEngine에 위임하고,
 * 이 엔진은 스냅샷 고유의 두 가지만 담당한다:
 *   1) 초과매도(SELL > 보유) 사전검증 → 데이터 오류로 거부 (정책)
 *   2) marketPrice 기반 미실현손익 계산
 */
object PositionEngine {

    private const val SCALE = 10
    private val ROUNDING = RoundingMode.HALF_UP

    fun calculate(
        trades: List<TradeRaw>,
        marketPrice: BigDecimal,
    ): PositionSnapshot {
        if (trades.isEmpty()) throw PositionException.emptyTrades()

        val assetId = trades.first().assetId

        // 초과매도 사전검증 — 스냅샷은 완전한 이력을 전제하므로 데이터 오류로 거부
        var held = BigDecimal.ZERO
        for (trade in trades) {
            when (trade.tradeType) {
                TradeType.BUY  -> held = held.add(trade.quantity)
                TradeType.SELL -> {
                    if (trade.quantity.compareTo(held) > 0) {
                        throw PositionException.insufficientQuantity(assetId, trade.quantity, held)
                    }
                    held = held.subtract(trade.quantity)
                }
            }
        }

        val position    = FifoCostEngine.replay(trades)
        val totalQty     = position.totalQuantity
        val averageCost  = position.averageCost
        val unrealizedPnl = marketPrice.subtract(averageCost).multiply(totalQty)

        return PositionSnapshot(
            assetId       = assetId,
            totalQuantity = totalQty.setScale(SCALE, ROUNDING),
            averageCost   = averageCost,
            realizedPnl   = position.realizedPnl.setScale(SCALE, ROUNDING),
            unrealizedPnl = unrealizedPnl.setScale(SCALE, ROUNDING),
        )
    }
}
```

- [ ] **Step 3: 미사용 snapshot.PositionLot 삭제**

```bash
cd /Users/hong9/IdeaProjects/allfolio
rm allfolio-backend/snapshot/src/main/kotlin/com/allfolio/snapshot/domain/PositionLot.kt
```

(PositionLot은 PositionEngine에서만 쓰였고 이제 코어 CostLot으로 대체됨.)

- [ ] **Step 4: 동일한 11개 테스트가 그대로 GREEN인지 확인 (동작 보존 증거)**

Run: `./gradlew :snapshot:test --tests "com.allfolio.snapshot.domain.PositionEngineTest"`
Expected: `BUILD SUCCESSFUL`, 11 tests passed — 재작성 전과 동일. 하나라도 실패하면 코어 로직이 기존 FIFO와 다르다는 뜻이므로 커밋하지 말고 원인 조사.

- [ ] **Step 5: Commit**

```bash
git add allfolio-backend/snapshot/src/main/kotlin/com/allfolio/snapshot/domain/PositionEngine.kt \
  allfolio-backend/snapshot/src/main/kotlin/com/allfolio/snapshot/domain/PositionLot.kt
git commit -m "refactor: delegate snapshot PositionEngine FIFO to shared FifoCostEngine"
```

---

### Task 6: 전체 검증 + PR

- [ ] **Step 1: 전체 테스트**

Run: `cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew test`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Push + PR**

```bash
cd /Users/hong9/IdeaProjects/allfolio
git push -u origin feat/pnl-cost-basis-unification
gh pr create --title "feat: unify FIFO cost-basis into shared engine; fix post-restart FIFO drift" --body "## Summary
- TradeRaw 기반 FIFO 원가 계산을 trade 모듈의 순수 \`FifoCostEngine\` 하나로 통합.
- pnl 초기화기가 lots 없이 avgCost만 만들어 재부팅 후 \`costMethod=FIFO\`가 avgCost로 조용히 폴백하던 버그 수정 — 초기화기도 write-path와 동일한 lot 구조를 재생성.
- snapshot PositionEngine도 같은 코어로 위임(FIFO 구현 단일화). 기존 characterization 11개 GREEN 유지가 동작 보존 증거.

## 통합 전/후
- 전: FIFO 소진 로직이 snapshot·pnl 2곳 + 초기화기의 이동평균(lots 없음)까지 3갈래.
- 후: 순수 코어 1곳. pnl은 Redis DTO↔코어 매핑(레거시 lot-less shim 포함), snapshot은 코어 결과 투영 + 초과매도 사전검증만 자체 보유.

## 범위 밖
- unified-asset(StockTrade 도메인, 이동평균)은 다른 모듈/모델이라 별도 과제.

## Tests
- \`FifoCostEngineTest\`: BUY/SELL FIFO/전량매도/초과매도 clamp/증분==배치 동치성
- \`PositionDataMapperTest\`: lots 매핑 + 레거시 lot-less 수량 보존 shim
- \`PositionCacheRebuildConsistencyTest\`: 재부팅(replay) lots == write-path(apply) lots
- snapshot 11개 characterization GREEN 유지
- \`./gradlew test\` 전체 통과

설계·계획: docs/superpowers/specs/2026-07-08-pnl-cost-basis-unification-design.md, docs/superpowers/plans/2026-07-08-pnl-cost-basis-unification.md

🤖 Generated with [Claude Code](https://claude.com/claude-code)"
```
