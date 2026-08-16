package com.allfolio.unifiedasset.application.port

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * 통화 하나의 그날 평가액.
 *
 * @param valueNative 환산 전 원통화 금액
 * @param fxRate      그날 적용한 1단위당 KRW. KRW와 미지원 통화는 1
 */
data class CurrencyValue(val currency: String, val valueNative: BigDecimal, val fxRate: BigDecimal)

/**
 * 통화별 일간 평가액 저장 포트 (AF-106).
 *
 * 구현은 backend-app의 `NavCurrencyDailyStore`다 — 거래 경로(`SnapshotTriggerService`)와
 * 같은 스토어를 쓴다. **SQL 소유자를 하나로 유지하는 것이 이 포트의 존재 이유다**:
 * 같은 테이블에 쓰는 코드가 두 벌이면 스키마가 바뀌는 날 한쪽만 고쳐진다.
 *
 * 포트가 필요한 이유는 모듈 의존이 단방향이기 때문이다 — `backend-app → unified-asset`이라
 * unified-asset이 스토어를 직접 부를 수 없다.
 */
interface NavCurrencyStore {
    /** 해당 (portfolio, date)의 기존 행을 지우고 [values]로 대체한다. 빈 목록이면 지우기만 한다. */
    fun replace(portfolioId: UUID, date: LocalDate, values: List<CurrencyValue>)
}
