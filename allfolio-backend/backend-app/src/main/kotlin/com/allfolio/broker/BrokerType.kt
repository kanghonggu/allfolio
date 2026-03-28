package com.allfolio.broker

/**
 * 지원 브로커 타입
 *
 * 새 증권사 추가 시: enum value 추가 + BrokerAdapter 구현체 등록만 하면 됨.
 */
enum class BrokerType(
    val displayName: String,
    val isCrypto: Boolean = false,
) {
    BINANCE("Binance", isCrypto = true),
    TOSS("토스증권"),
    SAMSUNG("삼성증권"),
    ;

    companion object {
        fun from(value: String): BrokerType =
            entries.first { it.name.equals(value, ignoreCase = true) }
    }
}
