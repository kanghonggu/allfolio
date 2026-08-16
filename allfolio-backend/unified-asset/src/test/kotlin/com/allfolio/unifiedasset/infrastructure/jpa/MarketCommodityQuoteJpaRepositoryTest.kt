package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.MarketCommodityQuoteEntity
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
 * 원자재는 (코드, 거래일)로 한 건이다. 지수와 달리 슬롯이 없다 — 하루(또는 한 달) 한 값이다.
 *
 * **이 파일이 없으면 파생 쿼리가 아무 테스트도 안 문다.** 수집 서비스의 단위 테스트는
 * `Store` 포트를 인메모리로 구현하므로 쿼리의 **의미를 손으로 다시 적는다** — 실제 파생 쿼리
 * 이름이 `...OrderByTradeDateAsc`든 `LessThanEqual`이든 그쪽은 전부 초록이다.
 * `Asc` 한 글자만 틀리면 모든 `prev_close`가 그 종목의 **최초 관측가**가 되는데,
 * 숫자는 그럴듯하고 오류도 로그도 안 난다.
 * (`MarketRateJpaRepositoryTest`가 같은 이유로 존재한다. 형제 표에는 없던
 *  `findFirstBy...LessThan...Desc`가 여기 처음 생겼으니 더 필요하다.)
 */
@DataJpaTest
@ContextConfiguration(classes = [MarketCommodityQuoteJpaRepositoryTest.TestConfig::class])
class MarketCommodityQuoteJpaRepositoryTest {

    @Autowired private lateinit var repository: MarketCommodityQuoteJpaRepository

    @Autowired private lateinit var entityManager: EntityManager

