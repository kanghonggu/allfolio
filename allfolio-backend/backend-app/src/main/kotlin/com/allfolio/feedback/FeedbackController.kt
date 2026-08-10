package com.allfolio.feedback

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class FeedbackRequest(
    val kind: FeedbackKind,

    @field:NotBlank(message = "내용을 입력해주세요")
    @field:Size(max = FeedbackEntity.MAX_MESSAGE_LENGTH, message = "내용은 2000자까지 입력할 수 있습니다")
    val message: String,

    // ── 화면이 자동으로 채우는 재현 정보 ──
    val pageUrl: String? = null,
    val userAgent: String? = null,
    val viewport: String? = null,
    val lastApiError: String? = null,
    val consoleErrors: List<String> = emptyList(),
)

data class FeedbackResponse(val id: UUID)

/**
 * 앱 내 1:1 문의 접수 (AF-94). 로그인 필수 — SecurityConfig의 anyRequest().authenticated()가 적용된다.
 * 목록·상태 조회·답변은 이번 범위 밖이고, 접수된 내용은 DB에서 직접 확인한다.
 */
@RestController
@RequestMapping("/api/feedback")
class FeedbackController(
    private val feedbackService: FeedbackService,
) {
    @PostMapping
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    fun submit(
        @RequestHeader("X-User-Id") userId: UUID,
        @Valid @RequestBody req: FeedbackRequest,
    ): FeedbackResponse = FeedbackResponse(
        feedbackService.submit(
            userId,
            FeedbackSubmission(
                kind = req.kind,
                message = req.message,
                pageUrl = req.pageUrl,
                userAgent = req.userAgent,
                viewport = req.viewport,
                lastApiError = req.lastApiError,
                consoleErrors = req.consoleErrors,
            ),
        )
    )

    @ExceptionHandler(FeedbackRateLimitExceeded::class)
    fun handleRateLimit(e: FeedbackRateLimitExceeded): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", e.retryAfter.seconds.coerceAtLeast(1).toString())
            .body(mapOf("error" to "문의가 너무 자주 접수되고 있습니다. 잠시 후 다시 시도해주세요"))
}
