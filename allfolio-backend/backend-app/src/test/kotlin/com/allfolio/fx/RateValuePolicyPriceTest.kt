package com.allfolio.fx

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * PRICE가 상한을 안 걸고 PERCENT가 거는지 못 박는다.
 *
 * **WTI(~70)로만 검증하면 안 된다** — 100 이하라 PERCENT로도 통과해서
 * 정책이 바뀐 걸 못 본다. 그래서 100을 넘는 값이 반드시 들어간다.
 */
class RateValuePolicyPriceTest {

    private fun bd(v: String) = BigDecimal(v)

    @Test
    fun `PRICE는 상한이 없다 - 구리 금 지수가 다 통과한다`() {
        assertTrue(RateValuePolicy.PRICE.accepts(bd("9000")))     // 구리 USD/MT
        assertTrue(RateValuePolicy.PRICE.accepts(bd("150000")))   // 금 KRW/g
        assertTrue(RateValuePolicy.PRICE.accepts(bd("180.5")))    // 종합지수
        assertTrue(RateValuePolicy.PRICE.accepts(bd("70.25")))    // WTI
    }

    @Test
    fun `PRICE는 0 이하를 거른다`() {
        assertFalse(RateValuePolicy.PRICE.accepts(BigDecimal.ZERO))
        assertFalse(RateValuePolicy.PRICE.accepts(bd("-1")))
    }

    @Test
    fun `PERCENT였다면 구리 금 지수가 버려진다 - 이게 정책을 나눈 이유다`() {
        assertFalse(RateValuePolicy.PERCENT.accepts(bd("9000")))
        assertFalse(RateValuePolicy.PERCENT.accepts(bd("150000")))
        assertFalse(RateValuePolicy.PERCENT.accepts(bd("180.5")))
        // WTI는 통과한다 — 우연이고, 유가가 100을 넘으면 깨진다
        assertTrue(RateValuePolicy.PERCENT.accepts(bd("70.25")))
        assertFalse(RateValuePolicy.PERCENT.accepts(bd("101")))
    }
}
