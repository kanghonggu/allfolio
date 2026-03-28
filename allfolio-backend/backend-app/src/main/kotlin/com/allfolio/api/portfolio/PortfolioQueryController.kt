package com.allfolio.api.portfolio

import com.allfolio.fx.CurrencyConverter
import com.allfolio.pnl.CostBasisMethod
import com.allfolio.pnl.PositionCacheService
import com.allfolio.pnl.PositionData
import com.allfolio.trade.infrastructure.entity.TradeRawEntity
import com.allfolio.trade.infrastructure.repository.TradeRawJpaRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

@RestController
@RequestMapping("/api/portfolios")
class PortfolioQueryController(
    private val tradeRepository: TradeRawJpaRepository,
    private val positionCacheService: PositionCacheService,
    private val currencyConverter: CurrencyConverter,
) {
    /** GET /api/portfolios/{id}/trades — 거래 내역 최신순 200건 */
    @GetMapping("/{id}/trades")
    fun getTrades(@PathVariable id: UUID): ResponseEntity<List<TradeHistoryDto>> {
        val trades = tradeRepository.findTop200ByPortfolioIdOrderByExecutedAtDesc(id)
        return ResponseEntity.ok(trades.map { TradeHistoryDto.from(it) })
    }

    /**
     * GET /api/portfolios/{id}/positions?costMethod=AVG_COST|FIFO
     *
     * costMethod 에 따른 원가 계산 방식:
     *   AVG_COST (기본): 매수 가중평균 단가
     *   FIFO:            가장 오래된 lot 단가 (선입선출 기준 미실현손익용)
     *
     * krwValue = costBasis × quantity → KRW 환산
     */
    @GetMapping("/{id}/positions")
    fun getPositions(
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "AVG_COST") costMethod: CostBasisMethod,
    ): ResponseEntity<List<PositionDto>> {
        val positions = positionCacheService.getPositions(id)
        return ResponseEntity.ok(
            positions.values.map { PositionDto.from(it, costMethod, positionCacheService, currencyConverter) }
        )
    }
}

data class TradeHistoryDto(
    val tradeId: UUID,
    val assetId: UUID,
    val tradeType: String,
    val quantity: BigDecimal,
    val price: BigDecimal,
    val fee: BigDecimal,
    val tradeCurrency: String,
    val executedAt: LocalDateTime,
    val brokerType: String?,
) {
    companion object {
        fun from(e: TradeRawEntity) = TradeHistoryDto(
            tradeId       = e.id,
            assetId       = e.assetId,
            tradeType     = e.tradeType.name,
            quantity      = e.quantity,
            price         = e.price,
            fee           = e.fee,
            tradeCurrency = e.tradeCurrency,
            executedAt    = e.executedAt,
            brokerType    = e.brokerType,
        )
    }
}

data class PositionDto(
    val assetId: UUID,
    val quantity: BigDecimal,
    /** costMethod 에 따른 원가 단가 */
    val costBasis: BigDecimal,
    val currency: String,
    val costMethod: String,
    /** costBasis × quantity → KRW 환산 평가금액 (백엔드 계산) */
    val krwValue: BigDecimal,
) {
    companion object {
        fun from(
            p: PositionData,
            method: CostBasisMethod,
            service: PositionCacheService,
            converter: CurrencyConverter,
        ): PositionDto {
            val basis = service.costBasis(p, method)
            return PositionDto(
                assetId    = p.assetId,
                quantity   = p.quantity,
                costBasis  = basis,
                currency   = p.currency,
                costMethod = method.name,
                krwValue   = converter.toKrw(basis * p.quantity, p.currency),
            )
        }
    }
}
