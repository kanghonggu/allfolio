package com.allfolio.unifiedasset.domain.asset

enum class AssetType {
    STOCK,        // 주식
    CRYPTO,       // 암호화폐
    REAL_ESTATE,  // 부동산 (소유)
    JEONSE,       // 전세보증금 (반환 청구권)
    VEHICLE,      // 자동차
    GOLD,         // 금
    WATCH,        // 시계 (W5) — 평가는 watchpricedata 호가 중앙값
    CASH,         // 현금
    ETC,          // 기타
}
