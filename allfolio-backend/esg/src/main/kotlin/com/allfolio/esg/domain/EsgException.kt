package com.allfolio.esg.domain

import com.allfolio.common.domain.DomainException

class EsgException(
    errorCode: String,
    message: String,
) : DomainException(errorCode, message) {

    companion object {
        fun emptyAssets() = EsgException(
            "ESG_EMPTY_ASSETS",
            "자산 목록이 비어있어 ESG 점수를 계산할 수 없습니다",
        )
    }
}
