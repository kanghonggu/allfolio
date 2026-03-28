package com.allfolio.snapshot.application

import com.allfolio.snapshot.domain.DailySnapshotOrchestrator
import com.allfolio.snapshot.infrastructure.entity.PerformanceDailyEntity
import com.allfolio.snapshot.infrastructure.entity.RiskDailyEntity
import com.allfolio.snapshot.infrastructure.mapper.SnapshotMapper
import com.allfolio.snapshot.infrastructure.repository.PerformanceDailyJpaRepository
import com.allfolio.snapshot.infrastructure.repository.PositionDailyJpaRepository
import com.allfolio.snapshot.infrastructure.repository.RiskDailyJpaRepository
import com.allfolio.trade.infrastructure.mapper.TradeMapper
import com.allfolio.trade.infrastructure.repository.TradeRawJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 일간 스냅샷 생성 UseCase
 *
 * 트랜잭션 전략:
 * - 단일 트랜잭션
 * - DELETE (해당 date) → INSERT 패턴 (재계산 멱등성 보장)
 * - 순서: position → performance → risk
 *
 * 설계 원칙:
 * - @Transactional은 이 계층에만 위치
 * - 도메인 계산은 Orchestrator에 완전 위임 (side-effect 없음)
 * - Trade 저장은 포함하지 않는다 (RecordTradeUseCase 책임)
 */
@Service
@Transactional
class GenerateDailySnapshotUseCase(
    private val tradeRepository: TradeRawJpaRepository,
    private val positionRepository: PositionDailyJpaRepository,
    private val performanceRepository: PerformanceDailyJpaRepository,
    private val riskRepository: RiskDailyJpaRepository,
    private val portfolioValidator: PortfolioValidator,
) {
    /**
     * @return (PerformanceDailyEntity, RiskDailyEntity) — 캐시 갱신용으로 호출자에게 반환.
     *         @Transactional 커밋 후 캐시 처리는 호출자(Controller/Application Service) 책임.
     */
    fun generate(command: GenerateSnapshotCommand): Pair<PerformanceDailyEntity, RiskDailyEntity> {
        // ── Step 1: 기준일까지의 Trade 조회 ────────────────────────
        val cutoff = command.date.atTime(23, 59, 59)
        val tradesByAsset = loadTradesByAsset(command.portfolioId, cutoff)

        // ── Step 2: 도메인 계산 (순수, side-effect 없음) ────────────
        val result = DailySnapshotOrchestrator.generate(
            tradesByAsset            = tradesByAsset,
            marketPrices             = command.marketPrices,
            yesterdayNav             = command.yesterdayNav,
            externalCashFlow         = command.externalCashFlow,
            previousCumulativeReturn = command.previousCumulativeReturn,
            benchmarkReturn          = command.benchmarkReturn,
            recentDailyReturns       = command.recentDailyReturns,
        )

        // ── Step 2.5: 계산 결과 검증 (LOG ONLY — 예외 없음, 성능 보호) ─
        portfolioValidator.validate(result, command.marketPrices, command.portfolioId)

        // ── Step 3: 기존 스냅샷 삭제 (재계산 멱등성) ───────────────
        positionRepository.deleteByPortfolioIdAndDate(command.portfolioId, command.date)
        performanceRepository.deleteByPortfolioIdAndDate(command.portfolioId, command.date)
        riskRepository.deleteByPortfolioIdAndDate(command.portfolioId, command.date)

        // ── Step 4: 계산 결과 저장 ─────────────────────────────────
        val positionEntities = result.positions.map { snapshot ->
            SnapshotMapper.toPositionEntity(snapshot, command.tenantId, command.portfolioId, command.date)
        }
        positionRepository.saveAll(positionEntities)

        val performanceEntity = performanceRepository.save(
            SnapshotMapper.toPerformanceEntity(result.performance, command.tenantId, command.portfolioId, command.date)
        )

        val riskEntity = riskRepository.save(
            SnapshotMapper.toRiskEntity(result.risk, command.tenantId, command.portfolioId, command.date)
        )

        return Pair(performanceEntity, riskEntity)
    }

    // ──────────────────────────────────────────────
    // private helpers
    // ──────────────────────────────────────────────

    /**
     * 포트폴리오 전체 거래를 assetId 기준으로 그룹핑한다.
     * PositionEngine 입력 형태: Map<assetId, List<TradeRaw>>
     */
    private fun loadTradesByAsset(
        portfolioId: java.util.UUID,
        cutoff: LocalDateTime,
    ) = tradeRepository
        .findByPortfolioIdAndExecutedAtLessThanEqualOrderByExecutedAtAsc(portfolioId, cutoff)
        .let { TradeMapper.toDomainList(it) }
        .groupBy { it.assetId }
}
