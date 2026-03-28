package com.allfolio.risk.domain

import com.allfolio.common.domain.DomainException

class RiskException(
    errorCode: String,
    message: String,
    cause: Throwable? = null,
) : DomainException(errorCode, message, cause) {

    companion object {
        fun emptyReturns() =
            RiskException(
                "RISK_EMPTY_RETURNS",
                "Daily returns list must not be empty",
            )

        fun insufficientData(required: Int, actual: Int) =
            RiskException(
                "RISK_INSUFFICIENT_DATA",
                "Insufficient data for risk calculation: required=$required, actual=$actual",
            )
    }
}
