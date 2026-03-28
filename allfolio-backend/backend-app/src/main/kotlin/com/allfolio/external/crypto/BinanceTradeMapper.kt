package com.allfolio.external.crypto

import com.allfolio.trade.application.RecordTradeCommand
import com.allfolio.trade.domain.TradeType
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Binance DTO → 도메인 Command 변환 (Anti-Corruption Layer)
 *
 * 자산 ID 규칙:
 *   symbol의 baseAsset(BTCUSDT → BTC) 을 UUID.nameUUIDFromBytes 로 결정론적 변환
 *   예) BTC → UUID("sha..."), ETH → UUID("sha...")
 *   운영 시 asset 테이블의 실제 UUID로 매핑 테이블 교체 가능
 *
 * 통화 단위:
 *   commissionAsset 그대로 사용 (BNB, USDT 등)
 */
object BinanceTradeMapper {

    /** Binance 심볼에서 BASE 자산 추출 (BTCUSDT → BTC, ETHUSDT → ETH) */
    private val QUOTE_CURRENCIES = listOf("USDT", "BUSD", "BTC", "ETH", "BNB")

    fun baseAsset(symbol: String): String =
        QUOTE_CURRENCIES.firstOrNull { symbol.endsWith(it) && symbol.length > it.length }
            ?.let { symbol.removeSuffix(it) }
            ?: symbol

    /** baseAsset 문자열 → 결정론적 UUID (같은 심볼은 항상 같은 UUID) */
    fun assetId(symbol: String): UUID =
        UUID.nameUUIDFromBytes("binance-asset:${baseAsset(symbol)}".toByteArray(Charsets.UTF_8))

    fun toCommand(
        dto: BinanceTradeDto,
        tenantId: UUID,
        portfolioId: UUID,
    ): RecordTradeCommand = RecordTradeCommand(
        tenantId      = tenantId,
        portfolioId   = portfolioId,
        assetId       = assetId(dto.symbol),
        tradeType     = if (dto.isBuyer) TradeType.BUY else TradeType.SELL,
        quantity      = BigDecimal(dto.qty),
        price         = BigDecimal(dto.price),
        fee           = BigDecimal(dto.commission),
        tradeCurrency = dto.commissionAsset,
        executedAt    = Instant.ofEpochMilli(dto.time)
            .atZone(ZoneOffset.UTC)
            .toLocalDateTime(),
    )
}
