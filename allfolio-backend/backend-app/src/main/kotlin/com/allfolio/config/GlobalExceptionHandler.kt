package com.allfolio.config

import com.allfolio.common.crypto.SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE
import com.allfolio.common.crypto.SensitiveDataReconnectionRequiredException
import com.allfolio.common.crypto.requiresSensitiveDataReconnection
import com.fasterxml.jackson.databind.exc.MismatchedInputException
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
    fun handleUnparseable(e: Exception): ResponseEntity<Map<String, String>> {
        // 역직렬화 실패도 가능하면 어느 필드가 문제인지 알려준다 (QA 후속 #5)
        val field = ((e as? HttpMessageNotReadableException)?.cause as? MismatchedInputException)
            ?.path?.mapNotNull { it.fieldName }?.joinToString(".")?.takeIf { it.isNotBlank() }
        val message =
            if (field != null) "'$field' 값이 없거나 형식이 올바르지 않습니다. 입력값을 확인해주세요."
            else "요청 형식이 올바르지 않습니다. 입력값을 확인해주세요."
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(mapOf("error" to message))
    }

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

    /**
     * ECOS(한국은행) 환율 조회 실패 (AF-100).
     *
     * `code`를 본문에 함께 싣는 이유는 운영자의 다음 행동이 code마다 갈리기 때문이다 —
     * `INFO-200`(해당 기간 자료 없음)은 구간을 좁혀 재실행, `CONN`/`HTTP-5xx`는 그냥 재시도,
     * `NO_KEY`/`NO_SERIES`는 Render 환경변수 등록이다. 이 분기를 [ResponseStatusException]으로
     * 옮길 수 없어 전용 핸들러를 둔다: reason 하나뿐이라 code를 실을 자리가 없다.
     *
     * **상태 코드가 둘로 갈린다.** 기본은 502다 — 상류가 실패한 것이지 우리 서버가 깨진 게 아니다.
     * 다만 `NO_KEY`/`NO_SERIES`는 **우리 설정 누락**이라 500으로 보낸다.
     * `EcosStatisticSearchClient`가 이 둘을 [IllegalArgumentException](→400)이 아니라
     * [com.allfolio.fx.EcosApiException]으로 던진 이유가 정확히 이것("설정 누락은 서버 문제다")인데,
     * 여기서 502로 뭉뚱그리면 같은 오진을 반대편으로 반복한다 — 502를 본 운영자는 한국은행 상태를
     * 확인하러 가지만 실제 할 일은 `ECOS_API_KEY`를 등록하는 것이다.
     *
     * **예외 객체를 로깅하지 않는다 — 의도된 것이다.**
     * ECOS 인증키가 URL 경로에 들어가서, 원본 스택(Reactor checkpoint·되울린 URI)에 키가 박힌다.
     * `EcosStatisticSearchClient`가 던지기 전에 정제하므로 지금 도달하는 예외는 안전하지만,
     * 나중에 누군가 정제되지 않은 throw 지점을 추가해도 여기서 새지 않도록 code와 클래스 이름만 남긴다.
     * (원인 진단에 필요한 응답 미리보기는 클라이언트가 마스킹해 자기 로그에 이미 남긴다.)
     */
    @ExceptionHandler(com.allfolio.fx.EcosApiException::class)
    fun handleEcosApi(e: com.allfolio.fx.EcosApiException): ResponseEntity<Map<String, String>> {
        val status =
            if (e.code == "NO_KEY" || e.code == "NO_SERIES") HttpStatus.INTERNAL_SERVER_ERROR
            else HttpStatus.BAD_GATEWAY
        log.warn("ECOS API failed type={} code={} status={}", e.javaClass.simpleName, e.code, status.value())
        return ResponseEntity.status(status)
            .body(mapOf("error" to e.detail, "code" to e.code))
    }

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
