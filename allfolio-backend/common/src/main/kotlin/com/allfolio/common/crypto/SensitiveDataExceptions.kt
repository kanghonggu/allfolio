package com.allfolio.common.crypto

const val SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE: String =
    "저장된 인증 정보를 읽을 수 없습니다. 다시 연동해주세요."

open class SensitiveDataReconnectionRequiredException(
    message: String = SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class LegacyPlaintextDetectedException(
    detail: String = "Legacy plaintext sensitive value detected",
) : SensitiveDataReconnectionRequiredException(
    SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE,
    IllegalStateException(detail),
)

class EncryptedSensitiveDataReadException(
    cause: Throwable,
) : SensitiveDataReconnectionRequiredException(
    SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE,
    cause,
)

fun Throwable.requiresSensitiveDataReconnection(): Boolean =
    generateSequence(this as Throwable?) { it.cause }
        .any { it is SensitiveDataReconnectionRequiredException }
