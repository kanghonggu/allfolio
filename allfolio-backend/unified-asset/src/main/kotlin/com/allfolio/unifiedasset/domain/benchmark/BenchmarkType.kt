package com.allfolio.unifiedasset.domain.benchmark

/**
 * 지원 벤치마크 지수. name은 benchmark_daily.index_type 문자열과 일치한다
 * (KOSPI·BTC는 기존 대시보드가 쓰는 타입 문자열 유지 — 호환).
 */
enum class BenchmarkType(val yahooTicker: String, val label: String) {
    SPX("^GSPC", "S&P 500"),

    /**
     * **[yahooTicker]가 쓰이지 않는다.** KOSPI 종가는 AF-107 이후 공공데이터포털 수집기
     * (`FscIndexCollectService`)가 `benchmark_daily`에 채운다. **그래도 지우지 않는다** —
     * enum이 셋을 함께 들고 있고 SPX·BTC는 계속 쓴다. 값을 비우면 "티커가 없는 벤치마크"라는
     * 새 상태가 생기고, 그걸 아무도 검사하지 않는다.
     */
    KOSPI("^KS11", "KOSPI"),

    BTC("BTC-USD", "Bitcoin"),
    ;

    /**
     * Yahoo 동기화 대상인가. **KOSPI만 false다** — FSC가 채우므로 Yahoo가 같이 쓰면
     * 같은 행을 번갈아 덮어써 값이 실행마다 흔들린다(AF-107). 두 소스 다 "정상 동작"이라
     * 오류도 로그도 안 난다.
     *
     * 이 판별을 서비스로 옮기지 말 것 — 벤치마크가 늘 때 여기 한 곳만 보면 되게 둔다.
     */
    val syncedFromYahoo: Boolean get() = this != KOSPI
}
