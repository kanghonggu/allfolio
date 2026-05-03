package com.allfolio.price

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface MarketPriceTickRepository : JpaRepository<MarketPriceTickEntity, Long> {

    /** 특정 심볼의 최근 N개 틱 조회 (최신순) */
    fun findTop1000BySymbolOrderByTickTimestampDesc(symbol: String): List<MarketPriceTickEntity>

    /** 시간 범위 내 틱 조회 */
    fun findBySymbolAndTickTimestampBetweenOrderByTickTimestampAsc(
        symbol: String,
        from: Instant,
        to: Instant,
    ): List<MarketPriceTickEntity>

    /** 심볼별 최신 가격 조회 (SSE fallback용) */
    @Query("SELECT t FROM MarketPriceTickEntity t WHERE t.symbol = :symbol ORDER BY t.tickTimestamp DESC LIMIT 1")
    fun findLatestBySymbol(@Param("symbol") symbol: String): MarketPriceTickEntity?
}
