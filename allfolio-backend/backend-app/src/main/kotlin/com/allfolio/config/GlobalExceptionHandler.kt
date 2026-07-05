package com.allfolio.config

import com.allfolio.common.crypto.SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE
import com.allfolio.common.crypto.SensitiveDataReconnectionRequiredException
import com.allfolio.common.crypto.requiresSensitiveDataReconnection
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(e: NoSuchElementException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(mapOf("error" to (e.message ?: "Not found")))

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(e: IllegalArgumentException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(mapOf("error" to (e.message ?: "Bad request")))

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrity(e: DataIntegrityViolationException): ResponseEntity<Map<String, String>> {
        log.error("Data integrity violation", e)
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(mapOf("error" to "입력값이 올바르지 않습니다. 값의 길이나 형식을 확인해주세요."))
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(e: ResponseStatusException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(e.statusCode)
            .body(mapOf("error" to (e.reason ?: "요청을 처리할 수 없습니다.")))

    @ExceptionHandler(SensitiveDataReconnectionRequiredException::class)
    fun handleSensitiveDataReconnectionRequired(
        e: SensitiveDataReconnectionRequiredException,
    ): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(mapOf("error" to (e.message ?: SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE)))

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<Map<String, String>> {
        if (e.requiresSensitiveDataReconnection()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(mapOf("error" to SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE))
        }
        log.error("Unexpected error", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(mapOf("error" to "서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요."))
    }
}
