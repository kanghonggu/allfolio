package com.allfolio.trade.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * **`apply` 반복과 `replay`가 정말 같은가** — 험한 시퀀스까지 (AF-174).
 *
 * ## 왜 또 쓰나
 *
 * [FifoCostEngine.replay]의 KDoc이 *"apply를 반복 fold하는 것과 결과는 동일"*이라고
 * **주장한다.** 그런데 두 함수는 자료구조가 다르다 — `apply`는 매번 `ArrayList`를 새로 만들고
 * `replay`는 `ArrayDeque`에 가변 lot을 쌓는다. **같은 규칙을 두 번 구현해 둔 것**이고,
 * 그런 쌍은 경계에서 갈린다.
 *
 * 기존 [FifoCostEngineTest]의 `apply incrementally equals replay in batch`는 그 주장을
 * **매수·매수·부분매도·매수** 하나로만 검사한다. 소진도 초과도 없는 순한 경우다. 갈릴 만한
 * 자리는 전부 안 밟는다:
 *
 * - **초과매도** — `apply` 쪽은 `remaining.signum() <= 0` 분기로 남은 lot을 그대로 복사하고,
 *   `replay` 쪽은 `deque.isNotEmpty()`로 루프를 끝낸다. 종료 조건이 서로 다른 코드다
 * - **빈 포지션 매도** — 소진량 0에 수수료만 남는다. 부호를 한쪽만 틀리면 여기서 갈린다
 * - **lot 정확 소진** — `leftover.signum() > 0`(버림)과 `lot.quantity.signum() == 0`
 *   (`removeFirst`)이 같은 순간을 서로 다른 조건으로 판정한다. 한 칸 어긋나면 빈 lot이 남는다
 *
 * ## 어떻게 검사하나
 *
 * 시퀀스마다 두 경로를 돌려 **수량·평단·실현손익·lot 목록 전체**를 맞춰 본다. 합계만 보면
 * lot이 쪼개지거나 빈 lot이 남는 차이를 놓친다.
 *
 * `@TestFactory`로 시퀀스마다 케이스를 따로 낸다 — 하나로 묶으면 **어느 시퀀스에서 갈렸는지**가
 * 실패 메시지에 안 나온다.
 *
 * ## 변이로 확인한 것 (2026-09-10)
 *
 * `replay`에서만 수수료를 빼지 않게 고치면 **여기 여섯 케이스가 깨진다.** 반면 기존
 * [FifoCostEngineTest]는 **하나도 안 깨진다** — 그쪽 등가성 시퀀스에는 수수료가 붙은 매도가
 * 없기 때문이다. 이 파일이 메우는 구멍이 실재한다는 직접 증거다.
 *
 * ## 🔴 지나가다 본 것 — `replay`에는 무한 루프 여지가 있다
 *
 * 소진된 lot을 빼는 조건(`lot.quantity.signum() == 0`)을 건드리면 while 루프가 **안 끝난다.**
 * 수량 0인 lot이 앞에 남으면 `consumed`가 0이라 `remaining`이 줄지 않고, 초과매도라
 * `deque`도 안 빈다. 변이를 넣어 보다 실제로 매달렸다.
 *
 * 지금 코드는 맞으므로 **고치지 않았다** — lot 엔진 로직 변경은 승인이 필요한 영역이다
 * (금지 목록 Tier 2). 가드를 넣을지는 사람이 정할 일이라 여기 기록만 남긴다.
 */
class FifoCostEngineEquivalenceTest {

    private fun trade(type: TradeType, qty: String, price: String, fee: String = "0") =
        TradeRaw.reconstruct(
            id = TradeId.newId(),
            portfolioId = PORTFOLIO,
            assetId = ASSET,
            tradeType = type,
            quantity = BigDecimal(qty),
            price = BigDecimal(price),
            fee = BigDecimal(fee),
            tradeCurrency = "KRW",
            executedAt = LocalDateTime.now(),
            createdAt = LocalDateTime.now(),
        )

    private fun buy(qty: String, price: String, fee: String = "0") = trade(TradeType.BUY, qty, price, fee)
    private fun sell(qty: String, price: String, fee: String = "0") = trade(TradeType.SELL, qty, price, fee)

