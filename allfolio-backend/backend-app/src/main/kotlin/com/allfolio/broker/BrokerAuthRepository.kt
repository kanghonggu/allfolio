package com.allfolio.broker

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

interface BrokerAuthRepository : JpaRepository<BrokerAuthEntity, UUID> {
    fun findByUserIdAndBrokerType(userId: UUID, brokerType: BrokerType): BrokerAuthEntity?

    @Modifying
    @Transactional
    @Query("DELETE FROM BrokerAuthEntity b WHERE b.userId = :userId AND b.brokerType = :brokerType")
    fun deleteByUserIdAndBrokerType(userId: UUID, brokerType: BrokerType): Int
}
