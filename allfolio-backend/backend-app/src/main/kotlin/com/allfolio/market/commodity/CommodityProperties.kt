package com.allfolio.market.commodity

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * 원자재 수집 대상.
 *
 * **목록이 소스·주기별로 셋이다.** `MarketRateProperties`가 ECOS·FRED로 갈린 것과 같은
 * 이유로 소스가 갈리고(FSC는 좌표가 오퍼레이션 코드다), FRED 안에서 다시 일간·월간이
 * 갈린다 — 발행처(EIA/IMF)도 신선도도 다르고, 화면이 섹션을 가르는 근거가 그것이다.
 *
 * **코드 목록은 [allCodes] 하나만 본다.** 수집과 조회가 각자 `fredDaily + fredMonthly + fsc`를
 * 더하면 소스가 넷이 되는 날 한쪽만 고쳐지고, 증상은 "수집은 되는데 화면에 없다"이다 —
 * 오류도 로그도 없다. AF-FRED가 정확히 이 실수를 했다.
 *
 * **환경변수로 항목 하나만 패치할 수는 없다.** 스프링은 리스트를 병합하지 않고 우선순위가
 * 높은 쪽으로 통째로 교체한다 — `MarketRateProperties`와 같은 함정이다. 항목을 고칠 땐
 * `application.yml`을 고칠 것.
 */
@Component
@ConfigurationProperties(prefix = "market-commodity")
class CommodityProperties {
    /** 일간 에너지(FRED/EIA). 신선도는 영업일 3일 */
    var fredDaily: List<CommodityItem> = emptyList()

    /** 월간 지표(FRED/IMF). 신선도는 두 달 */
    var fredMonthly: List<CommodityItem> = emptyList()

    /** 금(금융위 FSC). 좌표가 시리즈 ID가 아니라 오퍼레이션 코드다 */
    var fsc: List<CommodityItem> = emptyList()

    val allItems: List<CommodityItem>
        get() = fredDaily + fredMonthly + fsc

    val allCodes: List<String>
        get() = allItems.map { it.code }

    /**
     * 원자재 한 종목의 수집 설정.
     *
     * `MarketRateProperties`처럼 목록별로 클래스를 가르지 않는다 — FRED든 FSC든 좌표가
     * 문자열 하나라서, 나눠 봐야 이름만 다른 같은 모양이 둘이 된다.
     */
    class CommodityItem {
        /**
         * 우리가 정한 canonical 코드. `market_commodity_quote`의 **`code` 컬럼**이 된다.
         *
         * **형제 표와 달리 접두어가 없다.** `market_rate.rate_code`·`market_index_quote`를
         * 따라 `commodity_code`로 쓰면 운영 Neon에 그 컬럼이 없어 배포 후 첫 insert에서
         * 터진다 — 마이그레이션이 `code`로 만들었다. 빠뜨린 접두어가 아니라 정한 이름이다.
         */
        var code: String = ""

        /** FRED series_id. FSC는 오퍼레이션 코드 */
        var seriesId: String = ""

        /**
         * USD/bbl · USD/MT · KRW/g · index.
         *
         * **`USc/lb`와 `USD/lb`는 한 글자 차이에 100배 차이다** — 정규화하지 말 것.
         */
        var unit: String = ""

        /** D | M. `(frequency, source)` 짝이 EIA·IMF·FSC를 가른다 */
        var frequency: String = ""
    }
}