    /** 갈릴 만한 자리만 모았다. 이름이 곧 그 자리의 설명이다. */
    private val sequences: Map<String, List<TradeRaw>> = linkedMapOf(
        "빈 포지션에 매도 — 소진량 0, 수수료만 남는다" to listOf(
            sell("5", "300", fee = "40"),
        ),
        "초과매도 — 보유분까지만 소진하고 나머지는 버린다" to listOf(
            buy("10", "100"),
            sell("25", "300", fee = "50"),
        ),
        "lot을 정확히 소진 — 빈 lot이 남으면 안 된다" to listOf(
            buy("10", "100"),
            buy("5", "200"),
            sell("10", "300"),
        ),
        "여러 lot을 정확히 걸쳐 소진" to listOf(
            buy("10", "100"),
            buy("5", "200"),
            sell("15", "300"),
        ),
        "전량 매도 후 재매수 — 실현손익이 누적된다" to listOf(
            buy("10", "100"),
            sell("10", "150"),
            buy("4", "250"),
            sell("2", "300", fee = "10"),
        ),
        "초과매도 뒤에도 매매가 이어진다" to listOf(
            buy("3", "100"),
            sell("10", "120"),
            buy("7", "90"),
            sell("2", "110", fee = "5"),
        ),
        "부분 소진이 lot 중간에서 멈춘다" to listOf(
            buy("10", "100"),
            sell("3", "130"),
            sell("3", "140"),
            sell("3", "150"),
        ),
        "소수 수량 — 나눗셈 없이 더하기만 하므로 정확해야 한다" to listOf(
            buy("0.3", "1000"),
            buy("0.7", "2000"),
            sell("0.9", "3000", fee = "1.5"),
        ),
    )

    @TestFactory
    fun `apply 반복과 replay는 험한 시퀀스에서도 같다`(): List<DynamicTest> =
        sequences.map { (name, trades) ->
            DynamicTest.dynamicTest(name) {
                val incremental = trades.fold(LotPosition.EMPTY) { pos, t ->
                    FifoCostEngine.apply(pos, t.tradeType, t.quantity, t.price, t.fee)
                }
                val batch = FifoCostEngine.replay(trades)

                assertBd("[$name] 수량", incremental.totalQuantity, batch.totalQuantity)
                assertBd("[$name] 평단", incremental.averageCost, batch.averageCost)
                assertBd("[$name] 실현손익", incremental.realizedPnl, batch.realizedPnl)

                // 🔴 합계만 맞추면 lot이 쪼개지거나 빈 lot이 남는 차이를 놓친다.
                assertEquals(
                    incremental.lots.map { it.unitPrice.stripTrailingZeros() to it.quantity.stripTrailingZeros() },
                    batch.lots.map { it.unitPrice.stripTrailingZeros() to it.quantity.stripTrailingZeros() },
                    "[$name] lot 목록이 갈렸다",
                )
            }
        }

    /**
     * 위 등가성이 **비어 있지 않은 주장**임을 보인다.
     *
     * 두 경로가 모두 같은 값을 내는데 그 값이 전부 0이면 등가성 테스트는 아무것도 안 문다.
     * 초과매도 시퀀스가 실제로 손익을 만들어 내는지 한 번 못 박는다.
     */
    @org.junit.jupiter.api.Test
    fun `초과매도는 보유분만큼만 손익을 낸다`() {
        val pos = FifoCostEngine.replay(sequences.getValue("초과매도 — 보유분까지만 소진하고 나머지는 버린다"))

        // 10주만 보유 → 10 x (300 − 100) − 수수료 50 = 1,950
        assertBd("실현손익", BigDecimal("1950"), pos.realizedPnl)
        assertBd("잔여 수량", BigDecimal.ZERO, pos.totalQuantity)
    }

    private fun assertBd(what: String, expected: BigDecimal, actual: BigDecimal) =
        assertEquals(0, expected.compareTo(actual), "$what: expected $expected but was $actual")

    companion object {
        private val PORTFOLIO = UUID.randomUUID()
        private val ASSET = UUID.randomUUID()
    }
}
