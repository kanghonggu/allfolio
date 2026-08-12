package com.allfolio.fx.exchange

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Upbit ticker 응답 → USDT/KRW.
 *
 * HTTP에서 분리한 이유: 이 자리에 테스트가 없어서 동작할 수 없는 Binance 클라이언트가
 * 배포됐다. 순수 함수로 두면 실제 응답 픽스처로 네트워크 없이 회귀를 막는다.
 *
 * 응답 형태: [{"market":"KRW-USDT","trade_price":1408.0, ...}]
 *
 * 숫자를 BigDecimal로 만들 때 asText()를 거치는 이유는 asDouble()이 2진 부동소수점을
 * 경유하면서 정밀도를 잃기 때문이다. 환율은 평가액 전체에 곱해지는 값이라 그 오차가 증폭된다.
 */
@Component
class UpbitFxParser(private val objectMapper: ObjectMapper) {

    fun parse(body: String): BigDecimal {
        val root = try {
            objectMapper.readTree(body)
        } catch (e: Exception) {
            throw FxQuoteException("Upbit 응답이 JSON이 아닙니다", e)
        }

        if (!root.isArray || root.isEmpty) {
            throw FxQuoteException("Upbit 응답이 비어 있습니다")
        }

        val price = root[0].get("trade_price")
            ?: throw FxQuoteException("Upbit 응답에 trade_price가 없습니다")

        if (!price.isNumber) {
            throw FxQuoteException("Upbit trade_price가 숫자가 아닙니다: ${price.asText()}")
        }

        return BigDecimal(price.asText())
    }
}
