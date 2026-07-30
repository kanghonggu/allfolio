package com.allfolio.unifiedasset.infrastructure.entity

import com.allfolio.unifiedasset.domain.exclusion.ExclusionItem
import com.allfolio.unifiedasset.domain.exclusion.ExclusionList
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "ua_exclusion_lists")
class ExclusionListEntity(
    @Id @Column(columnDefinition = "uuid") val id: UUID,
    @Column(name = "user_id", nullable = false, columnDefinition = "uuid") val userId: UUID,
    @Column(nullable = false, length = 100) val name: String,
    @Column(nullable = false, length = 30) val category: String,
    @Column(length = 500) val description: String?,
    @Column(nullable = false) val active: Boolean,
    @Column(name = "created_at", nullable = false) val createdAt: LocalDateTime,
    @Column(name = "updated_at", nullable = false) val updatedAt: LocalDateTime,
) {
    /** items는 어댑터에서 별도 조회해 채운다. */
    fun toDomain(items: List<ExclusionItem>) =
        ExclusionList(id, userId, name, category, description, active, createdAt, updatedAt, items)
    companion object {
        fun fromDomain(l: ExclusionList) =
            ExclusionListEntity(l.id, l.userId, l.name, l.category, l.description, l.active, l.createdAt, l.updatedAt)
    }
}
