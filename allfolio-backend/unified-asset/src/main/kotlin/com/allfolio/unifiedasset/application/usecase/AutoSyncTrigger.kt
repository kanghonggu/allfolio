package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.domain.account.AccountStatus
import com.allfolio.unifiedasset.domain.sync.SyncTrigger
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 동기화를 요청 스레드 밖으로 던지는 seam.
 * AccountSyncRunner와 같은 이유로 인터페이스를 둔다 — 테스트에서 fake로 대체 가능하게.
 * 유일 구현체는 AsyncAccountSyncExecutor.
 */
interface AccountSyncDispatcher {
    fun dispatch(accountId: UUID)
}

/**
 * 쓰기 작업(거래 저장·삭제, 계좌 생성) 직후 해당 계좌 동기화를 자동으로 건다 (AF-90).
 *
 * 거래를 저장해도 ua_assets가 갱신되지 않아, 사용자가 sync 화면을 찾아 "재동기화"를
 * 누르기 전까지 대시보드가 ₩0으로 남던 문제를 없앤다.
 *
 * 동기화는 종목별 시세 조회(HTTP)를 포함해 느릴 수 있으므로 응답을 막지 않는다.
 * 대신 요청 스레드에서 계좌 상태를 즉시 SYNCING으로 바꿔, 프론트가 저장 직후부터
 * "반영 중"을 표시하고 완료를 폴링할 수 있게 한다(비동기 시작 시점 경합 제거).
 */
@Component
class AutoSyncTrigger(
    private val accountRepository: AccountRepository,
    private val dispatcher: AccountSyncDispatcher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun requestSync(accountId: UUID) {
        runCatching { accountRepository.updateStatus(accountId, AccountStatus.SYNCING) }
            .onFailure { e -> log.warn("[AutoSync] SYNCING 표시 실패 accountId={}", accountId, e) }

        runCatching { dispatcher.dispatch(accountId) }
            .onFailure { e ->
                // 비동기 제출 자체가 거부되면 계좌가 SYNCING에 갇힌다 — 즉시 되돌린다
                log.error("[AutoSync] 자동 동기화 제출 실패 accountId={}", accountId, e)
                runCatching { accountRepository.updateStatus(accountId, AccountStatus.ACTIVE) }
            }
    }
}

/**
 * @Async 프록시가 걸리도록 분리한 실행 빈.
 * AutoSyncTrigger 안에서 자기 메서드를 호출하면 프록시를 타지 않아 동기 실행돼 버린다.
 */
@Component
class AsyncAccountSyncExecutor(
    private val syncRunner: AccountSyncRunner,
    private val accountRepository: AccountRepository,
) : AccountSyncDispatcher {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    override fun dispatch(accountId: UUID) {
        // 결과를 전달할 호출자가 없다 — 상태와 이력은 SyncAccountUseCase가 남긴다
        runCatching { syncRunner.execute(accountId, SyncTrigger.AUTO) }
            .onSuccess { result ->
                log.info("[AutoSync] 완료 accountId={} synced={} status={}",
                    accountId, result.synced, result.status)
            }
            .onFailure { e ->
                // execute()는 내부에서 실패를 ERROR로 흡수하지만, 그 밖으로 던지는 경우
                // 계좌가 SYNCING으로 남는다. 프론트가 무한 폴링하지 않도록 상태를 확정한다.
                log.error("[AutoSync] 실패 accountId={}", accountId, e)
                runCatching { accountRepository.updateStatus(accountId, AccountStatus.ERROR) }
            }
    }
}
