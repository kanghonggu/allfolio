package com.allfolio.unifiedasset.api

import com.allfolio.unifiedasset.application.usecase.*
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

data class ChatRequest(val messages: List<ChatMessage>)

@RestController
@RequestMapping("/api/ai")
class AiConsultantController(private val svc: AiConsultantService) {

    @GetMapping("/config")
    fun getConfig(@RequestHeader("X-User-Id") userId: UUID): AiConfigResponse? =
        svc.getConfig(userId)

    @PostMapping("/config")
    fun saveConfig(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestBody req: SaveAiConfigRequest,
    ) = svc.saveConfig(userId, req)

    @DeleteMapping("/config")
    fun deleteConfig(@RequestHeader("X-User-Id") userId: UUID) =
        svc.deleteConfig(userId)

    @PostMapping("/chat", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun chat(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestBody req: ChatRequest,
        response: HttpServletResponse,
    ): SseEmitter {
        response.setHeader("X-Accel-Buffering", "no")
        response.setHeader("Cache-Control", "no-cache")
        return svc.chat(userId, req.messages)
    }
}
