package com.allfolio.pnl

import com.allfolio.trade.domain.FifoCostEngine
import com.allfolio.trade.domain.LotPosition
import com.allfolio.trade.domain.TradeType
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.UUID

/**
 * 포지션 캐시 서비스 — Redis Hash 기반
 *
 * Redis 구조:
 *   key:   pnl:positions:{portfolioId}
 *   field: {assetId}
 *   value: JSON(PositionData)
 *
 * Lot 관리 (write path는 CostBasisMethod와 무관하게 항상 FIFO):
 *   - BUY:  lot 추가 → avgCost(잔여 lots 가중평균) 재계산
 *   - SELL: FIFO로 앞에서부터 소진 → 잔여 lot 저장
 *   원가/소진 계산은 공용 FifoCostEngine에 위임한다.
 *   CostBasisMethod(AVG_COST/FIFO)는 저장 방식이 아니라 read-time costBasis() 투영에만 영향.
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

    /**
     * Redis 연결 확인 (PING) — 연결 불가 시 예외 전파.
     * PositionCacheInitializer가 기동 직후 Redis 준비 여부를 확인/재시도할 때 사용.
     */
    fun ping() {
        redisTemplate.execute(RedisCallback { connection -> connection.ping() })
    }

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
            log.error("[PositionCache] init failed portfolioId={}: {}", portfolioId, e.message, e)
        }
    }

    // ──────────────────────────────────────────────
    // Cost basis helpers (read-time calculation)
    // ──────────────────────────────────────────────

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

    private fun positionKey(portfolioId: UUID) = "pnl:positions:$portfolioId"
}
