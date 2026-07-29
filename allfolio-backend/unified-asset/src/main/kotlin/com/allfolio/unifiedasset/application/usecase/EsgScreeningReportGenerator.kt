package com.allfolio.unifiedasset.application.usecase

import com.allfolio.esg.domain.EsgEngine
import com.allfolio.report.application.GeneratedReport
import com.allfolio.report.application.ReportBodyGenerator
import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.archive.ReportType
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.UUID

/**
 * R-07 투자배제·ESG 스크리닝 생성 엔진 (R2 #42 BE).
 * 기존 EsgEngine 재사용 ESG 스코어(자산유형 기반) + 코드 내장 프리셋 기반 배제 스크리닝.
 * 총평가액 0(자산 없음)은 EsgEngine.calculate 예외를 피해 유효한 0 보고서.
 * v1 제외: 사용자 배제리스트·관리(SCR-RPT-11), 위반 이력·감시로그·편입일, 국가/ISIN 매칭.
 */
@Component
class EsgScreeningReportGenerator(
    private val assetRepository: AssetRepository,
    private val fx: FxConverter,
) : ReportBodyGenerator {

    override val type = ReportType.ESG_SCREENING

    private val mapper = jacksonObjectMapper()
    private val mc = MathContext(10, RoundingMode.HALF_UP)

    override fun generate(userId: UUID, period: ReportPeriod): GeneratedReport {
        val valued = assetRepository.findByUserId(userId).map { it to it.currentValueInKrw(fx) }
        val totalKrw = valued.fold(BigDecimal.ZERO) { a, (_, v) -> a + v }

        val body: Map<String, Any?> = if (totalKrw <= BigDecimal.ZERO) emptyReport() else {
            val score = EsgEngine.calculate(valued.map { (a, v) -> EsgEngine.AssetInput(a.type.name, v) })

            val breakdown = valued.map { (a, v) ->
                val (e, s, g) = EsgEngine.scoreOf(a.type.name)
                val assetTotal = BigDecimal(e).multiply(BigDecimal("0.35"))
                    .add(BigDecimal(s).multiply(BigDecimal("0.30")))
                    .add(BigDecimal(g).multiply(BigDecimal("0.35")))
                    .setScale(2, RoundingMode.HALF_UP)
                mapOf(
                    "name" to a.name, "type" to a.type.name, "weight" to pct(v, totalKrw),
                    "e" to e, "s" to s, "g" to g, "total" to assetTotal, "rating" to EsgEngine.rating(assetTotal),
                )
            }.sortedByDescending { it["total"] as BigDecimal }

            val violated = valued.mapNotNull { (a, v) ->
                EsgExclusionPreset.lookup(a.symbol)?.let { ex -> Triple(a, v, ex) }
            }.sortedByDescending { it.second }
            val violationValueKrw = violated.fold(BigDecimal.ZERO) { acc, t -> acc + t.second }
            val violations = violated.map { (a, v, ex) ->
                mapOf("name" to a.name, "symbol" to a.symbol, "listName" to ex.listName,
                    "reason" to ex.reason, "valueKrw" to v, "weight" to pct(v, totalKrw))
            }

            mapOf(
                "esg" to mapOf(
                    "rating" to score.rating, "totalScore" to score.total,
                    "environmental" to score.environmental, "social" to score.social, "governance" to score.governance,
                ),
                "esgBreakdown" to breakdown,
                "screening" to mapOf(
                    "violationCount" to violated.size, "violationValueKrw" to violationValueKrw,
                    "violationWeight" to pct(violationValueKrw, totalKrw),
                ),
                "violations" to violations,
                "note" to NOTE,
            )
        }
        return GeneratedReport(asOfDate = period.end, bodyJson = mapper.writeValueAsString(body))
    }

    private fun emptyReport(): Map<String, Any?> = mapOf(
        "esg" to mapOf("rating" to "-", "totalScore" to BigDecimal.ZERO,
            "environmental" to BigDecimal.ZERO, "social" to BigDecimal.ZERO, "governance" to BigDecimal.ZERO),
        "esgBreakdown" to emptyList<Any>(),
        "screening" to mapOf("violationCount" to 0, "violationValueKrw" to BigDecimal.ZERO, "violationWeight" to BigDecimal.ZERO),
        "violations" to emptyList<Any>(),
        "note" to NOTE,
    )

    private fun pct(a: BigDecimal, b: BigDecimal): BigDecimal =
        if (b <= BigDecimal.ZERO) BigDecimal.ZERO
        else a.divide(b, mc).multiply(BigDecimal(100), mc).setScale(2, RoundingMode.HALF_UP)

    companion object { private const val NOTE = "ESG 점수는 자산유형 기반 · 배제는 v1 내장 프리셋 기준" }
}
