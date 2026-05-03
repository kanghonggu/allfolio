package com.allfolio.unifiedasset.domain.account

enum class AccountProvider {
    // 암호화폐 거래소
    BINANCE,  // 바이낸스
    UPBIT,    // 업비트
    BITHUMB,  // 빗썸
    COINONE,  // 코인원
    BYBIT,    // 바이빗
    OKX,      // OKX
    // 국내 증권사
    KIS,      // 한국투자증권
    KIWOOM,   // 키움증권
    STOCK,    // 기타 국내 증권사 (레거시)
    // 기타
    WALLET,   // 블록체인 지갑
    CSV,      // CSV 파일 업로드
    MANUAL,   // 수동 입력
}
