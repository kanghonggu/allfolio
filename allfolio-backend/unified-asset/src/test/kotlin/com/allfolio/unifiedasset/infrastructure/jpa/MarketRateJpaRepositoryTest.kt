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
        // 소수점 4자리를 끝까지 채운 값 — 컬럼 스케일이 좁으면 여기서 잘려 단언이 깨진다
        save(rate("KTB_3Y", LocalDate.of(2026, 8, 12), "3.1234"))
        save(rate("KTB_10Y", LocalDate.of(2026, 8, 11), "3.40"))

        val found = repository.findByRateCodeAndQuoteDateBetween(
            "KTB_3Y", LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 12),
        )

        assertThat(found).hasSize(3)
        assertThat(found.map { it.rateCode }).containsOnly("KTB_3Y")
        assertThat(found.single { it.quoteDate == LocalDate.of(2026, 8, 12) }.rateValue)
            .isEqualByComparingTo("3.1234")
    }

    /**
     * 시장 화면(AF-104)이 쓰는 묶음 조회. 지표마다 부르면 왕복이 지표 수만큼 나서 한 번으로 묶었다.
     *
     * 코드 필터와 날짜 필터가 **함께** 걸려야 한다 — 파생 쿼리 이름을 잘못 지어 한쪽이 빠지면
     * 화면에 남의 지표가 섞이거나 구간 밖 행이 최신으로 잡힌다.
     */
    @Test
    fun `묶음 구간 조회는 요청한 지표만 경계를 포함해 가져온다`() {
        save(rate("KTB_3Y", LocalDate.of(2026, 8, 10), "3.10"))
        save(rate("KTB_3Y", LocalDate.of(2026, 8, 12), "3.12"))
        save(rate("BASE_RATE", LocalDate.of(2026, 8, 11), "2.50"))
        // 구간 밖 — 날짜 필터가 빠지면 이게 딸려 온다
        save(rate("KTB_3Y", LocalDate.of(2026, 8, 13), "3.20"))
        // 요청 밖 지표 — 코드 필터가 빠지면 이게 딸려 온다
        save(rate("CD_91D", LocalDate.of(2026, 8, 11), "3.00"))

        val found = repository.findByRateCodeInAndQuoteDateBetween(
            listOf("KTB_3Y", "BASE_RATE"), LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 12),
        )

        assertThat(found).hasSize(3)
        assertThat(found.map { it.rateCode }).containsOnly("KTB_3Y", "BASE_RATE")
        assertThat(found.map { it.quoteDate }).doesNotContain(LocalDate.of(2026, 8, 13))
    }

    /** 수집된 적 없는 지표를 섞어 물어도 예외가 아니라 그냥 안 나온다 — 호출부가 빠진 것을 가려낸다 */
    @Test
    fun `묶음 조회에 없는 지표를 섞어도 있는 것만 나온다`() {
        save(rate("KTB_3Y", LocalDate.of(2026, 8, 11), "3.12"))

        val found = repository.findByRateCodeInAndQuoteDateBetween(
            listOf("KTB_3Y", "NEVER_COLLECTED"), LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 12),
        )

        assertThat(found.map { it.rateCode }).containsExactly("KTB_3Y")
    }

    @Test
    fun `마이너스 금리도 저장된다`() {
        save(rate("CALL_ON", LocalDate.of(2026, 8, 12), "-0.25"))

        assertThat(repository.findAll().single().rateValue).isEqualByComparingTo("-0.25")
    }

    @Test
    fun `조회해 온 행을 고쳐 다시 저장하면 행이 늘지 않고 값만 바뀐다`() {
        // Task 5의 멱등성 전체가 이 한 가지 JPA 동작에 기대고 있다 — id가 할당식이라
        // saveAll이 persist가 아니라 merge를 타고, 그래서 같은 id면 UPDATE가 된다.
        // 수집 서비스에는 트랜잭션이 없어 조회와 저장이 각각 별도 트랜잭션이다. 즉 저장 시점의
        // 엔티티는 분리(detached) 상태이고, 더티 체킹이 대신 저장해 주지 않는다 — 여기서도 그대로 재현한다.
        save(rate("KTB_3Y", LocalDate.of(2026, 8, 12), "3.15"))

        val detached = repository.findByRateCodeAndQuoteDateBetween(
            "KTB_3Y", LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 12),
        ).single()
        entityManager.clear()
        detached.rateValue = BigDecimal("3.20")
        detached.source = "ECOS"

        repository.saveAll(listOf(detached))
        entityManager.flush()
        entityManager.clear()

        // 행이 늘었다면 uk_market_rate가 먼저 터졌겠지만, count로 못 박아 둔다
        assertThat(repository.count()).isEqualTo(1)
        assertThat(repository.findAll().single().rateValue).isEqualByComparingTo("3.20")
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
