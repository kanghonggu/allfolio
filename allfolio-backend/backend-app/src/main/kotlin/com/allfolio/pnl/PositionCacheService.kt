package com.allfolio.pnl

import com.allfolio.trade.domain.TradeType
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * 포지션 캐시 서비스 — Redis Hash 기반
 *
 * Redis 구조:
 *   key:   pnl:positions:{portfolioId}
 *   field: {assetId}
 *   value: JSON(PositionData)
 *
 * Lot 관리:
 *   - BUY:         PositionLot 추가 → avgCost 재계산
 *   - SELL FIFO:   앞에서부터 소진 → 잔여 Lot 저장
 *   - SELL AvgCost: totalQuantity 차감, lots 비례 차감
 *
 * 성능:
 *   - HSET/HGET: Redis O(1)
 *   - lots 처리: O(k), k = lot 개수 (통상 매우 작음)
 *   - DB 접근 없음
 */
@Service
class PositionCacheService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ──────────────────────────────────────────────
    // Write path
    // ──────────────────────────────────────────────

    /**
     * 트레이드 반영 — BrokerFacade에서 record() 성공 후 호출
     *
     * Trade write path(RecordTradeUseCase) 와 완전 분리.
     * SELL 처리는 항상 FIFO로 lots를 소진하며,
     * avgCost는 잔여 lots 기준 가중평균으로 자동 갱신된다.
     *
     * @param currency 매수 통화 (KRW | USDT)
     */
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

        val updated: PositionData? = when (tradeType) {
            TradeType.BUY  -> applyBuy(existing, portfolioId, assetId, price, quantity, currency)
            TradeType.SELL -> applySellFifo(existing, portfolioId, assetId, quantity)
        }

        if (updated == null) {
            // 포지션 청산 — Redis field 삭제
            runCatching { redisTemplate.opsForHash<String, String>().delete(key, field) }
            return
        }

        runCatching {
            redisTemplate.opsForHash<String, String>().put(key, field, objectMapper.writeValueAsString(updated))
        }.onFailure { e ->
            log.warn("[PositionCache] HSET failed portfolioId={} assetId={}: {}", portfolioId, assetId, e.message)
        }
    }

    // ──────────────────────────────────────────────
    // Read path
    // ──────────────────────────────────────────────

    /** 특정 자산 포지션 조회 — Redis HGET O(1) */
    fun getPosition(portfolioId: UUID, assetId: UUID): PositionData? =
        runCatching {
            val json = redisTemplate.opsForHash<String, String>()
                .get(positionKey(portfolioId), assetId.toString())
            json?.let { objectMapper.readValue(it, PositionData::class.java) }
        }.getOrElse { e ->
            log.warn("[PositionCache] HGET failed portfolioId={} assetId={}: {}", portfolioId, assetId, e.message)
            null
        }

    /** 포트폴리오 전체 포지션 — Redis HGETALL O(N) */
    fun getPositions(portfolioId: UUID): Map<UUID, PositionData> =
        runCatching {
            redisTemplate.opsForHash<String, String>()
                .entries(positionKey(portfolioId))
                .mapNotNull { (field, json) ->
                    runCatching {
                        UUID.fromString(field) to objectMapper.readValue(json, PositionData::class.java)
                    }.getOrNull()
                }
                .toMap()
        }.getOrElse { e ->
            log.warn("[PositionCache] HGETALL failed portfolioId={}: {}", portfolioId, e.message)
            emptyMap()
        }

    /** 스냅샷에서 포지션 초기화 (PositionCacheInitializer에서 사용) */
    fun initPositions(portfolioId: UUID, positions: Map<UUID, PositionData>) {
        if (positions.isEmpty()) return
        val key     = positionKey(portfolioId)
        val entries = positions.mapKeys { it.key.toString() }
            .mapValues { objectMapper.writeValueAsString(it.value) }
        runCatching {
            redisTemplate.opsForHash<String, String>().putAll(key, entries)
            log.info("[PositionCache] initialized portfolioId={} positions={}", portfolioId, positions.size)
        }.onFailure { e ->
            log.error("[PositionCache] init failed portfolioId={}: {}", portfolioId, e.message)
        }
    }

    // ──────────────────────────────────────────────
    // Cost basis helpers (read-time calculation)
    // ──────────────────────────────────────────────

    /**
     * costMethod 에 따른 원가 단가 반환.
     *   AVG_COST: lots 가중평균 (= PositionData.avgCost)
     *   FIFO:     가장 오래된 lot 단가 (lots[0].price)
     *             lots 없으면 avgCost fallback
     */
    fun costBasis(data: PositionData, method: CostBasisMethod): BigDecimal =
        when (method) {
            CostBasisMethod.AVG_COST -> data.avgCost
            CostBasisMethod.FIFO     -> data.lots.firstOrNull()?.price ?: data.avgCost
        }

    // ──────────────────────────────────────────────
    // Private — trade logic
    // ──────────────────────────────────────────────

    private fun applyBuy(
        existing: PositionData?,
        portfolioId: UUID,
        assetId: UUID,
        price: BigDecimal,
        quantity: BigDecimal,
        currency: String,
    ): PositionData {
        val newLot     = PositionLot(price = price, quantity = quantity)
        val updatedLots = (existing?.lots ?: emptyList()) + newLot

        return PositionData(
            portfolioId = portfolioId,
            assetId     = assetId,
            quantity    = updatedLots.sumOf { it.quantity },
            avgCost     = weightedAvgCost(updatedLots),
            currency    = currency,
            lots        = updatedLots,
        )
    }

    /**
     * SELL FIFO: 가장 오래된 lot부터 소진.
     * @return null 이면 포지션 완전 청산 → 호출자가 Redis field 삭제
     */
    private fun applySellFifo(
        existing: PositionData?,
        portfolioId: UUID,
        assetId: UUID,
        sellQty: BigDecimal,
    ): PositionData? {
        val currentQty = existing?.quantity ?: BigDecimal.ZERO
        val newQty     = (currentQty - sellQty).max(BigDecimal.ZERO)

        if (newQty <= BigDecimal.ZERO) return null  // 청산

        // lots 있으면 FIFO 소진, 없으면 totalQuantity 차감만 수행 (legacy 데이터 호환)
        val remainingLots = if (existing?.lots.isNullOrEmpty()) {
            emptyList()
        } else {
            consumeFifo(existing!!.lots.toMutableList(), sellQty)
        }

        return PositionData(
            portfolioId = portfolioId,
            assetId     = assetId,
            quantity    = newQty,
            avgCost     = if (remainingLots.isEmpty()) existing?.avgCost ?: BigDecimal.ZERO
                          else weightedAvgCost(remainingLots),
            currency    = existing?.currency ?: "KRW",
            lots        = remainingLots,
        )
    }

    /**
     * FIFO lot 소진. 원본 리스트를 변경하지 않고 새 리스트를 반환.
     */
    private fun consumeFifo(lots: MutableList<PositionLot>, sellQty: BigDecimal): List<PositionLot> {
        var remaining = sellQty
        val result    = lots.map { PositionLot(it.price, it.quantity, it.purchasedAt) }.toMutableList()
        val iter      = result.iterator()

        while (iter.hasNext() && remaining > BigDecimal.ZERO) {
            val lot = iter.next()
            if (lot.quantity <= remaining) {
                remaining -= lot.quantity
                iter.remove()
            } else {
                lot.quantity -= remaining
                remaining = BigDecimal.ZERO
            }
        }
        return result
    }

    /** lots 기반 가중평균 단가 */
    private fun weightedAvgCost(lots: List<PositionLot>): BigDecimal {
        val totalQty  = lots.sumOf { it.quantity }
        if (totalQty <= BigDecimal.ZERO) return BigDecimal.ZERO
        val totalCost = lots.fold(BigDecimal.ZERO) { acc, lot -> acc + lot.price * lot.quantity }
        return totalCost.divide(totalQty, 10, RoundingMode.HALF_UP)
    }

    private fun positionKey(portfolioId: UUID) = "pnl:positions:$portfolioId"
}
