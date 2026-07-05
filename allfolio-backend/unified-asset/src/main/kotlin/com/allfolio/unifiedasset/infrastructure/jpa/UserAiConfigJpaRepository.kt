package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.UserAiConfigEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

interface UserAiConfigJpaRepository : JpaRepository<UserAiConfigEntity, UUID> {
    @Modifying
    @Transactional
    @Query("DELETE FROM UserAiConfigEntity c WHERE c.userId = :userId")
    fun deleteByUserId(userId: UUID): Int
}
