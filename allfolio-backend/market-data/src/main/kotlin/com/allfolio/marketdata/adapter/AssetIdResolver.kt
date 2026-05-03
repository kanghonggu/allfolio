package com.allfolio.marketdata.adapter

import java.util.UUID

/**
 * exchange + symbol → 결정론적 UUID 변환
 *
 * 규칙: UUID.nameUUIDFromBytes("{exchange}:{symbol}")
 *   - 동일 exchange+symbol은 항상 동일 UUID
 *   - backend-app의 BinanceTradeMapper, KisTradeMapper와 동일 방식 사용
 */
object AssetIdResolver {

    fun resolve(exchange: String, symbol: String): String =
        UUID.nameUUIDFromBytes("$exchange:$symbol".toByteArray()).toString()

    // Binance는 심볼 앞부분이 baseAsset (BTCUSDT → BTC)
    // backend-app BinanceTradeMapper와 동일한 키 생성을 위해 baseAsset 추출
    fun resolveBinance(symbol: String): String {
        val base = extractBaseAsset(symbol)
        return UUID.nameUUIDFromBytes("BINANCE:$base".toByteArray()).toString()
    }

    private fun extractBaseAsset(symbol: String): String {
        val quotes = listOf("USDT", "BTC", "ETH", "BNB", "BUSD", "USDC")
        return quotes.firstOrNull { symbol.endsWith(it) }
            ?.let { symbol.removeSuffix(it) }
            ?: symbol
    }
}
