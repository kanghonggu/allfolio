package com.allfolio.report.domain

import java.math.BigDecimal

data class AssetEsgRow(
    val name: String,
    val type: String,
    val currentValue: BigDecimal,
    val weight: BigDecimal,         // 포트폴리오 내 비중 (0~1, 소수)
    val environmental: BigDecimal,
    val social: BigDecimal,
    val governance: BigDecimal,
    val total: BigDecimal,
    val rating: String,
)
