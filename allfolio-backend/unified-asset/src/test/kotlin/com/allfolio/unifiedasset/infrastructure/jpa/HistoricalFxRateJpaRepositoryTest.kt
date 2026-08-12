package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.HistoricalFxRateEntity
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
 * 체결일 환율 조회는 "그 날짜 이하의 가장 최근 고시" 한 건이다.
 * 이 규칙 하나로 주말·공휴일이 직전 영업일로 자동으로 이어지고,
 * 백필 범위 이전 날짜는 행이 없어 miss로 떨어진다.
 */
@DataJpaTest
@ContextConfiguration(classes = [HistoricalFxRateJpaRepositoryTest.TestConfig::class])
class HistoricalFxRateJpaRepositoryTest {

    @Autowired
    private lateinit var repository: HistoricalFxRateJpaRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Test
    fun `정확히 그 날짜의 고시가 있으면 그것을 준다`() {
        save(LocalDate.of(2025, 8, 8), "1385.500000")
        // 소수점 6자리를 끝까지 채운 값 — 컬럼 스케일이 좁으면 여기서 잘려 단언이 깨진다
        save(LocalDate.of(2025, 8, 11), "1385.123456")

        val found = repository.findTopByCurrencyAndBaseDateLessThanEqualOrderByBaseDateDesc(
            "USD", LocalDate.of(2025, 8, 11),
        )

        assertThat(found?.baseDate).isEqualTo(LocalDate.of(2025, 8, 11))
        assertThat(found?.rateKrw).isEqualByComparingTo("1385.123456")
    }

    @Test
    fun `주말은 직전 영업일 고시로 잇는다`() {
        save(LocalDate.of(2025, 8, 8), "1385.500000") // 금요일
        save(LocalDate.of(2025, 8, 11), "1390.200000") // 월요일 — 더 최신 행을 건너뛰어야 한다

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
    fun `같은 날짜 같은 통화는 두 번 들어갈 수 없다`() {
        save(LocalDate.of(2025, 8, 11), "1390.200000")

        // 자연키가 겹치는 다른 id의 행 — flush 시점에 DB 제약이 막아야 한다
        assertThatThrownBy {
            repository.saveAndFlush(
                HistoricalFxRateEntity(
                    id = UUID.randomUUID(),
                    baseDate = LocalDate.of(2025, 8, 11),
                    currency = "USD",
                    rateKrw = BigDecimal("1391.000000"),
                    source = "ECOS",
                    createdAt = LocalDateTime.now(),
                ),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `범위 조회는 경계를 포함한다`() {
        save(LocalDate.of(2025, 8, 7), "1380.000000")
        save(LocalDate.of(2025, 8, 8), "1385.500000")
        save(LocalDate.of(2025, 8, 11), "1390.200000")
        // 범위 안에 있지만 통화가 다른 행 — 끼어들면 백필이 JPY를 USD 환율로 덮는다
        save(LocalDate.of(2025, 8, 8), "9.500000", currency = "JPY")

        val rows = repository.findAllByCurrencyAndBaseDateBetween(
            "USD", LocalDate.of(2025, 8, 7), LocalDate.of(2025, 8, 8),
        )

        assertThat(rows.map { it.baseDate })
            .containsExactlyInAnyOrder(LocalDate.of(2025, 8, 7), LocalDate.of(2025, 8, 8))
    }

    @Test
    fun `조회해 온 행을 고쳐 다시 저장하면 행이 늘지 않고 값만 바뀐다`() {
        // 백필의 멱등성 전체가 이 한 가지 JPA 동작에 기대고 있다 —
        // id가 할당식이라 saveAll이 persist가 아니라 merge를 타고, 그래서 같은 id면 UPDATE가 된다.
        // 백필 서비스에는 트랜잭션이 없어 조회와 저장이 각각 별도 트랜잭션이다. 즉 저장 시점의
        // 엔티티는 분리 상태이고, 더티 체킹이 대신 저장해 주지 않는다 — 여기서도 그대로 재현한다.
        save(LocalDate.of(2025, 8, 11), "1390.200000")

        val detached = repository.findAllByCurrencyAndBaseDateBetween(
            "USD", LocalDate.of(2025, 8, 11), LocalDate.of(2025, 8, 11),
        ).single()
        entityManager.clear()
        detached.rateKrw = BigDecimal("1391.500000")
        detached.source = "ECOS"

        repository.saveAll(listOf(detached))
        entityManager.flush()
        entityManager.clear()

        // 행이 늘었다면 uk_fx_rate_daily가 먼저 터졌겠지만, count로 못 박아 둔다
        assertThat(repository.count()).isEqualTo(1)
        assertThat(repository.findAll().single().rateKrw).isEqualByComparingTo("1391.500000")
    }

    /**
     * flush + clear로 영속성 컨텍스트를 비운다.
     * 이게 없으면 조회가 1차 캐시에서 방금 만든 인스턴스를 그대로 돌려주고,
     * 컬럼 타입(NUMERIC(18,6))을 실제로 거치지 않아 정밀도 손실을 못 잡는다.
     */
    private fun save(date: LocalDate, rate: String, currency: String = "USD") {
        repository.saveAndFlush(
            HistoricalFxRateEntity(
                id = UUID.randomUUID(),
                baseDate = date,
                currency = currency,
                rateKrw = BigDecimal(rate),
                source = "ECOS",
                createdAt = LocalDateTime.now(),
            ),
        )
        entityManager.clear()
    }

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = [HistoricalFxRateEntity::class])
    @EnableJpaRepositories(basePackageClasses = [HistoricalFxRateJpaRepository::class])
    class TestConfig
}
