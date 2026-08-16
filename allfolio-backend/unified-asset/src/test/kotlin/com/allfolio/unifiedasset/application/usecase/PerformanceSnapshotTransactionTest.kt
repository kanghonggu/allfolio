package com.allfolio.unifiedasset.application.usecase

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * `record()`는 performance_daily와 nav_currency_daily를 한 트랜잭션으로 취급해야 한다
 * (`PerformanceSnapshotService.record` KDoc). 즉 `navCurrencyStore.replace`가 던지면
 * `record()`는 그 예외를 삼키지 말고 그대로 밖으로 전파해야 한다.
 *
 * 호출자 일부(예: 계좌 sync)는 이미 `@Transactional` 트랜잭션 안에서 `record()`를 부른다.
 * Postgres는 트랜잭션 안에서 SQL 오류가 나면 그 트랜잭션 전체를 abort 상태로 만들어,
 * 이후 어떤 문장도 커밋 시점에 실패한다. `record()`가 여기서 예외를 잡아 로그만 남기면
 * 호출자는 정상 흐름으로 착각한 채 커밋을 시도하다 아무 진단 정보 없이 실패한다. 게다가
 * NAV 행은 이미 찍혔는데 통화 행이 없는 상태는, AF-106의 읽기 쪽(`JdbcNavFxHistorySource`)이
 * 기여도 분해를 통째로 포기하는 바로 그 상태다 — 둘 다 없는 편이 차라리 깨끗하다.
 * 그래서 `record()` 본문을 try/catch로 감싸면 안 된다.
 */
class PerformanceSnapshotTransactionTest {

    private val ymd = LocalDate.of(2024, 2, 29)

    @Test
    fun `통화 행 쓰기가 실패하면 예외가 그대로 전파된다 - 삼키면 트랜잭션이 이미 죽어 있다`() {
        val jdbc = CapturingJdbcTemplate()
        val store = ThrowingNavCurrencyStore()

        assertThrows(RuntimeException::class.java) {
            snapshotService(jdbc, store).record(
                UUID.randomUUID(),
                mapOf("KRW" to BigDecimal("1000")),
                ymd,
            )
        }
    }
}
