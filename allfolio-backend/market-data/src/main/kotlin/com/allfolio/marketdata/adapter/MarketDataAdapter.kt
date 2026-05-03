package com.allfolio.marketdata.adapter

interface MarketDataAdapter {
    val exchange: String
    fun connect()
    fun disconnect()
    fun subscribe(symbols: List<String>)
    fun isConnected(): Boolean
}
