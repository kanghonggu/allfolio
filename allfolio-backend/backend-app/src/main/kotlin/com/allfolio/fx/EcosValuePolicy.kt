package com.allfolio.fx

import java.math.BigDecimal

/**
 * ECOS가 준 값을 받아들일지 판정한다.
 *
 * 파서가 판정을 들고 있으면 첫 호출자(환율)의 가정이 모든 호출자에게 강요된다 —
 * 실제로 `rate <= 0` 가드가 그랬고, 금리에 그대로 쓰면 0.00% 공표일이 조용히 사라진다.
 * 그래서 무엇이 말이 되는 값인지는 도메인을 아는 호출자가 정한다.
 */
enum class EcosValuePolicy {
    /** 환율 — 0원짜리 환율은 없다. 0 이하는 파싱 사고이지 값이 아니다 */
    POSITIVE {
        override fun accepts(value: BigDecimal): Boolean = value > BigDecimal.ZERO
    },

    /**
     * 금리(연 %) — **부호로 거르지 않는다.** 0.00% 공표도, 마이너스 금리도 실재한다.
     * 대신 단위 오인(연 3.5%를 350으로 주는 계열)과 파싱 사고만 잡는다.
     * 한계는 ±100%: 한국 금리 시계열이 이 범위를 벗어나면 값이 아니라 형식이 바뀐 것이다.
     */
    PERCENT {
        override fun accepts(value: BigDecimal): Boolean = value.abs() <= BigDecimal("100")
    },
    ;

    abstract fun accepts(value: BigDecimal): Boolean
}
