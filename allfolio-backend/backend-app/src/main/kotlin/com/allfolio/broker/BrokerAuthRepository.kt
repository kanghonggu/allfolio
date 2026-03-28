package com.allfolio.broker

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface BrokerAuthRepository : JpaRepository<BrokerAuthEntity, UUID> {
    fun findByUserIdAndBrokerType(userId: UUID, brokerType: BrokerType): BrokerAuthEntity?
}
