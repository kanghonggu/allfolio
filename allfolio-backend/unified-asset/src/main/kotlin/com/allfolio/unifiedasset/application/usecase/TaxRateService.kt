package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.TaxRateRepository
import com.allfolio.unifiedasset.domain.tax.IncomeType
import com.allfolio.unifiedasset.domain.tax.TaxRate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

data class RegisterTaxRateCommand(
    val country: String,
    val incomeType: IncomeType,
    val rate: BigDecimal,
    val effectiveStart: LocalDate,
)

@Service
class TaxRateService(
    private val repository: TaxRateRepository,
) {
    fun list(): List<TaxRate> = repository.findAll()

    fun findEffectiveRate(country: String, incomeType: IncomeType, date: LocalDate): TaxRate? =
        repository.findEffective(country, incomeType, date)

    @Transactional
    fun register(cmd: RegisterTaxRateCommand, adminId: UUID): TaxRate {
        validate(cmd)
        val now = LocalDateTime.now()
        val open = repository.findOpen(cmd.country, cmd.incomeType)
        if (open != null) {
            if (!cmd.effectiveStart.isAfter(open.effectiveStart)) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "새 적용 시작일은 기존 적용 시작일 이후여야 합니다.",
                )
            }
            repository.save(open.copy(effectiveEnd = cmd.effectiveStart.minusDays(1), updatedAt = now))
        }
        return repository.save(
            TaxRate(
                id = UUID.randomUUID(),
                country = cmd.country,
                incomeType = cmd.incomeType,
                rate = cmd.rate,
                effectiveStart = cmd.effectiveStart,
                effectiveEnd = null,
                updatedBy = adminId,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun validate(cmd: RegisterTaxRateCommand) {
        if (cmd.country.length != 2 || cmd.country.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "국가는 2자리 코드여야 합니다.")
        }
        if (cmd.rate < BigDecimal.ZERO || cmd.rate > BigDecimal(50)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "세율은 0~50% 범위여야 합니다.")
        }
    }
}
