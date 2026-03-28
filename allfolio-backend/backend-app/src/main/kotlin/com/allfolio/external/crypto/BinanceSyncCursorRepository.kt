package com.allfolio.external.crypto

import org.springframework.data.jpa.repository.JpaRepository

interface BinanceSyncCursorRepository : JpaRepository<BinanceSyncCursorEntity, BinanceSyncCursorId>
