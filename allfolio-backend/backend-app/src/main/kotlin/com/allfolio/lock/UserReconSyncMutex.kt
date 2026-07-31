package com.allfolio.lock

import com.allfolio.reconciliation.application.ReconLockPort
import com.allfolio.unifiedasset.application.port.ReconMutex
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/**
 * 대사↔동기화 상호 배제 락 단일 구현 (P2 #17) — reconciliation·unified-asset 양쪽 포트를 구현해
 * 두 모듈이 코드 의존 없이 같은 키(recon:lock:{userId})를 공유한다.
 *
 * - 획득: SET NX EX(5분) — Toss/SamsungApiClient의 기존 락 패턴과 동일
 * - 해제: 토큰 비교 후 삭제(Lua) — 만료 후 타 주체 락을 지우는 사고 방지
 * - Redis 장애: 획득 실패로 간주(안전 우선 거부, v2 스펙 §6 트레이드오프)
 */
@Component
class UserReconSyncMutex(
    private val redis: StringRedisTemplate,
) : ReconLockPort, ReconMutex {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun tryAcquire(userId: UUID): String? {
        val token = UUID.randomUUID().toString()
        return runCatching {
            redis.opsForValue().setIfAbsent(key(userId), token, LOCK_TTL) == true
        }.getOrElse { e ->
            log.warn("[ReconLock] acquire failed (redis down?) userId={}", userId, e)
            false
        }.let { acquired -> if (acquired) token else null }
    }

    override fun release(userId: UUID, token: String) {
        runCatching {
            redis.execute(RELEASE_SCRIPT, listOf(key(userId)), token)
        }.onFailure { e ->
            // TTL이 있어 방치해도 5분 내 자연 해제 — 로그만 남긴다
            log.warn("[ReconLock] release failed userId={} (TTL로 자연 해제 예정)", userId, e)
        }
    }

    private fun key(userId: UUID) = "recon:lock:$userId"

    companion object {
        /** 예상 최대 실행시간 + 버퍼 */
        val LOCK_TTL: Duration = Duration.ofMinutes(5)

        private val RELEASE_SCRIPT: RedisScript<Long> = RedisScript.of(
            """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """.trimIndent(),
            Long::class.java,
        )
    }
}
