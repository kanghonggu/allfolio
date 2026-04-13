package com.allfolio.unifiedasset.domain.account

enum class AccountProvider {
    BINANCE,  // 코인 거래소
    STOCK,    // 국내 증권사
    WALLET,   // 블록체인 지갑
    CSV,      // CSV 파일 업로드
    MANUAL,   // 수동 입력
}
