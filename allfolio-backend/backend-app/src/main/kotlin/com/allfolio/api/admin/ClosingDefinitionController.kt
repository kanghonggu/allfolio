package com.allfolio.api.admin

import com.allfolio.workflow.application.WfDefinitionService
import com.allfolio.workflow.application.WfStepCommand
import com.allfolio.workflow.application.WfSubStepCommand
import com.allfolio.workflow.domain.WfActionType
import com.allfolio.workflow.domain.WfTermGb
import com.allfolio.workflow.infrastructure.entity.WfStepEntity
import com.allfolio.workflow.infrastructure.entity.WfSubStepEntity
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** 워크플로우 정의 관리 API (P3 #30, SCR-DASH-03) — ADMIN 게이트. */
@RestController
@RequestMapping("/api/admin/closing/definitions")
class ClosingDefinitionController(private val definitionService: WfDefinitionService) {

    @GetMapping
    fun list(): List<WfStepDefResponse> =
        definitionService.listSteps().map { step ->
            step.toResponse(definitionService.listSubSteps(step.stepCd).map { it.toResponse() })
        }

    @PostMapping("/steps")
    @ResponseStatus(HttpStatus.CREATED)
    fun saveStep(
        @RequestHeader("X-User-Id") adminId: UUID,
        @RequestBody req: WfStepRequest,
    ): WfStepDefResponse = definitionService.saveStep(
        WfStepCommand(
            stepCd = req.stepCd.trim(), stepSeq = req.stepSeq, stepName = req.stepName.trim(),
            stepGroup = req.stepGroup?.trim()?.takeIf { it.isNotEmpty() },
            termGb = req.termGb, cutoffStart = req.cutoffStart, cutoffEnd = req.cutoffEnd,
            essentialStepCd = req.essentialStepCd?.trim()?.takeIf { it.isNotEmpty() },
            url = req.url?.trim()?.takeIf { it.isNotEmpty() },
            holidayExceptYn = req.holidayExceptYn, useYn = req.useYn,
        ),
        adminId,
    ).toResponse(emptyList())

    @DeleteMapping("/steps/{stepCd}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteStep(
        @RequestHeader("X-User-Id") adminId: UUID,
        @PathVariable stepCd: String,
    ) = definitionService.deleteStep(stepCd, adminId)

    @PostMapping("/substeps")
    @ResponseStatus(HttpStatus.CREATED)
    fun saveSubStep(
        @RequestHeader("X-User-Id") adminId: UUID,
        @RequestBody req: WfSubStepRequest,
    ): WfSubStepDefResponse = definitionService.saveSubStep(
        WfSubStepCommand(
            stepCd = req.stepCd.trim(), subStepCd = req.subStepCd.trim(), subStepSeq = req.subStepSeq,
            subStepName = req.subStepName.trim(), autoManual = req.autoManual,
            closingCheckYn = req.closingCheckYn, dateTerm = req.dateTerm, dateGb = req.dateGb,
            actionType = req.actionType, actionRef = req.actionRef,
            timeoutSec = req.timeoutSec, pollIntervalSec = req.pollIntervalSec, useYn = req.useYn,
        ),
        adminId,
    ).toResponse()

    @DeleteMapping("/substeps/{stepCd}/{subStepCd}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteSubStep(
        @RequestHeader("X-User-Id") adminId: UUID,
        @PathVariable stepCd: String,
        @PathVariable subStepCd: String,
    ) = definitionService.deleteSubStep(stepCd, subStepCd, adminId)

    @GetMapping("/history")
    fun history(): List<WfDefHistResponse> = definitionService.history().map {
        WfDefHistResponse(
            id = it.id, entityType = it.entityType, entityKey = it.entityKey, crud = it.crud,
            snapshot = it.snapshot, changedBy = it.changedBy?.toString(), changedAt = it.changedAt.toString(),
        )
    }

    private fun WfStepEntity.toResponse(subSteps: List<WfSubStepDefResponse>) = WfStepDefResponse(
        stepCd = stepCd, stepSeq = stepSeq, stepName = stepName, stepGroup = stepGroup,
        termGb = termGb.name, cutoffStart = cutoffStart, cutoffEnd = cutoffEnd,
        essentialStepCd = essentialStepCd, url = url,
        holidayExceptYn = holidayExceptYn, useYn = useYn, subSteps = subSteps,
    )

    private fun WfSubStepEntity.toResponse() = WfSubStepDefResponse(
        stepCd = id.stepCd, subStepCd = id.subStepCd, subStepSeq = subStepSeq, subStepName = subStepName,
        autoManual = autoManual, closingCheckYn = closingCheckYn, dateTerm = dateTerm, dateGb = dateGb,
        actionType = actionType.name, actionRef = actionRef,
        timeoutSec = timeoutSec, pollIntervalSec = pollIntervalSec, useYn = useYn,
    )
}

data class WfStepRequest(
    val stepCd: String,
    val stepSeq: Int,
    val stepName: String,
    val stepGroup: String? = null,
    val termGb: WfTermGb,
    val cutoffStart: String? = null,
    val cutoffEnd: String? = null,
    val essentialStepCd: String? = null,
    val url: String? = null,
    val holidayExceptYn: Boolean = true,
    val useYn: Boolean = true,
)

data class WfSubStepRequest(
    val stepCd: String,
    val subStepCd: String,
    val subStepSeq: Int,
    val subStepName: String,
    val autoManual: String,
    val closingCheckYn: Boolean = true,
    val dateTerm: Int? = null,
    val dateGb: String? = null,
    val actionType: WfActionType,
    val actionRef: String? = null,
    val timeoutSec: Int = 300,
    val pollIntervalSec: Int = 10,
    val useYn: Boolean = true,
)

data class WfStepDefResponse(
    val stepCd: String,
    val stepSeq: Int,
    val stepName: String,
    val stepGroup: String?,
    val termGb: String,
    val cutoffStart: String?,
    val cutoffEnd: String?,
    val essentialStepCd: String?,
    val url: String?,
    val holidayExceptYn: Boolean,
    val useYn: Boolean,
    val subSteps: List<WfSubStepDefResponse>,
)

data class WfSubStepDefResponse(
    val stepCd: String,
    val subStepCd: String,
    val subStepSeq: Int,
    val subStepName: String,
    val autoManual: String,
    val closingCheckYn: Boolean,
    val dateTerm: Int?,
    val dateGb: String?,
    val actionType: String,
    val actionRef: String?,
    val timeoutSec: Int,
    val pollIntervalSec: Int,
    val useYn: Boolean,
)

data class WfDefHistResponse(
    val id: UUID,
    val entityType: String,
    val entityKey: String,
    val crud: String,
    val snapshot: String?,
    val changedBy: String?,
    val changedAt: String,
)
