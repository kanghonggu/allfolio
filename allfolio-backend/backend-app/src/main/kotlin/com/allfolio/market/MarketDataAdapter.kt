package com.allfolio.market

/**
 * 실시간 시세 WebSocket Adapter 인터페이스
 *
 * 구현체:
 *   BinanceWsAdapter — OkHttp WebSocket, btcusdt@trade 스트림
 *   TossWsAdapter    — 추후 구현
 */
interface MarketDataAdapter {
    val exchange: String
    fun connect()
    fun disconnect()
    fun subscribe(symbols: List<String>)
    fun isConnected(): Boolean
}
