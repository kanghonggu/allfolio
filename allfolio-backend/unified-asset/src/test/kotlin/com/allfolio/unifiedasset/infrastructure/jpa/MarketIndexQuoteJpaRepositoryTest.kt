package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.MarketIndexQuoteEntity
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
 * 지수 시세는 (지수코드, 거래일, 슬롯)로 한 건이다.
 * KIS 응답에 기준시각이 없어 조회 시각을 키로 쓸 수 없기 때문 — 스케줄 지점이 곧 키다.
 */
@DataJpaTest
@ContextConfiguration(classes = [MarketIndexQuoteJpaRepositoryTest.TestConfig::class])
class MarketIndexQuoteJpaRepositoryTest {

    @Autowired private lateinit var repository: MarketIndexQuoteJpaRepository

    @Autowired private lateinit var entityManager: EntityManager

    private val yesterday = LocalDate.of(2026, 8, 11)
    private val today = LocalDate.of(2026, 8, 12)

    // UNIQUE 제약이 엔티티에 선언돼 있지 않으면 H2에 제약이 아예 안 생겨
    // 중복 삽입이 조용히 커밋된다 — AF-100에서 실제로 물린 함정이다.
    @Test
    fun `같은 지수 같은 날 같은 슬롯은 두 번 못 들어간다`() {
        save(quote("KOSPI", today, "CLOSE", "2500"))

        assertThatThrownBy {
            repository.saveAndFlush(quote("KOSPI", today, "CLOSE", "2650"))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `슬롯이 다르면 같은 날에도 들어간다`() {
        save(quote("KOSPI", today, "OPEN", "2600"))
        save(quote("KOSPI", today, "CLOSE", "2650"))

        assertThat(repository.count()).isEqualTo(2)
    }

    // 화면이 쓰는 조회. 다른 지수가 섞여 있어도 그 지수만 봐야 한다.
    @Test
    fun `그 지수의 가장 최근 한 건을 준다`() {
        save(quote("KOSPI", yesterday, "CLOSE", "2500"))
        save(quote("KOSPI", today, "OPEN", "2600"))
        save(quote("KOSDAQ", today, "OPEN", "900"))

        val found = repository.findLatest("KOSPI")

        assertThat(found?.price).isEqualByComparingTo("2600")
        assertThat(found?.tradeDate).isEqualTo(today)
    }

    // 슬롯을 문자열로 정렬하면 사전순이라 CLOSE < MID < OPEN이 되어,
    // 같은 날 시가가 종가보다 최신으로 잡힌다. 화면이 종가 대신 시가를 보여주게 된다.
    // Spring Data의 파생 쿼리(findTopBy...OrderBySlotDesc)로는 이 순서를 표현할 수 없다.
    @Test
    fun `같은 날에는 종가가 시가보다 최신이다`() {
        save(quote("KOSPI", today, "OPEN", "2600"))
        save(quote("KOSPI", today, "CLOSE", "2650"))

        val found = repository.findLatest("KOSPI")

        assertThat(found?.slot).isEqualTo("CLOSE")
        assertThat(found?.price).isEqualByComparingTo("2650")
    }

    /**
     * flush + clear로 영속성 컨텍스트를 비운다.
     * 이게 없으면 조회가 1차 캐시에서 방금 만든 인스턴스를 그대로 돌려주고,
     * DB 제약과 컬럼 타입을 실제로 거치지 않는다.
     */
    private fun save(entity: MarketIndexQuoteEntity) {
        repository.saveAndFlush(entity)
        entityManager.clear()
    }

    private fun quote(
        indexCode: String,
        tradeDate: LocalDate,
        slot: String,
        price: String = "2500",
    ) = MarketIndexQuoteEntity(
        id = UUID.randomUUID(),
        indexCode = indexCode,
        tradeDate = tradeDate,
        slot = slot,
        price = BigDecimal(price),
        prevClose = BigDecimal("2480.0000"),
        changeValue = BigDecimal("20.0000"),
        changeRate = BigDecimal("0.8065"),
        prevCloseDate = tradeDate.minusDays(1),
        marketStatus = "장마감",
        source = "KIS",
        collectedAt = LocalDateTime.now(),
    )

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = [MarketIndexQuoteEntity::class])
    @EnableJpaRepositories(basePackageClasses = [MarketIndexQuoteJpaRepository::class])
    class TestConfig
}
