package com.allfolio.realasset

import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

/** 사용자가 보유한 자산 한 건(평가 제외) */
data class RealAssetHolding(
    val id: UUID,
    val assetType: AssetType,
    val subType: String?,
    val name: String,
    val quantity: BigDecimal,
    val purity: BigDecimal,
    val acquiredAt: LocalDate,
    val acquiredCostKrw: Long,
)

/** 그 자산의 가장 최근 평가 스냅샷 */
data class LatestValuation(
    val unitPrice: BigDecimal,
    val priceUnit: String,
    val valuationKrw: Long,
    val valuedOn: LocalDate,
    val priceAsOf: LocalDate,
    val stalenessDays: Int,
    val priceBasis: PriceBasis,
    val confidence: Confidence?,
)

/**
 * 화면에 나가는 한 줄.
 *
 * **평가 관련 필드가 전부 nullable이다 — 이게 요점이다.** 스냅샷이 아직 없으면(등록 당일,
 * 배치 전) "0원"이 아니라 "모른다"이다. 0을 내면 화면이 전액 손실로 표시한다.
 */
data class RealAssetView(
    val id: UUID,
    val assetType: AssetType,
    val subType: String?,
    val name: String,
    val quantity: BigDecimal,
    val purity: BigDecimal,
    val acquiredAt: LocalDate,
    val acquiredCostKrw: Long,
    val valuationKrw: Long?,
    val profitKrw: Long?,
    /** 소수 넷째 자리(0.2500 = 25%). 취득가가 0이면 null — 0으로 나눌 수 없다 */
    val profitRate: BigDecimal?,
    val unitPrice: BigDecimal?,
    val priceUnit: String?,
    val valuedOn: LocalDate?,
    /** **화면에 반드시 노출한다.** 사용자가 일요일에 보는 숫자가 금요일 종가임을 숨기지 않는다 */
    val priceAsOf: LocalDate?,
    /** 정상 범위는 1~4다. 5 이상이면 소스가 멈춘 것이다 */
    val stalenessDays: Int?,
    val priceBasis: PriceBasis?,
    val confidence: Confidence?,
)

/**
 * 실물자산 조회 (A1 · G7).
 *
 * **평가가 없는 자산도 목록에서 빠지지 않는다.** 등록 당일에는 스냅샷이 없는데, 그때 목록에서
 * 사라지면 사용자는 등록이 실패한 줄 안다. 자산은 늘 보이고 평가 칸만 빈다.
 */
@Service
class RealAssetQueryService(
    private val store: Store,
) {
    interface Store {
        fun holdings(userId: UUID): List<RealAssetHolding>

        fun latestValuations(assetIds: Collection<UUID>): Map<UUID, LatestValuation>
    }

    fun findByUser(userId: UUID): List<RealAssetView> {
        val holdings = store.holdings(userId)
        if (holdings.isEmpty()) return emptyList()

        // 자산마다 조회하면 보유 수만큼 왕복이 된다(Neon은 원격). 한 번에 읽는다.
        val latest = store.latestValuations(holdings.map { it.id })

        return holdings.map { holding ->
            val valuation = latest[holding.id]
            val profit = valuation?.let { it.valuationKrw - holding.acquiredCostKrw }

            RealAssetView(
                id = holding.id,
                assetType = holding.assetType,
                subType = holding.subType,
                name = holding.name,
                quantity = holding.quantity,
                purity = holding.purity,
                acquiredAt = holding.acquiredAt,
                acquiredCostKrw = holding.acquiredCostKrw,
                valuationKrw = valuation?.valuationKrw,
                profitKrw = profit,
                // **취득가 0에서 예외를 던지지 않는다.** 증여받은 금을 0원으로 등록하는 경우가
                // 있고, 예외로 끝내면 그 자산 하나 때문에 목록 전체가 500이 된다.
                profitRate = if (profit != null && holding.acquiredCostKrw > 0) {
                    BigDecimal(profit).divide(BigDecimal(holding.acquiredCostKrw), 4, RoundingMode.HALF_UP)
                } else {
                    null
                },
                unitPrice = valuation?.unitPrice,
                priceUnit = valuation?.priceUnit,
                valuedOn = valuation?.valuedOn,
                priceAsOf = valuation?.priceAsOf,
                stalenessDays = valuation?.stalenessDays,
                priceBasis = valuation?.priceBasis,
                confidence = valuation?.confidence,
            )
        }
    }
}
