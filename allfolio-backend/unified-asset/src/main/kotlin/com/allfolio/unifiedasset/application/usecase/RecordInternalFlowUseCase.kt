package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.application.port.KrwConversion
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@Service
class RecordInternalFlowUseCase(
    private val repository: CashFlowRepository,
    private val fx: FxConverter,
    private val accountRepository: AccountRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun recordTransfer(
        userId: UUID, fromAccountId: UUID, toAccountId: UUID, flowDate: LocalDate,
        amount: BigDecimal, currency: String, memo: String?,
    ): List<CashFlow> {
        require(amount > BigDecimal.ZERO) { "이체 금액은 양수여야 합니다" }
        requireNotFuture(flowDate)
        requireOwned(userId, fromAccountId)
        requireOwned(userId, toAccountId)
        val cur = com.allfolio.unifiedasset.domain.common.Currencies.normalize(currency)
        // 과거 날짜 이체를 허용하므로(requireNotFuture) 환산도 그 날짜 기준이어야 한다
        val conversion = fx.toKrwOn(amount, cur, flowDate)
        val (out, inn) = CashFlow.transferPair(
            userId, fromAccountId, toAccountId, flowDate, amount, cur, conversion.amountKrw, memo,
        )
        val savedOut = repository.save(out)
        val savedInn = repository.save(inn)
        // 한 번의 환산이 두 레그에 쓰이므로 레그마다 찍어야 정정 대상을 행 단위로 셀 수 있다
        warnIfEstimated(savedOut, conversion)
        warnIfEstimated(savedInn, conversion)
        return listOf(savedOut, savedInn)
    }

    @Transactional
    fun recordFx(
        userId: UUID, accountId: UUID?, flowDate: LocalDate,
        fromAmount: BigDecimal, fromCurrency: String,
        toAmount: BigDecimal, toCurrency: String, memo: String?,
        toAccountId: UUID? = null,   // null이면 동일 계좌, 지정 시 계좌간 환전
    ): List<CashFlow> {
        require(fromAmount > BigDecimal.ZERO && toAmount > BigDecimal.ZERO) { "환전 금액은 양수여야 합니다" }
        requireNotFuture(flowDate)
        accountId?.let { requireOwned(userId, it) }
        toAccountId?.let { requireOwned(userId, it) }
        val fromCur = com.allfolio.unifiedasset.domain.common.Currencies.normalize(fromCurrency)
        val toCur = com.allfolio.unifiedasset.domain.common.Currencies.normalize(toCurrency)
        // 레그별 통화가 다르므로 환산도 레그별로 — 발생일 환율 기준
        val fromConversion = fx.toKrwOn(fromAmount, fromCur, flowDate)
        val toConversion = fx.toKrwOn(toAmount, toCur, flowDate)
        val (out, inn) = CashFlow.fxPair(
            userId, accountId, flowDate,
            fromAmount, fromCur, fromConversion.amountKrw,
            toAmount, toCur, toConversion.amountKrw, memo,
            toAccountId = toAccountId,
        )
        val savedOut = repository.save(out)
        val savedInn = repository.save(inn)
        warnIfEstimated(savedOut, fromConversion)
        warnIfEstimated(savedInn, toConversion)
        return listOf(savedOut, savedInn)
    }

    /**
     * 사용자가 쓴 메모는 서버가 고쳐 쓰지 않는다 — 추정 환산은 로그로만 남긴다.
     * 나중에 정정 대상을 세려면 행 식별자가 있어야 하므로 저장된 레그로 찍는다
     * (저장 전에 찍으면 롤백된 행까지 집계에 잡힌다).
     * 통화·날짜도 flow에서 읽는다 — 레그와 다른 값을 넘길 여지를 두지 않는다.
     */
    private fun warnIfEstimated(flow: CashFlow, conversion: KrwConversion) {
        if (!conversion.estimated) return
        log.warn(
            "[Fx] 과거 환율 없음 — 현재 환율로 환산 flowId={} userId={} accountId={} currency={} date={}",
            flow.id, flow.userId, flow.accountId, flow.currency, flow.flowDate,
        )
    }

    private fun requireNotFuture(flowDate: LocalDate) =
        require(!flowDate.isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Seoul")))) { "미래 날짜는 등록할 수 없습니다" }

    /** 계좌 소유권 검증 (QA) — 남의/없는 계좌는 404로 은닉 (AccountController 패턴 일치) */
    private fun requireOwned(userId: UUID, accountId: UUID) {
        val account = accountRepository.findById(accountId)
        if (account?.userId != userId) throw NoSuchElementException("Account not found: $accountId")
    }
}
