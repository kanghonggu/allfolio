package com.allfolio.unifiedasset.infrastructure.adapter

import com.allfolio.unifiedasset.application.port.SyncAdapter
import com.allfolio.unifiedasset.application.usecase.ConnectionTestResult
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.asset.AssetCategory
import com.allfolio.unifiedasset.domain.asset.AssetSourceType
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.asset.ValuationMethod
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 한국투자증권(KIS) 잔고조회 동기화 어댑터.
 *
 * 계좌의 apiKey(앱키)/apiSecret(앱시크릿)로 토큰 발급 → 주식잔고조회(TTTC8434R) →
 * 보유종목을 Asset으로 변환한다.
 * - externalId = "{CANO}_{상품코드}" (예: "50123456_01")
 * - 같은 pdno가 매매구분별 다중 행으로 오므로 pdno 기준 합산
 * - prpr/evlu_amt가 0(장 시간외 등)이면 매입평균가로 폴백
 */
@Component
class KisSyncAdapter(
    private val client: KisSyncClient,
) : SyncAdapter {
    private val log = LoggerFactory.getLogger(javaClass)

    override val supportedProvider = AccountProvider.KIS

    override fun sync(account: Account): List<Asset> {
        val appKey     = account.apiKey
        val appSecret  = account.apiSecret
        val externalId = account.externalId
        if (appKey.isNullOrBlank() || appSecret.isNullOrBlank() || externalId.isNullOrBlank()) {
            log.warn("KIS 자격증명/계좌번호 누락 account={}", account.id)
            return emptyList()
        }

        val (cano, prdt) = parseAccountNo(externalId)
        val resp = client.fetchBalance(appKey, appSecret, cano, prdt)
        if (resp.rtCd != "0") {
            throw RuntimeException("KIS 잔고조회 실패: ${resp.msg1}")
        }

        return resp.output1
            .filter { it.pdno.isNotBlank() }
            .groupBy { it.pdno }
            .mapNotNull { (pdno, rows) -> toAsset(account, pdno, rows) }
    }

    override fun testConnection(account: Account): ConnectionTestResult {
        val appKey    = account.apiKey
        val appSecret = account.apiSecret
        if (appKey.isNullOrBlank() || appSecret.isNullOrBlank())
            return ConnectionTestResult(false, "앱키와 앱시크릿을 입력하세요.")
        return try {
            client.issueToken(appKey, appSecret)
            ConnectionTestResult(true, "연결 성공! 앱키 인증 완료")
        } catch (e: Exception) {
            ConnectionTestResult(false, e.message ?: "연결 실패")
        }
    }

    private fun toAsset(account: Account, pdno: String, rows: List<KisBalanceItem>): Asset? {
        val qty = rows.sumOf { it.hldgQty.toBd() }
        if (qty <= BigDecimal.ZERO) return null

        val pchsAmt = rows.sumOf { it.pchsAmt.toBd() }
        val evluAmt = rows.sumOf { it.evluAmt.toBd() }
        val prpr    = rows.map { it.prpr.toBd() }.firstOrNull { it > BigDecimal.ZERO } ?: BigDecimal.ZERO

        val avgCost = if (pchsAmt > BigDecimal.ZERO)
            pchsAmt.divide(qty, 4, RoundingMode.HALF_UP)
        else rows.map { it.pchsAvgPric.toBd() }.firstOrNull { it > BigDecimal.ZERO } ?: BigDecimal.ZERO

        val hasMarketPrice = evluAmt > BigDecimal.ZERO || prpr > BigDecimal.ZERO
        val currentValue = when {
            evluAmt > BigDecimal.ZERO -> evluAmt
            prpr > BigDecimal.ZERO    -> prpr.multiply(qty)
            else                      -> avgCost.multiply(qty)
        }.setScale(2, RoundingMode.HALF_UP)

        return Asset.create(
            userId          = account.userId,
            accountId       = account.id,
            category        = AssetCategory.FINANCIAL,
            type            = AssetType.STOCK,
            sourceType      = AssetSourceType.STOCK_API,
            name            = rows.first().prdtName.ifBlank { pdno },
            symbol          = pdno,
            quantity        = qty,
            purchasePrice   = avgCost,
            currentValue    = currentValue,
            currency        = account.currency,
            valuationMethod = if (hasMarketPrice) ValuationMethod.MARKET_PRICE else ValuationMethod.USER_INPUT,
        )
    }

    /** "50123456_01" → ("50123456","01"). 방어적으로 숫자만 남긴 8-2 파싱도 지원. */
    private fun parseAccountNo(externalId: String): Pair<String, String> {
        val parts = externalId.split("_")
        if (parts.size >= 2 && parts[0].isNotBlank()) return parts[0] to parts[1]
        val digits = externalId.filter { it.isDigit() }
        return if (digits.length >= 10) digits.take(8) to digits.substring(8, 10)
        else digits to "01"
    }

    private fun String.toBd(): BigDecimal = trim().toBigDecimalOrNull() ?: BigDecimal.ZERO
}
