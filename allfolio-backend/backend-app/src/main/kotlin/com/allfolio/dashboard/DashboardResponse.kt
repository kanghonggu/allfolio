package com.allfolio.dashboard

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class DashboardResponse(
    val netWorth: NetWorthDto,
    val portfolio: PortfolioDto,
    val realAssets: List<RealAssetDto>,
)

data class NetWorthDto(
    val total: BigDecimal,
    val liquid: BigDecimal,
    val illiquid: BigDecimal,
    val debt: BigDecimal,
    // null = 30일 전 비교 기준 스냅샷 없음 (0 변동과 구분 — QA)
    // change30d는 입출금을 차감한 투자손익 (AF-95). 순자산 총변화가 아니다.
    val change30d: BigDecimal?,
    val changeRate30d: BigDecimal?,
    /** 기간 내 순 외부 입출금 — 화면이 "입출금 제외" 근거를 밝힐 수 있게 함께 내려준다 (AF-95) */
    val netFlow30d: BigDecimal?,
)

data class PortfolioDto(
    val totalValue: BigDecimal,
    val currency: String,
    val metrics: MetricsDto,
    val allocation: List<AllocationDto>,
    val positions: List<PositionDto>,
)

data class MetricsDto(
    val returnYtd: MetricValueDto?,
    val return1m: MetricValueDto?,
    val return3m: MetricValueDto?,
    val mdd: MetricValueDto?,
    val sharpe: MetricValueDto?,
    val var95: MetricValueDto?,
    val volatility: MetricValueDto?,
)

data class MetricValueDto(
    val value: BigDecimal,
    val grade: String,
    val stars: Int,
    val benchmarkVsKospi: BigDecimal?,
    val benchmarkVsBtc: BigDecimal?,
    val dataWarning: String?,
)

data class AllocationDto(
    val type: String,
    val ratio: BigDecimal,
    val value: BigDecimal,
    val grade: String,
)

data class PositionDto(
    val id: UUID,
    val name: String,
    val symbol: String?,
    val type: String,
    val currentValue: BigDecimal,
    // KRW 환산 평가액 — FE 먼지 포지션 판정은 원통화가 아니라 이 값 기준 (QA 후속 #4)
    val currentValueKrw: BigDecimal,
    val returnRate: BigDecimal,
    val weight: BigDecimal,
    val currency: String,
)

data class RealAssetDto(
    val id: UUID,
    val name: String,
    val type: String,
    val value: BigDecimal,
    val currency: String,
    val maturityDate: LocalDate?,
    val daysUntilMaturity: Long?,
)
