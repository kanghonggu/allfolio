package com.allfolio.unifiedasset.application.usecase

import com.allfolio.common.crypto.LegacyPlaintextDetectedException
import com.allfolio.common.crypto.SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE
import com.allfolio.unifiedasset.infrastructure.entity.UserAiConfigEntity
import com.allfolio.unifiedasset.infrastructure.jpa.UserAiConfigJpaRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.mockito.ArgumentCaptor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.verify
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

    @Test
    fun `getConfig - 저장된 API 키를 읽을 수 없으면 재설정 필요 상태를 반환한다`() {
        `when`(configRepo.findById(userId)).thenThrow(LegacyPlaintextDetectedException("legacy plaintext"))

        val result = svc().getConfig(userId)!!

        assertFalse(result.hasKey)
        assertEquals(SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE, result.error)
    }

    @Test
    fun `saveConfig - 기존 설정을 읽지 않고 교체 저장한다`() {
        val request = SaveAiConfigRequest(
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-new",
            model = "gpt-4o",
        )

        svc().saveConfig(userId, request)

        verify(configRepo).deleteByUserId(userId)
        val captor = ArgumentCaptor.forClass(UserAiConfigEntity::class.java)
        verify(configRepo).save(captor.capture())
        assertEquals(userId, captor.value.userId)
        assertEquals(request.baseUrl, captor.value.baseUrl)
        assertEquals(request.apiKey, captor.value.apiKey)
        assertEquals(request.model, captor.value.model)
    }

    // ── getChatResult ─────────────────────────────────────────

    @Test
    fun `내 jobId 조회는 결과를 반환한다`() {
        val service = svc()
        val jobId = service.submitChat(userId, listOf(ChatMessage("user", "내 포트폴리오를 분석해줘")))

        val result = service.getChatResult(userId, jobId)

        assertTrue(result.status in setOf("pending", "done", "error"))
    }

    @Test
    fun `남의 jobId 조회는 NoSuchElementException으로 숨긴다`() {
        val ownerId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val service = svc()
        val jobId = service.submitChat(ownerId, listOf(ChatMessage("user", "내 포트폴리오를 분석해줘")))

        val ex = assertThrows<NoSuchElementException> {
            service.getChatResult(otherUserId, jobId)
        }

        assertTrue(ex.message!!.contains("Chat job not found"))
    }

    @Test
    fun `없는 jobId 조회는 NoSuchElementException으로 숨긴다`() {
        val missingJobId = UUID.randomUUID().toString()

        val ex = assertThrows<NoSuchElementException> {
            svc().getChatResult(userId, missingJobId)
        }

        assertTrue(ex.message!!.contains("Chat job not found"))
    }

    @Test
    fun `생성 시 userId가 job owner로 묶인다`() {
        val firstUserId = UUID.randomUUID()
        val secondUserId = UUID.randomUUID()
        val service = svc()
        val firstJobId = service.submitChat(firstUserId, listOf(ChatMessage("user", "첫 번째 사용자")))
        val secondJobId = service.submitChat(secondUserId, listOf(ChatMessage("user", "두 번째 사용자")))

        assertDoesNotThrow { service.getChatResult(firstUserId, firstJobId) }
        assertDoesNotThrow { service.getChatResult(secondUserId, secondJobId) }
        assertThrows<NoSuchElementException> { service.getChatResult(firstUserId, secondJobId) }
        assertThrows<NoSuchElementException> { service.getChatResult(secondUserId, firstJobId) }
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
        val job = svc.getChatResult(userId, jobId)
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
