package com.allfolio.realasset

import java.math.BigDecimal

/**
 * `ua_assets.symbol`(자유 문자열)을 그램으로 옮긴다.
 *
 * **왜 필요한가**: 금 시세는 원/g인데 사용자는 돈·온스로도 넣는다. 기존 자산 등록 폼은
 * `g / 돈 / oz`를 placeholder로 안내할 뿐 검증하지 않으므로, 실제 값에는 표기 흔들림이 섞인다.
 *
 * **모르는 단위에 기본값을 주지 않는다 — 이게 이 객체의 존재 이유다.** 기본값을 g으로 두면
 * 돈으로 입력한 사용자의 금이 **3.75배**로 평가되고, 그 숫자는 그럴듯해서 화면으로는 못 잡는다.
 * 뜻이 명확하지 않으면 `null`을 주고 호출부가 그 자산을 건너뛴다(설계 1절 원칙 3).
 *
 * **오타를 받아 주지 않는다.** `onz` 같은 것을 `oz`로 고쳐 읽기 시작하면 어디까지가 오타이고
 * 어디부터가 다른 단위인지 정할 수 없게 된다. 대소문자·앞뒤 공백만 정규화한다 — 그건 추측이 아니다.
 */
object GoldWeight {

    /**
     * 트로이온스다. **상용온스(28.3495g)가 아니다** — 귀금속은 트로이온스로 거래한다.
     * 둘을 혼동하면 약 9.7% 적게 나오는데, 그 정도면 "시세가 좀 다르네"로 넘어가게 된다.
     */
    private val GRAMS_PER_TROY_OUNCE = BigDecimal("31.1034768")

    /** 1돈 = 3.75g */
    private val GRAMS_PER_DON = BigDecimal("3.75")

    private val FACTORS: Map<String, BigDecimal> = mapOf(
        "g" to BigDecimal.ONE,
        "gram" to BigDecimal.ONE,
        "grams" to BigDecimal.ONE,
        "그램" to BigDecimal.ONE,
        "돈" to GRAMS_PER_DON,
        "don" to GRAMS_PER_DON,
        "oz" to GRAMS_PER_TROY_OUNCE,
        "ozt" to GRAMS_PER_TROY_OUNCE,
        "온스" to GRAMS_PER_TROY_OUNCE,
    )

    /** 해석할 수 없으면 null. 호출부가 그 자산을 건너뛴다 */
    fun toGrams(quantity: BigDecimal, unit: String?): BigDecimal? {
        val factor = FACTORS[unit?.trim()?.lowercase()] ?: return null
        return quantity.multiply(factor)
    }
}
