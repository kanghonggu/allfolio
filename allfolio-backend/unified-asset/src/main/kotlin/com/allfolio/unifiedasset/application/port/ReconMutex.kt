package com.allfolio.unifiedasset.application.port

import java.util.UUID

/**
 * 대사↔동기화 상호 배제 락 (P2 #17). reconciliation 모듈 ReconLockPort와 같은 키
 * (recon:lock:{userId})를 쓰는 동기화 측 포트 — 두 모듈이 서로 코드 의존 없이
 * backend-app의 단일 구현(UserReconSyncMutex)을 공유한다.
 */
interface ReconMutex {
    /** 획득 성공 시 release용 토큰, 실패(대사 진행 중·Redis 장애) 시 null. */
    fun tryAcquire(userId: UUID): String?

    fun release(userId: UUID, token: String)
}
