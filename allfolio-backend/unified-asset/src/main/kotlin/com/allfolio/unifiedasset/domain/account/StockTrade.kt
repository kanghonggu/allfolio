package com.allfolio.unifiedasset.domain.account

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

class StockTrade private constructor(
    val id: UUID,
    val accountId: UUID,
    val userId: UUID,
    val tradeType: StockTradeType,
    val stockName: String,
    val symbol: String?,
    val quantity: BigDecimal,
    val price: BigDecimal,
    val totalAmount: BigDecimal,
    val fee: BigDecimal,
    val tax: BigDecimal,
    val tradedAt: LocalDate,
    val memo: String?,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun create(
            accountId: UUID,
            userId: UUID,
            tradeType: StockTradeType,
            stockName: String,
            symbol: String?,
            quantity: BigDecimal,
            price: BigDecimal,
            totalAmount: BigDecimal,
            fee: BigDecimal = BigDecimal.ZERO,
            tax: BigDecimal = BigDecimal.ZERO,
            tradedAt: LocalDate,
            memo: String?,
        ): StockTrade {
            require(fee >= BigDecimal.ZERO && tax >= BigDecimal.ZERO) { "수수료·세금은 음수일 수 없습니다" }
            require(!tradedAt.isAfter(LocalDate.now(KST))) { "미래 날짜 거래는 등록할 수 없습니다" }
            if (tradeType == StockTradeType.DIVIDEND) {
                // 배당은 총액=실수령액이라 수량x단가와 무관 — 음수만 차단
                require(quantity >= BigDecimal.ZERO && price >= BigDecimal.ZERO && totalAmount >= BigDecimal.ZERO) {
                    "배당 금액은 음수일 수 없습니다"
                }
            } else {
                require(quantity > BigDecimal.ZERO) { "수량은 양수여야 합니다" }
                require(price > BigDecimal.ZERO) { "단가는 양수여야 합니다" }
                // FE가 반올림한 정수를 보내므로 1 미만 오차는 허용, 그 이상 불일치는 조작으로 간주
                val expected = quantity * price
                require((totalAmount - expected).abs() <= TOTAL_AMOUNT_TOLERANCE) {
                    "총액이 수량x단가와 일치하지 않습니다 (기대 $expected, 입력 $totalAmount)"
                }
            }
            return StockTrade(
                id          = UUID.randomUUID(),
                accountId   = accountId,
                userId      = userId,
                tradeType   = tradeType,
                stockName   = stockName.trim(),
                symbol      = symbol?.trim()?.uppercase()?.takeIf { it.isNotBlank() },
                quantity    = quantity,
                price       = price,
                totalAmount = totalAmount,
                fee         = fee,
                tax         = tax,
                tradedAt    = tradedAt,
                memo        = memo?.trim()?.takeIf { it.isNotBlank() },
                createdAt   = LocalDateTime.now(),
            )
        }

        private val KST = ZoneId.of("Asia/Seoul")

        /** FE가 Math.round(qty x price) 정수를 보내므로 반올림 오차 1 미만까지 허용 */
        private val TOTAL_AMOUNT_TOLERANCE = BigDecimal.ONE

        fun reconstruct(
            id: UUID, accountId: UUID, userId: UUID, tradeType: StockTradeType,
            stockName: String, symbol: String?, quantity: BigDecimal, price: BigDecimal,
            totalAmount: BigDecimal, fee: BigDecimal, tax: BigDecimal,
            tradedAt: LocalDate, memo: String?, createdAt: LocalDateTime,
        ) = StockTrade(id, accountId, userId, tradeType, stockName, symbol, quantity, price,
            totalAmount, fee, tax, tradedAt, memo, createdAt)
    }
}
