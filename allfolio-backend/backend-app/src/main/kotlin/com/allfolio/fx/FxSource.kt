package com.allfolio.fx

import java.math.BigDecimal
import java.time.LocalDate

/**
 * KRW 환산에 실제로 쓰인 환율 한 건과 그 출처 (AF-105).
 *
 * [baseDate]·[roundNo]는 하나은행 고시일 때만 채워진다. 거래소 시세·코인 시세에는
 * 대조할 수 있는 "고시 회차"라는 개념이 없어서, 있는 척하면 사용자가 대조하러 갔다가
 * 아무것도 못 찾는다.
 */
data class FxSource(
    /** ISO 통화 코드 (대문자) */
    val currency: String,
    /** 1단위당 KRW */
    val rate: BigDecimal,
    /** 화면에 그대로 노출되는 한국어 문구 */
    val source: String,
    val baseDate: LocalDate?,
    val roundNo: Int?,
)
