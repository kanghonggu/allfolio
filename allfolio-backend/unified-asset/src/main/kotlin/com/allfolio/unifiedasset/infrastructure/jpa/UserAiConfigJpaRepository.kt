package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.UserAiConfigEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserAiConfigJpaRepository : JpaRepository<UserAiConfigEntity, UUID>
