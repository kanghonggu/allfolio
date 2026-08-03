package com.allfolio.unifiedasset.domain.account

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatNoException
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class StockTradeTest {

    private val today = LocalDate.now(ZoneId.of("Asia/Seoul"))

    private fun create(
        tradeType: StockTradeType = StockTradeType.BUY,
        quantity: BigDecimal = BigDecimal("10"),
        price: BigDecimal = BigDecimal("1000"),
        totalAmount: BigDecimal = BigDecimal("10000"),
        fee: BigDecimal = BigDecimal.ZERO,
        tax: BigDecimal = BigDecimal.ZERO,
        tradedAt: LocalDate = today,
    ) = StockTrade.create(
        accountId = UUID.randomUUID(), userId = UUID.randomUUID(),
        tradeType = tradeType, stockName = "삼성전자", symbol = "005930",
        quantity = quantity, price = price, totalAmount = totalAmount,
        fee = fee, tax = tax, tradedAt = tradedAt, memo = null,
    )

    @Test
    fun `정상 거래는 생성된다`() {
        val trade = create()
        assertThat(trade.totalAmount).isEqualByComparingTo("10000")
    }

    @Test
    fun `수량이 0 이하이면 거부한다`() {
        assertThatIllegalArgumentException().isThrownBy { create(quantity = BigDecimal.ZERO) }
        assertThatIllegalArgumentException().isThrownBy { create(quantity = BigDecimal("-5")) }
    }

    @Test
    fun `단가가 0 이하이면 거부한다`() {
        assertThatIllegalArgumentException().isThrownBy {
            create(price = BigDecimal.ZERO, totalAmount = BigDecimal.ZERO)
        }
        assertThatIllegalArgumentException().isThrownBy {
            create(price = BigDecimal("-1000"), totalAmount = BigDecimal("-10000"))
        }
    }

    @Test
    fun `수수료·세금이 음수이면 거부한다`() {
        assertThatIllegalArgumentException().isThrownBy { create(fee = BigDecimal("-1")) }
        assertThatIllegalArgumentException().isThrownBy { create(tax = BigDecimal("-1")) }
    }

    @Test
    fun `총액이 수량x단가와 크게 다르면 거부한다`() {
        // QA 재현 케이스: qty 10 x price 1000 = 10,000인데 totalAmount 999,999,999
        assertThatIllegalArgumentException().isThrownBy {
            create(totalAmount = BigDecimal("999999999"))
        }
    }

    @Test
    fun `총액의 정수 반올림 오차는 허용한다`() {
        // FE가 Math.round(qty x price)로 보냄 — 소수 절사/반올림 1원 미만 오차 허용
        assertThatNoException().isThrownBy {
            create(
                quantity = BigDecimal("10.5"), price = BigDecimal("123.45"),
                totalAmount = BigDecimal("1296"), // 실제 1296.225
            )
        }
    }

    @Test
    fun `배당은 총액=실수령액이므로 수량x단가 불일치·수량 0을 허용한다`() {
        assertThatNoException().isThrownBy {
            create(
                tradeType = StockTradeType.DIVIDEND,
                quantity = BigDecimal.ZERO, price = BigDecimal.ZERO,
                totalAmount = BigDecimal("50000"),
            )
        }
    }

    @Test
    fun `배당도 음수 값은 거부한다`() {
        assertThatIllegalArgumentException().isThrownBy {
            create(
                tradeType = StockTradeType.DIVIDEND,
                quantity = BigDecimal.ZERO, price = BigDecimal.ZERO,
                totalAmount = BigDecimal("-50000"),
            )
        }
        assertThatIllegalArgumentException().isThrownBy {
            create(
                tradeType = StockTradeType.DIVIDEND,
                quantity = BigDecimal("-1"), price = BigDecimal.ZERO,
                totalAmount = BigDecimal.ZERO,
            )
        }
    }

    @Test
    fun `미래 날짜 거래는 거부한다`() {
        assertThatIllegalArgumentException().isThrownBy { create(tradedAt = today.plusDays(1)) }
        assertThatIllegalArgumentException().isThrownBy { create(tradedAt = LocalDate.of(2099, 1, 1)) }
    }

    @Test
    fun `오늘과 과거 날짜 거래는 허용한다`() {
        assertThatNoException().isThrownBy { create(tradedAt = today) }
        assertThatNoException().isThrownBy { create(tradedAt = today.minusYears(1)) }
    }
}
