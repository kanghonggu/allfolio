package com.allfolio.workflow.application

import com.allfolio.workflow.domain.WfActionType
import com.allfolio.workflow.domain.WfJobStatus
import com.allfolio.workflow.domain.WfTermGb
import com.allfolio.workflow.infrastructure.entity.WfHolidayEntity
import com.allfolio.workflow.infrastructure.entity.WfJobLogEntity
import com.allfolio.workflow.infrastructure.entity.WfStepEntity
import com.allfolio.workflow.infrastructure.entity.WfSubStepEntity
import com.allfolio.workflow.infrastructure.entity.WfSubStepId
import com.allfolio.workflow.infrastructure.jpa.WfHolidayJpaRepository
import com.allfolio.workflow.infrastructure.jpa.WfJobLogJpaRepository
import com.allfolio.workflow.infrastructure.jpa.WfStepJpaRepository
import com.allfolio.workflow.infrastructure.jpa.WfSubStepJpaRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

class WfStepExecutorTest {

    private val ymd = LocalDate.of(2026, 8, 3)  // 월요일

    private class InMemoryLogRepo : WfJobLogJpaRepository by mock(WfJobLogJpaRepository::class.java) {
        val rows = mutableListOf<WfJobLogEntity>()
        override fun <S : WfJobLogEntity> save(entity: S): S {
            rows.removeAll { it.id == entity.id }; rows += entity; return entity
        }
        override fun findByYmdAndStepCdAndSubStepCdOrderByExecSeqDesc(
            ymd: LocalDate, stepCd: String, subStepCd: String,
        ): List<WfJobLogEntity> =
            rows.filter { it.ymd == ymd && it.stepCd == stepCd && it.subStepCd == subStepCd }
                .sortedByDescending { it.execSeq }
    }

    private class FixedStepRepo(private val steps: List<WfStepEntity>) :
        WfStepJpaRepository by mock(WfStepJpaRepository::class.java) {
        override fun findByUseYnTrueOrderByStepSeq(): List<WfStepEntity> = steps.sortedBy { it.stepSeq }
        override fun findById(id: String): Optional<WfStepEntity> =
            Optional.ofNullable(steps.find { it.stepCd == id })
    }

    private class FixedSubRepo(private val subs: List<WfSubStepEntity>) :
        WfSubStepJpaRepository by mock(WfSubStepJpaRepository::class.java) {
        override fun findByIdStepCdAndUseYnTrueOrderBySubStepSeq(stepCd: String): List<WfSubStepEntity> =
            subs.filter { it.id.stepCd == stepCd && it.useYn }.sortedBy { it.subStepSeq }
        override fun findByIdStepCd(stepCd: String): List<WfSubStepEntity> =
            subs.filter { it.id.stepCd == stepCd }
    }

    private class EmptyHolidayRepo : WfHolidayJpaRepository by mock(WfHolidayJpaRepository::class.java) {
        override fun findByIdDayBetween(from: LocalDate, to: LocalDate): List<WfHolidayEntity> = emptyList()
    }

    private class FakeLock(private val acquirable: Boolean = true) : WfLockPort {
        var released = false
        override fun tryAcquire(ymd: LocalDate): String? = if (acquirable) "t" else null
        override fun release(ymd: LocalDate, token: String) { released = true }
    }

    private class FakeAction(override val ref: String, private val fail: Boolean = false) : WfAction {
        var calls = 0
        override fun execute(ctx: WfContext): WfActionResult {
            calls++
            if (fail) throw IllegalStateException("action boom")
            return WfActionResult("ok")
        }
    }

    private fun step(cd: String, seq: Int, essential: String? = null) = WfStepEntity(
        stepCd = cd, stepSeq = seq, stepName = cd, termGb = WfTermGb.D,
        essentialStepCd = essential, holidayExceptYn = false,
    )

    private fun sub(stepCd: String, cd: String, seq: Int = 1, type: WfActionType = WfActionType.CHAIN, ref: String? = "$stepCd-ACT") =
        WfSubStepEntity(
            id = WfSubStepId(stepCd, cd), subStepSeq = seq, subStepName = cd,
            autoManual = if (type == WfActionType.MANUAL) "M" else "A",
            actionType = type, actionRef = ref,
        )

    private fun executor(
        steps: List<WfStepEntity>, subs: List<WfSubStepEntity>, actions: List<WfAction>,
        logs: InMemoryLogRepo = InMemoryLogRepo(), lock: FakeLock = FakeLock(),
    ) = WfStepExecutor(actions, FixedStepRepo(steps), FixedSubRepo(subs), logs, EmptyHolidayRepo(), lock)

