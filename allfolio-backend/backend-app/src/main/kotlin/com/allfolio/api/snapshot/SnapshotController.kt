package com.allfolio.api.snapshot

import com.allfolio.api.cache.SnapshotCacheRepository
import com.allfolio.api.portfolio.PortfolioSnapshotResponse
import com.allfolio.snapshot.application.GenerateDailySnapshotUseCase
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/snapshots")
class SnapshotController(
    private val generateDailySnapshotUseCase: GenerateDailySnapshotUseCase,
    private val snapshotCache: SnapshotCacheRepository,
) {
    /**
     * POST /api/snapshots/daily
     * 일간 스냅샷 생성 — DELETE(해당 date) + INSERT 멱등 처리
     *
     * Cache 전략:
     * 1. UseCase @Transactional 완료(커밋) 후 호출
     * 2. evict → saveLatest 순서 보장 (@Transactional 내부 Redis 호출 금지)
     */
    @PostMapping("/daily")
    fun generate(
        @RequestBody @Valid request: GenerateSnapshotRequest,
    ): ResponseEntity<Void> {
        val command = request.toCommand()

        // @Transactional — DB 커밋 완료 후 반환
        val (performance, risk) = generateDailySnapshotUseCase.generate(command)

        // 커밋 이후 캐시 처리 (@Transactional 외부)
        val response = PortfolioSnapshotResponse.of(performance, risk)
        snapshotCache.evict(command.tenantId, command.portfolioId, command.date)
        snapshotCache.saveLatest(command.tenantId, command.portfolioId, response)

        return ResponseEntity.ok().build()
    }
}
