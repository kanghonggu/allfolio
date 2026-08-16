package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.application.port.KrwConversion
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class RecordInternalFlowUseCaseTest {
    private val saved = mutableListOf<CashFlow>()
    private val repo = object : CashFlowRepository {
        override fun save(cashFlow: CashFlow): CashFlow { saved.add(cashFlow); return cashFlow }
        override fun findById(id: UUID): CashFlow? = null
        override fun findByUserIdAndPeriod(userId: UUID, from: LocalDate, to: LocalDate) = emptyList<CashFlow>()
        override fun findByUserId(userId: UUID) = emptyList<CashFlow>()
        override fun delete(id: UUID) {}
        override fun deleteByAccountId(accountId: UUID) {}
    }
    private val fx = object : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String) =
            if (currency.uppercase() == "KRW") amount else amount * BigDecimal("1300")

        override fun rateOf(currency: String): BigDecimal =
            if (currency.uppercase() == "KRW") BigDecimal.ONE else BigDecimal("1300")
    }
    private val user = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private val a1 = UUID.randomUUID(); private val a2 = UUID.randomUUID()
    private val foreign = UUID.randomUUID()   // other 유저 소유
    private val date = LocalDate.of(2026, 6, 10)

    private fun acct(id: UUID, owner: UUID) = com.allfolio.unifiedasset.domain.account.Account.reconstruct(
        id = id, userId = owner,
        provider = com.allfolio.unifiedasset.domain.account.AccountProvider.MANUAL,
        accountType = com.allfolio.unifiedasset.domain.account.AccountType.MANUAL,
        accountName = "acct", externalId = null, currency = "KRW",
        status = com.allfolio.unifiedasset.domain.account.AccountStatus.ACTIVE,
        lastSyncedAt = null, createdAt = java.time.LocalDateTime.now(),
        apiKey = null, apiSecret = null, walletAddress = null, chain = null,
    )
    private val accountRepo = object : com.allfolio.unifiedasset.application.port.AccountRepository {
        val store = mapOf(a1 to acct(a1, user), a2 to acct(a2, user), foreign to acct(foreign, other))
        override fun save(account: com.allfolio.unifiedasset.domain.account.Account) = account
        override fun findById(id: UUID) = store[id]
        override fun findByUserId(userId: UUID) = store.values.filter { it.userId == userId }
        override fun findByProviders(providers: Collection<com.allfolio.unifiedasset.domain.account.AccountProvider>) = emptyList<com.allfolio.unifiedasset.domain.account.Account>()
        override fun delete(id: UUID) {}
        override fun updateStatus(id: UUID, status: com.allfolio.unifiedasset.domain.account.AccountStatus) {}
    }
    private val uc = RecordInternalFlowUseCase(repo, fx, accountRepo)

    /**
     * 현재 환율은 항상 1300, 발생일 환율은 [datedRate].
     * [estimated]면 그날 환율을 못 찾아 현재 환율(1300)로 근사한 상황을 흉내낸다.
     * KRW는 어느 쪽이든 환산이 없으므로 estimated=false.
     */
    private fun fxStub(datedRate: String = "1100", estimated: Boolean = false) = object : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String) =
            if (currency.uppercase() == "KRW") amount else amount * BigDecimal("1300")

        override fun rateOf(currency: String): BigDecimal =
            if (currency.uppercase() == "KRW") BigDecimal.ONE else BigDecimal("1300")

        override fun toKrwOn(amount: BigDecimal, currency: String, date: LocalDate) = when {
            currency.uppercase() == "KRW" -> KrwConversion(amount, null, false)
            estimated -> KrwConversion(amount * BigDecimal("1300"), null, estimated = true)
            else -> KrwConversion(amount * BigDecimal(datedRate), date, false)
        }
    }

    @Test
    fun `recordTransfer는 2레그를 linkId 공유로 저장한다`() {
        val legs = uc.recordTransfer(user, a1, a2, date, BigDecimal("500"), "KRW", "이체")
        assertThat(legs).hasSize(2)
        assertThat(saved).hasSize(2)
        assertThat(legs.map { it.type }).containsExactlyInAnyOrder(FlowType.TRANSFER_OUT, FlowType.TRANSFER_IN)
        assertThat(legs[0].linkId).isEqualTo(legs[1].linkId)
        assertThat(legs.all { it.amountKrw.compareTo(BigDecimal("500")) == 0 }).isTrue
    }

    @Test
    fun `recordFx는 레그별 amountKrw로 2레그를 저장한다`() {
        // 레그별 값이 서로 달라야 짝이 바뀌었을 때 잡힌다
        val legs = uc.recordFx(user, a1, date, BigDecimal("2600"), "KRW", BigDecimal("1"), "USD", "환전")
        val out = legs.first { it.type == FlowType.FX_OUT }
        val inn = legs.first { it.type == FlowType.FX_IN }
        assertThat(out.amountKrw).isEqualByComparingTo("2600")     // KRW 2600 → 2600
        assertThat(inn.amountKrw).isEqualByComparingTo("1300")     // USD 1 × 1300 → 1300
        assertThat(out.linkId).isEqualTo(inn.linkId)
    }

    @Test
    fun `남의 계좌로 이체하면 예외 (소유권 검증)`() {
        assertThatThrownBy { uc.recordTransfer(user, foreign, a1, date, BigDecimal("500"), "KRW", null) }
            .isInstanceOf(NoSuchElementException::class.java)
        assertThatThrownBy { uc.recordTransfer(user, a1, foreign, date, BigDecimal("500"), "KRW", null) }
            .isInstanceOf(NoSuchElementException::class.java)
        assertThat(saved).isEmpty()   // 실패 시 레그 미기록
    }

    @Test
    fun `남의 계좌로 환전하면 예외 (소유권 검증)`() {
        assertThatThrownBy { uc.recordFx(user, foreign, date, BigDecimal("1300"), "KRW", BigDecimal("1"), "USD", null) }
            .isInstanceOf(NoSuchElementException::class.java)
        assertThatThrownBy { uc.recordFx(user, a1, date, BigDecimal("1300"), "KRW", BigDecimal("1"), "USD", null, toAccountId = foreign) }
            .isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun `존재하지 않는 계좌로 이체하면 예외`() {
        assertThatThrownBy { uc.recordTransfer(user, UUID.randomUUID(), a1, date, BigDecimal("500"), "KRW", null) }
            .isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun `미래 날짜 이체·환전은 예외`() {
        val tomorrow = LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).plusDays(1)
        assertThatThrownBy { uc.recordTransfer(user, a1, a2, tomorrow, BigDecimal("500"), "KRW", null) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { uc.recordFx(user, a1, tomorrow, BigDecimal("1300"), "KRW", BigDecimal("1"), "USD", null) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `음수-같은계좌-같은통화는 예외`() {
        assertThatThrownBy { uc.recordTransfer(user, a1, a2, date, BigDecimal("-1"), "KRW", null) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { uc.recordTransfer(user, a1, a1, date, BigDecimal("1"), "KRW", null) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { uc.recordFx(user, a1, date, BigDecimal("1"), "KRW", BigDecimal("1"), "KRW", null) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `과거 날짜 환전은 그날 환율로 레그별 환산한다`() {
        val legs = RecordInternalFlowUseCase(repo, fxStub(), accountRepo)
            .recordFx(user, a1, date, BigDecimal("1000000"), "KRW", BigDecimal("900"), "USD", "달러 환전")

        // 레그마다 자기 통화·자기 금액으로 환산된다 — 짝이 바뀌면 두 단언 다 깨진다
        assertThat(legs.single { it.currency == "KRW" }.amountKrw).isEqualByComparingTo("1000000")
        // 오늘 환율 1300(=1,170,000)이 아니라 발생일 환율 1100
        assertThat(legs.single { it.currency == "USD" }.amountKrw).isEqualByComparingTo("990000")
    }

    @Test
    fun `계좌간 환전은 레그별 환산·estimated가 각자 적용된다`() {
        // KRW 레그는 환산 없음(estimated=false), USD 레그는 과거 환율을 못 찾아 현재 환율로 근사
        val legs = RecordInternalFlowUseCase(repo, fxStub(estimated = true), accountRepo)
            .recordFx(user, a1, date, BigDecimal("1000000"), "KRW", BigDecimal("800"), "USD", "달러 환전", toAccountId = a2)

        val out = legs.first { it.type == FlowType.FX_OUT }
        val inn = legs.first { it.type == FlowType.FX_IN }
        assertThat(out.amountKrw).isEqualByComparingTo("1000000")   // KRW 그대로
        assertThat(inn.amountKrw).isEqualByComparingTo("1040000")   // USD 800 × 1300(근사)
        // 계좌간 환전 성공 경로 — 레그별 계좌가 갈린다(WARN이 읽는 값)
        assertThat(out.accountId).isEqualTo(a1)
        assertThat(inn.accountId).isEqualTo(a2)
    }

    @Test
    fun `과거 날짜 이체도 그날 환율로 환산한다`() {
        val legs = RecordInternalFlowUseCase(repo, fxStub(), accountRepo)
            .recordTransfer(user, a1, a2, date, BigDecimal("1000"), "USD", "달러 이체")

        // 두 레그가 한 환산을 공유한다 — 스프레드(out−inn)가 0이어야 하므로 필수
        assertThat(legs).hasSize(2)
        assertThat(legs.map { it.amountKrw }).allMatch { it.compareTo(BigDecimal("1100000")) == 0 }
    }

    @Test
    fun `과거 환율을 못 찾으면 현재 환율로 근사하고 메모는 그대로 둔다`() {
        val legs = RecordInternalFlowUseCase(repo, fxStub(estimated = true), accountRepo)
            .recordTransfer(user, a1, a2, date, BigDecimal("1000"), "USD", "달러 이체")

        assertThat(legs.map { it.amountKrw }).allMatch { it.compareTo(BigDecimal("1300000")) == 0 }
        // 사용자가 쓴 메모는 그대로다
        assertThat(legs.map { it.memo }).allMatch { it == "달러 이체" }
    }
}
