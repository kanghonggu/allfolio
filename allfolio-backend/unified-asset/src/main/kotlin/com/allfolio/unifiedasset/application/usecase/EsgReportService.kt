package com.allfolio.unifiedasset.application.usecase

import com.allfolio.esg.domain.EsgEngine
import com.allfolio.report.domain.AssetEsgRow
import com.allfolio.report.domain.EsgReport
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

@Service
class EsgReportService(
    private val assetRepository: AssetRepository,
    private val fx: FxConverter,
) {
    fun generate(userId: UUID): EsgReport {
        val assets = assetRepository.findByUserId(userId)
        if (assets.isEmpty()) throw ResponseStatusException(HttpStatus.NOT_FOUND, "자산이 없습니다")

        // 가치 가중 ESG 점수는 통화가 섞이면 왜곡되므로 KRW 환산값으로 가중한다.
        val totalValue = assets.navInKrw(fx)

        val inputs = assets.map { EsgEngine.AssetInput(it.type.name, it.currentValueInKrw(fx)) }
        val portfolioScore = EsgEngine.calculate(inputs)

        val breakdown = assets.map { asset ->
            val (e, s, g) = EsgEngine.scoreOf(asset.type.name)
            val assetTotal = BigDecimal(e).multiply(BigDecimal("0.35"))
                .add(BigDecimal(s).multiply(BigDecimal("0.30")))
                .add(BigDecimal(g).multiply(BigDecimal("0.35")))
                .setScale(2, RoundingMode.HALF_UP)
            val weight = if (totalValue > BigDecimal.ZERO)
                asset.currentValueInKrw(fx).divide(totalValue, 4, RoundingMode.HALF_UP)
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
            generatedAt        = OffsetDateTime.now(KST),
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

    companion object {
        /**
         * `generatedAt`은 KST 오프셋을 달아 내보낸다 — Render 컨테이너는 TZ 설정이 없어 UTC라
         * 기본 타임존을 쓰면 한국 사용자에게 9시간 어긋난다. 배경은 [ReportService.Companion] 참고.
         */
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
