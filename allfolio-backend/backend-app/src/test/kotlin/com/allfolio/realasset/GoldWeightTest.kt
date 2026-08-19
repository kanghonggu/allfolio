package com.allfolio.realasset

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * `ua_assets.symbol`은 자유 문자열이다 — 기존 자산 등록 폼이 `g / 돈 / oz`를 **placeholder로만**
 * 안내하고 검증하지 않는다. 그래서 평가 배치는 이 값을 해석해야 하는데,
 * **틀리면 조용히 배수로 어긋난다**: 돈을 g으로 읽으면 평가액이 3.75배가 된다.
 *
 * 그래서 규칙은 하나다 — **모르는 단위는 추측하지 말고 null.** 호출부가 그 자산을 건너뛴다.
 */
class GoldWeightTest {

    @Test
    fun `그램은 그대로다`() {
        assertThat(GoldWeight.toGrams(BigDecimal("10"), "g")).isEqualByComparingTo("10")
    }

    /** 1돈 = 3.75g. 한국 사용자가 가장 많이 쓰는 단위다 */
    @Test
    fun `돈은 3_75를 곱한다`() {
        assertThat(GoldWeight.toGrams(BigDecimal("1"), "돈")).isEqualByComparingTo("3.75")
        assertThat(GoldWeight.toGrams(BigDecimal("10"), "돈")).isEqualByComparingTo("37.5")
    }

    /**
     * 금은 **트로이온스**다(31.1034768g). 상용온스(28.3495g)로 계산하면 약 9.7% 적게 나오는데,
     * 그 정도 오차는 "시세가 좀 다르네" 정도로 보여서 화면으로는 못 잡는다.
     */
    @Test
    fun `온스는 트로이온스다`() {
        assertThat(GoldWeight.toGrams(BigDecimal("1"), "oz")).isEqualByComparingTo("31.1034768")
    }

    /** 대소문자·앞뒤 공백은 사람이 넣는 값이라 늘 섞인다. 이건 추측이 아니라 정규화다 */
    @Test
    fun `대소문자와 공백은 무시한다`() {
        assertThat(GoldWeight.toGrams(BigDecimal("1"), " G ")).isEqualByComparingTo("1")
        assertThat(GoldWeight.toGrams(BigDecimal("1"), "OZ")).isEqualByComparingTo("31.1034768")
    }

    /** 폼 placeholder에 없는 표기도 사람은 넣는다. 뜻이 명확한 것만 받는다 */
    @Test
    fun `같은 뜻의 다른 표기도 받는다`() {
        assertThat(GoldWeight.toGrams(BigDecimal("1"), "그램")).isEqualByComparingTo("1")
        assertThat(GoldWeight.toGrams(BigDecimal("1"), "gram")).isEqualByComparingTo("1")
        assertThat(GoldWeight.toGrams(BigDecimal("1"), "onz")).isNull()  // 오타는 추측하지 않는다
    }

    /**
     * **여기가 이 클래스의 존재 이유다.** 모르는 단위에 기본값(예: g)을 주면 돈으로 입력한
     * 사용자의 금이 3.75배로 평가되고, 그 숫자는 그럴듯해서 아무도 못 알아챈다.
     */
    @Test
    fun `모르는 단위는 null이다`() {
        assertThat(GoldWeight.toGrams(BigDecimal("1"), "kg")).isNull()
        assertThat(GoldWeight.toGrams(BigDecimal("1"), "개")).isNull()
        assertThat(GoldWeight.toGrams(BigDecimal("1"), "")).isNull()
        assertThat(GoldWeight.toGrams(BigDecimal("1"), null)).isNull()
    }
}
