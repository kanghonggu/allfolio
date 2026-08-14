package com.allfolio.market.query

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * 시장 화면 노출 설정 (AF-104).
 *
 * **[indicesEnabled]는 AF-108 재배포 검토의 미결 때문에 있다.** KIS 개인용 오픈API의 시세
 * 재배포 가능 여부가 확정되지 않았고(원문 미확보), Twelve Data 무료 티어는 불가로 확정됐다.
 * 지금은 켜 두지만, 답이 "불가"로 오면 **설정 한 줄로 지수를 화면에서 뺄 수 있어야 한다** —
 * 그러지 않으면 화면을 통째로 들어내야 하고, 그게 AF-108이 막으려던 상황이다.
 *
 * 환율(하나은행)·금리(한국은행)는 성격이 달라 같은 제약을 받지 않을 가능성이 높아 플래그가 없다.
 *
 * 접두사가 `market-index`·`market-rate`(수집 대상 목록)와 달리 `market`인 것은 의도한 것이다 —
 * 이건 수집이 아니라 **화면에 무엇을 내보낼지**의 설정이라 수집 설정에 얹으면 안 된다.
 */
@Component
@ConfigurationProperties(prefix = "market")
class MarketQueryProperties {
    var indicesEnabled: Boolean = true
}
