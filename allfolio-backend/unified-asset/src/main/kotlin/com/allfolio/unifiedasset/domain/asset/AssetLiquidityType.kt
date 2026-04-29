package com.allfolio.unifiedasset.domain.asset

enum class AssetLiquidityType {
    LIQUID,    // 주식·코인 — 기관 지표 계산 대상
    ILLIQUID,  // 전세·부동산·차량 — Net Worth 합산 전용
}
