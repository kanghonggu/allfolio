package com.allfolio.portfolio.application

import com.allfolio.portfolio.application.port.PortfolioRepository
import com.allfolio.portfolio.application.usecase.CreatePortfolioUseCase
import com.allfolio.portfolio.application.usecase.DeletePortfolioUseCase
import com.allfolio.portfolio.application.usecase.ListPortfoliosUseCase
import com.allfolio.portfolio.domain.Portfolio
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class PortfolioUseCaseTest {

    @Test
    fun `create stores user id as owner and defaults base currency to KRW`() {
        val repository = InMemoryPortfolioRepository()
        val useCase = CreatePortfolioUseCase(repository)
        val userId = UUID.randomUUID()

        val portfolio = useCase.execute(userId, "Core Portfolio")

        assertEquals(userId, portfolio.tenantId)
        assertEquals("Core Portfolio", portfolio.name)
        assertEquals("KRW", portfolio.baseCurrency)
        assertEquals("KRW", portfolio.reportingCurrency)
        assertNull(portfolio.benchmarkId)
    }

    @Test
    fun `delete soft deletes an owned portfolio`() {
        val repository = InMemoryPortfolioRepository()
        val createUseCase = CreatePortfolioUseCase(repository)
        val deleteUseCase = DeletePortfolioUseCase(repository)
        val userId = UUID.randomUUID()
        val portfolio = createUseCase.execute(userId, "Core Portfolio")

        deleteUseCase.execute(userId, portfolio.id.value)

        assertNull(repository.findByIdAndUserId(portfolio.id.value, userId))
        assertEquals(setOf(portfolio.id.value), repository.deletedIds)
    }

    @Test
    fun `delete rejects a portfolio owned by another user`() {
        val repository = InMemoryPortfolioRepository()
        val createUseCase = CreatePortfolioUseCase(repository)
        val deleteUseCase = DeletePortfolioUseCase(repository)
        val ownerId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val portfolio = createUseCase.execute(ownerId, "Core Portfolio")

        assertThrows(NoSuchElementException::class.java) {
            deleteUseCase.execute(otherUserId, portfolio.id.value)
        }

        assertEquals(emptySet<UUID>(), repository.deletedIds)
    }

    @Test
    fun `list returns only my active portfolios`() {
        val repository = InMemoryPortfolioRepository()
        val createUseCase = CreatePortfolioUseCase(repository)
        val listUseCase = ListPortfoliosUseCase(repository)
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val active = createUseCase.execute(userId, "Active")
        val deleted = createUseCase.execute(userId, "Deleted")
        createUseCase.execute(otherUserId, "Other")
        repository.softDelete(deleted.id.value, userId)

        val portfolios = listUseCase.execute(userId)

        assertEquals(listOf(active.id), portfolios.map { it.id })
    }

    private class InMemoryPortfolioRepository : PortfolioRepository {
        private val portfolios = linkedMapOf<UUID, Portfolio>()
        val deletedIds = linkedSetOf<UUID>()

        override fun save(portfolio: Portfolio): Portfolio {
            portfolios[portfolio.id.value] = portfolio
            return portfolio
        }

        override fun findByIdAndUserId(id: UUID, userId: UUID): Portfolio? =
            portfolios[id]?.takeIf { it.tenantId == userId && id !in deletedIds }

        override fun findByUserId(userId: UUID): List<Portfolio> =
            portfolios.values.filter { it.tenantId == userId && it.id.value !in deletedIds }

        override fun softDelete(id: UUID, userId: UUID): Int {
            val portfolio = portfolios[id]
                ?.takeIf { it.tenantId == userId && id !in deletedIds }
                ?: return 0
            deletedIds.add(portfolio.id.value)
            return 1
        }
    }
}
