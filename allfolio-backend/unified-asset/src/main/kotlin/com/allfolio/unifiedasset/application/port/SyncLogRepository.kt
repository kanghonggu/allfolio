package com.allfolio.unifiedasset.application.port

import com.allfolio.unifiedasset.domain.sync.SyncLog
import java.util.UUID

interface SyncLogRepository {
    fun save(log: SyncLog): SyncLog

    /**
     * **바깥 트랜잭션이 롤백돼도 남는** 저장 (AF-193 후속).
     *
     * `SyncAccountUseCase.execute`는 `@Transactional`이다. 계좌 조회가 그 밖의 예외로 터져
     * 예외를 그대로 되던지는 경로에서는 **바깥 트랜잭션이 롤백되고, 같은 트랜잭션에 쓴
     * 이력도 함께 사라진다.** 실패를 남기려고 쓴 줄이 실패했다는 이유로 지워지는 셈이다.
     *
     * 그 경로만을 위해 새 트랜잭션으로 쓴다. 여기까지 온 시점엔 아직 아무것도 안 썼으므로
     * 트랜잭션을 나눠도 반쪽짜리 커밋이 생기지 않는다.
     *
     * 🔴 **이 문제는 인메모리 대역으로는 안 보인다.** 대역에는 트랜잭션이 없어서 [save]와
     * 구별이 안 되고, 그래서 처음 구현에서 그냥 [save]를 불렀다가 놓쳤다. 트랜잭션이 없는
     * 구현에서는 [save]와 같게 두는 게 맞다.
     */
    fun saveIsolated(log: SyncLog): SyncLog = save(log)

    /** created_at 내림차순 최대 limit건. */
    fun findByAccountId(accountId: UUID, limit: Int): List<SyncLog>

    /** 사용자의 계좌별 최신 로그 1건. key=accountId. */
    fun findLatestByUserId(userId: UUID): Map<UUID, SyncLog>

    fun deleteByAccountId(accountId: UUID)
}
