package com.allfolio.fx

import java.math.BigDecimal

/**
 * 외부 소스가 준 값을 받아들일지 판정한다.
 *
 * 파서가 판정을 들고 있으면 첫 호출자(환율)의 가정이 모든 호출자에게 강요된다 —
 * 실제로 `rate <= 0` 가드가 그랬고, 금리에 그대로 쓰면 0.00% 공표일이 조용히 사라진다.
 * 그래서 무엇이 말이 되는 값인지는 도메인을 아는 호출자가 정한다.
 *
 * **이름에서 `Ecos`를 뺀 이유**: FRED도 [PERCENT]를 그대로 쓴다. 소스가 둘이 된 시점에
 * `EcosValuePolicy`는 거짓말이 됐다. 패키지가 아직 `fx`인 것은 유일한 사용처인
 * `EcosResponseParser`가 여기 있어서다 — 세 번째 소스가 붙으면 중립 패키지로 옮길 것.
 */
enum class RateValuePolicy {
    /** 환율 — 0원짜리 환율은 없다. 0 이하는 파싱 사고이지 값이 아니다 */
    POSITIVE {
        override fun accepts(value: BigDecimal): Boolean = value > BigDecimal.ZERO
    },

    /**
     * 금리(연 %) — **부호로 거르지 않는다.** 0.00% 공표도, 마이너스 금리도 실재한다.
     * 대신 위쪽 단위 오인(연 3.5%를 350으로 주는 계열)과 파싱 사고만 잡는다.
     * 한계는 ±100%: 한국 금리 시계열이 이 범위를 벗어나면 값이 아니라 형식이 바뀐 것이다.
     *
     * **구조적으로 못 잡는 것: 반대 방향 단위 오인.** 연 3.5%를 소수 `0.035`로 주는 계열이 있으면
     * 이 경계로는 절대 걸리지 않는다 — `0.035`는 그 자체로 있을 법한 금리값이라 어떤 상한도
     * 구분할 수 없다. 이건 시계열 코드를 확정할 때 눈으로 한 번 확인해야 하는 몫이지,
     * 이 정책이 대신 잡아 줄 수 있는 종류의 문제가 아니다.
     */
    PERCENT {
        override fun accepts(value: BigDecimal): Boolean = value.abs() <= BigDecimal("100")
    },

    /**
     * 시세(원자재 등) — **상한을 걸지 않는다.** 2026-08-16 실측으로 구리 13,552 USD/MT ·
     * 니켈 17,588 USD/MT · 금 ~150,000 KRW/g · 종합지수 194.9가 전부 정상값이라
     * 어떤 상한도 진짜 값을 자른다.
     *
     * **[PERCENT]를 쓰면 안 된다.** `|value| <= 100`이라 위 셋이 전부 버려지고,
     * WTI(~70)만 우연히 통과했다가 유가가 100달러를 넘는 날 조용히 사라진다.
     *
     * **[POSITIVE]를 재사용하지 않는 이유**: 술어는 같지만 뜻이 다르다. POSITIVE의 KDoc은
     * "0원짜리 환율은 없다"는 환율 도메인의 진술이고, 그쪽 판단이 바뀌면 원자재가 따라 움직인다.
     *
     * **못 잡는 것**: 반대 방향 단위 오인. USD/MT를 USD/kg로 주면 값이 1000분의 1이 되는데
     * 그것도 양수다. [PERCENT]의 KDoc이 적은 것과 같은 한계이고, 시리즈를 확정할 때
     * 눈으로 한 번 보는 것이 유일한 방어다.
     */
    PRICE {
        override fun accepts(value: BigDecimal): Boolean = value > BigDecimal.ZERO
    },
    ;

    abstract fun accepts(value: BigDecimal): Boolean
}
