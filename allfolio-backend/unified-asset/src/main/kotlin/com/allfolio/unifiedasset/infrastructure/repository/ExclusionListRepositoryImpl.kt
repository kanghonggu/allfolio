package com.allfolio.unifiedasset.infrastructure.repository

import com.allfolio.unifiedasset.application.port.ExclusionListRepository
import com.allfolio.unifiedasset.domain.exclusion.ExclusionItem
import com.allfolio.unifiedasset.domain.exclusion.ExclusionList
import com.allfolio.unifiedasset.infrastructure.entity.ExclusionItemEntity
import com.allfolio.unifiedasset.infrastructure.entity.ExclusionListEntity
import com.allfolio.unifiedasset.infrastructure.jpa.ExclusionItemJpaRepository
import com.allfolio.unifiedasset.infrastructure.jpa.ExclusionListJpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ExclusionListRepositoryImpl(
    private val listJpa: ExclusionListJpaRepository,
    private val itemJpa: ExclusionItemJpaRepository,
) : ExclusionListRepository {

    private fun assemble(lists: List<ExclusionListEntity>): List<ExclusionList> {
        if (lists.isEmpty()) return emptyList()
        val itemsByList = itemJpa.findByListIdIn(lists.map { it.id })
            .groupBy { it.listId }
        return lists.map { l ->
            l.toDomain(itemsByList[l.id].orEmpty().map { it.toDomain() }.sortedBy { it.addedAt })
        }
    }

    override fun findByUser(userId: UUID) =
        assemble(listJpa.findByUserIdOrderByCreatedAtDesc(userId))

    override fun findActiveByUser(userId: UUID) =
        assemble(listJpa.findByUserIdAndActiveTrue(userId))

    override fun findById(id: UUID): ExclusionList? =
        listJpa.findById(id).orElse(null)?.let { assemble(listOf(it)).first() }

    override fun saveList(list: ExclusionList): ExclusionList =
        listJpa.save(ExclusionListEntity.fromDomain(list)).toDomain(emptyList())

    override fun deleteList(id: UUID) = listJpa.deleteById(id)  // items: FK ON DELETE CASCADE

    override fun addItem(item: ExclusionItem): ExclusionItem =
        itemJpa.save(ExclusionItemEntity.fromDomain(item)).toDomain()

    override fun deleteItem(itemId: UUID) = itemJpa.deleteById(itemId)

    override fun existsItem(listId: UUID, symbol: String): Boolean =
        itemJpa.existsByListIdAndSymbol(listId, symbol)
}
