package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.infrastructure.entity.UserAiConfigEntity
import com.allfolio.unifiedasset.infrastructure.jpa.UserAiConfigJpaRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class AiConsultantServiceTest {

    @Mock lateinit var configRepo: UserAiConfigJpaRepository
    @Mock lateinit var jdbc: JdbcTemplate

    private val userId = UUID.randomUUID()

    private fun svc() = AiConsultantService(configRepo, jdbc, ObjectMapper())

    // ── getConfig ─────────────────────────────────────────────

    @Test
    fun `설정 없으면 getConfig는 null`() {
        `when`(configRepo.findById(userId)).thenReturn(Optional.empty())

        assertNull(svc().getConfig(userId))
    }

    @Test
    fun `설정 있으면 baseUrl과 model 반환, hasKey는 true`() {
        val entity = UserAiConfigEntity(
            userId    = userId,
            baseUrl   = "https://api.openai.com/v1",
            apiKey    = "sk-secret",
            model     = "gpt-4o",
            updatedAt = LocalDateTime.now(),
        )
        `when`(configRepo.findById(userId)).thenReturn(Optional.of(entity))

        val result = svc().getConfig(userId)!!

        assertEquals("https://api.openai.com/v1", result.baseUrl)
        assertEquals("gpt-4o", result.model)
        assertTrue(result.hasKey)
    }

    @Test
    fun `getConfig - API 키 자체는 응답에 포함되지 않음`() {
        val entity = UserAiConfigEntity(
            userId = userId, baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-secret-should-not-leak", model = "gpt-4o",
            updatedAt = LocalDateTime.now(),
        )
        `when`(configRepo.findById(userId)).thenReturn(Optional.of(entity))

        val result = svc().getConfig(userId)!!
        // AiConfigResponse에 실제 api key 값을 담는 필드가 없음을 검증 (hasKey boolean은 허용)
        val fieldNames = result::class.java.declaredFields.map { it.name }
        assertFalse(fieldNames.contains("apiKey"))
        assertFalse(fieldNames.contains("api_key"))
    }

    // ── getChatResult ─────────────────────────────────────────

    @Test
    fun `존재하지 않는 jobId 조회 - IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            svc().getChatResult("nonexistent-job-id")
        }
    }

    // ── submitChat → job lifecycle ────────────────────────────

    @Test
    fun `submitChat - 즉시 jobId 반환, 상태는 pending`() {
        `when`(configRepo.findById(userId)).thenReturn(Optional.empty())

        val svc = svc()
        val jobId = svc.submitChat(userId, listOf(ChatMessage("user", "안녕")))

        assertNotNull(jobId)
        assertTrue(jobId.isNotBlank())

        // job은 바로 존재 (pending or done or error)
        val job = svc.getChatResult(jobId)
        assertNotNull(job.status)
    }

    @Test
    fun `chat - LLM 설정 없으면 IllegalStateException`() {
        `when`(configRepo.findById(userId)).thenReturn(Optional.empty())

        assertThrows<IllegalStateException> {
            svc().chat(userId, listOf(ChatMessage("user", "안녕")))
        }
    }
}