    @Test
    fun `선행단계 미완료면 게이트 SKIP - 로그를 만들지 않는다`() {
        val logs = InMemoryLogRepo()
        val a1 = FakeAction("S1-ACT", fail = true)
        val a2 = FakeAction("S2-ACT")
        val summary = executor(
            steps = listOf(step("S1", 10), step("S2", 20, essential = "S1")),
            subs = listOf(sub("S1", "S1-1"), sub("S2", "S2-1")),
            actions = listOf(a1, a2), logs = logs,
        ).runDaily(ymd)

        assertEquals(listOf("S1"), summary.executedSteps)
        assertEquals(listOf("S2"), summary.gateSkippedSteps)
        assertEquals(0, a2.calls)
        assertTrue(logs.rows.none { it.stepCd == "S2" })
    }

    @Test
    fun `선행 성공 시 게이트 통과 - 체인이 이어진다`() {
        val logs = InMemoryLogRepo()
        val exec = executor(
            steps = listOf(step("S1", 10), step("S2", 20, essential = "S1")),
            subs = listOf(sub("S1", "S1-1"), sub("S2", "S2-1")),
            actions = listOf(FakeAction("S1-ACT"), FakeAction("S2-ACT")), logs = logs,
        )
        val summary = exec.runDaily(ymd)
        assertEquals(listOf("S1", "S2"), summary.executedSteps)
        assertTrue(logs.rows.all { it.status == WfJobStatus.SUCCESS })
    }

    @Test
    fun `하위단계 오류는 같은 단계 후속을 중단하고 ERROR 로그를 남긴다`() {
        val logs = InMemoryLogRepo()
        val second = FakeAction("S1-ACT2")
        executor(
            steps = listOf(step("S1", 10)),
            subs = listOf(sub("S1", "S1-1", 1, ref = "S1-ACT"), sub("S1", "S1-2", 2, ref = "S1-ACT2")),
            actions = listOf(FakeAction("S1-ACT", fail = true), second), logs = logs,
        ).runDaily(ymd)

        assertEquals(WfJobStatus.ERROR, logs.rows.single { it.subStepCd == "S1-1" }.status)
        assertEquals(0, second.calls)
    }

    @Test
    fun `MANUAL은 PENDING 로그만 1회 만들고 실행하지 않는다 - 재실행에도 중복 생성 없음`() {
        val logs = InMemoryLogRepo()
        val exec = executor(
            steps = listOf(step("S5", 50)),
            subs = listOf(sub("S5", "S5-1", type = WfActionType.MANUAL, ref = null)),
            actions = emptyList(), logs = logs,
        )
        exec.runDaily(ymd)
        exec.runDaily(ymd)
        val pending = logs.rows.filter { it.stepCd == "S5" }
        assertEquals(1, pending.size)
        assertEquals(WfJobStatus.PENDING, pending[0].status)
    }

    @Test
    fun `이미 성공한 하위단계는 runDaily에서 재실행하지 않는다`() {
        val logs = InMemoryLogRepo()
        val action = FakeAction("S1-ACT")
        val exec = executor(listOf(step("S1", 10)), listOf(sub("S1", "S1-1")), listOf(action), logs)
        exec.runDaily(ymd)
        exec.runDaily(ymd)
        assertEquals(1, action.calls)
        assertEquals(1, logs.rows.size)
    }

    @Test
    fun `runSubStep 재실행은 차수를 올린다`() {
        val logs = InMemoryLogRepo()
        val action = FakeAction("S1-ACT")
        val exec = executor(listOf(step("S1", 10)), listOf(sub("S1", "S1-1")), listOf(action), logs)
        exec.runDaily(ymd)
        exec.runSubStep(ymd, "S1", "S1-1", executor = "admin")
        val seqs = logs.rows.filter { it.subStepCd == "S1-1" }.map { it.execSeq }.sorted()
        assertEquals(listOf(1, 2), seqs)
        assertEquals(2, action.calls)
    }

    @Test
    fun `manualComplete - 사유 없으면 거부, 있으면 새 차수 로그`() {
        val logs = InMemoryLogRepo()
        val exec = executor(
            listOf(step("S5", 50)),
            listOf(sub("S5", "S5-1", type = WfActionType.MANUAL, ref = null)),
            emptyList(), logs,
        )
        exec.runDaily(ymd)  // PENDING 생성
        assertThrows(IllegalArgumentException::class.java) {
            exec.manualComplete(ymd, "S5", "S5-1", success = true, remark = " ", executor = "admin")
        }
        exec.manualComplete(ymd, "S5", "S5-1", success = true, remark = "대시보드 확인 완료", executor = "admin")
        val latest = logs.rows.filter { it.subStepCd == "S5-1" }.maxBy { it.execSeq }
        assertEquals(WfJobStatus.SUCCESS, latest.status)
        assertEquals(2, latest.execSeq)
        assertEquals("M", latest.autoManual)
    }

    @Test
    fun `락 획득 실패면 ClosingInProgressException`() {
        val exec = executor(emptyList(), emptyList(), emptyList(), lock = FakeLock(acquirable = false))
        assertThrows(ClosingInProgressException::class.java) { exec.runDaily(ymd) }
    }
}
