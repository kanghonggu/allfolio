package com.allfolio.unifiedasset.api

import com.allfolio.unifiedasset.application.usecase.*
import org.springframework.web.bind.annotation.*
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

    @PostMapping("/chat")
    fun startChat(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestBody req: ChatRequest,
    ): Map<String, String> = mapOf("jobId" to svc.submitChat(userId, req.messages))

    @GetMapping("/chat/{jobId}")
    fun chatResult(@PathVariable jobId: String): ChatJobResult =
        svc.getChatResult(jobId)
}
