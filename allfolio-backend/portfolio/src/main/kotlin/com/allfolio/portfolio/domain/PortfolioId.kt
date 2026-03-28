package com.allfolio.portfolio.domain

import java.util.UUID

data class PortfolioId(val value: UUID) {

    override fun toString(): String = value.toString()

    companion object {
        fun newId(): PortfolioId = PortfolioId(UUID.randomUUID())

        fun of(value: String): PortfolioId = PortfolioId(UUID.fromString(value))

        fun of(value: UUID): PortfolioId = PortfolioId(value)
    }
}
