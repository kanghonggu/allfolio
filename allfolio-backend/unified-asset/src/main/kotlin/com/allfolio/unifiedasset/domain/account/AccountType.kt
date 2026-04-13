package com.allfolio.unifiedasset.domain.account

enum class AccountType {
    EXCHANGE,  // 거래소 (Binance 등)
    STOCK,     // 증권사
    WALLET,    // 블록체인 지갑
    BANK,      // 은행 (현금)
    MANUAL,    // 수동 자산 계좌
}
