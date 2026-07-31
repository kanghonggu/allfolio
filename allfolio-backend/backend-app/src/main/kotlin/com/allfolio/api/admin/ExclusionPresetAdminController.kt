package com.allfolio.api.admin

import com.allfolio.unifiedasset.application.usecase.ExclusionPresetService
import com.allfolio.unifiedasset.application.usecase.UpsertPresetCommand
import com.allfolio.unifiedasset.domain.exclusion.ExclusionPreset
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime
import java.util.UUID

@RestController
@RequestMapping("/api/admin/exclusion-presets")
class ExclusionPresetAdminController(
    private val exclusionPresetService: ExclusionPresetService,
) {
    /** GET — 전체 배제 프리셋 목록. */
    @GetMapping
    fun list(): ResponseEntity<List<ExclusionPresetResponse>> =
        ResponseEntity.ok(exclusionPresetService.list().map { it.toResponse() })

    /** POST — 등록/수정(upsert) (ADMIN). */
    @PostMapping
    fun upsert(
        @RequestHeader("X-User-Id") adminId: UUID,
        @RequestBody req: UpsertPresetRequest,
    ): ResponseEntity<ExclusionPresetResponse> {
        val saved = exclusionPresetService.upsert(
            UpsertPresetCommand(req.symbol, req.listName, req.reason), adminId,
        )
        return ResponseEntity.ok(saved.toResponse())
    }

    /** DELETE — 프리셋 삭제 (ADMIN). */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) = exclusionPresetService.delete(id)

    private fun ExclusionPreset.toResponse() = ExclusionPresetResponse(
        id, symbol, listName, reason, updatedBy, updatedAt,
    )
}

data class UpsertPresetRequest(
    val symbol: String,
    val listName: String,
    val reason: String,
)

data class ExclusionPresetResponse(
    val id: UUID,
    val symbol: String,
    val listName: String,
    val reason: String,
    val updatedBy: UUID?,
    val updatedAt: LocalDateTime,
)
