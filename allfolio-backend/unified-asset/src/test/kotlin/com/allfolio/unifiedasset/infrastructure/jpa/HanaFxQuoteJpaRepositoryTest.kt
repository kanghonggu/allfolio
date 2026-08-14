package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.HanaFxQuoteEntity
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
 * 평가 경로는 "그 통화의 가장 최근 고시" 한 건만 본다.
 * 같은 날 여러 회차가 쌓이므로 기준일뿐 아니라 회차까지 내림차순이어야 한다.
 */
@DataJpaTest
@ContextConfiguration(classes = [HanaFxQuoteJpaRepositoryTest.TestConfig::class])
class HanaFxQuoteJpaRepositoryTest {

    @Autowired private lateinit var repository: HanaFxQuoteJpaRepository

    @Autowired private lateinit var entityManager: EntityManager

    private val friday = LocalDate.of(2026, 8, 7)
    private val monday = LocalDate.of(2026, 8, 10)

    @Test
    fun `같은 날 여러 회차가 있으면 회차가 큰 것을 준다`() {
        save(friday, 1, "USD", "1380.0000")
        // 소수점 4자리를 끝까지 채운 값 — 컬럼 스케일이 좁으면 여기서 반올림돼 단언이 깨진다.
        // 4자리가 필요한 이유는 (100) 단위 통화를 1단위로 정규화할 때 100으로 나누기 때문.
        save(friday, 32, "USD", "1390.5678")
        save(friday, 12, "USD", "1385.0000")

        val found = repository.findTopByCurrencyOrderByBaseDateDescRoundNoDesc("USD")

        assertThat(found?.roundNo).isEqualTo(32)
        assertThat(found?.baseRate).isEqualByComparingTo("1390.5678")
    }

    @Test
    fun `기준일이 더 최근이면 회차가 작아도 그것을 준다`() {
        save(friday, 32, "USD", "1390.5678")
        save(monday, 1, "USD", "1400.0000")

        val found = repository.findTopByCurrencyOrderByBaseDateDescRoundNoDesc("USD")

        assertThat(found?.baseDate).isEqualTo(monday)
        assertThat(found?.roundNo).isEqualTo(1)
    }

    @Test
    fun `다른 통화의 고시는 섞이지 않는다`() {
        save(friday, 32, "JPY", "9.5000")

        assertThat(repository.findTopByCurrencyOrderByBaseDateDescRoundNoDesc("USD")).isNull()
    }

    @Test
    fun `고시가 하나도 없으면 null을 준다`() {
        assertThat(repository.findTopByCurrencyOrderByBaseDateDescRoundNoDesc("USD")).isNull()
    }

    @Test
    fun `회차 단위 조회는 그 회차의 통화만 준다`() {
        save(friday, 32, "USD", "1390.5678")
        save(friday, 32, "JPY", "9.5000")
        save(friday, 31, "USD", "1389.0000")
        // 같은 회차 다른 기준일 — 회차만 보고 날짜를 놓치면 수집기가 전날 32회차를
        // 오늘 값으로 덮어써 (기준일, 회차, 통화) 키가 지키려던 이력이 조용히 사라진다
        save(monday, 32, "USD", "1400.0000")

        val rows = repository.findAllByBaseDateAndRoundNo(friday, 32)

        assertThat(rows).hasSize(2)
        assertThat(rows.map { it.currency }).containsExactlyInAnyOrder("USD", "JPY")
    }

    /** 시장 화면(AF-104)은 통화를 안 가리고 "가장 최근 회차"를 먼저 찾은 뒤 그 회차 전량을 읽는다 */
    @Test
    fun `통화를 안 가리면 전체에서 가장 최근 회차를 준다`() {
        save(friday, 32, "USD", "1390.5678")
        save(monday, 1, "JPY", "9.5000")
        save(monday, 12, "USD", "1400.0000")

        val found = repository.findTopByOrderByBaseDateDescRoundNoDesc()

        assertThat(found?.baseDate).isEqualTo(monday)
        assertThat(found?.roundNo).isEqualTo(12)
    }

    /**
     * 전일대비가 걸린 쿼리다. **직전 "회차"가 아니라 직전 "기준일"의 마지막 회차**를 줘야 한다 —
     * 직전 회차(여기서는 월요일 1회차)를 주면 화면의 전일대비가 장중 변동으로 바뀌는데,
     * 그 숫자는 그럴듯해 보여서 눈으로는 안 걸린다.
     */
    @Test
    fun `직전 기준일 조회는 그날 안의 앞 회차를 건너뛰고 전날 마지막 회차를 준다`() {
        save(friday, 1, "USD", "1370.0000")
        save(friday, 40, "USD", "1380.0000")
        save(monday, 1, "USD", "1395.0000")
        save(monday, 12, "USD", "1400.0000")

        val found = repository.findTopByBaseDateLessThanOrderByBaseDateDescRoundNoDesc(monday)

        assertThat(found?.baseDate).isEqualTo(friday)
        assertThat(found?.roundNo).isEqualTo(40)
    }

    /** 첫 수집일에는 앞선 기준일이 없다 — 그때 전일대비는 만들어 낼 수 없다 */
    @Test
    fun `앞선 기준일이 없으면 직전 기준일 조회는 null을 준다`() {
        save(monday, 12, "USD", "1400.0000")

        assertThat(repository.findTopByBaseDateLessThanOrderByBaseDateDescRoundNoDesc(monday)).isNull()
    }

    @Test
    fun `같은 기준일 회차 통화는 두 번 들어갈 수 없다`() {
        save(friday, 32, "USD", "1390.5678")

        assertThatThrownBy {
            repository.saveAndFlush(
                entity(UUID.randomUUID(), friday, 32, "USD", "1391.0000"),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `조회해 온 행을 고쳐 다시 저장하면 행이 늘지 않고 값만 바뀐다`() {
        val id = UUID.randomUUID()
        repository.saveAndFlush(entity(id, friday, 32, "USD", "1390.5678"))
        entityManager.clear()

        val loaded = repository.findAllByBaseDateAndRoundNo(friday, 32).single()
        loaded.baseRate = BigDecimal("1391.2500")
        repository.saveAll(listOf(loaded))
        entityManager.flush()
        entityManager.clear()

        assertThat(repository.count()).isEqualTo(1)
        assertThat(repository.findAllByBaseDateAndRoundNo(friday, 32).single().baseRate)
            .isEqualByComparingTo("1391.25")
    }

    /**
     * flush + clear로 영속성 컨텍스트를 비운다.
     * 이게 없으면 조회가 1차 캐시에서 방금 만든 인스턴스를 그대로 돌려주고,
     * 컬럼 타입(NUMERIC(18,4))을 실제로 거치지 않아 정밀도 손실을 못 잡는다.
     */
    private fun save(date: LocalDate, round: Int, currency: String, rate: String) {
        repository.saveAndFlush(entity(UUID.randomUUID(), date, round, currency, rate))
        entityManager.clear()
    }

    private fun entity(id: UUID, date: LocalDate, round: Int, currency: String, rate: String) =
        HanaFxQuoteEntity(
            id = id,
            baseDate = date,
            roundNo = round,
            currency = currency,
            baseRate = BigDecimal(rate),
            cashBuy = null,
            cashSell = null,
            remitSend = null,
            remitReceive = null,
            collectedAt = LocalDateTime.now(),
        )

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = [HanaFxQuoteEntity::class])
    @EnableJpaRepositories(basePackageClasses = [HanaFxQuoteJpaRepository::class])
    class TestConfig
}
