package com.allfolio.external.crypto

import com.allfolio.trade.application.RecordTradeUseCase
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * Binance Spot 거래 자동 동기화
 *
 * 동작 흐름:
 * 1. binance_sync_cursor 조회 → 마지막 trade ID 확인
 * 2. Binance GET /api/v3/myTrades?fromId={lastId+1} 호출
 * 3. 신규 거래를 RecordTradeUseCase.record() 로 저장
 * 4. 각 거래 저장 시 TradeRecordedEvent → AFTER_COMMIT → Snapshot 자동 생성
 * 5. 커서 업데이트
 *
 * 중복 방지:
 * - fromId 커서로 이미 처리한 거래 재조회 방지 (1차)
 * - API 키 미설정 시 자동 스킵 (로컬 개발)
 */
@Component
class BinanceSyncService(
    private val binanceProperties: BinanceProperties,
    private val binanceApiClient: BinanceApiClient,
    private val cursorRepository: BinanceSyncCursorRepository,
    private val recordTradeUseCase: RecordTradeUseCase,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 60_000)
    fun sync() {
        if (!binanceProperties.isConfigured()) {
            log.debug("[Binance] API key not configured — skipping sync")
            return
        }

        binanceProperties.symbolList().forEach { symbol ->
            try {
                syncSymbol(symbol)
            } catch (e: BinanceApiException) {
                log.error("[Binance] API error for {} — {}", symbol, e.message)
            } catch (e: Exception) {
                log.error("[Binance] sync failed for {}", symbol, e)
            }
        }
    }

    @Transactional
    fun syncSymbol(symbol: String) {
        val cursorId = BinanceSyncCursorId(binanceProperties.portfolioId, symbol)
        val cursor   = cursorRepository.findById(cursorId)
            .orElse(BinanceSyncCursorEntity(id = cursorId))

        val fromId = if (cursor.lastTradeId > 0) cursor.lastTradeId + 1 else 0L

        val trades = binanceApiClient.fetchMyTrades(symbol, fromId = fromId, limit = 100)

        if (trades.isEmpty()) {
            log.debug("[Binance] no new trades for {}", symbol)
            return
        }

        log.info("[Binance] {} new trades for {} (fromId={})", trades.size, symbol, fromId)

        var recordedCount = 0
        trades.forEach { dto ->
            try {
                val command = BinanceTradeMapper.toCommand(
                    dto         = dto,
                    tenantId    = binanceProperties.tenantId,
                    portfolioId = binanceProperties.portfolioId,
                )
                recordTradeUseCase.record(command)
                recordedCount++

                log.info("[Binance] trade recorded id={} symbol={} side={} qty={} price={} time={}",
                    dto.id, dto.symbol,
                    if (dto.isBuyer) "BUY" else "SELL",
                    dto.qty, dto.price,
                    java.time.Instant.ofEpochMilli(dto.time))
            } catch (e: Exception) {
                log.error("[Binance] failed to record trade id={} symbol={}", dto.id, dto.symbol, e)
            }
        }

        // 커서 업데이트 — 이 배치에서 가장 큰 Binance trade ID로
        val maxTradeId = trades.maxOf { it.id }
        cursor.lastTradeId  = maxOf(cursor.lastTradeId, maxTradeId)
        cursor.syncedCount += recordedCount
        cursor.updatedAt    = LocalDateTime.now()
        cursorRepository.save(cursor)

        log.info("[Binance] sync done symbol={} recorded={}/{} cursor={}",
            symbol, recordedCount, trades.size, cursor.lastTradeId)
    }
}
