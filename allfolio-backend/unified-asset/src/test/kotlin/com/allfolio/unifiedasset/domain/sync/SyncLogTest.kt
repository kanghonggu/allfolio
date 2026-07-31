package com.allfolio.unifiedasset.domain.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class SyncLogTest {

    @Test
    fun `에러 메시지는 500자로 절단된다`() {
        val log = SyncLog.create(
            accountId = UUID.randomUUID(), userId = UUID.randomUUID(),
            trigger = SyncTrigger.MANUAL, status = SyncLogStatus.ERROR,
            syncedCount = 0, errorMessage = "x".repeat(600),
        )
        assertEquals(500, log.errorMessage!!.length)
    }

    @Test
    fun `성공 로그는 에러 메시지가 없다`() {
        val log = SyncLog.create(
            accountId = UUID.randomUUID(), userId = UUID.randomUUID(),
            trigger = SyncTrigger.SCHEDULED, status = SyncLogStatus.SUCCESS,
            syncedCount = 7, errorMessage = null,
        )
        assertEquals(7, log.syncedCount)
        assertNull(log.errorMessage)
    }
}
