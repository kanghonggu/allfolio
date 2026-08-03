package com.allfolio.api.tax

import com.allfolio.unifiedasset.application.usecase.TaxRateService
import com.allfolio.unifiedasset.domain.tax.IncomeType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 세율 마스터(tax_rates)의 현행 세율을 조회하는 사용자용 엔드포인트 (QA P3).
 * 세금 계산기가 하드코딩(15.4%) 대신 이 값을 사용해 세율 마스터와 실제 연동된다.
 */
@RestController
@RequestMapping("/api/tax-rates")
class TaxRateQueryController(
    private val taxRateService: TaxRateService,
) {
    @GetMapping("/effective")
    fun effective(@RequestParam(defaultValue = "KR") country: String): EffectiveTaxRatesResponse {
        val today = LocalDate.now()
        val normalized = country.trim().uppercase()
        val rates = IncomeType.entries
            .mapNotNull { type ->
                taxRateService.findEffectiveRate(normalized, type, today)?.let { type.name to it.rate }
            }
            .toMap()
        return EffectiveTaxRatesResponse(country = normalized, asOf = today, rates = rates)
    }
}

data class EffectiveTaxRatesResponse(
    val country: String,
    val asOf: LocalDate,
    /** 유형(DIVIDEND/INTEREST/DISTRIBUTION) → 현행 세율(percent). 마스터에 없으면 키 없음 */
    val rates: Map<String, BigDecimal>,
)
