package com.allfolio.unifiedasset.infrastructure.adapter

import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountType
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.asset.ValuationMethod
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class KisSyncAdapterTest {

    private fun kisAccount() = Account.create(
        userId      = java.util.UUID.randomUUID(),
        provider    = AccountProvider.KIS,
        accountType = AccountType.STOCK,
        accountName = "KIS 테스트",
        externalId  = "50123456_01",
        currency    = "KRW",
        apiKey      = "app-key",
        apiSecret   = "app-secret",
    )

    private class FakeKisSyncClient(
        var balance: KisBalanceResponse = KisBalanceResponse(rtCd = "0"),
        var tokenError: Exception? = null,
    ) : KisSyncClient {
        var lastCano: String? = null
        var lastPrdt: String? = null
        override fun issueToken(appKey: String, appSecret: String): String {
            tokenError?.let { throw it }
            return "token"
        }
        override fun fetchBalance(appKey: String, appSecret: String, cano: String, acntPrdtCd: String): KisBalanceResponse {
            lastCano = cano; lastPrdt = acntPrdtCd
            return balance
        }
    }

    @Test
    fun `단일 보유종목을 평가금액 기준 자산으로 매핑한다`() {
        val client = FakeKisSyncClient(KisBalanceResponse(
            rtCd = "0",
            output1 = listOf(KisBalanceItem(
                pdno = "005930", prdtName = "삼성전자", hldgQty = "10",
                pchsAvgPric = "70000", pchsAmt = "700000", prpr = "80000", evluAmt = "800000",
            )),
        ))
        val assets = KisSyncAdapter(client).sync(kisAccount())

        assertEquals(1, assets.size)
        val a = assets.first()
        assertEquals("삼성전자", a.name)
        assertEquals("005930", a.symbol)
        assertEquals(AssetType.STOCK, a.type)
        assertEquals(0, BigDecimal("10").compareTo(a.quantity))
        assertEquals(0, BigDecimal("70000").compareTo(a.purchasePrice))
        assertEquals(0, BigDecimal("800000.00").compareTo(a.currentValue))
        assertEquals(ValuationMethod.MARKET_PRICE, a.valuationMethod)
        assertEquals("50123456", client.lastCano)
        assertEquals("01", client.lastPrdt)
    }

    @Test
    fun `같은 종목의 매매구분 다중 행을 합산해 한 자산으로 만든다`() {
        val client = FakeKisSyncClient(KisBalanceResponse(
            rtCd = "0",
            output1 = listOf(
                KisBalanceItem(pdno = "009150", prdtName = "삼성전기", hldgQty = "1657",
                    pchsAvgPric = "135440", pchsAmt = "224424497", prpr = "150000", evluAmt = "248550000"),
                KisBalanceItem(pdno = "009150", prdtName = "삼성전기", hldgQty = "3",
                    pchsAvgPric = "123000", pchsAmt = "369000", prpr = "150000", evluAmt = "450000"),
            ),
        ))
        val assets = KisSyncAdapter(client).sync(kisAccount())

        assertEquals(1, assets.size)
        val a = assets.first()
        assertEquals(0, BigDecimal("1660").compareTo(a.quantity))
        assertEquals(0, BigDecimal("249000000.00").compareTo(a.currentValue))
    }

    @Test
    fun `현재가와 평가금액이 0이면 매입평균가로 폴백한다`() {
        val client = FakeKisSyncClient(KisBalanceResponse(
            rtCd = "0",
            output1 = listOf(KisBalanceItem(
                pdno = "005930", prdtName = "삼성전자", hldgQty = "10",
                pchsAvgPric = "70000", pchsAmt = "700000", prpr = "0", evluAmt = "0",
            )),
        ))
        val a = KisSyncAdapter(client).sync(kisAccount()).first()
        assertEquals(0, BigDecimal("700000.00").compareTo(a.currentValue))
        assertEquals(ValuationMethod.USER_INPUT, a.valuationMethod)
    }

    @Test
    fun `rt_cd가 0이 아니면 예외를 던진다`() {
        val client = FakeKisSyncClient(KisBalanceResponse(rtCd = "1", msg1 = "조회 실패"))
        val ex = assertThrows(RuntimeException::class.java) { KisSyncAdapter(client).sync(kisAccount()) }
        assertTrue(ex.message!!.contains("조회 실패"))
    }

    @Test
    fun `계좌번호가 없으면 빈 목록을 반환한다`() {
        val account = Account.create(
            userId = java.util.UUID.randomUUID(), provider = AccountProvider.KIS,
            accountType = AccountType.STOCK, accountName = "no-acct",
            externalId = null, currency = "KRW", apiKey = "k", apiSecret = "s",
        )
        assertTrue(KisSyncAdapter(FakeKisSyncClient()).sync(account).isEmpty())
    }

    @Test
    fun `testConnection은 토큰 발급 성공 시 success를 반환한다`() {
        val ok = KisSyncAdapter(FakeKisSyncClient()).testConnection(kisAccount())
        assertTrue(ok.success)

        val fail = KisSyncAdapter(FakeKisSyncClient(tokenError = RuntimeException("잘못된 앱키")))
            .testConnection(kisAccount())
        assertTrue(!fail.success)
        assertTrue(fail.message.contains("잘못된 앱키"))
    }
}
