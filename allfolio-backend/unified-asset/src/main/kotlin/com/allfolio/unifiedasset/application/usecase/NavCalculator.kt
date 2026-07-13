package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.domain.asset.Asset
import java.math.BigDecimal

/** 자산의 현재가치를 KRW로 환산한 값. */
fun Asset.currentValueInKrw(fx: FxConverter): BigDecimal = fx.toKrw(currentValue, currency)

/** 자산의 총 매입원가를 KRW로 환산한 값. */
fun Asset.purchaseCostInKrw(fx: FxConverter): BigDecimal = fx.toKrw(totalPurchaseCost(), currency)

/** 자산의 평가손익(현재가치 − 매입원가)을 KRW로 환산한 값. */
fun Asset.unrealizedPnlInKrw(fx: FxConverter): BigDecimal = currentValueInKrw(fx) - purchaseCostInKrw(fx)

/** 자산의 대출금을 KRW로 환산한 값(대출 없으면 0). */
fun Asset.loanAmountInKrw(fx: FxConverter): BigDecimal = fx.toKrw(loanAmount ?: BigDecimal.ZERO, currency)

/**
 * 여러 통화가 섞인 자산 목록의 현재가치를 각각 KRW로 환산해 합산한 NAV.
 *
 * `sumOf { it.currentValue }`처럼 통화를 무시하고 raw 합산하면
 * KRW 값과 USD 값을 그대로 더해 의미 없는 숫자가 나온다. 크로스-자산
 * NAV·총자산을 계산하는 모든 지점은 이 함수를 사용해야 한다.
 */
fun Collection<Asset>.navInKrw(fx: FxConverter): BigDecimal =
    fold(BigDecimal.ZERO) { acc, asset -> acc + asset.currentValueInKrw(fx) }
