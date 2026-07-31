package com.allfolio.lock

import com.allfolio.workflow.application.WfLockPort
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * 마감 워크플로우 일자 락 (P3 #23) — wf:lock:{ymd}, UserReconSyncMutex와 동일 패턴.
 * TTL 30분(전 사용자 루프 액션 대비 여유). Redis 장애 시 안전 우선 거부.
 */
@Component
class WfDailyLock(private val redis: StringRedisTemplate) : WfLockPort {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun tryAcquire(ymd: LocalDate): String? {
        val token = UUID.randomUUID().toString()
        return runCatching {
            redis.opsForValue().setIfAbsent(key(ymd), token, LOCK_TTL) == true
        }.getOrElse { e ->
            log.warn("[ClosingLock] acquire failed (redis down?) ymd={}", ymd, e)
            false
        }.let { acquired -> if (acquired) token else null }
    }

    override fun release(ymd: LocalDate, token: String) {
        runCatching {
            redis.execute(RELEASE_SCRIPT, listOf(key(ymd)), token)
        }.onFailure { e ->
            log.warn("[ClosingLock] release failed ymd={} (TTL로 자연 해제 예정)", ymd, e)
        }
    }

    private fun key(ymd: LocalDate) = "wf:lock:$ymd"

    companion object {
        val LOCK_TTL: Duration = Duration.ofMinutes(30)

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
