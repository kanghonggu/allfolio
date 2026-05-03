package com.allfolio.unifiedasset.application.usecase

data class ConnectionTestResult(
    val success: Boolean,
    val message: String,
    val assetCount: Int = 0,
)
