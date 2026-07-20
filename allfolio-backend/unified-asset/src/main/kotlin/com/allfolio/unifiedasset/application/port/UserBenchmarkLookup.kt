package com.allfolio.unifiedasset.application.port

import com.allfolio.unifiedasset.domain.benchmark.BenchmarkType
import java.util.UUID

/** 사용자 BM 설정 조회 포트 — 분석 유스케이스가 설정 서비스에 직접 묶이지 않도록 분리 */
interface UserBenchmarkLookup {
    fun get(userId: UUID): BenchmarkType?
}
