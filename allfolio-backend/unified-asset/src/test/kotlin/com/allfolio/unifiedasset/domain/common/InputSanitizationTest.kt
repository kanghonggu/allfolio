package com.allfolio.unifiedasset.domain.common

import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountType
import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.asset.AssetCategory
import com.allfolio.unifiedasset.domain.asset.AssetSourceType
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.asset.ValuationMethod
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

// QA P2 — 통화 화이트리스트 검증 + 계좌명·자산명 서버 사이드 산티타이징(XSS)
class InputSanitizationTest {

    private val userId = UUID.randomUUID()
    private val accountId = UUID.randomUUID()

    // ── Currencies ────────────────────────────────────────────

    @Test
    fun `통화 코드는 대문자로 정규화된다`() {
        assertThat(Currencies.normalize(" krw ")).isEqualTo("KRW")
        assertThat(Currencies.normalize("usd")).isEqualTo("USD")
    }

    @Test
    fun `지원하지 않는 통화는 거부한다`() {
        assertThatIllegalArgumentException().isThrownBy { Currencies.normalize("원") }
        assertThatIllegalArgumentException().isThrownBy { Currencies.normalize("WON") }
        assertThatIllegalArgumentException().isThrownBy { Currencies.normalize("") }
    }

    // ── Account ───────────────────────────────────────────────

    @Test
    fun `계좌명에서 HTML 태그를 제거한다`() {
        val account = Account.create(
            userId = userId, provider = AccountProvider.MANUAL, accountType = AccountType.MANUAL,
            accountName = "<script>alert(1)</script>내 계좌", currency = "KRW",
        )
        assertThat(account.accountName).doesNotContain("<").doesNotContain(">")
        assertThat(account.accountName).contains("내 계좌")
    }

    @Test
    fun `계좌 통화 기본값은 KRW이고 미지원 통화는 거부한다`() {
        val account = Account.create(
            userId = userId, provider = AccountProvider.MANUAL, accountType = AccountType.MANUAL,
            accountName = "계좌",
        )
        assertThat(account.currency).isEqualTo("KRW")
        assertThatIllegalArgumentException().isThrownBy {
            Account.create(
                userId = userId, provider = AccountProvider.MANUAL, accountType = AccountType.MANUAL,
                accountName = "계좌", currency = "원",
            )
        }
    }

    // ── Asset ─────────────────────────────────────────────────

    @Test
    fun `자산명에서 HTML 태그를 제거하고 통화를 검증한다`() {
        val asset = Asset.create(
            userId = userId, accountId = accountId,
            category = AssetCategory.FINANCIAL, type = AssetType.STOCK, sourceType = AssetSourceType.MANUAL,
            name = "<img src=x onerror=alert(1)>삼성전자", symbol = "005930",
            quantity = BigDecimal.ONE, purchasePrice = BigDecimal.TEN, currentValue = BigDecimal.TEN,
            currency = "krw", valuationMethod = ValuationMethod.USER_INPUT,
        )
        assertThat(asset.name).doesNotContain("<").doesNotContain(">")
        assertThat(asset.name).contains("삼성전자")
        assertThat(asset.currency).isEqualTo("KRW")

        assertThatIllegalArgumentException().isThrownBy {
            Asset.create(
                userId = userId, accountId = accountId,
                category = AssetCategory.FINANCIAL, type = AssetType.STOCK, sourceType = AssetSourceType.MANUAL,
                name = "삼성전자", symbol = null,
                quantity = BigDecimal.ONE, purchasePrice = BigDecimal.TEN, currentValue = BigDecimal.TEN,
                currency = "달러", valuationMethod = ValuationMethod.USER_INPUT,
            )
        }
    }
}
