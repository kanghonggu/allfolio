package com.allfolio.unifiedasset.application.port

import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountStatus
import java.util.UUID

interface AccountRepository {
    fun save(account: Account): Account
    fun findById(id: UUID): Account?
    fun findByUserId(userId: UUID): List<Account>
    fun findByProviders(providers: Collection<AccountProvider>): List<Account>
    fun delete(id: UUID)
    fun updateStatus(id: UUID, status: AccountStatus)

    /**
     * 계좌 소유자 id만 읽는다 (AF-193).
     *
     * ## 왜 [findById]로 대신할 수 없나
     *
     * 동기화가 **계좌를 찾는 단계에서** 터지면 그 실패는 `ua_sync_logs`에 안 남았다.
     * `record()`가 `Account` 객체를 필요로 해서 구조적으로 못 불렀기 때문이다. 그런데
     * 로그의 `user_id`는 NOT NULL이라 **소유자를 모르면 기록 자체가 불가능하다.**
     *
     * 그리고 그 실패의 가장 흔한 모양이 **복호화 실패**다(민감정보 재연결 필요).
     * [findById]는 그 복호화를 타므로, 실패한 바로 그 호출로 소유자를 알아낼 수 없다.
     * 이 메서드는 컬럼 하나만 읽어 그 문제를 비껴간다.
     *
     * ## 🔴 기본 구현에 기대지 말 것
     *
     * 아래 기본 구현은 [findById]를 타므로 **정작 필요한 순간에 null을 준다.** 테스트
     * 대역이 컴파일되도록 둔 것이고, **실제 어댑터는 복호화를 건너뛰는 경로로 재정의해야
     * 한다**(`AccountRepositoryImpl`이 그렇게 한다. `AccountRepositoryUserIdOverrideTest`가
     * 그 재정의가 사라지지 않게 고정한다).
     */
    fun findUserId(id: UUID): UUID? = runCatching { findById(id)?.userId }.getOrNull()
}
