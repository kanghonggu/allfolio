package com.allfolio.unifiedasset.application.usecase

/** 통화 코드 → 지역 라벨(근사). 자산에 국가/거래소 필드가 없어 통화 기준 추정. */
object CurrencyRegionMapper {
    private val MAP = mapOf(
        "KRW" to "국내", "USD" to "미국", "JPY" to "일본", "EUR" to "유럽",
        "CNY" to "중국", "HKD" to "홍콩", "GBP" to "영국",
    )

    fun regionOf(currency: String?): String =
        currency?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let { MAP[it] ?: "기타" } ?: "기타"
}
