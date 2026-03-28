package com.allfolio.snapshot.domain

import com.allfolio.common.domain.DomainException
import java.math.BigDecimal

class PerformanceException(
    errorCode: String,
    message: String,
    cause: Throwable? = null,
) : DomainException(errorCode, message, cause) {

    companion object {
        fun negativeNav(nav: BigDecimal) =
            PerformanceException(
                "PERFORMANCE_NEGATIVE_NAV",
                "NAV must be non-negative: $nav",
            )

        fun negativeYesterdayNav(nav: BigDecimal) =
            PerformanceException(
                "PERFORMANCE_NEGATIVE_YESTERDAY_NAV",
                "Yesterday NAV must be non-negative: $nav",
            )
    }
}
