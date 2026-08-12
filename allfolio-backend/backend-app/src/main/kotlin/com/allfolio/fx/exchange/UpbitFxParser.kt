package com.allfolio.fx.exchange

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Upbit ticker 응답 → 심볼별 KRW.
 *
 * HTTP에서 분리한 이유: 이 자리에 테스트가 없어서 동작할 수 없는 Binance 클라이언트가
 * 배포됐다. 순수 함수로 두면 실제 응답 픽스처로 네트워크 없이 회귀를 막는다.
 *
 * 응답 형태: [{"market":"KRW-USDT","trade_price":1409.0}, {"market":"KRW-BTC", ...}]
 *
 * **인덱스가 아니라 `market` 필드로 매칭한다.** Upbit이 markets= 순서를 지킨다는 보장이 없고,
 * 뒤바뀌면 BTC 가격이 USDT 자리에 들어가 자산이 6만 배가 된다.
 *
 * 숫자를 BigDecimal로 만들 때 asText()를 거치는 이유는 asDouble()이 2진 부동소수점을
 * 경유하면서 정밀도를 잃기 때문이다.
 */
@Component
class UpbitFxParser(private val objectMapper: ObjectMapper) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** "KRW-USDT" → "USDT" */
        private const val KRW_PREFIX = "KRW-"
    }

    fun parse(body: String): Map<String, BigDecimal> {
        val root = try {
            objectMapper.readTree(body)
        } catch (e: Exception) {
            throw FxQuoteException("Upbit 응답이 JSON이 아닙니다", e)
        }

        if (!root.isArray || root.isEmpty) {
            throw FxQuoteException("Upbit 응답이 비어 있습니다")
        }

        val rates = mutableMapOf<String, BigDecimal>()
        for (node in root) {
            val market = node.get("market")?.asText() ?: continue
            if (!market.startsWith(KRW_PREFIX)) continue

            val symbol = market.removePrefix(KRW_PREFIX)
            if (symbol !in FxSymbols.ALL) continue

            val price = node.get("trade_price")
            if (price == null || !price.isNumber) {
                // 한 심볼이 이상하다고 나머지를 버리지 않는다. 못 채운 심볼은 다음 소스가 맡는다.
                log.warn("[UpbitFx] {} trade_price가 없거나 숫자가 아니라 건너뜀", market)
                continue
            }

            rates[symbol] = BigDecimal(price.asText())
        }

        if (rates.isEmpty()) {
            throw FxQuoteException("Upbit 응답에서 아는 마켓을 찾지 못했습니다")
        }

        return rates
    }
}