    // UNIQUE 제약이 엔티티에 선언돼 있지 않으면 H2에 제약이 아예 안 생겨
    // 중복 삽입이 조용히 커밋된다 — AF-100에서 실제로 물린 함정이다.
    @Test
    fun `같은 종목 같은 날은 두 번 못 들어간다`() {
        save(quote("WTI", LocalDate.of(2026, 8, 12), "70.00"))

        assertThatThrownBy { save(quote("WTI", LocalDate.of(2026, 8, 12), "71.00")) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `종목이 다르면 같은 날에도 들어간다`() {
        save(quote("WTI", LocalDate.of(2026, 8, 12), "70.00"))
        save(quote("BRENT", LocalDate.of(2026, 8, 12), "74.00"))

        assertThat(repository.findAll()).hasSize(2)
    }

    /**
     * 코드 필터와 날짜 필터가 **함께** 걸려야 한다 — 파생 쿼리 이름을 잘못 지어 한쪽이 빠지면
     * 남의 종목이 섞이거나 구간 밖 행이 딸려 온다. 수집 경로에서 그 행은 `existing`으로 잡혀
     * 엉뚱한 날짜가 갱신 대상이 된다.
     */
    @Test
    fun `구간 조회는 요청한 종목만 경계를 포함해 가져온다`() {
        save(quote("WTI", LocalDate.of(2026, 8, 10), "70.00"))
        // 소수점 4자리를 끝까지 채운 값 — price 스케일이 좁으면 여기서 잘려 단언이 깨진다
        save(quote("WTI", LocalDate.of(2026, 8, 12), "70.1234", changeRate = "12.3456"))
        // 구간 밖 — 날짜 필터가 빠지면 이게 딸려 온다
        save(quote("WTI", LocalDate.of(2026, 8, 13), "72.00"))
        // 요청 밖 종목 — 코드 필터가 빠지면 이게 딸려 온다
        save(quote("BRENT", LocalDate.of(2026, 8, 11), "74.00"))

        val found = repository.findByCodeAndTradeDateBetween(
            "WTI", LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 12),
        )

        assertThat(found).hasSize(2)
        assertThat(found.map { it.code }).containsOnly("WTI")
        assertThat(found.map { it.tradeDate }).doesNotContain(LocalDate.of(2026, 8, 13))
        val latest = found.single { it.tradeDate == LocalDate.of(2026, 8, 12) }
        assertThat(latest.price).isEqualByComparingTo("70.1234")
        // change_rate는 NUMERIC(9,4) — 여기가 좁으면 변동률이 조용히 반올림된다
        assertThat(latest.changeRate).isEqualByComparingTo("12.3456")
    }

    /**
     * **전일대비 계산 전체가 이 쿼리 하나에 걸려 있다.**
     *
     * 정렬이 `Asc`면 가장 오래된 행이 나와 모든 `prev_close`가 "최초 관측가"가 되고,
     * `LessThanEqual`이면 자기 자신이 나와 변동이 언제나 0이 된다. 둘 다 숫자가 그럴듯해서
     * 화면으로는 못 잡는다. 그래서 **여러 날짜 + 같은 날짜 + 남의 종목**을 한꺼번에 깔아 둔다.
     */
    @Test
    fun `직전 조회는 그 종목의 가장 가까운 이전 행을 준다`() {
        save(quote("WTI", LocalDate.of(2026, 6, 1), "60.00"))
        save(quote("WTI", LocalDate.of(2026, 7, 1), "65.00"))
        save(quote("WTI", LocalDate.of(2026, 8, 11), "70.00")) // 이게 나와야 한다
        save(quote("WTI", LocalDate.of(2026, 8, 12), "71.00")) // 기준일 자신 — LessThanEqual이면 이게 나온다
        save(quote("WTI", LocalDate.of(2026, 8, 13), "72.00")) // 미래
        save(quote("BRENT", LocalDate.of(2026, 8, 12), "74.00")) // 남의 종목

        val prior = repository.findFirstByCodeAndTradeDateLessThanOrderByTradeDateDesc(
            "WTI", LocalDate.of(2026, 8, 12),
        )

        assertThat(prior?.tradeDate).isEqualTo(LocalDate.of(2026, 8, 11))
        assertThat(prior?.price).isEqualByComparingTo("70.00")
    }

    /**
     * 월간 계열의 "직전"은 한 달 전이다. 날짜 산술("어제")로 찾으면 영원히 못 찾는 그 경우를
     * 쿼리 층에서도 못 박는다 — 창 밖 행이라 구간 조회로는 절대 안 잡힌다.
     */
    @Test
    fun `직전 조회는 한 달 전 행도 찾는다`() {
        save(quote("COPPER", LocalDate.of(2026, 7, 1), "9000.0000", frequency = "M", unit = "USD/MT"))

        val prior = repository.findFirstByCodeAndTradeDateLessThanOrderByTradeDateDesc(
            "COPPER", LocalDate.of(2026, 8, 1),
        )

        assertThat(prior?.tradeDate).isEqualTo(LocalDate.of(2026, 7, 1))
    }

    /** 첫 관측에는 직전이 없다. 예외가 아니라 null이어야 서비스가 `prev_close`를 null로 남긴다 */
    @Test
    fun `직전 행이 없으면 null이다`() {
        save(quote("WTI", LocalDate.of(2026, 8, 12), "71.00"))

        assertThat(
            repository.findFirstByCodeAndTradeDateLessThanOrderByTradeDateDesc("WTI", LocalDate.of(2026, 8, 12)),
        ).isNull()
    }

    /**
     * **`prev_close`·`change_*`가 정말 nullable로 선언됐는지.** 엔티티에서 `nullable = false`가
     * 되면 H2에 NOT NULL이 생겨 첫 관측 저장이 통째로 터진다 — 운영에서는 마이그레이션이
     * nullable이라 안 터지고, 대신 **0으로 채우는 판본이 조용히 들어온다.**
     * 0(무변동)과 null(직전 값 없음)은 다르다.
     */
    @Test
    fun `직전 값이 없는 행은 전일대비를 비운 채 저장된다`() {
        save(quote("WTI", LocalDate.of(2026, 8, 12), "71.00", prevClose = null, changeValue = null, changeRate = null))

        val saved = repository.findAll().single()
        assertThat(saved.prevClose).isNull()
        assertThat(saved.changeValue).isNull()
        assertThat(saved.changeRate).isNull()
    }

    @Test
    fun `조회해 온 행을 고쳐 다시 저장하면 행이 늘지 않고 값만 바뀐다`() {
        // Task 5의 멱등성 전체가 이 한 가지 JPA 동작에 기대고 있다 — id가 할당식이라
        // saveAll이 persist가 아니라 merge를 타고, 그래서 같은 id면 UPDATE가 된다.
        // 수집 서비스에는 트랜잭션이 없어 조회와 저장이 각각 별도 트랜잭션이다. 즉 저장 시점의
        // 엔티티는 분리(detached) 상태이고, 더티 체킹이 대신 저장해 주지 않는다 — 여기서도 그대로 재현한다.
        save(quote("WTI", LocalDate.of(2026, 8, 12), "70.00"))

        val detached = repository.findByCodeAndTradeDateBetween(
            "WTI", LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 12),
        ).single()
        entityManager.clear()
        detached.price = BigDecimal("71.00")
        detached.prevClose = BigDecimal("70.00")
        detached.changeValue = BigDecimal("1.00")
        detached.changeRate = BigDecimal("1.4286")
        detached.source = "FRED"

        repository.saveAll(listOf(detached))
        entityManager.flush()
        entityManager.clear()

        // 행이 늘었다면 uk_market_commodity_quote가 먼저 터졌겠지만, count로 못 박아 둔다
        assertThat(repository.count()).isEqualTo(1)
        val reloaded = repository.findAll().single()
        assertThat(reloaded.price).isEqualByComparingTo("71.00")
        assertThat(reloaded.changeRate).isEqualByComparingTo("1.4286")
    }

    private fun save(entity: MarketCommodityQuoteEntity) {
        repository.saveAndFlush(entity)
        entityManager.clear()
    }

    private fun quote(
        code: String,
        date: LocalDate,
        price: String,
        unit: String = "USD/bbl",
        frequency: String = "D",
        prevClose: String? = null,
        changeValue: String? = null,
        changeRate: String? = null,
    ) = MarketCommodityQuoteEntity(
        id = UUID.randomUUID(),
        code = code,
        tradeDate = date,
        price = BigDecimal(price),
        unit = unit,
        frequency = frequency,
        prevClose = prevClose?.let { BigDecimal(it) },
        changeValue = changeValue?.let { BigDecimal(it) },
        changeRate = changeRate?.let { BigDecimal(it) },
        source = "FRED",
        collectedAt = LocalDateTime.of(2026, 8, 12, 18, 10),
    )

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = [MarketCommodityQuoteEntity::class])
    @EnableJpaRepositories(basePackageClasses = [MarketCommodityQuoteJpaRepository::class])
    class TestConfig
}
