package com.allfolio.market.commodity

import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * 원자재 수집 대상.
 *
 * **목록이 소스·주기별로 셋이다.** `MarketRateProperties`가 ECOS·FRED로 갈린 것과 같은
 * 이유로 소스가 갈리고(FSC는 좌표가 종목 단축코드다), FRED 안에서 다시 일간·월간이
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

    /** 금(금융위 FSC). 좌표가 시리즈 ID가 아니라 **종목 단축코드**(`srtnCd`)다 */
    var fsc: List<CommodityItem> = emptyList()

    val allItems: List<CommodityItem>
        get() = fredDaily + fredMonthly + fsc

    val allCodes: List<String>
        get() = allItems.map { it.code }

    /**
     * 오타난 설정으로는 기동하지 않는다. `MarketRateProperties.validate()`와 같은 자리·같은 방식이다.
     *
     * 런타임 실패로 흘리면 매일 실패 한 줄이 쌓일 뿐이고 그 종목은 계속 비어 있다.
     * **`init` 블록으로는 안 된다**: 이 클래스는 리스트를 감싼 단순 POJO이고, code 중복 검사는
     * 항목 하나가 아니라 목록 전체를 봐야 하는 규칙이라 항목별로 도는 `init`으로는 표현할 수 없다.
     * 그래서 바인딩이 끝난 뒤 한 번 도는 `@PostConstruct`를 쓴다.
     *
     * **`frequency`를 값 목록으로 검사하는 것이 이 메서드의 핵심이다.** DB 컬럼이 `VARCHAR(1)`이라
     * `Daily` 같은 오타는 바인딩도 CI도 초록인 채 **운영 insert에서 길이 초과로** 터진다 —
     * 배포가 끝난 뒤에야, 그것도 첫 수집 시각에 드러나는 실패다. 여기서 막으면 기동이 실패해
     * 사람이 즉시 본다. 게다가 이 값은 화면이 「시세」와 「월간 지표」 섹션을 가르는 근거이기도 해서,
     * 길이만 맞는 엉뚱한 한 글자(`W`)도 통과시키면 안 된다.
     */
    @PostConstruct
    fun validate() {
        // **[allItems]로 돈다.** 목록별로 돌면 넷째 목록이 생기는 날 그 목록만 검사에서 빠지는데,
        // 그건 이 클래스 KDoc이 금지하는 패턴이다. 대신 code가 빈 항목의 라벨은 목록 이름이 아니라
        // 합친 목록의 위치가 된다 — 그 경우 어차피 짚을 이름이 없으므로 잃는 것이 없다
        val itemProblems = allItems.flatMapIndexed { index, item ->
            val label = item.code.ifBlank { "market-commodity[$index]" }
            buildList {
                if (item.code.isBlank()) add("$label: code가 비어 있습니다")
                if (item.seriesId.isBlank()) add("$label: series-id가 비어 있습니다")
                // 단위가 비면 화면이 단위 없는 숫자를 그럴듯하게 그린다. 값 정책(PRICE)은
                // 상한이 없어 단위 오인을 구조적으로 못 잡으므로 표기가 유일한 방어다
                if (item.unit.isBlank()) add("$label: unit이 비어 있습니다")
                if (item.frequency !in ALLOWED_FREQUENCIES) {
                    add("$label: 지원하지 않는 주기입니다: ${item.frequency} (D 또는 M)")
                }
            }
        }

        // code 중복은 항목 하나만 봐서는 알 수 없다 — 전체 목록을 훑어야 하는 유일한 규칙이라 따로 둔다.
        // 저장 키가 (code, trade_date)라 같은 배치 안에서 뒤 항목이 앞 항목을 덮어쓸 뿐 제약조건은
        // 안 걸리고, 요약은 "requested=17 collected=17 failed=0" 초록인 채 종목 하나가 사라진다.
        // **세 목록을 합쳐서 본다** — 목록별로 검사하면 같은 코드가 fredDaily와 fsc에 하나씩 있는
        // 경우를 놓치는데, 그건 값도 출처도 매 실행 뒤에 도는 쪽으로 뒤집히는 더 나쁜 판본이다
        val duplicateProblems = allCodes
            // 빈 코드는 위에서 이미 잡았다. 남겨 두면 "빈 문자열이 중복됩니다"라는 읽을 수 없는
            // 두 번째 문제가 같이 나온다
            .filter { it.isNotBlank() }
            .groupingBy { it }.eachCount()
            .filterValues { it > 1 }.keys
            .map { "$it: code가 중복됩니다" }

        val problems = itemProblems + duplicateProblems
        require(problems.isEmpty()) { "market-commodity 설정이 올바르지 않습니다 — " + problems.joinToString("; ") }
    }

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

        /**
         * 소스 안에서 계열 하나를 찍는 좌표. FRED는 series_id(예: `DCOILWTICO`)이고,
         * **FSC는 종목 단축코드(`srtnCd`)다** — 금은 `"04020000"`(금 99.99_1kg).
         *
         * **오퍼레이션 코드가 아니다.** `getGoldPriceInfo`는 `FscCommodityClient`가 경로에
         * 고정으로 들고 있고 설정에 들어오지 않는다. 여기에 그 이름을 적으면
         * `FscCommoditySource`의 `srtnCd` 필터가 전 행을 걸러 **조용히 0건**이 되고,
         * 요약은 `emptySeries=[GOLD_KRX]` — "정상적으로 빈 계열"로 보이는 초록이다.
         * (Task 3이 실측 전에 "오퍼레이션 코드"로 추정해 뒀던 자리다. Task 4 실측이 뒤집었다.)
         *
         * **FSC 값은 yml에서 따옴표로 감싼다** — `04020000`을 YAML이 숫자로 읽으면
         * 앞의 0이 날아가 `1056768`(8진수)이 되고, 위와 같은 조용한 0건이 된다.
         */
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

    companion object {
        /**
         * 허용하는 주기. **DB 컬럼이 `VARCHAR(1)`이라 한 글자여야 하고**, 화면이 섹션을 가르는
         * 근거이기도 해서 길이만 맞는 아무 글자여서도 안 된다. 늘리려면 화면(`CommodityPanel`)의
         * 섹션 분기와 `CommodityCollectService.windowDaysFor`를 같이 볼 것
         */
        private val ALLOWED_FREQUENCIES = setOf("D", "M")
    }
}
