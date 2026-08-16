package com.allfolio.market.query

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
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
 *
 * **`market` 접두사를 쓰는 게 여기만이 아니다.** `MarketPriceBatchWriter`가
 * `@ConditionalOnProperty(name = ["market.tick.db-enabled"])`로 같은 접두사 아래를 본다
 * (그 키는 지금 `application.yml`에 없어 충돌은 아니다). 다만 yml의 `market:` 블록에는
 * AF-108 재배포 관련 주석이 여섯 줄 붙어 있어, `market.tick.*`을 거기 중첩하면 무관한 수집 설정이
 * 재배포 경고문 아래에 놓여 둘 다 오해받는다 — 새 `market.*` 키는 그 블록 밖에 따로 둘 것.
 *
 * **이 설정이 고장 나는 방식은 전부 "그래도 지수가 나간다"로 끝난다.** 접두사 오타, yml 키 이름
 * 변경, `market:` 블록 이동, 플레이스홀더 편집 — `@ConfigurationProperties`는 기본이
 * `ignoreUnknownFields = true`고 아래 필드 기본값도 `true`라, 어느 경우든 조용히 켜진 채 뜬다.
 * 그래서 [logResolved]가 해석된 값을 기동 로그에 남기고,
 * `MarketQueryPropertiesBindingTest`가 실제 yml + 환경변수 이름으로 바인딩을 못 박는다.
 */
@Component
@ConfigurationProperties(prefix = "market")
class MarketQueryProperties {
    var indicesEnabled: Boolean = true

    /**
     * 원자재 노출 스위치 (AF-108 원자재 탭).
     *
     * **[indicesEnabled]와 같은 이유로 있고 같은 실패 방식을 갖는다** — 바인딩이 어떻게 깨지든
     * 기본값 `true` 때문에 "그래도 나간다"로 떨어진다. 그래서 [logResolved]가 함께 찍고
     * `MarketQueryPropertiesBindingTest`가 환경변수 이름까지 못 박는다.
     *
     * **지수와 한 플래그로 묶지 않는다.** 소스가 다르다 — 지수는 KIS 개인용 오픈API(재배포 미결),
     * 원자재는 FRED(미국 정부·IMF 재배포분)다. 한쪽 답이 "불가"로 왔을 때 다른 쪽까지 끄면
     * 멀쩡한 탭을 잃고, 반대로 한쪽을 살리려다 막아야 할 쪽이 새어 나간다.
     */
    var commoditiesEnabled: Boolean = true

    /**
     * 기동 시 **해석된** 값을 한 줄 남긴다.
     *
     * 이 플래그를 뒤집는 순간은 대개 사고 대응 중이고(AF-108 답이 "불가"로 온 날),
     * 그때 손에 있는 건 Render 로그 꼬리다. 로그가 없으면 뒤집기가 먹었는지 확인하려고
     * 유효한 JWT로 엔드포인트를 불러 봐야 하는데, 급한 사람이 그걸 하고 있을 이유가 없다.
     *
     * 값이 `true`로 찍히는 것을 보고서야 "안 먹었다"를 알 수 있다는 게 요점이다 —
     * 위 KDoc대로 이 설정의 실패는 전부 켜진 쪽으로 떨어지므로 증상이 안 보인다.
     * 환경변수 이름을 함께 찍는다: 운영자가 대시보드에 실제로 입력하는 이름이 그것이다.
     */
    @PostConstruct
    fun logResolved() {
        log.info("[AF-104] market.indices-enabled={} (env MARKET_INDICES_ENABLED)", indicesEnabled)
        log.info("[AF-108] market.commodities-enabled={} (env MARKET_COMMODITIES_ENABLED)", commoditiesEnabled)
    }

    companion object {
        private val log = LoggerFactory.getLogger(MarketQueryProperties::class.java)
    }
}
