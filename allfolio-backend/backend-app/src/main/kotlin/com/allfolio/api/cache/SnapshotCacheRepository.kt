package com.allfolio.api.cache

import com.allfolio.api.portfolio.PortfolioSnapshotResponse
import com.allfolio.snapshot.infrastructure.cache.CacheKeys
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

/**
 * Snapshot Cache-Aside Repository
 *
 * 설계 원칙:
 * - Redis 예외 발생 시 로그 후 null 반환 (DB fallback 보장)
 * - @Transactional 내부에서 호출 금지
 * - TTL 없음 — 재계산 시 evict + 재삽입
 */
@Repository
class SnapshotCacheRepository(
    private val redisTemplate: RedisTemplate<String, Any>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun getSnapshot(tenantId: UUID, portfolioId: UUID, date: LocalDate): PortfolioSnapshotResponse? =
        safeGet(CacheKeys.snapshot(tenantId, portfolioId, date))

    fun getLatest(tenantId: UUID, portfolioId: UUID): PortfolioSnapshotResponse? =
        safeGet(CacheKeys.latest(tenantId, portfolioId))

    fun saveSnapshot(
        tenantId: UUID,
        portfolioId: UUID,
        date: LocalDate,
        snapshot: PortfolioSnapshotResponse,
    ) = safeSet(CacheKeys.snapshot(tenantId, portfolioId, date), snapshot)

    fun saveLatest(
        tenantId: UUID,
        portfolioId: UUID,
        snapshot: PortfolioSnapshotResponse,
    ) = safeSet(CacheKeys.latest(tenantId, portfolioId), snapshot)

    fun evict(tenantId: UUID, portfolioId: UUID, date: LocalDate) {
        try {
            redisTemplate.delete(CacheKeys.snapshot(tenantId, portfolioId, date))
            redisTemplate.delete(CacheKeys.latest(tenantId, portfolioId))
        } catch (e: Exception) {
            log.warn("[Cache] evict failed key=snapshot:{}:{}:{}", tenantId, portfolioId, date, e)
        }
    }

    // ──────────────────────────────────────────────
    // Redis 장애 격리
    // ──────────────────────────────────────────────

    private fun safeGet(key: String): PortfolioSnapshotResponse? = try {
        when (val raw = redisTemplate.opsForValue().get(key)) {
            null                           -> null
            is PortfolioSnapshotResponse   -> raw
            else -> objectMapper.convertValue(raw, PortfolioSnapshotResponse::class.java)
        }
    } catch (e: Exception) {
        log.warn("[Cache] get failed key={}", key, e)
        null
    }

    private fun safeSet(key: String, value: Any) {
        try {
            redisTemplate.opsForValue().set(key, value)
        } catch (e: Exception) {
            log.warn("[Cache] set failed key={}", key, e)
        }
    }
}
