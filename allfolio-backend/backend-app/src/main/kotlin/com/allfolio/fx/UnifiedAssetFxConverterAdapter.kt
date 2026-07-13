package com.allfolio.fx

import com.allfolio.unifiedasset.application.port.FxConverter
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * unified-asset의 [FxConverter] 포트를 backend-app FX 인프라([CurrencyConverter],
 * Redis 캐시 환율)로 연결하는 어댑터.
 *
 * unified-asset 모듈은 backend-app에 의존할 수 없으므로(의존 방향이 반대),
 * 포트는 unified-asset에 두고 구현(어댑터)만 여기서 주입한다.
 */
@Component
class UnifiedAssetFxConverterAdapter(
    private val currencyConverter: CurrencyConverter,
) : FxConverter {
    override fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
        currencyConverter.toKrw(amount, currency)
}
