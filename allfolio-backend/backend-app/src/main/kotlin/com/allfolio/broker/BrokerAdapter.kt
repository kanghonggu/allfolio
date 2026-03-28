package com.allfolio.broker

import com.allfolio.trade.application.RecordTradeCommand
import java.util.UUID

/**
 * 브로커 어댑터 인터페이스 (Anti-Corruption Layer)
 *
 * 원칙:
 * - 도메인 레이어는 이 인터페이스를 모른다 (RecordTradeCommand만 공유)
 * - 외부 API 세부 사항은 구현체 내부에 완전히 캡슐화
 * - 새 증권사 추가 = BrokerAdapter 구현 + @Component 등록
 */
interface BrokerAdapter {
    val brokerType: BrokerType

    /**
     * 증분 거래 내역 조회
     *
     * @param portfolioId 포트폴리오 ID
     * @param accountId   증권 계좌 ID (브로커마다 형태 다름)
     * @param cursor      마지막 처리 커서 (빈 문자열 = 최초 조회)
     * @return            커맨드 목록 + 다음 커서
     */
    fun fetchTrades(
        portfolioId: UUID,
        accountId: String,
        cursor: String = "",
    ): BrokerTradeResult

    /**
     * 계좌 목록 조회
     * @param userId 시스템 사용자 ID
     */
    fun fetchAccounts(userId: UUID): List<BrokerAccountInfo>
}

/**
 * fetchTrades 결과
 * @param commands   RecordTradeUseCase로 전달할 커맨드 목록
 * @param nextCursor 다음 호출 시 사용할 커서 (마지막 externalId 또는 timestamp)
 */
data class BrokerTradeResult(
    val commands: List<RecordTradeCommand>,
    val nextCursor: String,
)

/** 계좌 정보 공통 모델 */
data class BrokerAccountInfo(
    val accountId: String,
    val accountName: String,
    val currency: String,
)
