package com.allfolio.reconciliation.application

import java.util.UUID

/**
 * 대사↔동기화 상호 배제 락 (P2 #17, v2 스펙 §6).
 * 키는 사용자 단위(recon:lock:{userId}) — 대사와 동기화가 같은 키를 두고 경합한다.
 * Redis 장애 시 안전 우선: 획득 실패로 간주(실행 거부) — 락 인프라 의존 트레이드오프 수용.
 */
interface ReconLockPort {
    /** 획득 성공 시 release용 토큰, 실패(잠김·Redis 장애) 시 null. */
    fun tryAcquire(userId: UUID): String?

    fun release(userId: UUID, token: String)
}

/** 동기화(또는 다른 대사) 진행 중이라 실행이 거부됨 — API에서 409로 매핑. */
class SyncInProgressException(message: String) : RuntimeException(message)
