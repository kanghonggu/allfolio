package com.allfolio.api.recon

import com.allfolio.reconciliation.application.ReconKdService
import com.allfolio.reconciliation.application.RegisterKdCommand
import com.allfolio.reconciliation.domain.KdValueType
import com.allfolio.reconciliation.infrastructure.entity.ReconKdEntity
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
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Known Difference USER CRUD (P2 #16) — 본인 스코프, 수정=버저닝, 삭제=use_yn false. */
@RestController
@RequestMapping("/api/recon/kds")
class ReconKdController(private val kdService: ReconKdService) {

    @GetMapping
    fun list(@RequestHeader("X-User-Id") userId: UUID): List<KdResponse> =
        kdService.list(userId).map { it.toResponse() }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun register(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestBody req: RegisterKdRequest,
    ): KdResponse = kdService.register(
        userId,
        RegisterKdCommand(
            kdCode = req.kdCode, targetSymbol = req.targetSymbol, targetField = req.targetField,
            valueType = req.valueType, allowValue = req.allowValue, reason = req.reason,
            apldStrtDt = req.apldStrtDt,
        ),
    ).toResponse()

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deactivate(
        @RequestHeader("X-User-Id") userId: UUID,
        @PathVariable id: UUID,
    ) = kdService.deactivate(userId, id)

    private fun ReconKdEntity.toResponse() = KdResponse(
        id = id, kdCode = kdCode, targetSymbol = targetSymbol, targetField = targetField,
        valueType = valueType.name, allowValue = allowValue, reason = reason,
        apldStrtDt = apldStrtDt.toString(), apldEndDt = apldEndDt.toString(), useYn = useYn,
        createdAt = createdAt.toString(),
    )
}

data class RegisterKdRequest(
    val kdCode: String,
    val targetSymbol: String? = null,
    val targetField: String? = null,
    val valueType: KdValueType,
    val allowValue: BigDecimal,
    val reason: String,
    val apldStrtDt: LocalDate,
)

data class KdResponse(
    val id: UUID,
    val kdCode: String,
    val targetSymbol: String?,
    val targetField: String?,
    val valueType: String,
    val allowValue: BigDecimal,
    val reason: String,
    val apldStrtDt: String,
    val apldEndDt: String,
    val useYn: Boolean,
    val createdAt: String,
)
