package com.allfolio.unifiedasset.domain.asset

enum class AssetSourceType {
    EXCHANGE_API,  // 거래소 API (Binance 등)
    WALLET,        // 블록체인 지갑 조회
    STOCK_API,     // 증권사 API
    CSV,           // CSV 파일 업로드
    MANUAL,        // 수동 입력
}
