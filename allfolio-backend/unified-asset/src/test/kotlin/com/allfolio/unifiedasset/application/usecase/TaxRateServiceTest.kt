package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.TaxRateRepository
import com.allfolio.unifiedasset.domain.tax.IncomeType
import com.allfolio.unifiedasset.domain.tax.TaxRate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class TaxRateServiceTest {

    private class FakeRepo : TaxRateRepository {
        val store = mutableListOf<TaxRate>()
        override fun findAll() = store.toList()
        override fun findOpen(country: String, incomeType: IncomeType) =
            store.firstOrNull { it.country == country && it.incomeType == incomeType && it.effectiveEnd == null }
        override fun findEffective(country: String, incomeType: IncomeType, date: LocalDate) =
            store.firstOrNull {
                val end = it.effectiveEnd
                it.country == country && it.incomeType == incomeType &&
                    !it.effectiveStart.isAfter(date) && (end == null || !end.isBefore(date))
            }
        override fun save(taxRate: TaxRate): TaxRate {
            store.removeIf { it.id == taxRate.id }
            store.add(taxRate)
            return taxRate
        }
    }

    private val admin = UUID.randomUUID()

    private fun service(repo: TaxRateRepository = FakeRepo()) = TaxRateService(repo) to repo

    private fun cmd(country: String = "US", type: IncomeType = IncomeType.DIVIDEND,
                    rate: String = "15", start: LocalDate = LocalDate.of(2024, 1, 1)) =
        RegisterTaxRateCommand(country, type, BigDecimal(rate), start)

    @Test
    fun `open이 없으면 신규 open 1건을 생성한다`() {
        val (svc, repo) = service()
        val r = svc.register(cmd(), admin)
        assertThat(r.effectiveEnd).isNull()
        assertThat(r.updatedBy).isEqualTo(admin)
        assertThat(repo.findAll()).hasSize(1)
    }

    @Test
    fun `기존 open이 있으면 마감하고 신규 open을 만든다 (버저닝)`() {
        val (svc, repo) = service()
        svc.register(cmd(start = LocalDate.of(2024, 1, 1)), admin)
        svc.register(cmd(rate = "16", start = LocalDate.of(2025, 1, 1)), admin)
        val all = repo.findAll().sortedBy { it.effectiveStart }
        assertThat(all).hasSize(2)
        assertThat(all[0].effectiveEnd).isEqualTo(LocalDate.of(2024, 12, 31)) // 신규start-1
        assertThat(all[1].effectiveEnd).isNull()
    }

    @Test
    fun `findEffectiveRate는 날짜에 맞는 버전을 반환한다`() {
        val (svc, repo) = service()
        svc.register(cmd(rate = "15", start = LocalDate.of(2024, 1, 1)), admin)
        svc.register(cmd(rate = "16", start = LocalDate.of(2025, 1, 1)), admin)
        assertThat(repo.findEffective("US", IncomeType.DIVIDEND, LocalDate.of(2024, 6, 1))!!.rate).isEqualByComparingTo("15")
        assertThat(repo.findEffective("US", IncomeType.DIVIDEND, LocalDate.of(2024, 12, 31))!!.rate).isEqualByComparingTo("15")
        assertThat(repo.findEffective("US", IncomeType.DIVIDEND, LocalDate.of(2025, 1, 1))!!.rate).isEqualByComparingTo("16")
    }

    @Test
    fun `신규 시작일이 기존 open 시작일 이후가 아니면 거부한다`() {
        val (svc, _) = service()
        svc.register(cmd(start = LocalDate.of(2025, 1, 1)), admin)
        assertThatThrownBy { svc.register(cmd(start = LocalDate.of(2025, 1, 1)), admin) }
            .isInstanceOf(ResponseStatusException::class.java)
    }

    @Test
    fun `세율 범위 밖이면 거부한다`() {
        val (svc, _) = service()
        assertThatThrownBy { svc.register(cmd(rate = "51"), admin) }.isInstanceOf(ResponseStatusException::class.java)
        assertThatThrownBy { svc.register(cmd(rate = "-1"), admin) }.isInstanceOf(ResponseStatusException::class.java)
    }

    @Test
    fun `국가 형식이 틀리면 거부한다`() {
        val (svc, _) = service()
        assertThatThrownBy { svc.register(cmd(country = "USA"), admin) }.isInstanceOf(ResponseStatusException::class.java)
        assertThatThrownBy { svc.register(cmd(country = ""), admin) }.isInstanceOf(ResponseStatusException::class.java)
    }
}
