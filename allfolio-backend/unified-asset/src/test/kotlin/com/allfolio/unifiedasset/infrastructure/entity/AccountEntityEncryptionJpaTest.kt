package com.allfolio.unifiedasset.infrastructure.entity

import com.allfolio.common.crypto.EncryptedStringConverter
import com.allfolio.common.crypto.EncryptionKeyProvider
import com.allfolio.common.crypto.SensitiveDataReconnectionRequiredException
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountStatus
import com.allfolio.unifiedasset.domain.account.AccountType
import com.allfolio.unifiedasset.infrastructure.jpa.AccountJpaRepository
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ContextConfiguration
import java.time.LocalDateTime
import java.util.UUID

@DataJpaTest
@ContextConfiguration(classes = [AccountEntityEncryptionJpaTest.TestConfig::class])
class AccountEntityEncryptionJpaTest {

    @Autowired
    private lateinit var repository: AccountJpaRepository

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var entityManager: EntityManager

    @Test
    fun `stores account credentials encrypted and reads them decrypted`() {
        val entity = accountEntity(apiKey = "plain-api-key", apiSecret = "plain-api-secret")

        val saved = repository.saveAndFlush(entity)
        entityManager.clear()

        val raw = jdbc.queryForMap(
            "SELECT api_key, api_secret FROM ua_accounts WHERE id = ?",
            saved.id,
        )

        val storedApiKey = raw["api_key"].toString()
        val storedApiSecret = raw["api_secret"].toString()
        assertTrue(storedApiKey.startsWith(EncryptedStringConverter.PREFIX))
        assertTrue(storedApiSecret.startsWith(EncryptedStringConverter.PREFIX))
        assertNotEquals("plain-api-key", storedApiKey)
        assertNotEquals("plain-api-secret", storedApiSecret)

        val reloaded = repository.findById(saved.id).orElseThrow()
        assertEquals("plain-api-key", reloaded.apiKey)
        assertEquals("plain-api-secret", reloaded.apiSecret)
    }

    @Test
    fun `legacy plaintext account credentials require reconnection`() {
        val accountId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO ua_accounts (
                id, user_id, provider, account_type, account_name, currency,
                status, created_at, api_key, api_secret
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            accountId,
            UUID.randomUUID(),
            AccountProvider.BINANCE.name,
            AccountType.EXCHANGE.name,
            "Legacy Binance",
            "USD",
            AccountStatus.ACTIVE.name,
            LocalDateTime.now(),
            "legacy-api-key",
            "legacy-api-secret",
        )
        entityManager.clear()

        val ex = assertThrows<RuntimeException> {
            repository.findById(accountId).orElseThrow()
        }

        assertTrue(ex.hasCause<SensitiveDataReconnectionRequiredException>())
    }

    private fun accountEntity(
        apiKey: String?,
        apiSecret: String?,
    ): AccountEntity = AccountEntity(
        id = UUID.randomUUID(),
        userId = UUID.randomUUID(),
        provider = AccountProvider.BINANCE,
        accountType = AccountType.EXCHANGE,
        accountName = "Binance",
        externalId = null,
        currency = "USD",
        status = AccountStatus.ACTIVE,
        lastSyncedAt = null,
        createdAt = LocalDateTime.now(),
        apiKey = apiKey,
        apiSecret = apiSecret,
        walletAddress = null,
        chain = null,
    )

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
        generateSequence(this as Throwable?) { it.cause }.any { it is T }

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = [AccountEntity::class])
    @EnableJpaRepositories(basePackageClasses = [AccountJpaRepository::class])
    class TestConfig

    companion object {
        private const val KEY = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="

        @JvmStatic
        @BeforeAll
        fun setUpKey() {
            System.setProperty(EncryptionKeyProvider.PROPERTY_NAME, KEY)
        }

        @JvmStatic
        @AfterAll
        fun clearKey() {
            System.clearProperty(EncryptionKeyProvider.PROPERTY_NAME)
        }
    }
}
