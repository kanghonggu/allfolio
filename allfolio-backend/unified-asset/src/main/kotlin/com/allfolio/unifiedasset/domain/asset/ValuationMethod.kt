package com.allfolio.unifiedasset.domain.asset

enum class ValuationMethod {
    MARKET_PRICE,  // 실시간 시장가
    BALANCE,       // 잔고 기준 (코인/주식 수량 × 현재가)
    USER_INPUT,    // 사용자 직접 입력
}
