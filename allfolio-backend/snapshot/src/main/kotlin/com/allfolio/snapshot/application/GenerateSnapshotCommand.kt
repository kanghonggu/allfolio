package com.allfolio.snapshot.application

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * 일간 스냅샷 생성 커맨드
 *
 * @param tenantId                  테넌트 식별자
 * @param portfolioId               포트폴리오 식별자
 * @param date                      스냅샷 생성 기준일
 * @param marketPrices              자산별 당일 종가 (assetId → price)
 * @param yesterdayNav              전일 NAV (첫날이면 0)
 * @param externalCashFlow          당일 외부 입출금 (없으면 0)
 * @param previousCumulativeReturn  전일까지 누적 수익률 (첫날이면 null)
 * @param benchmarkReturn           당일 벤치마크 수익률 (없으면 null)
 * @param recentDailyReturns        최근 N일 수익률 (리스크 계산용, 없으면 빈 리스트)
 */
data class GenerateSnapshotCommand(
    val tenantId: UUID,
    val portfolioId: UUID,
    val date: LocalDate,
    val marketPrices: Map<UUID, BigDecimal>,
    val yesterdayNav: BigDecimal = BigDecimal.ZERO,
    val externalCashFlow: BigDecimal = BigDecimal.ZERO,
    val previousCumulativeReturn: BigDecimal? = null,
    val benchmarkReturn: BigDecimal? = null,
    val recentDailyReturns: List<BigDecimal> = emptyList(),
)
