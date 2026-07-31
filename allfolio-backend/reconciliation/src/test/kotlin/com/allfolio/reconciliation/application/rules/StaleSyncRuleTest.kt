package com.allfolio.reconciliation.application.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class StaleSyncRuleTest {

    private val now = LocalDateTime.of(2026, 7, 31, 12, 0)

    @Test
    fun `ERROR 상태는 최근 동기화와 무관하게 위반이다`() {
        assertEquals("동기화 실패 상태", StaleSyncRule.violationReason("ERROR", now.minusMinutes(1), now))
    }

    @Test
    fun `한 번도 동기화되지 않으면 위반이다`() {
        assertNotNull(StaleSyncRule.violationReason("ACTIVE", null, now))
    }

    @Test
    fun `임계(26h) 초과 미동기화는 위반, 이내는 정상이다`() {
        assertNotNull(StaleSyncRule.violationReason("ACTIVE", now.minusHours(27), now))
        assertNull(StaleSyncRule.violationReason("ACTIVE", now.minusHours(25), now))
    }

    @Test
    fun `경계 - 정확히 26h 전은 위반이 아니다`() {
        assertNull(StaleSyncRule.violationReason("ACTIVE", now.minusHours(26), now))
    }
}
