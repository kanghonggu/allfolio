package com.allfolio.unifiedasset.api

import com.allfolio.unifiedasset.application.usecase.AddItemCommand
import com.allfolio.unifiedasset.application.usecase.CreateListCommand
import com.allfolio.unifiedasset.application.usecase.ExclusionListService
import com.allfolio.unifiedasset.application.usecase.PresetView
import com.allfolio.unifiedasset.application.usecase.UpdateListCommand
import com.allfolio.unifiedasset.domain.exclusion.ExclusionList
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime
import java.util.UUID

@RestController
@RequestMapping("/api/exclusion-lists")
class ExclusionListController(private val svc: ExclusionListService) {

    @GetMapping
    fun list(@RequestHeader("X-User-Id") userId: UUID): List<ExclusionListResponse> =
        svc.list(userId).map { it.toResponse() }

    @GetMapping("/presets")
    fun presets(): List<PresetView> = svc.presets()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestHeader("X-User-Id") userId: UUID, @RequestBody req: CreateListRequest): ExclusionListResponse =
        svc.create(userId, CreateListCommand(req.name, req.category, req.description)).toResponse()

    @PutMapping("/{id}")
    fun update(@RequestHeader("X-User-Id") userId: UUID, @PathVariable id: UUID, @RequestBody req: UpdateListRequest): ExclusionListResponse =
        svc.update(userId, id, UpdateListCommand(req.name, req.category, req.description, req.active)).toResponse()

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@RequestHeader("X-User-Id") userId: UUID, @PathVariable id: UUID) = svc.delete(userId, id)

    @PostMapping("/{id}/items")
    @ResponseStatus(HttpStatus.CREATED)
    fun addItem(@RequestHeader("X-User-Id") userId: UUID, @PathVariable id: UUID, @RequestBody req: AddItemRequest): ExclusionItemResponse =
        svc.addItem(userId, id, AddItemCommand(req.symbol, req.memo)).let { ExclusionItemResponse(it.id, it.symbol, it.memo, it.addedAt) }

    @DeleteMapping("/{id}/items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteItem(@RequestHeader("X-User-Id") userId: UUID, @PathVariable id: UUID, @PathVariable itemId: UUID) =
        svc.deleteItem(userId, id, itemId)

    @PostMapping("/{id}/items/import")
    fun importCsv(@RequestHeader("X-User-Id") userId: UUID, @PathVariable id: UUID, @RequestBody req: ImportCsvRequest): ImportResult =
        ImportResult(svc.importCsv(userId, id, req.csv))

    @PostMapping("/presets/clone")
    @ResponseStatus(HttpStatus.CREATED)
    fun clonePreset(@RequestHeader("X-User-Id") userId: UUID, @RequestBody req: ClonePresetRequest): ExclusionListResponse =
        svc.clonePreset(userId, req.presetName).toResponse()

    private fun ExclusionList.toResponse() = ExclusionListResponse(
        id, name, category, description, active, items.size,
        items.map { ExclusionItemResponse(it.id, it.symbol, it.memo, it.addedAt) }, updatedAt,
    )
}

data class CreateListRequest(val name: String, val category: String, val description: String?)
data class UpdateListRequest(val name: String, val category: String, val description: String?, val active: Boolean)
data class AddItemRequest(val symbol: String, val memo: String?)
data class ImportCsvRequest(val csv: String)
data class ClonePresetRequest(val presetName: String)
data class ImportResult(val added: Int)
data class ExclusionItemResponse(val id: UUID, val symbol: String, val memo: String?, val addedAt: LocalDateTime)
data class ExclusionListResponse(
    val id: UUID, val name: String, val category: String, val description: String?,
    val active: Boolean, val itemCount: Int, val items: List<ExclusionItemResponse>, val updatedAt: LocalDateTime,
)
