package com.allfolio.unifiedasset.api

import com.allfolio.unifiedasset.application.usecase.*
import org.springframework.web.bind.annotation.*
import java.util.UUID

data class ChatRequest(val messages: List<ChatMessage>)
data class ChatResponse(val content: String)

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

    @PostMapping("/chat")
    fun chat(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestBody req: ChatRequest,
    ): ChatResponse = ChatResponse(svc.chat(userId, req.messages))
}
