package com.allfolio.config

import com.allfolio.common.crypto.SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE
import com.allfolio.common.crypto.SensitiveDataReconnectionRequiredException
import com.allfolio.common.crypto.requiresSensitiveDataReconnection
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.time.format.DateTimeParseException

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

    /** 존재하지 않는 경로/정적 리소스 (QA P0 #3) — 500이 아니라 404 */
    @ExceptionHandler(NoResourceFoundException::class, NoHandlerFoundException::class)
    fun handleNoResource(e: Exception): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(mapOf("error" to "요청한 경로를 찾을 수 없습니다."))

    /** 바인딩·파싱 실패(깨진 JSON, 잘못된 날짜/타입, 필수 파라미터·헤더 누락) — 400 */
    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class,
        MissingServletRequestParameterException::class,
        MissingRequestHeaderException::class,
        DateTimeParseException::class,
    )
    fun handleUnparseable(e: Exception): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(mapOf("error" to "요청 형식이 올바르지 않습니다. 입력값을 확인해주세요."))

    /** @Valid 검증 실패 — 422 + 필드별 사유 */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<Map<String, String>> {
        val detail = e.bindingResult.fieldErrors
            .joinToString(", ") { "${it.field}: ${it.defaultMessage}" }
            .ifBlank { "입력값이 올바르지 않습니다." }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(mapOf("error" to detail))
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(e: HttpRequestMethodNotSupportedException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .body(mapOf("error" to "지원하지 않는 HTTP 메서드입니다."))

    /** 대사↔동기화 상호 배제 충돌 (P2 #17) — 일시적 상태라 409로 재시도 유도 */
    @ExceptionHandler(com.allfolio.reconciliation.application.SyncInProgressException::class)
    fun handleSyncInProgress(e: com.allfolio.reconciliation.application.SyncInProgressException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(mapOf("error" to (e.message ?: "Conflict")))

    /** 마감 워크플로우 실행 중복 (P3 #23) — 409 */
    @ExceptionHandler(com.allfolio.workflow.application.ClosingInProgressException::class)
    fun handleClosingInProgress(e: com.allfolio.workflow.application.ClosingInProgressException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(mapOf("error" to (e.message ?: "Conflict")))

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
