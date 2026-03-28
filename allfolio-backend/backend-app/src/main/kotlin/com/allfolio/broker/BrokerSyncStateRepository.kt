package com.allfolio.broker

import org.springframework.data.jpa.repository.JpaRepository

interface BrokerSyncStateRepository : JpaRepository<BrokerSyncStateEntity, BrokerSyncStateId>
