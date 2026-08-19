package com.allfolio.realasset

import com.allfolio.unifiedasset.infrastructure.entity.MarketCommodityQuoteEntity
import com.allfolio.unifiedasset.infrastructure.jpa.MarketCommodityQuoteJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 이 어댑터는 순수 위임이 아니라 **매핑**이라서 테스트가 있다(형제인 `JpaCommodityStore`는
 * 엔티티를 그대로 넘겨 테스트가 없다). 세 필드를 옮기는데, 그중 `price`는 같은 엔티티의
 * `prevClose`와 타입이 같아 바꿔 써도 컴파일이 통과하고 숫자도 그럴듯하게 나온다.
 */
class JpaCommodityQuoteLookupTest {

    private val repository = mock(MarketCommodityQuoteJpaRepository::class.java)
    private val lookup = JpaCommodityQuoteLookup(repository)

    private val asOf = LocalDate.of(2026, 8, 18)

    @Test
    fun `거래일과 종가와 단위를 옮긴다`() {
        `when`(repository.findFirstByCodeAndTradeDateLessThanEqualOrderByTradeDateDesc("GOLD_KRX", asOf))
            .thenReturn(
                entity(
                    tradeDate = LocalDate.of(2026, 8, 14),
                    price = "198350.0000",
                    unit = "KRW/g",
                    // 종가와 헷갈리기 쉬운 이웃 필드 — 이 값이 나오면 매핑이 틀린 것이다
                    prevClose = "200570.0000",
                ),
            )

        val quote = lookup.latestAsOf("GOLD_KRX", asOf)

        assertThat(quote).isNotNull
        assertThat(quote!!.tradeDate).isEqualTo(LocalDate.of(2026, 8, 14))
        assertThat(quote.price).isEqualByComparingTo("198350.0000")
        assertThat(quote.unit).isEqualTo("KRW/g")
    }

    /** 없으면 null. 어댑터가 여기서 기본값을 지어내면 평가가 0원으로 굳는다 */
    @Test
    fun `행이 없으면 null을 준다`() {
        `when`(repository.findFirstByCodeAndTradeDateLessThanEqualOrderByTradeDateDesc("GOLD_KRX", asOf))
            .thenReturn(null)

        assertThat(lookup.latestAsOf("GOLD_KRX", asOf)).isNull()
    }

    private fun entity(
        tradeDate: LocalDate,
        price: String,
        unit: String,
        prevClose: String? = null,
    ) = MarketCommodityQuoteEntity(
        id = UUID.randomUUID(),
        code = "GOLD_KRX",
        tradeDate = tradeDate,
        price = BigDecimal(price),
        unit = unit,
        frequency = "D",
        prevClose = prevClose?.let { BigDecimal(it) },
        changeValue = null,
        changeRate = null,
        source = "FSC",
        collectedAt = LocalDateTime.of(2026, 8, 18, 18, 20),
    )
}
