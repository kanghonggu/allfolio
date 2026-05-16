package com.allfolio.esg.domain

import java.math.BigDecimal

data class EsgScore(
    val environmental: BigDecimal,  // 0~100
    val social: BigDecimal,         // 0~100
    val governance: BigDecimal,     // 0~100
    val total: BigDecimal,          // 가중 평균 (E×0.35 + S×0.30 + G×0.35)
    val rating: String,             // "A+", "A", "B+", "B", "C+", "C"
)
