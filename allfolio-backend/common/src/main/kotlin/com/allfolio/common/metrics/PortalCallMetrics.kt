package com.allfolio.common.metrics

/**
 * 공공데이터포털을 부르는 소비자.
 *
 * **하나의 인증키(`FSC_API_KEY`)를 여럿이 나눠 쓴다.** 일일 한도를 넘겼을 때 "누가 먹었나"를
 * 답하려면 호출을 소비자별로 갈라 세는 수밖에 없다 — 지금은 성공한 호출이 `log.debug`라
 * 운영에서 아무 흔적도 남지 않는다(AF-203·AF-210).
 *
 * **오퍼레이션 단위로 나눈다. 클래스 단위가 아니다.** `FscStockClient` 하나가 셋으로 갈린 것이
 * 그래서다 — 현재가 폴백은 종목당 [STOCK_PRICE]가 먼저 돌고 그게 0건이면 [STOCK_ETF_PRICE]가
 * 한 번 더 돈다. 클래스로 뭉뚱그려 세면 "폴백의 폴백이 몇 번 도는가"라는 정작 궁금한 질문에
 * 답할 수 없다. 포털의 활용신청도 서비스 단위라 실패가 오퍼레이션별로 갈린다.
 *
 * @param tag 메트릭 태그 값. **바꾸면 기존 시계열이 끊긴다** — 대시보드가 이 문자열을 문다.
 */
enum class PortalConsumer(val tag: String) {
    /** 국토교통부 아파트 실거래가 (`getRTMSDataSvcAptTradeDev`) — admin 수동 수집 전용 */
    RTMS("rtms"),

    /** 금융위원회 지수시세 (`getStockMarketIndex`) — 평일 예약 수집 */
    INDEX("index"),

    /** 금융위원회 금시세 (`getGoldPriceInfo`) — 평일 예약 수집 */
    COMMODITY("commodity"),

    /** 금융위원회 주식시세 (`getStockPriceInfo`) — Yahoo 실패 시 KR 종목당 1회 */
    STOCK_PRICE("stock_price"),

    /** 금융위원회 증권상품시세 (`getETFPriceInfo`) — [STOCK_PRICE]가 값을 못 줬을 때 한 번 더 */
    STOCK_ETF_PRICE("stock_etf_price"),

    /** 금융위원회 KRX 상장종목 목록 (`getItemInfo`) — **페이지 하나가 호출 하나다** */
    STOCK_LIST("stock_list"),
}

/**
 * 포털 호출 1회를 세는 포트.
 *
 * ## 왜 포트인가 — Micrometer를 직접 부르지 않는 이유
 *
 * 네 클라이언트가 두 모듈에 흩어져 있는데(`backend-app` 셋, `unified-asset` 하나)
 * **`unified-asset`의 컴파일 클래스패스에 Micrometer가 없다.** 계측 하나 붙이자고
 * 모듈에 의존성을 새로 들이는 것은 금지(Tier 2-1)라, 두 모듈이 모두 보는 `common`에
 * 인터페이스만 두고 구현(`MicrometerPortalCallMetrics`)을 `backend-app`에 둔다 —
 * 이 레포가 `FxConverter`·`RateSource`에서 쓰는 것과 같은 모양이다.
 *
 * ## 구현체는 절대 던지지 않는다
 *
 * 계측이 시세 수집을 죽이면 안 된다. 카운터 증가는 in-memory O(1)이라 실패할 일이
 * 없지만(`BrokerMetrics` 주석 참조), 구현하는 쪽은 그 계약을 지킬 것.
 */
interface PortalCallMetrics {
    /** 포털이 쓸 수 있는 결과를 돌려줬다 */
    fun callSucceeded(consumer: PortalConsumer)

    /**
     * 호출은 나갔는데 쓸 결과를 못 얻었다.
     *
     * **HTTP 실패만이 아니다.** 포털은 미승인·쿼터초과를 HTTP 200에 다른 봉투로 실어 준다 —
     * 한도 초과를 진단하려고 만든 계측이 정작 한도 초과를 성공으로 세면 아무 소용이 없다.
     * 그래서 성공/실패의 기준은 전송 성공이 아니라 **쓸 수 있는 값을 얻었는가**다.
     */
    fun callFailed(consumer: PortalConsumer)
}

/**
 * 아무것도 세지 않는 구현. **테스트에서 계측이 관심 밖일 때만 쓴다.**
 *
 * 운영 경로의 기본값으로 쓰지 말 것 — 생성자 기본값이 no-op이면 배선이 끊겨도 조용해서,
 * "계측을 붙였다"는 사실이 "계측이 돈다"는 착각으로 굳는다. 그래서 클라이언트들의
 * 생성자 인자에 기본값을 두지 않았다(빈이 없으면 부팅이 시끄럽게 실패한다).
 */
object NoOpPortalCallMetrics : PortalCallMetrics {
    override fun callSucceeded(consumer: PortalConsumer) = Unit
    override fun callFailed(consumer: PortalConsumer) = Unit
}

/**
 * [block] 한 번을 포털 호출 한 번으로 세고, 결과에 따라 태그를 가른다.
 *
 * **`inline`이 아닌 것은 의도다.** inline이면 람다 안에서 바깥 함수로 빠져나가는
 * non-local return이 가능해지고, 그 경로는 카운터를 통째로 건너뛴다 — 계측이 조용히
 * 새는 가장 쉬운 길이다. non-inline이면 컴파일러가 애초에 막는다.
 * (그래서 non-local return이 실제로 있는 `FscStockClient`는 이 헬퍼 대신
 *  `try/finally`로 센다 — 거기 주석 참조.)
 */
fun <T> PortalCallMetrics.measurePortalCall(consumer: PortalConsumer, block: () -> T): T {
    val result = try {
        block()
    } catch (e: Throwable) {
        callFailed(consumer)
        throw e
    }
    callSucceeded(consumer)
    return result
}
