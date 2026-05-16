package com.allfolio.esg.domain

import java.math.BigDecimal
import java.math.RoundingMode

object EsgEngine {

    data class AssetInput(val type: String, val currentValue: BigDecimal)

    private val SCALE = 2
    private val ROUNDING = RoundingMode.HALF_UP

    private val SCORES: Map<String, Triple<Int, Int, Int>> = mapOf(
        "CRYPTO"      to Triple(20, 50, 40),
        "STOCK"       to Triple(60, 65, 65),
        "REAL_ESTATE" to Triple(55, 70, 65),
        "JEONSE"      to Triple(65, 80, 70),
        "VEHICLE"     to Triple(35, 60, 55),
        "GOLD"        to Triple(45, 55, 55),
        "CASH"        to Triple(80, 75, 80),
        "ETC"         to Triple(60, 60, 60),
    )

    private val DEFAULT_SCORE = Triple(60, 60, 60)

    fun scoreOf(type: String): Triple<Int, Int, Int> =
        SCORES[type] ?: DEFAULT_SCORE

    fun rating(total: BigDecimal): String = when {
        total >= BigDecimal("85") -> "A+"
        total >= BigDecimal("75") -> "A"
        total >= BigDecimal("65") -> "B+"
        total >= BigDecimal("55") -> "B"
        total >= BigDecimal("45") -> "C+"
        else                      -> "C"
    }

    fun calculate(assets: List<AssetInput>): EsgScore {
        if (assets.isEmpty()) throw EsgException.emptyAssets()

        val totalValue = assets.sumOf { it.currentValue }
        if (totalValue <= BigDecimal.ZERO) throw EsgException.emptyAssets()

        var eSum = BigDecimal.ZERO
        var sSum = BigDecimal.ZERO
        var gSum = BigDecimal.ZERO

        for (asset in assets) {
            val weight = asset.currentValue.divide(totalValue, 10, ROUNDING)
            val (e, s, g) = scoreOf(asset.type)
            eSum = eSum.add(weight.multiply(BigDecimal(e)))
            sSum = sSum.add(weight.multiply(BigDecimal(s)))
            gSum = gSum.add(weight.multiply(BigDecimal(g)))
        }

        val e = eSum.setScale(SCALE, ROUNDING)
        val s = sSum.setScale(SCALE, ROUNDING)
        val g = gSum.setScale(SCALE, ROUNDING)

        val total = e.multiply(BigDecimal("0.35"))
            .add(s.multiply(BigDecimal("0.30")))
            .add(g.multiply(BigDecimal("0.35")))
            .setScale(SCALE, ROUNDING)

        return EsgScore(
            environmental = e,
            social        = s,
            governance    = g,
            total         = total,
            rating        = rating(total),
        )
    }
}
