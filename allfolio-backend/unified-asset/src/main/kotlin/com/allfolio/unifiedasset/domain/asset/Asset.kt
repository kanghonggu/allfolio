package com.allfolio.unifiedasset.domain.asset

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class Asset private constructor(
    val id: UUID,
    val userId: UUID,
    val accountId: UUID,
    val category: AssetCategory,
    val type: AssetType,
    val sourceType: AssetSourceType,
    val name: String,
    val symbol: String?,
    val quantity: BigDecimal,
    val purchasePrice: BigDecimal,
    val currentValue: BigDecimal,
    val currency: String,
    val valuationMethod: ValuationMethod,
    val confidenceLevel: ConfidenceLevel,
    val lastUpdatedAt: LocalDateTime,
    val createdAt: LocalDateTime,
    val memo: String?,
    val subType: String?,
    val loanAmount: BigDecimal?,
    val maturityDate: LocalDate?,
    val liquidityType: AssetLiquidityType,
    /**
     * 사용자가 적은 면적(평). **전용인지 공급인지 모른다** — 등록 화면 라벨이 `면적 (평)`
     * 하나뿐이라 어느 쪽으로도 들어온다. 표시용이고 **시세 매칭에 쓰면 안 된다.**
     */
    val areaPyeong: BigDecimal?,
    /**
     * 이 `currentValue`가 어느 날짜의 시세로 계산됐는지. 자동 평가된 자산만 채워진다.
     *
     * **[lastUpdatedAt]과 다른 값이다** — 그쪽은 "우리가 언제 썼나"이고 이쪽은 "시세가
     * 언제 것인가"다. 금은 D+1 공표라 평일에도 둘이 하루 이상 벌어지고, 연휴 뒤엔 4일까지
     * 간다. 화면이 "8/14 종가 기준"이라고 말할 수 있으려면 이 값이어야 한다.
     */
    val priceAsOf: LocalDate? = null,
    /**
     * 전용면적(㎡). **실거래가 매칭 키**이고 [areaPyeong]과 역할이 다르다.
     *
     * 국토부 실거래가는 전용면적을 ㎡로 주는데, `areaPyeong`으로는 행을 고를 수 없다.
     * 단위가 달라서가 아니라 **무엇의 면적인지가 안 갈리기 때문**이다 — "34평"은 보통
     * 공급면적이고 그 집의 전용은 84㎡(≈25.4평)다. 같은 숫자를 전용으로 읽으면 한 평형 위
     * 단지의 시세를 가져오는데, **둘 다 그럴듯한 금액이라 화면으로는 안 보인다.**
     *
     * 그래서 **소스가 확정한 값만** 담는다. R2 선택 UI에서 단지·평형을 고르면 API가 준 값이
     * 그대로 들어온다. `areaPyeong`에서 환산해 채우지 말 것 — 그 환산에는 근거가 없다.
     *
     * 값이 없으면 자동 평가를 하지 않는다(설계 원칙: 산출 불가능하면 null).
     */
    val exclusiveAreaM2: BigDecimal? = null,
) {
    // ILLIQUID(부동산·차량 등): purchasePrice가 총액이므로 수량을 곱하지 않음
    fun totalPurchaseCost(): BigDecimal =
        if (liquidityType == AssetLiquidityType.ILLIQUID) purchasePrice
        else quantity.multiply(purchasePrice)

    fun unrealizedPnl(): BigDecimal = currentValue.subtract(totalPurchaseCost())
    fun returnRate(): BigDecimal {
        val cost = totalPurchaseCost()
        if (cost <= BigDecimal.ZERO) return BigDecimal.ZERO
        return unrealizedPnl().divide(cost, 6, java.math.RoundingMode.HALF_UP)
            .multiply(BigDecimal(100))
    }
    fun netEquity(): BigDecimal = currentValue.subtract(loanAmount ?: BigDecimal.ZERO)

    companion object {
        private val ILLIQUID_TYPES = setOf(
            AssetType.REAL_ESTATE, AssetType.JEONSE, AssetType.VEHICLE,
        )

        fun create(
            userId: UUID,
            accountId: UUID,
            category: AssetCategory,
            type: AssetType,
            sourceType: AssetSourceType,
            name: String,
            symbol: String?,
            quantity: BigDecimal,
            purchasePrice: BigDecimal,
            currentValue: BigDecimal,
            currency: String,
            valuationMethod: ValuationMethod,
            memo: String? = null,
            subType: String? = null,
            loanAmount: BigDecimal? = null,
            maturityDate: LocalDate? = null,
            areaPyeong: BigDecimal? = null,
            exclusiveAreaM2: BigDecimal? = null,
        ): Asset {
            // QA P2: 자산명 서버 사이드 산티타이징 + 통화 화이트리스트 검증
            val safeName = com.allfolio.unifiedasset.domain.common.sanitizeUserText(name)
            require(safeName.isNotBlank()) { "자산명은 필수입니다" }
            require(quantity >= BigDecimal.ZERO) { "수량은 0 이상이어야 합니다" }
            require(currentValue >= BigDecimal.ZERO) { "현재 가치는 0 이상이어야 합니다" }

            val confidence = when (valuationMethod) {
                ValuationMethod.MARKET_PRICE -> ConfidenceLevel.HIGH
                ValuationMethod.BALANCE      -> ConfidenceLevel.HIGH
                ValuationMethod.USER_INPUT   -> ConfidenceLevel.LOW
            }
            val now = LocalDateTime.now()
            return Asset(
                id              = UUID.randomUUID(),
                userId          = userId,
                accountId       = accountId,
                category        = category,
                type            = type,
                sourceType      = sourceType,
                name            = safeName,
                symbol          = symbol?.trim()
                    ?.let { if (type == AssetType.CRYPTO || type == AssetType.STOCK) it.uppercase() else it },
                quantity        = quantity,
                purchasePrice   = purchasePrice,
                currentValue    = currentValue,
                currency        = com.allfolio.unifiedasset.domain.common.Currencies.normalize(currency),
                valuationMethod = valuationMethod,
                confidenceLevel = confidence,
                lastUpdatedAt   = now,
                createdAt       = now,
                memo            = memo?.trim(),
                subType         = subType?.trim()?.uppercase(),
                loanAmount      = loanAmount,
                maturityDate    = maturityDate,
                liquidityType   = if (type in ILLIQUID_TYPES) AssetLiquidityType.ILLIQUID
                                  else AssetLiquidityType.LIQUID,
                areaPyeong      = areaPyeong,
                exclusiveAreaM2 = exclusiveAreaM2,
            )
        }

        fun reconstruct(
            id: UUID, userId: UUID, accountId: UUID, category: AssetCategory, type: AssetType,
            sourceType: AssetSourceType, name: String, symbol: String?, quantity: BigDecimal,
            purchasePrice: BigDecimal, currentValue: BigDecimal, currency: String,
            valuationMethod: ValuationMethod, confidenceLevel: ConfidenceLevel,
            lastUpdatedAt: LocalDateTime, createdAt: LocalDateTime, memo: String?,
            subType: String? = null, loanAmount: BigDecimal? = null,
            maturityDate: LocalDate? = null,
            liquidityType: AssetLiquidityType = AssetLiquidityType.LIQUID,
            areaPyeong: BigDecimal? = null,
            priceAsOf: LocalDate? = null,
            exclusiveAreaM2: BigDecimal? = null,
            // 위치 인자로 넘기지 않는다 — areaPyeong과 exclusiveAreaM2가 둘 다 BigDecimal?이라
            // 순서가 바뀌어도 컴파일된다. 그러면 표시용 값이 매칭 키로 들어가고 조용히 틀린다.
        ) = Asset(
            id = id, userId = userId, accountId = accountId, category = category, type = type,
            sourceType = sourceType, name = name, symbol = symbol, quantity = quantity,
            purchasePrice = purchasePrice, currentValue = currentValue, currency = currency,
            valuationMethod = valuationMethod, confidenceLevel = confidenceLevel,
            lastUpdatedAt = lastUpdatedAt, createdAt = createdAt, memo = memo,
            subType = subType, loanAmount = loanAmount, maturityDate = maturityDate,
            liquidityType = liquidityType, areaPyeong = areaPyeong, priceAsOf = priceAsOf,
            exclusiveAreaM2 = exclusiveAreaM2,
        )
    }
}
