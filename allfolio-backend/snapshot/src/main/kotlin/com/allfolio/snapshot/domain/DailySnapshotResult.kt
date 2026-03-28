package com.allfolio.snapshot.domain

import com.allfolio.risk.domain.RiskSnapshot
import java.math.BigDecimal

/**
 * 하루 기관형 리포트 스냅샷 계산 결과.
 * 불변 객체 — 생성 후 수정 불가.
 */
data class DailySnapshotResult(
    val positions: List<PositionSnapshot>,
    val performance: DailyPerformanceSnapshot,
    val risk: RiskSnapshot,
) {
    /** 포지션 보유 자산 수 */
    val activePositionCount: Int
        get() = positions.count { it.hasPosition() }

    /** 전체 NAV (포지션 합산) */
    val totalNav: BigDecimal
        get() = performance.nav
}
