package com.allfolio.unifiedasset.application.port

import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountStatus
import java.util.UUID

interface AccountRepository {
    fun save(account: Account): Account
    fun findById(id: UUID): Account?

    /**
     * 계좌 소유자만 읽는다 — 계좌 본체를 읽지 못해도 이력을 남기기 위한 경로 (AF-193).
     *
     * [findById]는 저장된 민감정보를 복호화하다 실패할 수 있는데, 그 실패를
     * `ua_sync_logs`에 남기려면 NOT NULL인 `user_id`를 알아야 한다. 운영 구현은
     * 복호화를 타지 않는 프로젝션으로 이 메서드를 재정의한다 — **기본 구현은
     * [findById]를 거치므로 같은 예외를 그대로 되던진다.** 복호화 실패를 흉내내는
     * 테스트 fake는 이 메서드도 함께 재정의해야 실제 구현과 같은 모양이 된다.
     *
     * 계좌 행 자체가 없으면 null.
     */
    fun findUserIdById(id: UUID): UUID? = findById(id)?.userId

    fun findByUserId(userId: UUID): List<Account>
    fun findByProviders(providers: Collection<AccountProvider>): List<Account>
    fun delete(id: UUID)
    fun updateStatus(id: UUID, status: AccountStatus)
}
