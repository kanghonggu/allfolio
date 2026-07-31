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
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class WfStepCommand(
    val stepCd: String,
    val stepSeq: Int,
    val stepName: String,
    val stepGroup: String?,
    val termGb: WfTermGb,
    val cutoffStart: String?,
    val cutoffEnd: String?,
    val essentialStepCd: String?,
    val url: String?,
    val holidayExceptYn: Boolean,
    val useYn: Boolean,
)

data class WfSubStepCommand(
    val stepCd: String,
    val subStepCd: String,
    val subStepSeq: Int,
    val subStepName: String,
    val autoManual: String,
    val closingCheckYn: Boolean,
    val dateTerm: Int?,
    val dateGb: String?,
    val actionType: WfActionType,
    val actionRef: String?,
    val timeoutSec: Int,
    val pollIntervalSec: Int,
    val useYn: Boolean,
)

/**
 * 워크플로우 정의 관리 (P3 #30, FR-STEP-009·FR-AUTH-004).
 * 단계·하위단계 업서트/소프트 삭제 — 모든 변경은 wf_def_hist에 JSON 스냅샷(C/U/D)으로 기록.
 * 정의는 데이터, 실행 액션(actionRef)은 코드 빈 — ref 오타는 실행 시 "액션 빈 없음" ERROR로 표면화.
 */
