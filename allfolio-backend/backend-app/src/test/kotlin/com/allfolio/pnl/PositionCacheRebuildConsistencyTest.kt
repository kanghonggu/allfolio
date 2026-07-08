package com.allfolio.pnl

import com.allfolio.trade.domain.FifoCostEngine
import com.allfolio.trade.domain.LotPosition
import com.allfolio.trade.domain.TradeId
import com.allfolio.trade.domain.TradeRaw
import com.allfolio.trade.domain.TradeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * 재부팅 정합성 회귀 테스트.
 * 초기화기(배치 replay)로 재구축한 포지션의 lots가
 * write-path(증분 apply)로 누적한 포지션의 lots와 동일해야 한다.
 * → costMethod=FIFO 원가가 재부팅 전후로 일치.
 */
class PositionCacheRebuildConsistencyTest {

    private val portfolioId = UUID.randomUUID()
    private val assetId = UUID.randomUUID()

    private fun trade(type: TradeType, qty: String, price: String) = TradeRaw.reconstruct(
        id = TradeId.newId(), portfolioId = portfolioId, assetId = assetId, tradeType = type,
        quantity = BigDecimal(qty), price = BigDecimal(price), fee = BigDecimal.ZERO,
        tradeCurrency = "KRW", executedAt = LocalDateTime.now(), createdAt = LocalDateTime.now(),
    )

    @Test
    fun `rebuild via replay produces same lots as incremental write path`() {
        val trades = listOf(
            trade(TradeType.BUY, "10", "100"),
            trade(TradeType.BUY, "10", "200"),
            trade(TradeType.SELL, "5", "300"),
            trade(TradeType.BUY, "4", "250"),
        )

        // 초기화기 경로: 배치 replay → PositionData
        val rebuilt = PositionDataMapper.toPositionData(
            FifoCostEngine.replay(trades), portfolioId, assetId, currency = "KRW",
        )

        // write-path 경로: 증분 apply → PositionData
        val incremental = trades.fold(LotPosition.EMPTY) { pos, t ->
            FifoCostEngine.apply(pos, t.tradeType, t.quantity, t.price)
        }.let { PositionDataMapper.toPositionData(it, portfolioId, assetId, currency = "KRW") }

        // PositionLot.purchasedAt은 생성 시각(System.currentTimeMillis())으로 채워지는 비교 무관 필드라
        // (PositionDataMapper.toPositionData 문서 참조) price/quantity만 비교한다.
        val incrementalLots = incremental.lots.map { it.price to it.quantity }
        val rebuiltLots = rebuilt.lots.map { it.price to it.quantity }
        assertEquals(incrementalLots, rebuiltLots, "재부팅 재구축 lots가 증분 누적 lots와 달라 FIFO 원가가 어긋남")
        assertEquals(0, incremental.quantity.compareTo(rebuilt.quantity))
    }
}
