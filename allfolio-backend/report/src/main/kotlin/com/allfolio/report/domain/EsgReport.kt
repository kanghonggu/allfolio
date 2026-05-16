package com.allfolio.report.domain

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

data class EsgReport(
    val userId: UUID,
    val generatedAt: LocalDateTime,
    val rating: String,
    val totalScore: BigDecimal,
    val environmentalScore: BigDecimal,
    val socialScore: BigDecimal,
    val governanceScore: BigDecimal,
    val assetBreakdown: List<AssetEsgRow>,  // 전체 자산, total 내림차순
    val topAssets: List<AssetEsgRow>,       // 상위 3개 (ESG 우수)
    val bottomAssets: List<AssetEsgRow>,    // 하위 3개 (개선 필요)
)
