package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.asset.AssetType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

data class PortfolioResponse(
    val userId: UUID,
    val totalValue: BigDecimal,
    val currency: String,
    val byType: Map<String, TypeAllocation>,
    val assets: List<AssetSummary>,
)

data class TypeAllocation(
    val type: AssetType,
    val totalValue: BigDecimal,
    val percentage: BigDecimal,
    val count: Int,
)

data class AssetSummary(
    val id: UUID,
    val accountId: UUID,
    val accountName: String,
    val name: String,
    val symbol: String?,
    val exchange: String?,
    val type: AssetType,
    val subType: String?,
    val quantity: BigDecimal,
    val avgCost: BigDecimal?,
    val currentValue: BigDecimal,
    val loanAmount: BigDecimal?,
    val netEquity: BigDecimal,
    val currency: String,
    val unrealizedPnl: BigDecimal,
    val returnRate: BigDecimal,
    val confidenceLevel: String,
)

@Service
class GetPortfolioUseCase(
    private val assetRepository: AssetRepository,
    private val accountRepository: AccountRepository,
) {
    @Transactional(readOnly = true)
    fun execute(userId: UUID): PortfolioResponse {
        val assets = assetRepository.findByUserId(userId)
        val accounts = accountRepository.findByUserId(userId).associateBy { it.id }

        // 가장 비중이 큰 통화를 기준 통화로 사용
        val primaryCurrency = assets
            .groupBy { it.currency }
            .maxByOrNull { (_, list) -> list.sumOf { it.currentValue } }
            ?.key ?: "KRW"

        val primaryAssets = assets.filter { it.currency == primaryCurrency }
        val totalValue = primaryAssets.sumOf { it.currentValue }

        val byType = primaryAssets
            .groupBy { it.type }
            .mapValues { (type, list) ->
                val typeValue = list.sumOf { it.currentValue }
                val pct = if (totalValue > BigDecimal.ZERO)
                    typeValue.divide(totalValue, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP)
                else BigDecimal.ZERO
                TypeAllocation(type, typeValue, pct, list.size)
            }
            .mapKeys { it.key.name }

        val summaries = assets.map { asset ->
            val accountName = accounts[asset.accountId]?.accountName ?: "Unknown"
            val account     = accounts[asset.accountId]
            AssetSummary(
                id               = asset.id,
                accountId        = asset.accountId,
                accountName      = accountName,
                name             = asset.name,
                symbol           = asset.symbol,
                exchange         = resolveExchange(asset.type, account?.provider?.name),
                type             = asset.type,
                subType          = asset.subType,
                quantity         = asset.quantity,
                avgCost          = asset.purchasePrice,
                currentValue     = asset.currentValue,
                loanAmount       = asset.loanAmount,
                netEquity        = asset.netEquity(),
                currency         = asset.currency,
                unrealizedPnl    = asset.unrealizedPnl(),
                returnRate       = asset.returnRate(),
                confidenceLevel  = asset.confidenceLevel.name,
            )
        }.sortedByDescending { it.currentValue }

        return PortfolioResponse(
            userId     = userId,
            totalValue = totalValue,
            currency   = primaryCurrency,
            byType     = byType,
            assets     = summaries,
        )
    }

    // 자산 유형 + provider → SSE 구독 exchange 키 결정
    private fun resolveExchange(type: AssetType, provider: String?): String? = when (type) {
        AssetType.STOCK -> "STOCK"
        AssetType.CRYPTO -> provider  // BINANCE, UPBIT 등 provider 이름 그대로
        else -> null
    }
}
