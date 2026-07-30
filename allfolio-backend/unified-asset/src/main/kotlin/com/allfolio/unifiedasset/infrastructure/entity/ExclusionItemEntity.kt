package com.allfolio.unifiedasset.infrastructure.entity

import com.allfolio.unifiedasset.domain.exclusion.ExclusionItem
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "ua_exclusion_items")
class ExclusionItemEntity(
    @Id @Column(columnDefinition = "uuid") val id: UUID,
    @Column(name = "list_id", nullable = false, columnDefinition = "uuid") val listId: UUID,
    @Column(nullable = false, length = 40) val symbol: String,
    @Column(length = 300) val memo: String?,
    @Column(name = "added_at", nullable = false) val addedAt: LocalDateTime,
) {
    fun toDomain() = ExclusionItem(id, listId, symbol, memo, addedAt)
    companion object {
        fun fromDomain(i: ExclusionItem) = ExclusionItemEntity(i.id, i.listId, i.symbol, i.memo, i.addedAt)
    }
}
