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

/**
 * 여러 통화가 섞인 자산 목록을 **통화별 원통화 합계**로 묶는다 (AF-106).
 *
 * **위 [navInKrw]의 경고("통화를 무시하고 raw 합산하면 KRW와 USD를 그대로 더해 의미 없는
 * 숫자가 나온다")에 걸리지 않는다** — 여기서는 통화로 *묶은 뒤* 같은 통화끼리만 더하기
 * 때문이다. 그 경고를 보고 이 함수를 "고치려" 들지 말 것.
 *
 * 키는 `trim().uppercase()`로 정규화한다. `FxConverter.toKrw`·`rateOf`가 같은 방식으로
 * 통화를 정규화하므로, 여기서 맞춰 두지 않으면 `"usd"`와 `"USD"`가 별개 행으로 저장되고
 * 환율 조회가 어긋난다.
 *
 * [navInKrw]와 달리 환산하지 않는다 — 환산은 `PerformanceSnapshotService.record()`가
 * 통화별로 한 번씩 하고, 그 값이 곧 `nav_currency_daily.value_native`가 된다.
 */
fun Collection<Asset>.navByCurrency(): Map<String, BigDecimal> =
    groupingBy { it.currency.trim().uppercase() }
        .fold(BigDecimal.ZERO) { acc, asset -> acc + asset.currentValue }
