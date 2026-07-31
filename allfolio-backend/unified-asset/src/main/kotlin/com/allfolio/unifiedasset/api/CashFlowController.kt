package com.allfolio.unifiedasset.api

import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.usecase.RecordCashFlowUseCase
import com.allfolio.unifiedasset.application.usecase.RecordInternalFlowUseCase
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/cashflows")
class CashFlowController(
    private val recordCashFlow: RecordCashFlowUseCase,
    private val repository: CashFlowRepository,
    private val recordInternalFlow: RecordInternalFlowUseCase,
) {

    data class RecordRequest(
        val accountId: UUID?,
        val flowDate: LocalDate,
        val flowType: FlowType,
        val amount: BigDecimal,
        val currency: String,
        val memo: String?,
    )

    data class CashFlowResponse(
        val id: UUID,
        val accountId: UUID?,
        val flowDate: LocalDate,
        val flowType: FlowType,
        val amount: BigDecimal,
        val currency: String,
        val amountKrw: BigDecimal,
        val memo: String?,
        val linkId: UUID?,
    )

    data class TransferRequest(
        val fromAccountId: UUID, val toAccountId: UUID, val flowDate: LocalDate,
        val amount: BigDecimal, val currency: String, val memo: String?,
    )
    data class FxRequest(
        val accountId: UUID?, val flowDate: LocalDate,
        val fromAmount: BigDecimal, val fromCurrency: String,
        val toAmount: BigDecimal, val toCurrency: String, val memo: String?,
    )

    @PostMapping
    fun record(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestBody request: RecordRequest,
    ): CashFlowResponse = recordCashFlow.record(
        userId = userId, accountId = request.accountId, flowDate = request.flowDate,
        type = request.flowType, amount = request.amount, currency = request.currency,
        memo = request.memo,
    ).toResponse()

    @GetMapping
    fun list(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
    ): List<CashFlowResponse> =
        (if (from != null && to != null) repository.findByUserIdAndPeriod(userId, from, to)
         else repository.findByUserId(userId)).map { it.toResponse() }

    @PostMapping("/transfer")
    fun transfer(@RequestHeader("X-User-Id") userId: UUID, @RequestBody req: TransferRequest): List<CashFlowResponse> =
        recordInternalFlow.recordTransfer(userId, req.fromAccountId, req.toAccountId, req.flowDate, req.amount, req.currency, req.memo)
            .map { it.toResponse() }

    @PostMapping("/fx")
    fun fx(@RequestHeader("X-User-Id") userId: UUID, @RequestBody req: FxRequest): List<CashFlowResponse> =
        recordInternalFlow.recordFx(userId, req.accountId, req.flowDate, req.fromAmount, req.fromCurrency, req.toAmount, req.toCurrency, req.memo)
            .map { it.toResponse() }

    @DeleteMapping("/{id}")
    fun delete(
        @RequestHeader("X-User-Id") userId: UUID,
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        val flow = repository.findById(id)
        // 소유권 검증: 남의 기록은 존재 여부도 노출하지 않는다
        if (flow == null || flow.userId != userId) return ResponseEntity.notFound().build()
        repository.delete(id)
        return ResponseEntity.noContent().build()
    }

    private fun CashFlow.toResponse() = CashFlowResponse(
        id = id, accountId = accountId, flowDate = flowDate, flowType = type,
        amount = amount, currency = currency, amountKrw = amountKrw, memo = memo,
        linkId = linkId,
    )
}
