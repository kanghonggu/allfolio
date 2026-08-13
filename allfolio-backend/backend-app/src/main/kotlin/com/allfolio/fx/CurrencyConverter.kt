package com.allfolio.fx

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 통화 변환 유틸리티
 *
 * KRW 기준 통합 — 모든 가격을 KRW 원화 기준으로 환산.
 * 지원 통화: KRW(1:1) · USD(하나은행 매매기준율) · USDT(거래소 시세) · BTC/ETH(코인 시세)
 * 미지원 통화: KRW 그대로 반환 + 경고 로그
 */
@Component
class CurrencyConverter(
    private val fxRateService: FxRateService,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    /**
     * 금액을 KRW로 환산한다.
     *
     * **분기표를 여기 두지 않고 [sourceOf] 하나에만 둔다.** 화면에 밝히는 환율과 환산에 쓰는
     * 환율이 서로 다른 `when`에서 나오면, 누가 통화를 추가하며 한쪽만 고치는 날 조용히 갈라진다.
     * 그러면 화면이 틀린 근거를 자신 있게 제시하게 되고, 신뢰를 만들려던 표기가 반대로 동작한다.
     * 두 경로를 같은 코드로 묶어 드리프트를 규율이 아니라 구조로 막는다.
     *
     * @param amount   환산 전 금액
     * @param currency "KRW" | "USD" | "USDT" | "BTC" | "ETH" (대소문자 구분 없음, 공백은 못 봐준다)
     * @return KRW 환산금액 (소수점 0자리, HALF_UP 반올림)
     */
    fun toKrw(amount: BigDecimal, currency: String): BigDecimal {
        if (currency.uppercase() == "KRW") return amount
        val source = sourceOf(currency)
            ?: run {
                log.warn("[CurrencyConverter] unsupported currency={} — returning as-is", currency)
                return amount
            }
        return (amount * source.rate).setScale(0, RoundingMode.HALF_UP)
    }

    /**
     * 이 통화를 KRW로 바꿀 때 쓰는 환율과 그 출처 (AF-105).
     *
     * **KRW와 미지원 통화가 똑같이 null인 것은 의도다.** 둘 다 환산이 일어나지 않았고,
     * 일어나지 않은 환산에는 밝힐 출처가 없다.
     */
    fun sourceOf(currency: String): FxSource? =
        when (val code = currency.uppercase()) {
            "KRW" -> null
            // AF-99: 법정통화 USD는 하나은행 공식 매매기준율.
            // 고시가 없으면 근사로 떨어지지만 표기를 없애지는 않는다 — 문구로 밝힌다.
            //
            // 폴백은 getUsdToKrw()를 부른다. getUsdtToKrw()를 직접 부르면 고시 조회를 한 번
            // 아낄 수 있지만, getUsdToKrw()는 인터페이스의 공개 계약이고 구현체가 usdQuoteRef()
            // 없이 그것만 오버라이드할 수 있다. 우회하면 "USD 환율"의 정의가 구현체마다 갈린다.
            "USD" -> fxRateService.usdQuoteRef()
                ?.let { FxSource("USD", it.rate, "하나은행 매매기준율", it.baseDate, it.roundNo) }
                ?: FxSource("USD", fxRateService.getUsdToKrw(), "고시 없음 · 거래소 시세 근사", null, null)
            // 스테이블코인은 거래소 시세를 유지한다 — 김치 프리미엄은 부정확이 아니라
            // 거래소에 실제 USDT를 들고 있는 사용자에게 실현 가능한 값이다
            "USDT" -> FxSource("USDT", fxRateService.getUsdtToKrw(), "거래소 시세", null, null)
            // QA P3: BTC/ETH도 코인당 KRW 시세로 환산 — 1:1 폴백은 0.5 BTC를 0.5원으로 축소하던 버그
            //
            // **시세가 없으면 예외를 그대로 흘린다. null로 바꾸지 말 것.**
            // toKrw가 이 함수 위에 서 있고 거기서 null은 "미지원 통화 → 원금 그대로"를 뜻한다.
            // 여기서 예외를 삼켜 null을 주면 0.5 BTC가 0.5원이 되어, 위 주석이 말하는 그 버그가
            // 그대로 되살아난다. 대시보드가 500이 나는 편이 6천만 배 저평가보다 낫다.
            "BTC", "ETH" -> FxSource(code, fxRateService.getCryptoToKrw(code), "코인 시세", null, null)
            else -> null
        }
}
