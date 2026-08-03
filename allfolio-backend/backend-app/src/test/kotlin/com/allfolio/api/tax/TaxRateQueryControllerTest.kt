package com.allfolio.api.tax

import com.allfolio.config.GlobalExceptionHandler
import com.allfolio.unifiedasset.application.port.TaxRateRepository
import com.allfolio.unifiedasset.application.usecase.TaxRateService
import com.allfolio.unifiedasset.domain.tax.IncomeType
import com.allfolio.unifiedasset.domain.tax.TaxRate
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

// QA P3 — 세금계산기가 하드코딩 대신 세율 마스터를 읽도록 사용자용 조회 엔드포인트
class TaxRateQueryControllerTest {

    private fun rate(income: IncomeType, r: String) = TaxRate(
        id = UUID.randomUUID(), country = "KR", incomeType = income, rate = BigDecimal(r),
        effectiveStart = LocalDate.of(2000, 1, 1), effectiveEnd = null,
        updatedBy = null, createdAt = LocalDateTime.now(), updatedAt = LocalDateTime.now(),
    )

    private fun mvc(effective: Map<IncomeType, TaxRate>) = MockMvcBuilders
        .standaloneSetup(TaxRateQueryController(TaxRateService(object : TaxRateRepository {
            override fun findAll() = emptyList<TaxRate>()
            override fun findOpen(country: String, incomeType: IncomeType): TaxRate? = null
            override fun findEffective(country: String, incomeType: IncomeType, date: LocalDate) = effective[incomeType]
            override fun save(taxRate: TaxRate) = taxRate
        })))
        .setControllerAdvice(GlobalExceptionHandler())
        .build()

    @Test
    fun `현행 세율 조회는 국가·유형별 현행 rate를 반환한다`() {
        mvc(mapOf(
            IncomeType.DIVIDEND to rate(IncomeType.DIVIDEND, "15.4"),
            IncomeType.INTEREST to rate(IncomeType.INTEREST, "15.4"),
        )).get("/api/tax-rates/effective") { param("country", "KR") }
            .andExpect {
                status { isOk() }
                jsonPath("$.country") { value("KR") }
                jsonPath("$.rates.DIVIDEND") { value(15.4) }
                jsonPath("$.rates.INTEREST") { value(15.4) }
            }
    }

    @Test
    fun `마스터에 없는 유형은 응답에서 제외한다`() {
        mvc(emptyMap()).get("/api/tax-rates/effective") { param("country", "KR") }
            .andExpect {
                status { isOk() }
                jsonPath("$.rates.DIVIDEND") { doesNotExist() }
            }
    }
}
