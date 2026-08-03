package com.allfolio.unifiedasset.domain.common

/**
 * 지원 통화 화이트리스트 (QA P2) — FE 통화 select 목록과 동기 유지.
 * FX 환산은 KRW/USD/USDT만 실환율, BTC/ETH는 기존 동작 유지(환산 한계는 별도 태스크).
 */
object Currencies {
    val SUPPORTED = setOf("KRW", "USD", "USDT", "BTC", "ETH")

    /** 트림·대문자 정규화 후 화이트리스트 검증. 미지원 코드는 IllegalArgumentException(→400). */
    fun normalize(code: String): String {
        val normalized = code.trim().uppercase()
        require(normalized in SUPPORTED) {
            "지원하지 않는 통화입니다: '$code' (지원: ${SUPPORTED.joinToString(", ")})"
        }
        return normalized
    }
}

/** 사용자 입력 텍스트에서 HTML 태그·꺾쇠를 제거한다 (서버 사이드 XSS 방어, QA P2). */
fun sanitizeUserText(text: String): String =
    text.replace(Regex("<[^>]*>"), "")
        .replace("<", "")
        .replace(">", "")
        .trim()
