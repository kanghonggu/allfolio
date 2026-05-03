package com.allfolio.marketdata.price

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface MarketPriceTickRepository : JpaRepository<MarketPriceTickEntity, Long> {

    fun findTop1000BySymbolOrderByTickTimestampDesc(symbol: String): List<MarketPriceTickEntity>

    fun findBySymbolAndTickTimestampBetween(
        symbol: String,
        from: Instant,
        to: Instant,
    ): List<MarketPriceTickEntity>

    @Query("SELECT t FROM MarketPriceTickEntity t WHERE t.symbol = :symbol ORDER BY t.tickTimestamp DESC LIMIT 1")
    fun findLatestBySymbol(symbol: String): MarketPriceTickEntity?
}
