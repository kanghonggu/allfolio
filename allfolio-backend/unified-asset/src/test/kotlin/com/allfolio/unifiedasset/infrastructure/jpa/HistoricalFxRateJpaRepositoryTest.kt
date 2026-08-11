package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.HistoricalFxRateEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.test.context.ContextConfiguration
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 체결일 환율 조회는 "그 날짜 이하의 가장 최근 고시" 한 건이다.
 * 이 규칙 하나로 주말·공휴일이 직전 영업일로 자동으로 이어지고,
 * 백필 범위 이전 날짜는 행이 없어 miss로 떨어진다.
 */
@DataJpaTest
@ContextConfiguration(classes = [HistoricalFxRateJpaRepositoryTest.TestConfig::class])
class HistoricalFxRateJpaRepositoryTest {

    @Autowired
    private lateinit var repository: HistoricalFxRateJpaRepository

    @Test
    fun `정확히 그 날짜의 고시가 있으면 그것을 준다`() {
        save(LocalDate.of(2025, 8, 8), "1385.500000")
        save(LocalDate.of(2025, 8, 11), "1390.200000")

        val found = repository.findTopByCurrencyAndBaseDateLessThanEqualOrderByBaseDateDesc(
            "USD", LocalDate.of(2025, 8, 11),
        )

        assertThat(found?.baseDate).isEqualTo(LocalDate.of(2025, 8, 11))
        assertThat(found?.rateKrw).isEqualByComparingTo("1390.2")
    }

    @Test
    fun `주말은 직전 영업일 고시로 잇는다`() {
        save(LocalDate.of(2025, 8, 8), "1385.500000") // 금요일

        // 2025-08-09는 토요일 — 고시가 없다
        val found = repository.findTopByCurrencyAndBaseDateLessThanEqualOrderByBaseDateDesc(
            "USD", LocalDate.of(2025, 8, 9),
        )

        assertThat(found?.baseDate).isEqualTo(LocalDate.of(2025, 8, 8))
    }

    @Test
    fun `백필 범위보다 이른 날짜는 찾지 못한다`() {
        save(LocalDate.of(2025, 8, 8), "1385.500000")

        val found = repository.findTopByCurrencyAndBaseDateLessThanEqualOrderByBaseDateDesc(
            "USD", LocalDate.of(2020, 1, 1),
        )

        assertThat(found).isNull()
    }

    @Test
    fun `다른 통화의 고시는 섞이지 않는다`() {
        save(LocalDate.of(2025, 8, 11), "1390.200000", currency = "JPY")

        val found = repository.findTopByCurrencyAndBaseDateLessThanEqualOrderByBaseDateDesc(
            "USD", LocalDate.of(2025, 8, 11),
        )

        assertThat(found).isNull()
    }

    @Test
    fun `범위 조회는 경계를 포함한다`() {
        save(LocalDate.of(2025, 8, 7), "1380.000000")
        save(LocalDate.of(2025, 8, 8), "1385.500000")
        save(LocalDate.of(2025, 8, 11), "1390.200000")

        val rows = repository.findAllByCurrencyAndBaseDateBetween(
            "USD", LocalDate.of(2025, 8, 7), LocalDate.of(2025, 8, 8),
        )

        assertThat(rows.map { it.baseDate })
            .containsExactlyInAnyOrder(LocalDate.of(2025, 8, 7), LocalDate.of(2025, 8, 8))
    }

    private fun save(date: LocalDate, rate: String, currency: String = "USD") {
        repository.save(
            HistoricalFxRateEntity(
                id = UUID.randomUUID(),
                baseDate = date,
                currency = currency,
                rateKrw = BigDecimal(rate),
                source = "ECOS",
                createdAt = LocalDateTime.now(),
            ),
        )
    }

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = [HistoricalFxRateEntity::class])
    @EnableJpaRepositories(basePackageClasses = [HistoricalFxRateJpaRepository::class])
    class TestConfig
}
