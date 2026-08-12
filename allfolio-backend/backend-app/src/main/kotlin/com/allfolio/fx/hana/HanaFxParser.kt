package com.allfolio.fx.hana

import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** 고시 한 행. 환율은 모두 통화 1단위 기준으로 정규화된 값이다. */
data class HanaFxRow(
    val currency: String,
    val baseRate: BigDecimal,
    val cashBuy: BigDecimal?,
    val cashSell: BigDecimal?,
    val remitSend: BigDecimal?,
    val remitReceive: BigDecimal?,
)

/**
 * @param skipped 컬럼 수·통화코드·숫자 파싱에 실패해 버린 행 수.
 *                조용히 삼키지 않고 호출자에게 보고한다.
 */
data class HanaFxSnapshot(
    val baseDate: LocalDate,
    val roundNo: Int,
    val rows: List<HanaFxRow>,
    val skipped: Int,
)

/**
 * 마크업 파싱 실패만이 아니라 **"하나은행 응답을 신뢰할 수 없다"** 를 폭넓게 뜻한다 —
 * 호출 실패·빈 본문·구조 부재는 물론, 기준일이 미래라 응답 자체가 앞뒤가 안 맞는 경우도 포함한다.
 * Task 11이 이 타입 하나로 502(외부 문제)를 판별하므로, 우리 쪽 판단 실패와 섞지 않는다.
 */
class HanaFxParseException(message: String) : RuntimeException("하나은행 응답 파싱 실패: $message")

/**
 * 하나은행 고시환율 화면 파서.
 *
 * 공식 API가 아니라 마크업이 바뀌면 예외가 아니라 조용히 빈 테이블이 온다.
 * 그래서 "구조가 아예 다르다"(기준일·회차·테이블 부재)는 예외로 올리고,
 * "행 하나가 이상하다"는 버리되 센다. 둘을 섞으면 전체 실패와 부분 실패를 구분할 수 없다.
 *
 * 테이블은 위치가 아니라 **내용**으로 고른다. 첫 번째 테이블을 집으면 앞에 놓인 무행 레이아웃
 * 테이블에 걸려 rows=0·skipped=0·WARN 없는 "깨끗해 보이는" 빈 스냅샷이 나온다 —
 * 이 클래스가 막겠다고 한 바로 그 조용한 전체 실패다. 조각(fragment) 응답이라 첫 테이블이
 * 환율 테이블일 것이라는 기대는 호출자가 보내는 요청 모양에 의존하므로 여기서 의존을 끊는다.
 *
 * 컬럼 순서(11개): 통화 · 현찰사실때(환율, 스프레드) · 현찰파실때(환율, 스프레드) ·
 * 송금보낼때 · 송금받을때 · 외화수표파실때 · 매매기준율 · 환가료율 · 미화환산율
 */
@Component
class HanaFxParser {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val COLUMN_COUNT = 11
        /** 제로패딩은 확인된 사실이지만, 패딩이 빠진 날 "기준일을 못 찾았다"고 원인을 잘못 대지 않게 1~2자리를 받는다 */
        private val BASE_DATE = Regex("""기준일\s*:\s*(\d{4})년\s*(\d{1,2})월\s*(\d{1,2})일""")
        private val ROUND_NO = Regex("""\((\d+)회차\)""")
        private val CURRENCY_CODE = Regex("""([A-Z]{3})""")

        /** 통화명에 (100)이 붙으면 100단위 고시다 — JPY·IDR·VND 등 */
        private val PER_HUNDRED = Regex("""\(\s*100\s*\)""")

        // 컬럼 인덱스 (0 = 통화)
        private const val CASH_BUY = 1
        private const val CASH_SELL = 3
        private const val REMIT_SEND = 5
        private const val REMIT_RECEIVE = 6
        private const val BASE_RATE = 8
    }

    fun parse(html: String): HanaFxSnapshot {
        val doc = Jsoup.parse(html)
        val text = doc.text()

        val dateMatch = BASE_DATE.find(text)
            ?: throw HanaFxParseException("기준일을 찾지 못했습니다")
        val roundMatch = ROUND_NO.find(text)
            ?: throw HanaFxParseException("고시 회차를 찾지 못했습니다")

        // 정규식이 맞아도 13월 45일이면 LocalDate.of가 raw DateTimeException을 던진다.
        // 수집기는 HanaFxParseException만 잡으므로 도메인 예외로 바꿔 올린다.
        val baseDate = runCatching {
            LocalDate.of(
                dateMatch.groupValues[1].toInt(),
                dateMatch.groupValues[2].toInt(),
                dateMatch.groupValues[3].toInt(),
            )
        }.getOrNull() ?: throw HanaFxParseException("기준일이 올바른 날짜가 아닙니다: ${dateMatch.value}")

        val roundNo = roundMatch.groupValues[1].toInt()

        val table = doc.select("table")
            .firstOrNull { t -> t.select("tr").any { it.select("td").size == COLUMN_COUNT } }
            ?: throw HanaFxParseException("11컬럼 행이 있는 환율 테이블을 찾지 못했습니다")

        var skipped = 0
        val rows = table.select("tr").mapNotNull { tr ->
            val cells = tr.select("td").map { it.text().trim() }
            if (cells.size != COLUMN_COUNT) {
                // th만 있는 헤더 행은 td가 0개다 — 이상 행이 아니므로 세지 않는다
                if (cells.isNotEmpty()) {
                    skipped++
                    log.warn("[하나은행] 행 건너뜀 — 컬럼 {}개 name={}", cells.size, cells[0])
                }
                return@mapNotNull null
            }
            toRow(cells) ?: run { skipped++; null }
        }

        if (skipped > 0) log.warn("[하나은행] 버린 행 {}건 baseDate={} round={}", skipped, baseDate, roundNo)
        return HanaFxSnapshot(baseDate, roundNo, rows, skipped)
    }

    /** 버리는 자리마다 통화명과 문제된 원문 셀을 남긴다 — skipped는 개수뿐이라 상세는 로그가 유일한 단서다 */
    private fun toRow(cells: List<String>): HanaFxRow? {
        val name = cells[0]
        val code = CURRENCY_CODE.find(name)?.groupValues?.get(1)
        if (code == null) {
            log.warn("[하나은행] 행 건너뜀 — 통화 코드 없음 name={}", name)
            return null
        }
        val divisor = if (PER_HUNDRED.containsMatchIn(name)) BigDecimal(100) else BigDecimal.ONE

        // 매매기준율이 없으면 그 행은 쓸모가 없다 — 평가·화면 양쪽이 이 값을 쓴다
        val baseRate = number(cells[BASE_RATE], divisor)
        if (baseRate == null) {
            log.warn("[하나은행] 행 건너뜀 — 매매기준율 이상 name={} 매매기준율={}", name, cells[BASE_RATE])
            return null
        }

        return HanaFxRow(
            currency = code,
            baseRate = baseRate,
            cashBuy = number(cells[CASH_BUY], divisor),
            cashSell = number(cells[CASH_SELL], divisor),
            remitSend = number(cells[REMIT_SEND], divisor),
            remitReceive = number(cells[REMIT_RECEIVE], divisor),
        )
    }

    /** 스프레드·환가료율·미화환산율에는 쓰지 않는다 — %·비율이라 100으로 나누면 안 된다 */
    private fun number(raw: String, divisor: BigDecimal): BigDecimal? =
        runCatching { BigDecimal(raw.replace(",", "")) }
            .getOrNull()
            ?.takeIf { it > BigDecimal.ZERO }
            ?.divide(divisor, 4, RoundingMode.HALF_UP)
}
