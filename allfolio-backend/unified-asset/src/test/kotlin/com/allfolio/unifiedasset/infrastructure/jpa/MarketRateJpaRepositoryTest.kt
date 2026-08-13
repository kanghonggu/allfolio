package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.MarketRateEntity
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.test.context.ContextConfiguration
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 금리는 (지표코드, 기준일)로 한 건이다.
 * 지수와 달리 슬롯이 없다 — 공표 기관이 확정한 하루 한 값이고, 응답이 기준일을 직접 준다.
 */
@DataJpaTest
@ContextConfiguration(classes = [MarketRateJpaRepositoryTest.TestConfig::class])
class MarketRateJpaRepositoryTest {

    @Autowired private lateinit var repository: MarketRateJpaRepository

    @Autowired private lateinit var entityManager: EntityManager

    // UNIQUE 제약이 엔티티에 선언돼 있지 않으면 H2에 제약이 아예 안 생겨
    // 중복 삽입이 조용히 커밋된다 — AF-100에서 실제로 물린 함정이다.
    @Test
    fun `같은 지표 같은 날은 두 번 못 들어간다`() {
        save(rate("KTB_3Y", LocalDate.of(2026, 8, 12), "3.15"))

        assertThatThrownBy { save(rate("KTB_3Y", LocalDate.of(2026, 8, 12), "3.20")) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `지표가 다르면 같은 날에도 들어간다`() {
        save(rate("KTB_3Y", LocalDate.of(2026, 8, 12), "3.15"))
        save(rate("KTB_10Y", LocalDate.of(2026, 8, 12), "3.40"))

        assertThat(repository.findAll()).hasSize(2)
    }

    @Test
    fun `구간 조회는 경계를 포함한다`() {
        save(rate("KTB_3Y", LocalDate.of(2026, 8, 10), "3.10"))
        save(rate("KTB_3Y", LocalDate.of(2026, 8, 11), "3.12"))
        save(rate("KTB_3Y", LocalDate.of(2026, 8, 12), "3.15"))
        save(rate("KTB_10Y", LocalDate.of(2026, 8, 11), "3.40"))

        val found = repository.findByRateCodeAndQuoteDateBetween(
            "KTB_3Y", LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 12),
        )

        assertThat(found).hasSize(3)
        assertThat(found.map { it.rateCode }).containsOnly("KTB_3Y")
    }

    @Test
    fun `마이너스 금리도 저장된다`() {
        save(rate("CALL_ON", LocalDate.of(2026, 8, 12), "-0.25"))

        assertThat(repository.findAll().single().rateValue).isEqualByComparingTo("-0.25")
    }

    private fun save(entity: MarketRateEntity) {
        repository.saveAndFlush(entity)
        entityManager.clear()
    }

    private fun rate(code: String, date: LocalDate, value: String) = MarketRateEntity(
        id = UUID.randomUUID(),
        rateCode = code,
        quoteDate = date,
        rateValue = BigDecimal(value),
        source = "ECOS",
        collectedAt = LocalDateTime.of(2026, 8, 12, 18, 10),
    )

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = [MarketRateEntity::class])
    @EnableJpaRepositories(basePackageClasses = [MarketRateJpaRepository::class])
    class TestConfig
}
