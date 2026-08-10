package com.allfolio.feedback

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

enum class FeedbackKind { BUG, IMPROVEMENT, QUESTION }

/**
 * 앱 내 1:1 문의 (AF-94). 접수 전용 — 목록·답변·공개 게시판은 범위 밖이다.
 *
 * 사용자가 적는 건 유형과 본문뿐이고, 재현에 필요한 나머지는 화면이 함께 보낸다.
 * 첨부파일은 의도적으로 받지 않는다 — 자산관리 앱 스크린샷에는 계좌번호·잔고가
 * 그대로 담겨 민감정보 저장소와 업로드 공격면이 한꺼번에 늘어난다.
 */
@Entity
@Table(name = "feedback")
class FeedbackEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    val kind: FeedbackKind,

    @Column(name = "message", nullable = false, length = MAX_MESSAGE_LENGTH)
    val message: String,

    /** 문의를 남긴 화면 — "여기 이상해요" 한 줄만 와도 어디인지 알 수 있게 */
    @Column(name = "page_url", length = 500)
    val pageUrl: String?,

    @Column(name = "user_agent", length = 500)
    val userAgent: String?,

    /** "1280x720" — 모바일/데스크톱 구분 */
    @Column(name = "viewport", length = 20)
    val viewport: String?,

    /** 직전 API 에러: 상태코드 + 엔드포인트. 콘솔 에러와 함께 사실상 스크린샷을 대체한다 */
    @Column(name = "last_api_error", length = 500)
    val lastApiError: String?,

    @Column(name = "console_errors", length = 2000)
    val consoleErrors: String?,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    companion object {
        const val MAX_MESSAGE_LENGTH = 2000
    }
}
