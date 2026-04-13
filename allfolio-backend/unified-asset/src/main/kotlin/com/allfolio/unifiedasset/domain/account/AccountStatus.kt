package com.allfolio.unifiedasset.domain.account

enum class AccountStatus {
    ACTIVE,    // 정상
    SYNCING,   // 동기화 중
    ERROR,     // 오류
    INACTIVE,  // 비활성
}
