package com.allfolio.snapshot.application

import com.allfolio.snapshot.domain.DailySnapshotResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * 포트폴리오 계산 검증 컴포넌트
 *
 * [설계 원칙]
 * - 예외를 던지지 않는다 (성능 보호, 계산 파이프라인 중단 금지)
 * - 모든 불일치는 WARN 로그만 남긴다
 * - GenerateDailySnapshotUseCase Step 2 이후(도메인 계산 완료, 저장 전) 호출
 *
 * [검증 항목]
 * 1. NAV ≈ Σ(position.qty × marketPrice) — 허용 오차 1%
 * 2. 음수 수량 포지션 없음
 * 3. 음수 평균단가(avgCost) 없음
 */
@Component
class PortfolioValidator {

    private val log = LoggerFactory.getLogger(javaClass)

    private val NAV_TOLERANCE_PCT = BigDecimal("0.01")   // 1% 허용 오차

    /**
     * @param result    도메인 계산 결과 (DailySnapshotOrchestrator.generate() 출력)
     * @param marketPrices 자산별 시장가 (assetId → KRW 기준가)
     * @param portfolioId 로그 식별용
     */
    fun validate(
        result: DailySnapshotResult,
        marketPrices: Map<UUID, BigDecimal>,
        portfolioId: UUID,
    ) {
        validateNoNegativeQuantity(result, portfolioId)
        validateNoNegativeAvgCost(result, portfolioId)
        validateNavConsistency(result, marketPrices, portfolioId)
    }

    // ──────────────────────────────────────────────
    // private validators
    // ──────────────────────────────────────────────

    private fun validateNoNegativeQuantity(result: DailySnapshotResult, portfolioId: UUID) {
        result.positions
            .filter { it.totalQuantity < BigDecimal.ZERO }
            .forEach { pos ->
                log.warn(
                    "[Validator] NEGATIVE_QUANTITY portfolio={} assetId={} qty={}",
                    portfolioId, pos.assetId, pos.totalQuantity,
                )
            }
    }

    private fun validateNoNegativeAvgCost(result: DailySnapshotResult, portfolioId: UUID) {
        result.positions
            .filter { it.hasPosition() && it.averageCost < BigDecimal.ZERO }
            .forEach { pos ->
                log.warn(
                    "[Validator] NEGATIVE_AVG_COST portfolio={} assetId={} avgCost={}",
                    portfolioId, pos.assetId, pos.averageCost,
                )
            }
    }

    private fun validateNavConsistency(
        result: DailySnapshotResult,
        marketPrices: Map<UUID, BigDecimal>,
        portfolioId: UUID,
    ) {
        val nav = result.performance.nav
        if (nav <= BigDecimal.ZERO) return   // NAV 0이면 검증 생략 (초기 데이터)

        val positionNav = result.positions
            .filter { it.hasPosition() }
            .mapNotNull { pos ->
                marketPrices[pos.assetId]?.let { price -> price * pos.totalQuantity }
            }
            .fold(BigDecimal.ZERO, BigDecimal::add)

        if (positionNav <= BigDecimal.ZERO) return   // 시장가 없으면 검증 생략

        val diff = (nav - positionNav).abs()
        val tolerance = nav * NAV_TOLERANCE_PCT

        if (diff > tolerance) {
            val diffPct = diff.divide(nav, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP)
            log.warn(
                "[Validator] NAV_MISMATCH portfolio={} nav={} positionNav={} diff={} diffPct={}%",
                portfolioId, nav, positionNav, diff, diffPct,
            )
        }
    }
}
