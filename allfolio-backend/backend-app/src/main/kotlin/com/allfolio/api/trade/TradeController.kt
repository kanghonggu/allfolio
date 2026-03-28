package com.allfolio.api.trade

import com.allfolio.trade.application.RecordTradeUseCase
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@RequestMapping("/api/trades")
class TradeController(
    private val recordTradeUseCase: RecordTradeUseCase,
) {
    /**
     * POST /api/trades
     * 거래 기록 — INSERT ONLY, Snapshot 생성 미포함
     */
    @PostMapping
    fun createTrade(
        @RequestBody @Valid request: TradeRequest,
    ): ResponseEntity<TradeResponse> {
        val tradeId = recordTradeUseCase.record(request.toCommand())
        return ResponseEntity
            .created(URI.create("/api/trades/${tradeId.value}"))
            .body(TradeResponse.from(tradeId))
    }
}
