package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.domain.asset.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class EsgReportServiceTest {

    @Mock lateinit var assetRepository: AssetRepository

    private val userId    = UUID.randomUUID()
    private val accountId = UUID.randomUUID()

    private fun svc() = EsgReportService(assetRepository)

    @Test
    fun `자산 없으면 ResponseStatusException 404`() {
        `when`(assetRepository.findByUserId(userId)).thenReturn(emptyList())

        val ex = assertThrows<ResponseStatusException> {
            svc().generate(userId)
        }
        assertEquals(404, ex.statusCode.value())
    }

    @Test
    fun `CASH 단일 자산 - ESG 보고서 반환`() {
        val asset = cashAsset(value = bd("1000000"))
        `when`(assetRepository.findByUserId(userId)).thenReturn(listOf(asset))

        val result = svc().generate(userId)

        assertEquals(userId, result.userId)
        assertEquals("A", result.rating)                          // CASH 총점 78.5 → A
        assertEquals(0, bd("78.50").compareTo(result.totalScore))
        assertEquals(1, result.assetBreakdown.size)
        assertEquals(1, result.topAssets.size)
        assertTrue(result.bottomAssets.isEmpty())                 // 자산 1개면 bottom 없음
    }

    @Test
    fun `assetBreakdown - total 내림차순 정렬`() {
        // CASH(78.5) > STOCK(62.75) > CRYPTO(36.0)
        val assets = listOf(
            cryptoAsset(value = bd("100000")),
            cashAsset(value = bd("100000")),
            stockAsset(value = bd("100000")),
        )
        `when`(assetRepository.findByUserId(userId)).thenReturn(assets)

        val result = svc().generate(userId)

        assertEquals("CASH",   result.assetBreakdown[0].type)
        assertEquals("STOCK",  result.assetBreakdown[1].type)
        assertEquals("CRYPTO", result.assetBreakdown[2].type)
    }

    @Test
    fun `topAssets - ESG 상위 3개`() {
        val assets = (1..5).map { cashAsset(value = bd("100000")) } +
                     listOf(cryptoAsset(value = bd("100000")))
        `when`(assetRepository.findByUserId(userId)).thenReturn(assets)

        val result = svc().generate(userId)

        assertTrue(result.topAssets.size <= 3)
        // topAssets 모두 bottomAssets보다 total이 높거나 같아야 함
        if (result.bottomAssets.isNotEmpty()) {
            val minTop = result.topAssets.minOf { it.total }
            val maxBottom = result.bottomAssets.maxOf { it.total }
            assertTrue(minTop >= maxBottom)
        }
    }

    @Test
    fun `weight - 포트폴리오 내 비중 합산은 1`() {
        val assets = listOf(
            cashAsset(value = bd("300000")),
            stockAsset(value = bd("700000")),
        )
        `when`(assetRepository.findByUserId(userId)).thenReturn(assets)

        val result = svc().generate(userId)

        val totalWeight = result.assetBreakdown.sumOf { it.weight }
        assertEquals(0, bd("1").compareTo(totalWeight.setScale(2)))
    }

    // ── helpers ───────────────────────────────────────────────

    private fun cashAsset(value: BigDecimal) = Asset.create(
        userId = userId, accountId = accountId,
        category = AssetCategory.FINANCIAL, type = AssetType.CASH,
        sourceType = AssetSourceType.MANUAL, name = "현금",
        symbol = null, quantity = value, purchasePrice = bd("1"),
        currentValue = value, currency = "KRW",
        valuationMethod = ValuationMethod.USER_INPUT,
    )

    private fun stockAsset(value: BigDecimal) = Asset.create(
        userId = userId, accountId = accountId,
        category = AssetCategory.FINANCIAL, type = AssetType.STOCK,
        sourceType = AssetSourceType.MANUAL, name = "삼성전자",
        symbol = "005930", quantity = bd("10"), purchasePrice = value.divide(bd("10")),
        currentValue = value, currency = "KRW",
        valuationMethod = ValuationMethod.MARKET_PRICE,
    )

    private fun cryptoAsset(value: BigDecimal) = Asset.create(
        userId = userId, accountId = accountId,
        category = AssetCategory.FINANCIAL, type = AssetType.CRYPTO,
        sourceType = AssetSourceType.MANUAL, name = "비트코인",
        symbol = "BTC", quantity = bd("0.01"), purchasePrice = value.divide(bd("0.01")),
        currentValue = value, currency = "USD",
        valuationMethod = ValuationMethod.MARKET_PRICE,
    )

    private fun bd(s: String) = BigDecimal(s)
}
