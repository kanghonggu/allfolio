package com.allfolio.api.portfolio

import com.allfolio.config.GlobalExceptionHandler
import com.allfolio.portfolio.application.port.PortfolioRepository
import com.allfolio.portfolio.application.usecase.CreatePortfolioUseCase
import com.allfolio.portfolio.application.usecase.DeletePortfolioUseCase
import com.allfolio.portfolio.application.usecase.ListPortfoliosUseCase
import com.allfolio.portfolio.domain.Portfolio
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID

class PortfolioControllerTest {
    private val repository = InMemoryPortfolioRepository()
    private val createUseCase = CreatePortfolioUseCase(repository)
    private val listUseCase = ListPortfoliosUseCase(repository)
    private val deleteUseCase = DeletePortfolioUseCase(repository)
    private val mockMvc = MockMvcBuilders
        .standaloneSetup(PortfolioController(createUseCase, listUseCase, deleteUseCase))
        .setControllerAdvice(GlobalExceptionHandler())
        .build()

    @Test
    fun `POST portfolios creates a portfolio`() {
        val userId = UUID.randomUUID()

        mockMvc.post("/api/portfolios") {
            header("X-User-Id", userId)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Core Portfolio"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.userId") { value(userId.toString()) }
            jsonPath("$.name") { value("Core Portfolio") }
            jsonPath("$.baseCurrency") { value("KRW") }
        }
    }

    @Test
    fun `GET portfolios returns only my active portfolios`() {
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val active = createUseCase.execute(userId, "Active")
        val deleted = createUseCase.execute(userId, "Deleted")
        createUseCase.execute(otherUserId, "Other")
        repository.softDelete(deleted.id.value, userId)

        mockMvc.get("/api/portfolios") {
            header("X-User-Id", userId)
        }.andExpect {
            status { isOk() }
            jsonPath("$", hasSize<Any>(1))
            jsonPath("$[0].id") { value(active.id.value.toString()) }
            jsonPath("$[0].name") { value("Active") }
        }
    }

    @Test
    fun `DELETE portfolios soft deletes an owned portfolio`() {
        val userId = UUID.randomUUID()
        val portfolio = createUseCase.execute(userId, "Core Portfolio")

        mockMvc.delete("/api/portfolios/${portfolio.id.value}") {
            header("X-User-Id", userId)
        }.andExpect {
            status { isOk() }
        }

        mockMvc.get("/api/portfolios") {
            header("X-User-Id", userId)
        }.andExpect {
            status { isOk() }
            jsonPath("$", hasSize<Any>(0))
        }
    }

    @Test
    fun `DELETE portfolios returns 404 for another user's portfolio`() {
        val ownerId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val portfolio = createUseCase.execute(ownerId, "Core Portfolio")

        mockMvc.delete("/api/portfolios/${portfolio.id.value}") {
            header("X-User-Id", otherUserId)
        }.andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `DELETE portfolios returns 404 for a missing portfolio`() {
        val userId = UUID.randomUUID()

        mockMvc.delete("/api/portfolios/${UUID.randomUUID()}") {
            header("X-User-Id", userId)
        }.andExpect {
            status { isNotFound() }
        }
    }

    private class InMemoryPortfolioRepository : PortfolioRepository {
        private val portfolios = linkedMapOf<UUID, Portfolio>()
        private val deletedIds = linkedSetOf<UUID>()

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
