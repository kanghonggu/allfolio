package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.unifiedasset.domain.account.StockTrade
import com.allfolio.unifiedasset.domain.account.StockTradeType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class FifoRealizedPnlCalculatorTest {

    private val acct = UUID.randomUUID()
    private val user = UUID.randomUUID()
    private val period = ReportPeriod.monthly(2026, 6) // 2026-06-01 ~ 2026-06-30

    private fun trade(type: StockTradeType, symbol: String?, qty: String, price: String, on: LocalDate, fee: String = "0") =
        StockTrade.create(
            accountId = acct, userId = user, tradeType = type, stockName = symbol ?: "?", symbol = symbol,
            quantity = BigDecimal(qty), price = BigDecimal(price),
            totalAmount = BigDecimal(qty).multiply(BigDecimal(price)), fee = BigDecimal(fee), tax = BigDecimal.ZERO,
            tradedAt = on, memo = null,
        )

    /**
     * `createdAt`을 **명시**해 만든다 (AF-200).
     *
     * `StockTrade.create`는 `createdAt`을 `now()`로 채운다 — 연속 호출이 같은 밀리초에
     * 떨어질 수 있어 **같은 날 타이브레이크를 재는 데 못 쓴다.** 그 축을 검사하려면
     * 시각을 우리가 정해야 한다.
     */
    private fun tradeAt(
        type: StockTradeType, symbol: String, qty: String, price: String,
        on: LocalDate, createdAt: java.time.LocalDateTime, fee: String = "0",
    ) = StockTrade.reconstruct(
        id = UUID.randomUUID(), accountId = acct, userId = user, tradeType = type,
        stockName = symbol, symbol = symbol,
        quantity = BigDecimal(qty), price = BigDecimal(price),
        totalAmount = BigDecimal(qty).multiply(BigDecimal(price)),
        fee = BigDecimal(fee), tax = BigDecimal.ZERO, tradedAt = on, memo = null,
        createdAt = createdAt,
    )

    // ── 기간 경계와 정렬 (AF-200 · 변이 FRP-M1·M2·M4) ─────────────

    /**
     * 🔴 **같은 날 여러 거래는 `createdAt`으로 줄을 세운다** (변이 FRP-M1).
     *
     * `tradedAt`은 일 단위라 같은 날 거래는 순서를 못 정한다. 2차 키를 빼면 `sortedWith`가
     * 안정 정렬이라 **입력 순서가 그대로 FIFO 순서가 된다** — 저장소가 주는 순서에 따라
     * 실현손익이 달라지는데, 그건 재현되지 않는 값이다.
     *
     * 여기서는 입력을 `createdAt` 역순으로 넣어 둘을 갈라 놓는다:
     *
     * | 정렬 | 소진되는 lot | 실현손익 |
     * |---|---|---|
     * | `createdAt` (올바름) | 100원 lot | 2,000 |
     * | 입력 순서 (변이) | 200원 lot | 1,000 |
     */
    @Test
    fun `같은 날 거래는 입력 순서가 아니라 createdAt으로 정렬한다`() {
        val day = LocalDate.of(2026, 6, 10)
        val t = java.time.LocalDateTime.of(2026, 6, 10, 9, 0)
        val r = FifoRealizedPnlCalculator.calculate(
            // 일부러 createdAt 역순으로 넣는다
            listOf(
                tradeAt(StockTradeType.BUY, "AAA", "10", "200", day, t.plusMinutes(2)),
                tradeAt(StockTradeType.BUY, "AAA", "10", "100", day, t.plusMinutes(1)),
                tradeAt(StockTradeType.SELL, "AAA", "10", "300", day, t.plusMinutes(3)),
            ),
            period,
        )

        assertThat(r["AAA"]).isEqualByComparingTo(BigDecimal("2000"))
    }

    /**
     * 🔴 **기간 종료일 당일 거래는 포함이다** (변이 FRP-M2).
     *
     * 필터가 `!isAfter(end)`에서 `isBefore(end)`로 한 칸 밀리면 **월말 마지막 날 거래가
     * 그달에서 통째로 빠진다.** 6월 30일에 판 것이 6월 보고서에 안 잡히는 셈이다.
     */
    @Test
    fun `기간 종료일 당일 매도도 당월에 포함한다`() {
        val r = FifoRealizedPnlCalculator.calculate(
            listOf(
                trade(StockTradeType.BUY, "AAA", "10", "100", LocalDate.of(2026, 6, 5)),
                trade(StockTradeType.SELL, "AAA", "10", "150", LocalDate.of(2026, 6, 30)),
            ),
            period,
        )

        assertThat(r["AAA"])
            .describedAs("6/30은 기간의 끝이지 기간 밖이 아니다")
            .isEqualByComparingTo(BigDecimal("500"))
    }

    /**
     * 🔴 **기간 시작일 당일 거래는 당월분이다** (변이 FRP-M4).
     *
     * 누적 스냅샷을 뜨는 시점이 `!isBefore(start)`에서 `isAfter(start)`로 밀리면, 시작일
     * 당일의 실현손익이 **"기간 이전"으로 잡혀 전월로 새어 나간다.**
     *
     * 여기서는 5월에 사서 **6월 1일에 판다.** 당월 실현손익은 전액이어야 한다.
     */
    @Test
    fun `기간 시작일 당일 매도는 당월분이다`() {
        val r = FifoRealizedPnlCalculator.calculate(
            listOf(
                trade(StockTradeType.BUY, "AAA", "10", "100", LocalDate.of(2026, 5, 20)),
                trade(StockTradeType.SELL, "AAA", "10", "150", LocalDate.of(2026, 6, 1)),
            ),
            period,
        )

        assertThat(r["AAA"])
            .describedAs("6/1은 기간의 시작이지 기간 이전이 아니다")
            .isEqualByComparingTo(BigDecimal("500"))
    }

    /** 반대쪽 대조군 — 전월에 이미 판 것은 당월에 안 잡힌다 */
    @Test
    fun `기간 시작 전날 매도는 당월분이 아니다`() {
        val r = FifoRealizedPnlCalculator.calculate(
            listOf(
                trade(StockTradeType.BUY, "AAA", "10", "100", LocalDate.of(2026, 5, 20)),
                trade(StockTradeType.SELL, "AAA", "10", "150", LocalDate.of(2026, 5, 31)),
            ),
            period,
        )

        assertThat(r["AAA"]).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `당월 매수 후 부분매도 실현손익`() {
        val r = FifoRealizedPnlCalculator.calculate(
            listOf(
                trade(StockTradeType.BUY, "AAA", "10", "100", LocalDate.of(2026, 6, 5)),
                trade(StockTradeType.SELL, "AAA", "4", "150", LocalDate.of(2026, 6, 20)),
            ),
            period,
        )
        assertThat(r["AAA"]).isEqualByComparingTo("200") // 4*(150-100)
    }

    @Test
    fun `이전월 매수 lot 원가가 당월 매도 실현손익에 반영된다 (경계)`() {
        val r = FifoRealizedPnlCalculator.calculate(
            listOf(
                trade(StockTradeType.BUY, "BBB", "10", "100", LocalDate.of(2026, 5, 10)),
                trade(StockTradeType.SELL, "BBB", "4", "150", LocalDate.of(2026, 6, 20)),
            ),
            period,
        )
        assertThat(r["BBB"]).isEqualByComparingTo("200") // 옛 lot 원가 100 사용, 당월분만
    }

    @Test
    fun `이전월 매도는 당월에 포함되지 않는다`() {
        val r = FifoRealizedPnlCalculator.calculate(
            listOf(
                trade(StockTradeType.BUY, "CCC", "10", "100", LocalDate.of(2026, 5, 1)),
                trade(StockTradeType.SELL, "CCC", "5", "150", LocalDate.of(2026, 5, 15)), // 5월 실현(제외)
                trade(StockTradeType.SELL, "CCC", "5", "200", LocalDate.of(2026, 6, 15)), // 6월 실현
            ),
            period,
        )
        assertThat(r["CCC"]).isEqualByComparingTo("500") // 5*(200-100), 5월분 5*(150-100) 제외
    }

    @Test
    fun `당월 전량매도 종목도 실현손익이 잡힌다`() {
        val r = FifoRealizedPnlCalculator.calculate(
            listOf(
                trade(StockTradeType.BUY, "DDD", "10", "100", LocalDate.of(2026, 6, 3)),
                trade(StockTradeType.SELL, "DDD", "10", "150", LocalDate.of(2026, 6, 25)),
            ),
            period,
        )
        assertThat(r["DDD"]).isEqualByComparingTo("500")
    }

    @Test
    fun `신용매수 매도도 BUY SELL로 매핑된다`() {
        val r = FifoRealizedPnlCalculator.calculate(
            listOf(
                trade(StockTradeType.CREDIT_BUY, "EEE", "10", "100", LocalDate.of(2026, 6, 5)),
                trade(StockTradeType.CREDIT_SELL, "EEE", "10", "120", LocalDate.of(2026, 6, 20)),
            ),
            period,
        )
        assertThat(r["EEE"]).isEqualByComparingTo("200")
    }

    @Test
    fun `배당 미수 심볼없음은 제외되고 매도없으면 0`() {
        val r = FifoRealizedPnlCalculator.calculate(
            listOf(
                trade(StockTradeType.DIVIDEND, "FFF", "0", "0", LocalDate.of(2026, 6, 5)),
                trade(StockTradeType.MARGIN, "FFF", "1", "100", LocalDate.of(2026, 6, 6)),
                trade(StockTradeType.BUY, "FFF", "10", "100", LocalDate.of(2026, 6, 7)), // 매수만
                trade(StockTradeType.BUY, null, "1", "1", LocalDate.of(2026, 6, 8)),      // symbol 없음 제외
            ),
            period,
        )
        assertThat(r["FFF"]).isEqualByComparingTo("0")
    }

    @Test
    fun `수수료는 실현손익에서 차감된다`() {
        val r = FifoRealizedPnlCalculator.calculate(
            listOf(
                trade(StockTradeType.BUY, "GGG", "10", "100", LocalDate.of(2026, 6, 5)),
                trade(StockTradeType.SELL, "GGG", "10", "150", LocalDate.of(2026, 6, 20), fee = "50"),
            ),
            period,
        )
        assertThat(r["GGG"]).isEqualByComparingTo("450") // 500 - 50
    }
}
