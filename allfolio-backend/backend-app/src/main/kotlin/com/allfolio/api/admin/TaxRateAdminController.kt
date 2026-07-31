package com.allfolio.api.admin

import com.allfolio.unifiedasset.application.usecase.RegisterTaxRateCommand
import com.allfolio.unifiedasset.application.usecase.TaxRateService
import com.allfolio.unifiedasset.domain.tax.IncomeType
import com.allfolio.unifiedasset.domain.tax.TaxRate
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@RestController
@RequestMapping("/api/admin/tax-rates")
class TaxRateAdminController(
    private val taxRateService: TaxRateService,
) {
    /** GET — 전체 세율(현행+이력). FE가 국가×유형으로 그룹핑해 이력 타임라인 렌더. */
    @GetMapping
    fun list(): ResponseEntity<List<TaxRateResponse>> =
        ResponseEntity.ok(taxRateService.list().map { it.toResponse() })

    /** POST — 등록/버저닝 (ADMIN). */
    @PostMapping
    fun register(
        @RequestHeader("X-User-Id") adminId: UUID,
        @RequestBody req: RegisterTaxRateRequest,
    ): ResponseEntity<TaxRateResponse> {
        val saved = taxRateService.register(
            RegisterTaxRateCommand(req.country, req.incomeType, req.rate, req.effectiveStart), adminId,
        )
        return ResponseEntity.ok(saved.toResponse())
    }

    private fun TaxRate.toResponse() = TaxRateResponse(
        id, country, incomeType, rate, effectiveStart, effectiveEnd, updatedBy, updatedAt,
    )
}

data class RegisterTaxRateRequest(
    val country: String,
    val incomeType: IncomeType,
    val rate: BigDecimal,
    val effectiveStart: LocalDate,
)

data class TaxRateResponse(
    val id: UUID,
    val country: String,
    val incomeType: IncomeType,
    val rate: BigDecimal,
    val effectiveStart: LocalDate,
    val effectiveEnd: LocalDate?,
    val updatedBy: UUID?,
    val updatedAt: LocalDateTime,
)
