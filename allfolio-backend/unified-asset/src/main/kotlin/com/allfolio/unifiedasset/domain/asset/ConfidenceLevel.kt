package com.allfolio.unifiedasset.domain.asset

enum class ConfidenceLevel {
    HIGH,    // 실시간 API
    MEDIUM,  // 최근 데이터 기반
    LOW,     // 수동 입력 또는 오래된 데이터
}
