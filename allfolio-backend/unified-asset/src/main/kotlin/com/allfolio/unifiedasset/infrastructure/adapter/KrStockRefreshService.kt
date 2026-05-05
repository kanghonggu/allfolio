package com.allfolio.unifiedasset.infrastructure.adapter

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * FSC API로 KRX 전체 상장종목을 매일 새벽 2시에 kr_stocks 테이블에 갱신.
 * API 키가 없으면 건너뜀.
 */
@Service
class KrStockRefreshService(
    private val fscStockClient: FscStockClient,
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 매일 새벽 2시 실행
    @Scheduled(cron = "0 0 2 * * *")
    fun refreshDaily() = refresh()

    @Transactional
    fun refresh() {
        if (!fscStockClient.isConfigured()) {
            log.info("[KrStockRefresh] FSC API 키 미설정 — 종목 갱신 건너뜀")
            return
        }

        val stocks = fscStockClient.listAllStocks()
        if (stocks.isEmpty()) {
            log.warn("[KrStockRefresh] FSC에서 종목 목록을 가져오지 못했습니다")
            return
        }

        // UPSERT: 새 종목 추가, 이름 변경 반영, 기존 데이터는 유지
        var upserted = 0
        stocks.chunked(500).forEach { batch ->
            val sql = """
                INSERT INTO kr_stocks (symbol, name, market)
                VALUES (?, ?, ?)
                ON CONFLICT (symbol) DO UPDATE
                  SET name = EXCLUDED.name,
                      market = EXCLUDED.market
            """.trimIndent()
            jdbc.batchUpdate(sql, batch.map { arrayOf(it.symbol, it.name, it.market) })
            upserted += batch.size
        }

        log.info("[KrStockRefresh] {}개 종목 갱신 완료 (FSC KRX 전체)", upserted)
    }
}
