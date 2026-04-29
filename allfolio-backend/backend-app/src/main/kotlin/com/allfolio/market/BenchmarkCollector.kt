package com.allfolio.market

import com.allfolio.snapshot.infrastructure.entity.BenchmarkDailyEntity
import com.allfolio.snapshot.infrastructure.entity.BenchmarkDailyId
import com.allfolio.snapshot.infrastructure.repository.BenchmarkDailyJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate

@Component
class BenchmarkCollector(
    private val benchmarkRepo: BenchmarkDailyJpaRepository,
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 평일 오후 4시 30분 — KRX 마감 후 KOSPI 종가 수집
    @Scheduled(cron = "0 30 16 * * MON-FRI")
    fun collectKospi() {
        val today = LocalDate.now()
        if (benchmarkRepo.existsById(BenchmarkDailyId("KOSPI", today))) return

        val price: BigDecimal? = jdbc.query(
            """SELECT price FROM market_price_tick
               WHERE exchange = 'KIS' AND symbol = '005930'
                 AND tick_timestamp >= ?::date AND tick_timestamp < (?::date + INTERVAL '1 day')
               ORDER BY tick_timestamp DESC LIMIT 1""",
            { rs, _ -> rs.getBigDecimal("price") },
            today, today,
        ).firstOrNull()

        if (price == null) {
            log.warn("BenchmarkCollector: KOSPI data not available for $today")
            return
        }

        benchmarkRepo.save(BenchmarkDailyEntity(BenchmarkDailyId("KOSPI", today), price))
        log.info("BenchmarkCollector: KOSPI saved $today=$price")
    }

    // 매일 00:30 — BTC UTC 전일 종가 수집
    @Scheduled(cron = "0 30 0 * * *")
    fun collectBtc() {
        val yesterday = LocalDate.now().minusDays(1)
        if (benchmarkRepo.existsById(BenchmarkDailyId("BTC", yesterday))) return

        val price: BigDecimal? = jdbc.query(
            """SELECT price FROM market_price_tick
               WHERE exchange = 'BINANCE' AND symbol = 'BTCUSDT'
                 AND tick_timestamp >= ?::date AND tick_timestamp < (?::date + INTERVAL '1 day')
               ORDER BY tick_timestamp DESC LIMIT 1""",
            { rs, _ -> rs.getBigDecimal("price") },
            yesterday, yesterday,
        ).firstOrNull()

        if (price == null) {
            log.warn("BenchmarkCollector: BTC data not available for $yesterday")
            return
        }

        benchmarkRepo.save(BenchmarkDailyEntity(BenchmarkDailyId("BTC", yesterday), price))
        log.info("BenchmarkCollector: BTC saved $yesterday=$price")
    }
}
