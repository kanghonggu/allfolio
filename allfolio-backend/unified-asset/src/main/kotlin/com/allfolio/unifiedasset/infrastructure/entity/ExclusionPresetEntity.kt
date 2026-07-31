package com.allfolio.unifiedasset.infrastructure.entity

import com.allfolio.unifiedasset.domain.exclusion.ExclusionPreset
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "exclusion_presets")
class ExclusionPresetEntity(
    @Id val id: UUID,
    @Column(name = "symbol", nullable = false, length = 40) val symbol: String,
    @Column(name = "list_name", nullable = false, length = 100) val listName: String,
    @Column(name = "reason", nullable = false, length = 200) val reason: String,
    @Column(name = "updated_by") val updatedBy: UUID?,
    @Column(name = "created_at", nullable = false) val createdAt: LocalDateTime,
    @Column(name = "updated_at", nullable = false) val updatedAt: LocalDateTime,
) {
    fun toDomain() = ExclusionPreset(id, symbol, listName, reason, updatedBy, createdAt, updatedAt)

    companion object {
        fun from(d: ExclusionPreset) = ExclusionPresetEntity(
            d.id, d.symbol, d.listName, d.reason, d.updatedBy, d.createdAt, d.updatedAt,
        )
    }
}
