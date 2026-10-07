package com.allfolio.api.snapshot

import com.allfolio.api.cache.SnapshotCacheRepository
import com.allfolio.auth.PortfolioAuthorizationService
import com.allfolio.snapshot.application.GenerateDailySnapshotUseCase
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/snapshots")
class SnapshotController(
    private val generateDailySnapshotUseCase: GenerateDailySnapshotUseCase,
    private val snapshotCache: SnapshotCacheRepository,
    private val portfolioAuthorizationService: PortfolioAuthorizationService,
) {
    /**
     * POST /api/snapshots/daily
     * 일간 스냅샷 생성 — DELETE(해당 date) + INSERT 멱등 처리
     *
     * Cache 전략:
     * 1. UseCase @Transactional 완료(커밋) 후 호출 (@Transactional 내부 Redis 호출 금지)
     * 2. evict만 한다 — latest를 여기서 채우지 않는다. 리스크는 조회 시점에 매매 대금 플로우로
     *    계산하는데(PortfolioSnapshotQueryController), 여기서 채우면 risk_daily(매수일=수익) 값이 들어간다.
     */
    @PostMapping("/daily")
    fun generate(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestBody @Valid request: GenerateSnapshotRequest,
    ): ResponseEntity<Void> {
        portfolioAuthorizationService.requireOwnedPortfolio(userId, request.portfolioId)
        val command = request.toCommand().copy(tenantId = userId)

        // @Transactional — DB 커밋 완료 후 반환
        generateDailySnapshotUseCase.generate(command)

        // 커밋 이후 캐시 처리 (@Transactional 외부)
        snapshotCache.evict(command.tenantId, command.portfolioId, command.date)

        return ResponseEntity.ok().build()
    }
}
