package com.allfolio.unifiedasset.domain.benchmark

/**
 * 지원 벤치마크 지수. name은 benchmark_daily.index_type 문자열과 일치한다
 * (KOSPI·BTC는 기존 대시보드가 쓰는 타입 문자열 유지 — 호환).
 */
enum class BenchmarkType(val yahooTicker: String, val label: String) {
    SPX("^GSPC", "S&P 500"),
    KOSPI("^KS11", "KOSPI"),
    BTC("BTC-USD", "Bitcoin"),
}
