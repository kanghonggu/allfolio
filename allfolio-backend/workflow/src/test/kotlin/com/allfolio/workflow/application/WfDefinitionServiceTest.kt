package com.allfolio.workflow.application

import com.allfolio.workflow.domain.WfActionType
import com.allfolio.workflow.domain.WfTermGb
import com.allfolio.workflow.infrastructure.entity.WfDefHistEntity
import com.allfolio.workflow.infrastructure.entity.WfStepEntity
import com.allfolio.workflow.infrastructure.entity.WfSubStepEntity
import com.allfolio.workflow.infrastructure.entity.WfSubStepId
import com.allfolio.workflow.infrastructure.jpa.WfDefHistJpaRepository
import com.allfolio.workflow.infrastructure.jpa.WfStepJpaRepository
import com.allfolio.workflow.infrastructure.jpa.WfSubStepJpaRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.util.Optional
import java.util.UUID

class WfDefinitionServiceTest {

    private val adminId = UUID.randomUUID()

    private class InMemoryStepRepo : WfStepJpaRepository by mock(WfStepJpaRepository::class.java) {
        val rows = mutableListOf<WfStepEntity>()
        override fun <S : WfStepEntity> save(entity: S): S {
            rows.removeAll { it.stepCd == entity.stepCd }; rows += entity; return entity
        }
        override fun findById(id: String): Optional<WfStepEntity> =
            Optional.ofNullable(rows.find { it.stepCd == id })
        override fun findAll(): MutableList<WfStepEntity> = rows.toMutableList()
    }

    private class InMemorySubRepo : WfSubStepJpaRepository by mock(WfSubStepJpaRepository::class.java) {
        val rows = mutableListOf<WfSubStepEntity>()
        override fun <S : WfSubStepEntity> save(entity: S): S {
            rows.removeAll { it.id == entity.id }; rows += entity; return entity
        }
        override fun findById(id: WfSubStepId): Optional<WfSubStepEntity> =
            Optional.ofNullable(rows.find { it.id == id })
        override fun findByIdStepCd(stepCd: String): List<WfSubStepEntity> =
            rows.filter { it.id.stepCd == stepCd }
    }

    private class InMemoryHistRepo : WfDefHistJpaRepository by mock(WfDefHistJpaRepository::class.java) {
        val rows = mutableListOf<WfDefHistEntity>()
        override fun <S : WfDefHistEntity> save(entity: S): S { rows += entity; return entity }
    }

    private fun stepCmd(cd: String = "S099", essential: String? = null) = WfStepCommand(
        stepCd = cd, stepSeq = 99, stepName = "테스트 단계", stepGroup = null,
        termGb = WfTermGb.D, cutoffStart = null, cutoffEnd = null,
        essentialStepCd = essential, url = null, holidayExceptYn = false, useYn = true,
    )

    private fun service(
        steps: InMemoryStepRepo = InMemoryStepRepo(),
        subs: InMemorySubRepo = InMemorySubRepo(),
        hist: InMemoryHistRepo = InMemoryHistRepo(),
    ) = Triple(WfDefinitionService(steps, subs, hist), steps, hist)

    @Test
    fun `단계 신규 저장은 C 이력, 수정은 U 이력을 JSON 스냅샷과 함께 남긴다`() {
        val (svc, steps, hist) = service()
        svc.saveStep(stepCmd(), adminId)
        assertEquals("C", hist.rows.single().crud)
        assertTrue(hist.rows.single().snapshot!!.contains("테스트 단계"))
        assertEquals(adminId, hist.rows.single().changedBy)

        svc.saveStep(stepCmd().copy(stepName = "수정된 단계"), adminId)
        assertEquals(listOf("C", "U"), hist.rows.map { it.crud })
        assertEquals("수정된 단계", steps.rows.single().stepName)
    }

    @Test
    fun `단계 삭제는 소프트(use_yn false) + D 이력(변경 전 스냅샷)`() {
        val (svc, steps, hist) = service()
        svc.saveStep(stepCmd(), adminId)
        svc.deleteStep("S099", adminId)
        assertFalse(steps.rows.single().useYn)
        val d = hist.rows.last()
        assertEquals("D", d.crud)
        assertTrue(d.snapshot!!.contains("테스트 단계"))
    }

    @Test
    fun `선행단계는 존재해야 하고 자기 자신일 수 없다`() {
        val (svc, _, _) = service()
        assertThrows(IllegalArgumentException::class.java) { svc.saveStep(stepCmd(essential = "NOPE"), adminId) }
        assertThrows(IllegalArgumentException::class.java) { svc.saveStep(stepCmd(essential = "S099"), adminId) }
    }

    @Test
    fun `하위단계 - 부모 단계 필수, CHAIN·POLL은 actionRef 필수, MANUAL은 불필요`() {
        val (svc, steps, hist) = service()
        svc.saveStep(stepCmd(), adminId)

        val manual = WfSubStepCommand(
            stepCd = "S099", subStepCd = "S099-1", subStepSeq = 1, subStepName = "수동확인",
            autoManual = "M", closingCheckYn = true, dateTerm = null, dateGb = null,
            actionType = WfActionType.MANUAL, actionRef = null, timeoutSec = 300, pollIntervalSec = 10, useYn = true,
        )
        svc.saveSubStep(manual, adminId)
        assertEquals("SUB_STEP", hist.rows.last().entityType)

        assertThrows(IllegalArgumentException::class.java) {
            svc.saveSubStep(manual.copy(subStepCd = "S099-2", actionType = WfActionType.CHAIN, actionRef = null), adminId)
        }
        assertThrows(IllegalArgumentException::class.java) {
            svc.saveSubStep(manual.copy(stepCd = "NOPE"), adminId)
        }
        assertEquals(1, steps.rows.size)
    }
}
