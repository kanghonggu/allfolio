package com.allfolio.feedback

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Example
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import java.util.Optional
import java.util.UUID
import java.util.function.Function

/** AF-94 — 접수 전용 문의. 본문 길이·컨텍스트 절단과 연속 전송 제한만 책임진다. */
class FeedbackServiceTest {

    private val repository = RecordingFeedbackRepository()
    private val service = FeedbackService(repository)
    private val userId: UUID = UUID.randomUUID()

    @Test
    fun `유형과 본문, 화면이 보낸 재현 정보를 함께 저장한다`() {
        service.submit(userId, submission(message = "  계좌 추가가 안 돼요  "))

        val saved = repository.saved.single()
        assertThat(saved.userId).isEqualTo(userId)
        assertThat(saved.kind).isEqualTo(FeedbackKind.BUG)
        assertThat(saved.message).isEqualTo("계좌 추가가 안 돼요")
        assertThat(saved.pageUrl).isEqualTo("/unified/accounts/new")
        assertThat(saved.lastApiError).isEqualTo("500 POST /api/unified/accounts")
        assertThat(saved.consoleErrors).contains("TypeError: x is undefined")
    }

    @Test
    fun `본문이 한도를 넘으면 잘라서 저장한다`() {
        service.submit(userId, submission(message = "가".repeat(FeedbackEntity.MAX_MESSAGE_LENGTH + 500)))

        assertThat(repository.saved.single().message).hasSize(FeedbackEntity.MAX_MESSAGE_LENGTH)
    }

    @Test
    fun `콘솔 에러는 최근 5건까지만 남긴다`() {
        service.submit(userId, submission(consoleErrors = (1..12).map { "error-$it" }))

        val lines = repository.saved.single().consoleErrors!!.lines()
        assertThat(lines).hasSize(5)
        assertThat(lines.first()).isEqualTo("error-1")
    }

    @Test
    fun `컨텍스트가 비어 있어도 접수된다`() {
        service.submit(
            userId,
            FeedbackSubmission(
                kind = FeedbackKind.QUESTION, message = "이건 어떻게 쓰나요",
                pageUrl = null, userAgent = null, viewport = null,
                lastApiError = null, consoleErrors = emptyList(),
            ),
        )

        assertThat(repository.saved.single().consoleErrors).isNull()
    }

    @Test
    fun `짧은 시간에 연속으로 보내면 제한한다`() {
        repeat(5) { service.submit(userId, submission()) }

        assertThatThrownBy { service.submit(userId, submission()) }
            .isInstanceOf(FeedbackRateLimitExceeded::class.java)
        assertThat(repository.saved).hasSize(5)
    }

    @Test
    fun `제한은 사용자별로 걸린다`() {
        repeat(5) { service.submit(userId, submission()) }

        service.submit(UUID.randomUUID(), submission())

        assertThat(repository.saved).hasSize(6)
    }

    private fun submission(
        message: String = "테스트 문의",
        consoleErrors: List<String> = listOf("TypeError: x is undefined"),
    ) = FeedbackSubmission(
        kind = FeedbackKind.BUG,
        message = message,
        pageUrl = "/unified/accounts/new",
        userAgent = "Mozilla/5.0",
        viewport = "1280x720",
        lastApiError = "500 POST /api/unified/accounts",
        consoleErrors = consoleErrors,
    )

    /** JpaRepository의 나머지 메서드는 이 서비스가 쓰지 않는다. */
    private class RecordingFeedbackRepository : FeedbackRepository {
        val saved = mutableListOf<FeedbackEntity>()

        override fun <S : FeedbackEntity> save(entity: S): S {
            saved.add(entity)
            return entity
        }

        override fun findAll(): List<FeedbackEntity> = saved
        override fun findAll(sort: Sort): List<FeedbackEntity> = saved
        override fun findAll(pageable: Pageable): Page<FeedbackEntity> = unsupported()
        override fun findAllById(ids: Iterable<UUID>): List<FeedbackEntity> = unsupported()
        override fun <S : FeedbackEntity> saveAll(entities: Iterable<S>): List<S> = unsupported()
        override fun findById(id: UUID): Optional<FeedbackEntity> = unsupported()
        override fun existsById(id: UUID): Boolean = unsupported()
        override fun count(): Long = saved.size.toLong()
        override fun deleteById(id: UUID) = unsupported()
        override fun delete(entity: FeedbackEntity) = unsupported()
        override fun deleteAllById(ids: Iterable<UUID>) = unsupported()
        override fun deleteAll(entities: Iterable<FeedbackEntity>) = unsupported()
        override fun deleteAll() = unsupported()
        override fun flush() = unsupported()
        override fun <S : FeedbackEntity> saveAndFlush(entity: S): S = unsupported()
        override fun <S : FeedbackEntity> saveAllAndFlush(entities: Iterable<S>): List<S> = unsupported()
        override fun deleteAllInBatch(entities: Iterable<FeedbackEntity>) = unsupported()
        override fun deleteAllByIdInBatch(ids: Iterable<UUID>) = unsupported()
        override fun deleteAllInBatch() = unsupported()
        @Deprecated("Deprecated in Java", ReplaceWith("getReferenceById(id)"))
        override fun getOne(id: UUID): FeedbackEntity = unsupported()
        @Deprecated("Deprecated in Java", ReplaceWith("getReferenceById(id)"))
        override fun getById(id: UUID): FeedbackEntity = unsupported()
        override fun getReferenceById(id: UUID): FeedbackEntity = unsupported()
        override fun <S : FeedbackEntity> findOne(example: Example<S>): Optional<S> = unsupported()
        override fun <S : FeedbackEntity> findAll(example: Example<S>): List<S> = unsupported()
        override fun <S : FeedbackEntity> findAll(example: Example<S>, sort: Sort): List<S> = unsupported()
        override fun <S : FeedbackEntity> findAll(example: Example<S>, pageable: Pageable): Page<S> = unsupported()
        override fun <S : FeedbackEntity> count(example: Example<S>): Long = unsupported()
        override fun <S : FeedbackEntity> exists(example: Example<S>): Boolean = unsupported()
        override fun <S : FeedbackEntity, R : Any> findBy(
            example: Example<S>,
            queryFunction: Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R>,
        ): R = unsupported()

        private fun unsupported(): Nothing = throw UnsupportedOperationException("테스트에서 사용하지 않는 메서드")
    }
}
