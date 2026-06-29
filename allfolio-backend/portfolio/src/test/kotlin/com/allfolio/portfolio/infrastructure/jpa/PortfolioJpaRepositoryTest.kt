package com.allfolio.portfolio.infrastructure.jpa

import com.allfolio.portfolio.infrastructure.entity.PortfolioEntity
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.test.context.ContextConfiguration
import java.time.LocalDateTime
import java.util.UUID

@DataJpaTest
@ContextConfiguration(classes = [PortfolioJpaRepositoryTest.TestConfig::class])
class PortfolioJpaRepositoryTest {

    @Autowired
    private lateinit var repository: PortfolioJpaRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Test
    fun `findByIdAndUserId returns only active portfolios owned by the user`() {
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val active = savePortfolio(userId, "Active")
        val deleted = savePortfolio(userId, "Deleted")
        val other = savePortfolio(otherUserId, "Other")

        repository.softDelete(deleted.id, userId, LocalDateTime.now())
        entityManager.flush()
        entityManager.clear()

        assertEquals(active.id, repository.findByIdAndUserIdAndDeletedAtIsNull(active.id, userId)?.id)
        assertNull(repository.findByIdAndUserIdAndDeletedAtIsNull(deleted.id, userId))
        assertNull(repository.findByIdAndUserIdAndDeletedAtIsNull(other.id, userId))

        val portfolios = repository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(userId)
        assertEquals(listOf(active.id), portfolios.map { it.id })
    }

    private fun savePortfolio(userId: UUID, name: String): PortfolioEntity =
        repository.saveAndFlush(
            PortfolioEntity(
                id = UUID.randomUUID(),
                userId = userId,
                name = name,
                baseCurrency = "KRW",
                createdAt = LocalDateTime.now(),
            )
        )

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = [PortfolioEntity::class])
    @EnableJpaRepositories(basePackageClasses = [PortfolioJpaRepository::class])
    class TestConfig
}
