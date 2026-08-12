package com.allfolio.fx.exchange

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Bithumb public ticker 응답 → USDT/KRW.
 *
 * 응답 형태: {"status":"0000","data":{"closing_price":"1409", ...}}
 *
 * **status 검사가 이 파서의 핵심이다.** Bithumb은 조회에 실패해도 HTTP 200을 주고
 * status만 바꾼다(2026-08-12 실측: 잘못된 심볼 → 200 + {"status":"5500"}, data 없음).
 * WebClient의 retrieve()가 예외를 던져 주지 않으므로 여기서 막지 않으면
 * 조회 실패가 그대로 환율로 흘러든다.
 *
 * 가격이 문자열로 온다는 점도 Upbit과 다르다.
 */
@Component
class BithumbFxParser(private val objectMapper: ObjectMapper) {

    companion object {
        private const val STATUS_OK = "0000"
    }

    fun parse(body: String): BigDecimal {
        val root = try {
            objectMapper.readTree(body)
        } catch (e: Exception) {
            throw FxQuoteException("Bithumb 응답이 JSON이 아닙니다", e)
        }

        val status = root.get("status")?.asText()
        if (status != STATUS_OK) {
            throw FxQuoteException("Bithumb status=$status")
        }

        val data = root.get("data")
            ?: throw FxQuoteException("Bithumb 응답에 data가 없습니다")

        val raw = data.get("closing_price")?.asText()
            ?: throw FxQuoteException("Bithumb 응답에 closing_price가 없습니다")

        return try {
            BigDecimal(raw)
        } catch (e: NumberFormatException) {
            throw FxQuoteException("Bithumb closing_price가 숫자가 아닙니다: $raw", e)
        }
    }
}
