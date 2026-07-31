package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.ExclusionPresetRepository
import com.allfolio.unifiedasset.domain.exclusion.ExclusionPreset
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

data class UpsertPresetCommand(val symbol: String, val listName: String, val reason: String)

@Service
class ExclusionPresetService(
    private val repository: ExclusionPresetRepository,
) {
    fun list(): List<ExclusionPreset> = repository.findAll()

    @Transactional
    fun upsert(cmd: UpsertPresetCommand, adminId: UUID): ExclusionPreset {
        val symbol = cmd.symbol.trim().uppercase()
        require(symbol.isNotBlank()) { "심볼은 필수입니다" }
        require(cmd.listName.isNotBlank()) { "리스트명은 필수입니다" }
        val now = LocalDateTime.now()
        val existing = repository.findBySymbol(symbol)
        return repository.save(
            existing?.copy(listName = cmd.listName.trim(), reason = cmd.reason.trim(), updatedBy = adminId, updatedAt = now)
                ?: ExclusionPreset(UUID.randomUUID(), symbol, cmd.listName.trim(), cmd.reason.trim(), adminId, now, now),
        )
    }

    @Transactional
    fun delete(id: UUID) = repository.delete(id)
}
