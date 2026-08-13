package com.allfolio.fx.upbit

import com.allfolio.fx.DailyRate
import com.allfolio.fx.SourceFetch
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate

/** Upbit 일봉 조회가 실패했다는 신호. */
class UpbitCandleException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Upbit 일봉 응답 → 일별 종가.
 *
 * 응답 형태(최신순 내림차순):
 *   [{"candle_date_time_kst":"2026-08-01T09:00:00","trade_price":90557000.0}, ...]
 *
 * HTTP에서 분리한 이유는 앞선 FX 작업과 같다 — 파서에 테스트가 없어서 동작할 수 없는
 * 클라이언트가 배포된 적이 있고, 이 시리즈에서 파서 테스트가 실제 회귀를 두 번 잡았다.
 *
 * **UTC가 아니라 KST 날짜를 쓴다.** `cash_flow.flow_date`가 KST 기준이라 utc를 쓰면
 * 어떤 날은 하루 밀린 환율이 붙는다.
 *
 * 빈 배열은 예외가 아니다 — 구간에 데이터가 없는 건 정상이고, 중단 판단은 백필 서비스가 한다.
 * 버린 행 수는 [SourceFetch.skipped]로 조용히 삼키지 않고 그대로 나간다.
 */
@Component
class UpbitCandleParser(private val objectMapper: ObjectMapper) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun parse(body: String): SourceFetch {
        val root = try {
            objectMapper.readTree(body)
        } catch (e: Exception) {
            throw UpbitCandleException("Upbit 일봉 응답이 JSON이 아닙니다", e)
        }

        // 오류 응답은 배열이 아니라 객체로 온다 — 배열 가정을 먼저 확인한다
        if (!root.isArray) {
            throw UpbitCandleException("Upbit 일봉 응답이 배열이 아닙니다: ${body.take(120)}")
        }

        var skipped = 0
        val rates = mutableListOf<DailyRate>()
        for (node in root) {
            val kst = node.get("candle_date_time_kst")?.asText()
            if (kst == null || kst.length < 10) {
                log.warn("[UpbitCandle] candle_date_time_kst가 없어 건너뜀")
                skipped++
                continue
            }

            val price = node.get("trade_price")
            if (price == null || !price.isNumber) {
                log.warn("[UpbitCandle] {} trade_price가 없거나 숫자가 아니라 건너뜀", kst)
                skipped++
                continue
            }

            val date = try {
                LocalDate.parse(kst.substring(0, 10))
            } catch (e: Exception) {
                log.warn("[UpbitCandle] 날짜를 읽지 못해 건너뜀: {}", kst)
                skipped++
                continue
            }

            val rate = BigDecimal(price.asText())
            if (rate <= BigDecimal.ZERO) {
                log.warn("[UpbitCandle] {} trade_price가 0 이하라 건너뜀: {}", kst, rate)
                skipped++
                continue
            }

            rates += DailyRate(date, rate)
        }
        return SourceFetch(rates, skipped)
    }
}