@Service
class WfDefinitionService(
    private val stepRepo: WfStepJpaRepository,
    private val subStepRepo: WfSubStepJpaRepository,
    private val histRepo: WfDefHistJpaRepository,
) {
    fun listSteps(): List<WfStepEntity> = stepRepo.findAll().sortedBy { it.stepSeq }

    fun listSubSteps(stepCd: String): List<WfSubStepEntity> =
        subStepRepo.findByIdStepCd(stepCd).sortedBy { it.subStepSeq }

    @Transactional
    fun saveStep(cmd: WfStepCommand, adminId: UUID): WfStepEntity {
        require(cmd.stepCd.isNotBlank()) { "단계 코드는 필수입니다" }
        require(cmd.stepName.isNotBlank()) { "단계명은 필수입니다" }
        if (cmd.essentialStepCd != null) {
            require(cmd.essentialStepCd != cmd.stepCd) { "선행 단계는 자기 자신일 수 없습니다" }
            require(stepRepo.findById(cmd.essentialStepCd).isPresent) { "선행 단계가 존재하지 않습니다: ${cmd.essentialStepCd}" }
        }
        val existing = stepRepo.findById(cmd.stepCd).orElse(null)
        val entity = (existing ?: WfStepEntity(
            stepCd = cmd.stepCd, stepSeq = cmd.stepSeq, stepName = cmd.stepName,
            termGb = cmd.termGb,
        )).apply {
            stepSeq = cmd.stepSeq; stepName = cmd.stepName; stepGroup = cmd.stepGroup
            termGb = cmd.termGb; cutoffStart = cmd.cutoffStart; cutoffEnd = cmd.cutoffEnd
            essentialStepCd = cmd.essentialStepCd; url = cmd.url
            holidayExceptYn = cmd.holidayExceptYn; useYn = cmd.useYn
        }
        val saved = stepRepo.save(entity)
        recordHist("STEP", saved.stepCd, if (existing == null) "C" else "U", stepSnapshot(saved), adminId)
        return saved
    }

    @Transactional
    fun deleteStep(stepCd: String, adminId: UUID) {
        val step = stepRepo.findById(stepCd).orElseThrow { NoSuchElementException("단계 없음: $stepCd") }
        recordHist("STEP", stepCd, "D", stepSnapshot(step), adminId)
        step.useYn = false
        stepRepo.save(step)
    }

    @Transactional
    fun saveSubStep(cmd: WfSubStepCommand, adminId: UUID): WfSubStepEntity {
        require(cmd.subStepCd.isNotBlank()) { "하위단계 코드는 필수입니다" }
        require(cmd.subStepName.isNotBlank()) { "하위단계명은 필수입니다" }
        require(stepRepo.findById(cmd.stepCd).isPresent) { "소속 단계가 존재하지 않습니다: ${cmd.stepCd}" }
        require(cmd.autoManual in setOf("A", "M")) { "자동/수동 구분은 A 또는 M이어야 합니다" }
        if (cmd.actionType != WfActionType.MANUAL) {
            require(!cmd.actionRef.isNullOrBlank()) { "${cmd.actionType} 유형은 actionRef가 필수입니다" }
        }
        val id = WfSubStepId(cmd.stepCd, cmd.subStepCd)
        val existing = subStepRepo.findById(id).orElse(null)
        val entity = (existing ?: WfSubStepEntity(
            id = id, subStepSeq = cmd.subStepSeq, subStepName = cmd.subStepName,
            autoManual = cmd.autoManual, actionType = cmd.actionType,
        )).apply {
            subStepSeq = cmd.subStepSeq; subStepName = cmd.subStepName
            autoManual = cmd.autoManual; closingCheckYn = cmd.closingCheckYn
            dateTerm = cmd.dateTerm; dateGb = cmd.dateGb
            actionType = cmd.actionType; actionRef = cmd.actionRef?.takeIf { it.isNotBlank() }
            timeoutSec = cmd.timeoutSec; pollIntervalSec = cmd.pollIntervalSec; useYn = cmd.useYn
        }
        val saved = subStepRepo.save(entity)
        recordHist("SUB_STEP", "${cmd.stepCd}/${cmd.subStepCd}", if (existing == null) "C" else "U", subSnapshot(saved), adminId)
        return saved
    }

    @Transactional
    fun deleteSubStep(stepCd: String, subStepCd: String, adminId: UUID) {
        val sub = subStepRepo.findById(WfSubStepId(stepCd, subStepCd))
            .orElseThrow { NoSuchElementException("하위단계 없음: $stepCd/$subStepCd") }
        recordHist("SUB_STEP", "$stepCd/$subStepCd", "D", subSnapshot(sub), adminId)
        sub.useYn = false
        subStepRepo.save(sub)
    }

    fun history(): List<WfDefHistEntity> = histRepo.findAllByOrderByChangedAtDesc().take(100)

    private fun recordHist(type: String, key: String, crud: String, snapshot: String, adminId: UUID) {
        histRepo.save(
            WfDefHistEntity(entityType = type, entityKey = key, crud = crud, snapshot = snapshot, changedBy = adminId)
        )
    }

    private fun stepSnapshot(s: WfStepEntity): String = MAPPER.writeValueAsString(
        mapOf(
            "stepCd" to s.stepCd, "stepSeq" to s.stepSeq, "stepName" to s.stepName,
            "stepGroup" to s.stepGroup, "termGb" to s.termGb.name,
            "cutoffStart" to s.cutoffStart, "cutoffEnd" to s.cutoffEnd,
            "essentialStepCd" to s.essentialStepCd, "url" to s.url,
            "holidayExceptYn" to s.holidayExceptYn, "useYn" to s.useYn,
        )
    )

    private fun subSnapshot(s: WfSubStepEntity): String = MAPPER.writeValueAsString(
        mapOf(
            "stepCd" to s.id.stepCd, "subStepCd" to s.id.subStepCd,
            "subStepSeq" to s.subStepSeq, "subStepName" to s.subStepName,
            "autoManual" to s.autoManual, "closingCheckYn" to s.closingCheckYn,
            "dateTerm" to s.dateTerm, "dateGb" to s.dateGb,
            "actionType" to s.actionType.name, "actionRef" to s.actionRef,
            "timeoutSec" to s.timeoutSec, "pollIntervalSec" to s.pollIntervalSec, "useYn" to s.useYn,
        )
    )

    companion object {
        private val MAPPER = jacksonObjectMapper()
    }
}
