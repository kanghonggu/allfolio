package com.allfolio.unifiedasset.application.port

import com.allfolio.unifiedasset.domain.benchmark.BenchmarkType
import java.math.BigDecimal
import java.time.LocalDate

/** 벤치마크 지수 일별 종가 히스토리 조회 포트 (구현: Yahoo 차트 API) */
interface BenchmarkHistoryClient {
    /** @param range Yahoo range 문자열 ("1mo", "1y" 등) */
    fun dailyHistory(type: BenchmarkType, range: String): List<Pair<LocalDate, BigDecimal>>
}
