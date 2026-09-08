package com.allfolio.unifiedasset.application.port

import com.allfolio.unifiedasset.domain.sync.SyncLog
import java.util.UUID

interface SyncLogRepository {
    fun save(log: SyncLog): SyncLog

    /**
     * 호출자의 트랜잭션과 분리해 저장한다 (AF-196).
     *
     * `SyncAccountUseCase.execute`는 `@Transactional`이고, 계좌 조회 예외를 **그대로
     * 되던지는** 경로가 하나 있다. 거기서 [save]로 남긴 이력은 예외가 프록시 밖으로
     * 나가는 순간 바깥 트랜잭션과 함께 롤백돼 사라진다 — "던지기 전에 남긴다"는 의도가
     * 실현되지 않았다. 그 경로만 이 메서드를 쓴다.
     *
     * 기본 구현은 [save] 그대로다. 트랜잭션이 없는 대역에서는 둘이 같은 뜻이고,
     * **분리가 실제로 되는지는 대역으로 잴 수 없다** — `SyncAccountUseCaseLookupFailureRollbackTest`가
     * 진짜 트랜잭션에서 잰다.
     */
    fun saveIsolated(log: SyncLog): SyncLog = save(log)

    /** created_at 내림차순 최대 limit건. */
    fun findByAccountId(accountId: UUID, limit: Int): List<SyncLog>

    /** 사용자의 계좌별 최신 로그 1건. key=accountId. */
    fun findLatestByUserId(userId: UUID): Map<UUID, SyncLog>

    fun deleteByAccountId(accountId: UUID)
}
