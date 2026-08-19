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
     * **바로 위 `LessThan`과 한 글자 차이인데 요구가 정반대다.** 전일대비는 "나보다 앞선"이
     * 필요해 기준일 자신이 나오면 변동이 늘 0이 되지만, 실물자산 평가(A1)의 폴백은
     * **기준일 자신이 최우선**이다 — 그날 시세가 있는데 전날 값으로 평가하면 하루씩 밀린다.
     *
     * 그래서 둘을 합치거나 한쪽을 다른 쪽으로 "정리"하지 말 것. 두 호출부의 요구가 다르다.
     * 이 테스트와 위 `직전 조회는...` 테스트는 **서로가 서로의 변이 검출기**다.
     */
    @Test
    fun `폴백 조회는 기준일 자신을 포함한다`() {
        save(quote("GOLD_KRX", LocalDate.of(2026, 8, 13), "200570.0000", unit = "KRW/g"))
        save(quote("GOLD_KRX", LocalDate.of(2026, 8, 14), "198350.0000", unit = "KRW/g"))

        val found = repository.findFirstByCodeAndTradeDateLessThanEqualOrderByTradeDateDesc(
            "GOLD_KRX", LocalDate.of(2026, 8, 14),
        )

        assertThat(found?.tradeDate).isEqualTo(LocalDate.of(2026, 8, 14))
        assertThat(found?.price).isEqualByComparingTo("198350.0000")
    }

    /**
     * 실측한 연휴다 — 2026-08-15 광복절이 토요일이라 08-17(월)이 대체공휴일이 되면서
     * 08-14(금) 다음 영업일이 08-18(화)이 됐다. 소스는 D+1 공표라 08-18에 쓸 수 있는
     * 가장 신선한 값이 08-14다(공백 4일).
     *
     * **날짜 하한을 걸면 여기서 null이 나온다.** "직전 1영업일"로 좁힌 구현이 정확히 이 케이스에서
     * 깨지고, 증상은 예외가 아니라 그 자산이 평가에서 통째로 빠지는 것이다.
     */
    @Test
    fun `폴백 조회는 연휴를 건너뛰고 직전 영업일까지 내려간다`() {
        save(quote("GOLD_KRX", LocalDate.of(2026, 8, 14), "198350.0000", unit = "KRW/g"))
        // 미래 행 — 날짜 필터가 빠지면 이게 나온다
        save(quote("GOLD_KRX", LocalDate.of(2026, 8, 19), "199000.0000", unit = "KRW/g"))
        // 남의 종목 — 코드 필터가 빠지면 이게 나온다
        save(quote("WTI", LocalDate.of(2026, 8, 17), "70.00"))

        val found = repository.findFirstByCodeAndTradeDateLessThanEqualOrderByTradeDateDesc(
            "GOLD_KRX", LocalDate.of(2026, 8, 18),
        )

        assertThat(found?.code).isEqualTo("GOLD_KRX")
        assertThat(found?.tradeDate).isEqualTo(LocalDate.of(2026, 8, 14))
    }

    /** 수집 시작 이전 날짜를 물으면 null. 0원으로 메우지 않게 하는 자리다(설계 1절 원칙 3) */
    @Test
    fun `폴백 조회는 그 이전 행이 하나도 없으면 null이다`() {
        save(quote("GOLD_KRX", LocalDate.of(2026, 8, 14), "198350.0000", unit = "KRW/g"))

        assertThat(
            repository.findFirstByCodeAndTradeDateLessThanEqualOrderByTradeDateDesc(
                "GOLD_KRX", LocalDate.of(2026, 8, 13),
            ),
        ).isNull()
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

    /**
     * **화면(AF-108 원자재 탭)이 보는 것이 이 쿼리 하나다.**
     *
     * `MarketQueryServiceTest`는 이 리포지터리를 목으로 세우고 스텁이 코드로 걸러 주므로,
     * "코드마다 최신 한 행"이라는 규칙은 **거기서 검증되지 않는다** — `NOT EXISTS`를 통째로
     * 지워 전체 행을 돌려주게 만들어도 그쪽은 전부 초록이다. 그 변이의 운영 증상은 조용하다:
     * 조회 서비스가 `associateBy`로 접으므로 오류 없이, DB가 준 순서에 따라 **묵은 행이 최신인 척**
     * 화면에 뜬다. 그래서 여기서 문다.
     *
     * 여러 날짜 + 여러 종목 + 요청 밖 종목을 한꺼번에 깔아 세 가지를 함께 본다.
     */
    @Test
    fun `최신 조회는 종목마다 가장 최근 한 행씩만 준다`() {
        save(quote("WTI", LocalDate.of(2026, 8, 10), "68.00"))
        save(quote("WTI", LocalDate.of(2026, 8, 13), "70.00")) // 이게 나와야 한다
        save(quote("WTI", LocalDate.of(2026, 8, 11), "69.00"))
        // 월간 계열 — 최신 관측이 두 달 묵어도 빠지면 안 된다(조회 창을 두는 구현이 여기서 깨진다)
        save(quote("COPPER", LocalDate.of(2026, 5, 1), "8800.0000", frequency = "M", unit = "USD/MT"))
        save(quote("COPPER", LocalDate.of(2026, 6, 1), "9000.0000", frequency = "M", unit = "USD/MT"))
        // 요청 밖 종목 — 코드 필터가 빠지면 이게 딸려 온다
        save(quote("BRENT", LocalDate.of(2026, 8, 13), "74.00"))

        val found = repository.findLatestByCodes(listOf("WTI", "COPPER", "NATGAS"))

        assertThat(found.map { it.code to it.tradeDate }).containsExactlyInAnyOrder(
            "WTI" to LocalDate.of(2026, 8, 13),
            "COPPER" to LocalDate.of(2026, 6, 1),
        )
        assertThat(found.single { it.code == "WTI" }.price).isEqualByComparingTo("70.00")
        // 수집된 적 없는 코드(NATGAS)는 예외가 아니라 그냥 빠진다 — 호출부가 설정으로 가려낸다
        assertThat(found.map { it.code }).doesNotContain("NATGAS", "BRENT")
    }

    /** 한 종목도 수집된 적 없으면 빈 목록이다. 화면 쪽에서 `[]`(데이터 없음)로 나가는 경로다 */
    @Test
    fun `최신 조회는 행이 없으면 빈 목록이다`() {
        assertThat(repository.findLatestByCodes(listOf("WTI"))).isEmpty()
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
