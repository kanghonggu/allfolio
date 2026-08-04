package com.allfolio.unifiedasset.api

import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.usecase.RecordCashFlowUseCase
import com.allfolio.unifiedasset.application.usecase.RecordInternalFlowUseCase
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
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

    // 필수 필드는 nullable + Bean Validation — 누락 시 Jackson 역직렬화 실패로
    // "요청 형식이 올바르지 않습니다"로 뭉개지지 않고 필드별 메시지(422)가 나간다 (QA 후속 #5)
    data class RecordRequest(
        val accountId: UUID?,
        @field:NotNull(message = "날짜를 입력하세요")
        val flowDate: LocalDate?,
        @field:NotNull(message = "입출금 유형을 선택하세요")
        val flowType: FlowType?,
        @field:NotNull(message = "금액을 입력하세요")
        @field:DecimalMin(value = "0", inclusive = false, message = "입출금 금액은 양수여야 합니다")
        val amount: BigDecimal?,
        @field:NotBlank(message = "통화를 입력하세요")
        val currency: String?,
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
        @field:NotNull(message = "출발 계좌를 선택하세요")
        val fromAccountId: UUID?,
        @field:NotNull(message = "도착 계좌를 선택하세요")
        val toAccountId: UUID?,
        @field:NotNull(message = "날짜를 입력하세요")
        val flowDate: LocalDate?,
        @field:NotNull(message = "금액을 입력하세요")
        @field:DecimalMin(value = "0", inclusive = false, message = "이체 금액은 양수여야 합니다")
        val amount: BigDecimal?,
        @field:NotBlank(message = "통화를 입력하세요")
        val currency: String?,
        val memo: String?,
    )
    data class FxRequest(
        val accountId: UUID?,
        @field:NotNull(message = "날짜를 입력하세요")
        val flowDate: LocalDate?,
        @field:NotNull(message = "환전 출금(From) 금액을 입력하세요")
        @field:DecimalMin(value = "0", inclusive = false, message = "환전 금액은 양수여야 합니다")
        val fromAmount: BigDecimal?,
        @field:NotBlank(message = "환전 출금(From) 통화를 입력하세요")
        val fromCurrency: String?,
        @field:NotNull(message = "환전 입금(To) 금액을 입력하세요")
        @field:DecimalMin(value = "0", inclusive = false, message = "환전 금액은 양수여야 합니다")
        val toAmount: BigDecimal?,
        @field:NotBlank(message = "환전 입금(To) 통화를 입력하세요")
        val toCurrency: String?,
        val memo: String?,
        val toAccountId: UUID? = null,   // 지정 시 계좌간 환전(FX_IN 도착 계좌). null이면 동일 계좌
    )

    @PostMapping
    fun record(
        @RequestHeader("X-User-Id") userId: UUID,
        @Valid @RequestBody request: RecordRequest,
    ): CashFlowResponse = recordCashFlow.record(
        userId = userId, accountId = request.accountId, flowDate = request.flowDate!!,
        type = request.flowType!!, amount = request.amount!!, currency = request.currency!!,
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
    fun transfer(@RequestHeader("X-User-Id") userId: UUID, @Valid @RequestBody req: TransferRequest): List<CashFlowResponse> =
        recordInternalFlow.recordTransfer(userId, req.fromAccountId!!, req.toAccountId!!, req.flowDate!!, req.amount!!, req.currency!!, req.memo)
            .map { it.toResponse() }

    @PostMapping("/fx")
    fun fx(@RequestHeader("X-User-Id") userId: UUID, @Valid @RequestBody req: FxRequest): List<CashFlowResponse> =
        recordInternalFlow.recordFx(userId, req.accountId, req.flowDate!!, req.fromAmount!!, req.fromCurrency!!, req.toAmount!!, req.toCurrency!!, req.memo, req.toAccountId)
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
