package com.allfolio.snapshot.infrastructure.cache

import java.time.LocalDate
import java.util.UUID

object CacheKeys {
    // v2: 리스크를 매매 대금 플로우로 다시 계산하면서 올렸다. v1 항목은 TTL 없이 risk_daily(매수일=수익) 값을
    // 들고 있어 그대로 두면 계속 읽힌다. v1 키는 고아로 남는다 — 메모리만 쓰고 읽히지 않는다.
    private const val NS = "snapshot:v2"

    /** snapshot:{tenantId}:{portfolioId}:{date} */
    fun snapshot(tenantId: UUID, portfolioId: UUID, date: LocalDate): String =
        "$NS:$tenantId:$portfolioId:$date"

    /** snapshot:{tenantId}:{portfolioId}:latest */
    fun latest(tenantId: UUID, portfolioId: UUID): String =
        "$NS:$tenantId:$portfolioId:latest"
}
