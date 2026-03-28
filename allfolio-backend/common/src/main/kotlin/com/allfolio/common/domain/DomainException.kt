package com.allfolio.common.domain

open class DomainException(
    val errorCode: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
