package com.allfolio.unifiedasset.application.usecase

import com.allfolio.esg.domain.EsgEngine
import com.allfolio.report.domain.AssetEsgRow
import com.allfolio.report.domain.EsgReport
import com.allfolio.unifiedasset.application.port.AssetRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.util.UUID

@Service
class EsgReportService(
    private val assetRepository: AssetRepository,
) {
    fun generate(userId: UUID): EsgReport {
        val assets = assetRepository.findByUserId(userId)
        if (assets.isEmpty()) throw ResponseStatusException(HttpStatus.NOT_FOUND, "자산이 없습니다")

        val totalValue = assets.sumOf { it.currentValue }

        val inputs = assets.map { EsgEngine.AssetInput(it.type.name, it.currentValue) }
        val portfolioScore = EsgEngine.calculate(inputs)

        val breakdown = assets.map { asset ->
            val (e, s, g) = EsgEngine.scoreOf(asset.type.name)
            val assetTotal = BigDecimal(e).multiply(BigDecimal("0.35"))
                .add(BigDecimal(s).multiply(BigDecimal("0.30")))
                .add(BigDecimal(g).multiply(BigDecimal("0.35")))
                .setScale(2, RoundingMode.HALF_UP)
            val weight = if (totalValue > BigDecimal.ZERO)
                asset.currentValue.divide(totalValue, 4, RoundingMode.HALF_UP)
            else BigDecimal.ZERO

            AssetEsgRow(
                name          = asset.name,
                type          = asset.type.name,
                currentValue  = asset.currentValue,
                weight        = weight,
                environmental = BigDecimal(e),
                social        = BigDecimal(s),
                governance    = BigDecimal(g),
                total         = assetTotal,
                rating        = EsgEngine.rating(assetTotal),
            )
        }.sortedByDescending { it.total }

        return EsgReport(
            userId             = userId,
            generatedAt        = LocalDateTime.now(),
            rating             = portfolioScore.rating,
            totalScore         = portfolioScore.total,
            environmentalScore = portfolioScore.environmental,
            socialScore        = portfolioScore.social,
            governanceScore    = portfolioScore.governance,
            assetBreakdown     = breakdown,
            topAssets          = breakdown.take(3),
            bottomAssets       = if (breakdown.size > 3) breakdown.takeLast(3).reversed() else emptyList(),
        )
    }
}
