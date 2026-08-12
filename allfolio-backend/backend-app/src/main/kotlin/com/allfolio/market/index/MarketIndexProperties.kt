package com.allfolio.market.index

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "market-index")
class MarketIndexProperties {
    var domestic: List<DomesticIndex> = emptyList()

    class DomesticIndex {
        /** 우리가 정한 canonical 코드. DB의 index_code가 된다 */
        var code: String = ""
        /** KIS FID_INPUT_ISCD */
        var kisIscd: String = ""
    }
}
