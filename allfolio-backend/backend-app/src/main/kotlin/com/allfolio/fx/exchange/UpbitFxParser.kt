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
 * **숫자 처리 — asText()가 double을 피해 주지는 않는다.**
 * 이 주석은 원래 "asText()를 거치는 이유는 asDouble()이 부동소수점을 경유해 정밀도를 잃기
 * 때문"이라고 적혀 있었는데 사실이 아니다. `readTree`는 소수점이 있는 JSON 숫자를 전부
 * `DoubleNode`로 만들므로(실측 확인) **double 변환은 asText()를 부르기 전에 이미 끝나 있다.**
 * 여기서 asText()와 decimalValue()는 결과가 같다.
 *
 * 그런데도 값은 정확하다. `Double.toString`은 그 double로 되돌아가는 최단 표기를 내놓고,
 * 우리가 다루는 값(1,409 · 90,047,000 · 2,680,000)은 2^53보다 한참 작아 double에서 정확하다.
 * `1408.55`도 `"1408.55"`로 되돌아온다.
 *
 * **진짜 피해야 하는 것은 `BigDecimal(node.asDouble())`이다.** BigDecimal(double) 생성자는
 * 0.1을 0.1000000000000000055511151231257827로 펼친다. asText()·decimalValue()는 둘 다
 * 그 생성자를 타지 않으므로 안전하다.
 *
 * 진짜로 double을 안 거치게 하려면 ObjectMapper에 USE_BIG_DECIMAL_FOR_FLOATS를 켜야 하는데,
 * 이 값들에는 이득이 없어 켜지 않았다.
 *
 * 부작용 하나: BTC가 로그에 `9.0047E+7`로 찍힌다. Double.toString이 1e7 이상에서 지수 표기를
 * 쓰기 때문이고 값 자체는 90,047,000이 맞다.
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
