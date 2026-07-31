package com.allfolio.workflow.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WfRollupTest {

    private fun s(vararg statuses: WfJobStatus?): WfStepRollup = WfRollup.rollup(statuses.toList())

    @Test
    fun `전부 미실행이면 STANDBY`() {
        assertEquals(WfStepRollup.STANDBY, s(null, null))
        assertEquals(WfStepRollup.STANDBY, s(WfJobStatus.PENDING, null))
    }

    @Test
    fun `전부 성공이면 FINISH`() {
        assertEquals(WfStepRollup.FINISH, s(WfJobStatus.SUCCESS, WfJobStatus.SUCCESS))
    }

    @Test
    fun `하나라도 오류면 ERROR - 성공·실행중 혼재보다 우선`() {
        assertEquals(WfStepRollup.ERROR, s(WfJobStatus.SUCCESS, WfJobStatus.ERROR, WfJobStatus.RUNNING))
    }

    @Test
    fun `오류 없이 하나라도 실행중이면 RUNNING`() {
        assertEquals(WfStepRollup.RUNNING, s(WfJobStatus.SUCCESS, WfJobStatus.RUNNING))
    }

    @Test
    fun `그 외 혼재는 PAUSED`() {
        assertEquals(WfStepRollup.PAUSED, s(WfJobStatus.SUCCESS, WfJobStatus.PENDING))
        assertEquals(WfStepRollup.PAUSED, s(WfJobStatus.SUCCESS, WfJobStatus.PAUSED))
    }

    @Test
    fun `마감판정 대상이 없으면 FINISH 취급`() {
        assertEquals(WfStepRollup.FINISH, s())
    }
}
