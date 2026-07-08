package com.allfolio.broker

import java.util.UUID

/**
 * 브로커 계좌 1개의 증분 동기화 진입점.
 * BrokerSyncScheduler가 BrokerFacade에 직접 묶이지 않도록 하는 절단면 —
 * 테스트에서 fake로 대체해 병렬 실행 시맨틱을 검증한다.
 */
interface BrokerAccountSyncer {
    /** @return 저장 건수. -1 = rate limited */
    fun syncAccount(brokerType: BrokerType, portfolioId: UUID, accountId: String): Int
}
