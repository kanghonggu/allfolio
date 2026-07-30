package com.allfolio.unifiedasset.application.port

import com.allfolio.unifiedasset.domain.exclusion.ExclusionItem
import com.allfolio.unifiedasset.domain.exclusion.ExclusionList
import java.util.UUID

interface ExclusionListRepository {
    fun findByUser(userId: UUID): List<ExclusionList>        // items 포함
    fun findActiveByUser(userId: UUID): List<ExclusionList>  // items 포함, active만
    fun findById(id: UUID): ExclusionList?                   // items 포함
    fun saveList(list: ExclusionList): ExclusionList         // 메타만 저장(items 제외)
    fun deleteList(id: UUID)                                 // items는 DB cascade
    fun addItem(item: ExclusionItem): ExclusionItem
    fun deleteItem(itemId: UUID)
    fun existsItem(listId: UUID, symbol: String): Boolean
}
