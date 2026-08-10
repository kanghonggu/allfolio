package com.allfolio.feedback

import org.slf4j.LoggerFactory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Repository
interface FeedbackRepository : JpaRepository<FeedbackEntity, UUID>

/** 연속 전송 제한을 넘겼을 때 — 컨트롤러가 429로 바꾼다. */
class FeedbackRateLimitExceeded(val retryAfter: Duration) : RuntimeException("문의는 잠시 후 다시 보낼 수 있습니다")

data class FeedbackSubmission(
    val kind: FeedbackKind,
    val message: String,
    val pageUrl: String?,
    val userAgent: String?,
    val viewport: String?,
    val lastApiError: String?,
    val consoleErrors: List<String>,
)

@Service
class FeedbackService(
    private val repository: FeedbackRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 사용자별 최근 전송 시각. 단일 인스턴스 운영이라 메모리로 충분하고,
     * 재시작 시 초기화돼도 실을 잃는 게 없다(스팸 방어가 아니라 오작동·연타 방어).
     */
    private val recentSubmissions = ConcurrentHashMap<UUID, MutableList<Instant>>()

    @Transactional
    fun submit(userId: UUID, submission: FeedbackSubmission): UUID {
        enforceRateLimit(userId)

        val saved = repository.save(
            FeedbackEntity(
                userId = userId,
                kind = submission.kind,
                message = submission.message.trim().take(FeedbackEntity.MAX_MESSAGE_LENGTH),
                pageUrl = submission.pageUrl?.take(500),
                userAgent = submission.userAgent?.take(500),
                viewport = submission.viewport?.take(20),
                lastApiError = submission.lastApiError?.take(500),
                consoleErrors = submission.consoleErrors
                    .take(MAX_CONSOLE_ERRORS)
                    .joinToString("\n") { it.take(500) }
                    .take(2000)
                    .ifBlank { null },
            )
        )
        log.info("[Feedback] 접수 id={} userId={} kind={} page={}",
            saved.id, userId, submission.kind, submission.pageUrl)
        return saved.id
    }

    private fun enforceRateLimit(userId: UUID) {
        val now = Instant.now()
        val window = recentSubmissions.compute(userId) { _, existing ->
            val kept = (existing ?: mutableListOf())
                .filter { Duration.between(it, now) < RATE_WINDOW }
                .toMutableList()
            kept
        }!!
        if (window.size >= MAX_PER_WINDOW) {
            val oldest = window.min()
            throw FeedbackRateLimitExceeded(RATE_WINDOW - Duration.between(oldest, now))
        }
        window += now
        // 오래된 사용자 항목이 쌓이지 않게 — 창이 빈 항목은 버린다
        recentSubmissions.entries.removeIf { it.value.isEmpty() }
    }

    companion object {
        private val RATE_WINDOW: Duration = Duration.ofMinutes(10)
        private const val MAX_PER_WINDOW = 5
        private const val MAX_CONSOLE_ERRORS = 5
    }
}
