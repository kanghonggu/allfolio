package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class RecordCashFlowUseCaseTest {

    private val userId = UUID.randomUUID()

    private class InMemoryRepo : CashFlowRepository {
        val saved = mutableListOf<CashFlow>()
        override fun save(cashFlow: CashFlow): CashFlow { saved.add(cashFlow); return cashFlow }
        override fun findById(id: UUID) = saved.firstOrNull { it.id == id }
        override fun findByUserIdAndPeriod(userId: UUID, from: LocalDate, to: LocalDate) =
            saved.filter { it.userId == userId && it.flowDate in from..to }
        override fun findByUserId(userId: UUID) = saved.filter { it.userId == userId }
        override fun delete(id: UUID) { saved.removeIf { it.id == id } }
    }

    private val fx = object : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
            if (currency.uppercase() == "KRW") amount else amount * BigDecimal("1400")
    }

    private val ownedAccount = UUID.randomUUID()
    private val accountRepo = object : com.allfolio.unifiedasset.application.port.AccountRepository {
        val store = mapOf(ownedAccount to com.allfolio.unifiedasset.domain.account.Account.reconstruct(
            id = ownedAccount, userId = userId,
            provider = com.allfolio.unifiedasset.domain.account.AccountProvider.MANUAL,
            accountType = com.allfolio.unifiedasset.domain.account.AccountType.MANUAL,
            accountName = "acct", externalId = null, currency = "KRW",
            status = com.allfolio.unifiedasset.domain.account.AccountStatus.ACTIVE,
            lastSyncedAt = null, createdAt = java.time.LocalDateTime.now(),
            apiKey = null, apiSecret = null, walletAddress = null, chain = null,
        ))
        override fun save(account: com.allfolio.unifiedasset.domain.account.Account) = account
        override fun findById(id: UUID) = store[id]
        override fun findByUserId(userId: UUID) = store.values.filter { it.userId == userId }
        override fun findByProviders(providers: Collection<com.allfolio.unifiedasset.domain.account.AccountProvider>) = emptyList<com.allfolio.unifiedasset.domain.account.Account>()
        override fun delete(id: UUID) {}
        override fun updateStatus(id: UUID, status: com.allfolio.unifiedasset.domain.account.AccountStatus) {}
    }

    @Test
    fun `records deposit with fixed krw conversion`() {
        val repo = InMemoryRepo()
        val useCase = RecordCashFlowUseCase(repo, fx, accountRepo)

        val flow = useCase.record(
            userId = userId, accountId = null, flowDate = LocalDate.of(2026, 6, 15),
            type = FlowType.DEPOSIT, amount = BigDecimal("100"), currency = "USD", memo = null,
        )

        assertEquals(0, BigDecimal("140000").compareTo(flow.amountKrw))
        assertEquals(1, repo.saved.size)
    }

    @Test
    fun `krw flow keeps amount as is`() {
        val useCase = RecordCashFlowUseCase(InMemoryRepo(), fx, accountRepo)
        val flow = useCase.record(
            userId = userId, accountId = null, flowDate = LocalDate.of(2026, 6, 15),
            type = FlowType.WITHDRAWAL, amount = BigDecimal("500000"), currency = "KRW", memo = "출금",
        )
        assertEquals(0, BigDecimal("500000").compareTo(flow.amountKrw))
        assertEquals(0, BigDecimal("-500000").compareTo(flow.signedKrw()))
    }

    @Test
    fun `내 계좌 지정 입금은 정상 기록된다`() {
        val repo = InMemoryRepo()
        val useCase = RecordCashFlowUseCase(repo, fx, accountRepo)
        useCase.record(userId, ownedAccount, LocalDate.of(2026, 6, 15), FlowType.DEPOSIT, BigDecimal("100"), "KRW", null)
        assertEquals(1, repo.saved.size)
    }

    @Test
    fun `남의 계좌 지정 입출금은 404로 거부된다 (소유권 검증)`() {
        val useCase = RecordCashFlowUseCase(InMemoryRepo(), fx, accountRepo)
        assertThrows(NoSuchElementException::class.java) {
            useCase.record(userId, UUID.randomUUID(), LocalDate.of(2026, 6, 15),
                FlowType.DEPOSIT, BigDecimal("100"), "KRW", null)
        }
    }

    @Test
    fun `non-positive amount is rejected`() {
        val useCase = RecordCashFlowUseCase(InMemoryRepo(), fx, accountRepo)
        assertThrows(IllegalArgumentException::class.java) {
            useCase.record(
                userId = userId, accountId = null, flowDate = LocalDate.of(2026, 6, 15),
                type = FlowType.DEPOSIT, amount = BigDecimal.ZERO, currency = "KRW", memo = null,
            )
        }
    }

    @Test
    fun `future flow date is rejected`() {
        val useCase = RecordCashFlowUseCase(InMemoryRepo(), fx, accountRepo)
        val tomorrow = LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).plusDays(1)
        assertThrows(IllegalArgumentException::class.java) {
            useCase.record(
                userId = userId, accountId = null, flowDate = tomorrow,
                type = FlowType.DEPOSIT, amount = BigDecimal("100"), currency = "KRW", memo = null,
            )
        }
    }

    @Test
    fun `today flow date is accepted`() {
        val useCase = RecordCashFlowUseCase(InMemoryRepo(), fx, accountRepo)
        val today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
        val flow = useCase.record(
            userId = userId, accountId = null, flowDate = today,
            type = FlowType.DEPOSIT, amount = BigDecimal("100"), currency = "KRW", memo = null,
        )
        assertEquals(today, flow.flowDate)
    }

    @Test
    fun `internal flow types are rejected on generic record path`() {
        val useCase = RecordCashFlowUseCase(InMemoryRepo(), fx, accountRepo)
        // 내부이동(환전·이체)은 페어 레그로만 기록되어야 하므로 /record 우회 차단
        listOf(FlowType.TRANSFER_IN, FlowType.TRANSFER_OUT, FlowType.FX_IN, FlowType.FX_OUT).forEach { t ->
            assertThrows(IllegalArgumentException::class.java) {
                useCase.record(
                    userId = userId, accountId = null, flowDate = LocalDate.of(2026, 6, 15),
                    type = t, amount = BigDecimal("100"), currency = "KRW", memo = null,
                )
            }
        }
    }
}
