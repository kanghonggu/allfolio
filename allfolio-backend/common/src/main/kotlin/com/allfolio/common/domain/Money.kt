package com.allfolio.common.domain

import java.math.BigDecimal
import java.math.RoundingMode

data class Money(
    val amount: BigDecimal,
    val currency: String,
) {
    init {
        require(currency.isNotBlank()) { "Currency must not be blank" }
    }

    operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        return Money(amount + other.amount, currency)
    }

    operator fun minus(other: Money): Money {
        requireSameCurrency(other)
        return Money(amount - other.amount, currency)
    }

    operator fun times(factor: BigDecimal): Money =
        Money(amount.multiply(factor), currency)

    operator fun times(factor: Double): Money =
        this * factor.toBigDecimal()

    fun isPositive(): Boolean = amount > BigDecimal.ZERO

    fun isNegative(): Boolean = amount < BigDecimal.ZERO

    fun isZero(): Boolean = amount.compareTo(BigDecimal.ZERO) == 0

    fun requireNonNegative() {
        require(amount >= BigDecimal.ZERO) {
            "Money amount must be non-negative: $amount $currency"
        }
    }

    fun scale(scale: Int, roundingMode: RoundingMode = RoundingMode.HALF_UP): Money =
        Money(amount.setScale(scale, roundingMode), currency)

    private fun requireSameCurrency(other: Money) {
        require(currency == other.currency) {
            "Currency mismatch: $currency vs ${other.currency}"
        }
    }

    override fun toString(): String = "$amount $currency"

    companion object {
        fun zero(currency: String): Money = Money(BigDecimal.ZERO, currency)

        fun of(amount: Long, currency: String): Money =
            Money(BigDecimal.valueOf(amount), currency)

        fun of(amount: Double, currency: String): Money =
            Money(BigDecimal.valueOf(amount), currency)
    }
}
