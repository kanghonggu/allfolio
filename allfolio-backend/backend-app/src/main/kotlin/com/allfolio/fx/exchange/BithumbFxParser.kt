package com.allfolio.fx.exchange

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Bithumb `ALL_KRW` 응답 → 심볼별 KRW.
 *
 * 응답 형태: {"status":"0000","data":{"BTC":{"closing_price":"89880000"}, ..., "date":"1786..."}}
 *
 * **status 검사가 이 파서의 핵심이다.** Bithumb은 조회에 실패해도 HTTP 200을 주고
 * status만 바꾼다(2026-08-12 실측: 잘못된 심볼 → 200 + {"status":"5500"}, data 없음).
 * WebClient의 retrieve()가 예외를 던져 주지 않으므로 여기서 막지 않으면
 * 조회 실패가 그대로 환율로 흘러든다.
 *
 * **data를 순회하지 않는다.** 실측 481개 키 중 하나가 코인이 아니라 `date` 문자열이라
 * 순회하면 거기서 깨진다. 아는 심볼을 키로 직접 꺼내면 그 문제가 구조적으로 사라지고,
 * 상장 코인이 늘어도 영향을 받지 않는다.
 *
 * 가격이 문자열로 온다는 점도 Upbit과 다르다.
 */
@Component
class BithumbFxParser(private val objectMapper: ObjectMapper) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val STATUS_OK = "0000"
    }

    fun parse(body: String): Map<String, BigDecimal> {
        val root = try {
            objectMapper.readTree(body)
        } catch (e: Exception) {
            throw FxQuoteException("Bithumb 응답이 JSON이 아닙니다", e)
        }

        val status = root.get("status")?.asText()
        if (status != STATUS_OK) {
            val message = root.get("message")?.asText()
            throw FxQuoteException("Bithumb status=$status message=$message")
        }

        val data = root.get("data")
            ?: throw FxQuoteException("Bithumb 응답에 data가 없습니다")

        val rates = mutableMapOf<String, BigDecimal>()
        for (symbol in FxSymbols.ALL) {
            val entry = data.get(symbol) ?: continue
            val raw = entry.get("closing_price")?.asText() ?: run {
                log.warn("[BithumbFx] {} closing_price가 없어 건너뜀", symbol)
                null
            } ?: continue

            val value = raw.toBigDecimalOrNull() ?: run {
                log.warn("[BithumbFx] {} closing_price가 숫자가 아니라 건너뜀: {}", symbol, raw)
                null
            } ?: continue

            rates[symbol] = value
        }

        if (rates.isEmpty()) {
            throw FxQuoteException("Bithumb 응답에서 아는 심볼을 찾지 못했습니다")
        }

        return rates
    }
}
