package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.domain.cashflow.FlowType
import com.allfolio.unifiedasset.infrastructure.entity.CashFlowEntity
import jakarta.persistence.EntityManager
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
 * 소급 재계산이 대상 행을 긁어오는 쿼리 (AF-100 2단계).
 *
 * **엔티티를 직접 만들어 넣는다.** `CashFlow.create(...)`를 거치면 `currency`가 `uppercase()`로
 * 정규화돼 소문자 `krw` 케이스를 아예 만들 수 없다. 여기서 검증할 대상은 JPA 쿼리이지
 * 도메인 검증이 아니다.
 *
 * `saveAndFlush` + `clear`를 쓰는 이유는 1차 캐시만 보고 통과하는 걸 막기 위해서다 —
 * 쿼리가 실제로 DB에 나가야 `UPPER(...)` 비교가 검증된다.
 */
@DataJpaTest
@ContextConfiguration(classes = [CashFlowRecomputeQueryTest.TestConfig::class])
class CashFlowRecomputeQueryTest {

    @Autowired private lateinit var repository: CashFlowJpaRepository

    @Autowired private lateinit var entityManager: EntityManager

    // 원화 행은 재계산 대상이 아니다 — amount_krw == amount가 정의라 계산할 것이 없다.
    // 조회 단계에서 걸러야 서비스가 "바뀐 것 없음"을 세느라 헛돌지 않는다.
    @Test
    fun `원화가 아닌 행만 돌려준다`() {
        save(currency = "KRW", amount = "1000", amountKrw = "1000")
        save(currency = "USD", amount = "100", amountKrw = "140000")
        save(currency = "BTC", amount = "0.5", amountKrw = "45000000")

        val rows = repository.findNonKrwOrderByFlowDate()

        assertThat(rows.map { it.currency }).containsExactlyInAnyOrder("USD", "BTC")
    }

    // 소문자로 저장된 행이 있어도 원화는 원화다. 저장 시 uppercase()가 걸리지만
    // 과거 데이터나 직접 INSERT된 행까지 보장되지는 않는다.
    @Test
    fun `원화 판정은 대소문자를 가리지 않는다`() {
        save(currency = "krw", amount = "1000", amountKrw = "1000")

        assertThat(repository.findNonKrwOrderByFlowDate()).isEmpty()
    }

    // 환율 조회 캐시는 (통화, 날짜) 단위다. 날짜순으로 주면 같은 날짜가 뭉쳐 와
    // 캐시 적중률이 올라간다 — 재계산은 같은 날짜를 수없이 반복한다.
    @Test
    fun `흐름일자 오름차순으로 준다`() {
        save(currency = "USD", flowDate = LocalDate.of(2025, 3, 1))
        save(currency = "USD", flowDate = LocalDate.of(2024, 1, 1))
        save(currency = "USD", flowDate = LocalDate.of(2024, 6, 1))

        assertThat(repository.findNonKrwOrderByFlowDate().map { it.flowDate })
            .containsExactly(
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 6, 1),
                LocalDate.of(2025, 3, 1),
            )
    }

    // ── helpers ──────────────────────────────────────────────

    private fun save(
        currency: String,
        amount: String = "100",
        amountKrw: String = "140000",
        flowDate: LocalDate = LocalDate.of(2025, 1, 1),
    ) {
        repository.saveAndFlush(
            CashFlowEntity(
                id = UUID.randomUUID(),
                userId = UUID.randomUUID(),
                accountId = null,
                flowDate = flowDate,
                flowType = FlowType.DEPOSIT,
                amount = BigDecimal(amount),
                currency = currency,
                amountKrw = BigDecimal(amountKrw),
                memo = null,
                createdAt = LocalDateTime.now(),
                linkId = null,
            ),
        )
        entityManager.clear()
    }

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = [CashFlowEntity::class])
    @EnableJpaRepositories(basePackageClasses = [CashFlowJpaRepository::class])
    class TestConfig
}
