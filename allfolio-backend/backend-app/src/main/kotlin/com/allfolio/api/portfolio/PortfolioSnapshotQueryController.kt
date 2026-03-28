package com.allfolio.api.portfolio

import com.allfolio.api.cache.SnapshotCacheRepository
import com.allfolio.snapshot.infrastructure.repository.PerformanceDailyJpaRepository
import com.allfolio.snapshot.infrastructure.repository.RiskDailyJpaRepository
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/portfolios")
class PortfolioSnapshotQueryController(
    private val performanceRepository: PerformanceDailyJpaRepository,
    private val riskRepository: RiskDailyJpaRepository,
    private val snapshotCache: SnapshotCacheRepository,
) {
    /**
     * GET /api/portfolios/{id}/snapshot/{date}?tenantId=...
     *
     * Cache-Aside:
     * 1. Redis 조회 → hit 시 즉시 반환
     * 2. miss → DB 조회 → Redis 저장 → 반환
     * 3. Redis 장애 → DB 결과 그대로 반환 (fallback)
     */
    @GetMapping("/{id}/snapshot/{date}")
    fun getSnapshot(
        @PathVariable id: UUID,
        @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
        @RequestParam tenantId: UUID,
    ): ResponseEntity<PortfolioSnapshotResponse> {
        snapshotCache.getSnapshot(tenantId, id, date)?.let {
            return ResponseEntity.ok(it)
        }

        val performance = performanceRepository.findByPortfolioAndDateAndTenant(id, date, tenantId)
            ?: return ResponseEntity.notFound().build()

        val risk = riskRepository.findByPortfolioAndDateAndTenant(id, date, tenantId)
            ?: return ResponseEntity.notFound().build()

        val response = PortfolioSnapshotResponse.of(performance, risk)
        snapshotCache.saveSnapshot(tenantId, id, date, response)
        return ResponseEntity.ok(response)
    }

    /**
     * GET /api/portfolios/{id}/snapshot/latest?tenantId=...
     */
    @GetMapping("/{id}/snapshot/latest")
    fun getLatestSnapshot(
        @PathVariable id: UUID,
        @RequestParam tenantId: UUID,
    ): ResponseEntity<PortfolioSnapshotResponse> {
        snapshotCache.getLatest(tenantId, id)?.let {
            return ResponseEntity.ok(it)
        }

        val performance = performanceRepository.findTopByIdPortfolioIdOrderByIdDateDesc(id)
            ?: return ResponseEntity.notFound().build()

        val risk = riskRepository.findByPortfolioAndDateAndTenant(id, performance.id.date, tenantId)
            ?: return ResponseEntity.notFound().build()

        val response = PortfolioSnapshotResponse.of(performance, risk)
        snapshotCache.saveLatest(tenantId, id, response)
        return ResponseEntity.ok(response)
    }
}
