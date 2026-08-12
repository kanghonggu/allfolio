package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@Service
class RecordCashFlowUseCase(
    private val repository: CashFlowRepository,
    private val fxConverter: FxConverter,
    private val accountRepository: AccountRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun record(
        userId: UUID, accountId: UUID?, flowDate: LocalDate, type: FlowType,
        amount: BigDecimal, currency: String, memo: String?,
    ): CashFlow {
        require(amount > BigDecimal.ZERO) { "입출금 금액은 양수여야 합니다" }
        require(!flowDate.isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Seoul")))) { "미래 날짜는 등록할 수 없습니다" }
        // 내부이동(환전·이체)은 반드시 페어 레그로만 기록 → /transfer, /fx 유스케이스 사용
        require(!type.isInternal()) { "환전·이체는 /transfer, /fx 로 기록해야 합니다(페어 레그)" }
        // 계좌 지정 시 소유권 검증 — 남의/없는 계좌는 404로 은닉 (QA)
        accountId?.let { id ->
            if (accountRepository.findById(id)?.userId != userId)
                throw NoSuchElementException("Account not found: $id")
        }
        val cur = com.allfolio.unifiedasset.domain.common.Currencies.normalize(currency)
        // 과거 날짜 입력을 허용하므로(위 require) 환산도 그 날짜 기준이어야 한다
        val conversion = fxConverter.toKrwOn(amount, cur, flowDate)
        val flow = CashFlow.create(userId, accountId, flowDate, type, amount, cur, conversion.amountKrw, memo)
        if (conversion.estimated) {
            // 사용자가 쓴 메모는 서버가 고쳐 쓰지 않는다 — 로그로만 남긴다(행 식별자 포함, 영향 행 수 집계용)
            log.warn(
                "[Fx] 과거 환율 없음 — 현재 환율로 환산 flowId={} userId={} accountId={} currency={} date={}",
                flow.id, userId, accountId, cur, flowDate,
            )
        }
        return repository.save(flow)
    }
}
