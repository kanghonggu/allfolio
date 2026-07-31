package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.ExclusionListRepository
import com.allfolio.unifiedasset.application.port.ExclusionPresetRepository
import com.allfolio.unifiedasset.domain.exclusion.ExclusionItem
import com.allfolio.unifiedasset.domain.exclusion.ExclusionList
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.util.UUID

data class CreateListCommand(val name: String, val category: String, val description: String?)
data class UpdateListCommand(val name: String, val category: String, val description: String?, val active: Boolean)
data class AddItemCommand(val symbol: String, val memo: String?)
data class PresetView(val name: String, val symbols: List<PresetSymbol>)
data class PresetSymbol(val symbol: String, val reason: String)

@Service
class ExclusionListService(
    private val repository: ExclusionListRepository,
    private val exclusionPresetRepository: ExclusionPresetRepository,
) {
    fun list(userId: UUID): List<ExclusionList> = repository.findByUser(userId)

    fun presets(): List<PresetView> =
        exclusionPresetRepository.findAll()
            .groupBy { it.listName }
            .map { (listName, ps) -> PresetView(listName, ps.map { PresetSymbol(it.symbol, it.reason) }) }

    @Transactional
    fun create(userId: UUID, cmd: CreateListCommand): ExclusionList {
        validateName(cmd.name)
        val now = LocalDateTime.now()
        return repository.saveList(
            ExclusionList(UUID.randomUUID(), userId, cmd.name.trim(), cmd.category.trim(),
                cmd.description?.trim()?.takeIf { it.isNotBlank() }, true, now, now),
        )
    }

    @Transactional
    fun update(userId: UUID, id: UUID, cmd: UpdateListCommand): ExclusionList {
        val existing = owned(userId, id)
        validateName(cmd.name)
        repository.saveList(
            existing.copy(name = cmd.name.trim(), category = cmd.category.trim(),
                description = cmd.description?.trim()?.takeIf { it.isNotBlank() },
                active = cmd.active, updatedAt = LocalDateTime.now()),
        )
        // saveList는 메타만 반환(items 제외) → 종목 포함 응답을 위해 재조회
        return repository.findById(id)!!
    }

    @Transactional
    fun delete(userId: UUID, id: UUID) {
        owned(userId, id)
        repository.deleteList(id)
    }

    @Transactional
    fun addItem(userId: UUID, listId: UUID, cmd: AddItemCommand): ExclusionItem {
        owned(userId, listId)
        val symbol = normalizeSymbol(cmd.symbol)
        // 중복이면 기존 반환(무시)
        if (repository.existsItem(listId, symbol)) {
            return repository.findById(listId)!!.items.first { it.symbol == symbol }
        }
        return repository.addItem(
            ExclusionItem(UUID.randomUUID(), listId, symbol, cmd.memo?.trim()?.takeIf { it.isNotBlank() }, LocalDateTime.now()),
        )
    }

    @Transactional
    fun deleteItem(userId: UUID, listId: UUID, itemId: UUID) {
        val list = owned(userId, listId)
        if (list.items.none { it.id == itemId }) throw ResponseStatusException(HttpStatus.NOT_FOUND, "종목을 찾을 수 없습니다.")
        repository.deleteItem(itemId)
    }

    @Transactional
    fun importCsv(userId: UUID, listId: UUID, csv: String): Int {
        owned(userId, listId)
        val symbols = csv.split('\n', ',').map { normalizeSymbol(it) }.filter { it.isNotBlank() }.distinct()
        var added = 0
        val now = LocalDateTime.now()
        for (s in symbols) {
            if (!repository.existsItem(listId, s)) {
                repository.addItem(ExclusionItem(UUID.randomUUID(), listId, s, null, now)); added++
            }
        }
        return added
    }

    @Transactional
    fun clonePreset(userId: UUID, presetName: String): ExclusionList {
        val preset = presets().firstOrNull { it.name == presetName }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "프리셋을 찾을 수 없습니다.")
        val now = LocalDateTime.now()
        val list = repository.saveList(
            ExclusionList(UUID.randomUUID(), userId, "${preset.name} (복제)",
                "프리셋복제", "내장 프리셋 복제", true, now, now),
        )
        preset.symbols.forEach { ps ->
            repository.addItem(ExclusionItem(UUID.randomUUID(), list.id, ps.symbol, ps.reason, now))
        }
        return repository.findById(list.id)!!
    }

    private fun owned(userId: UUID, id: UUID): ExclusionList {
        val l = repository.findById(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "리스트를 찾을 수 없습니다.")
        if (l.userId != userId) throw ResponseStatusException(HttpStatus.FORBIDDEN, "권한이 없습니다.")
        return l
    }

    private fun validateName(name: String) {
        if (name.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "이름은 필수입니다.")
    }

    private fun normalizeSymbol(s: String): String = s.trim().uppercase()
}
