package com.allfolio.reconciliation.application.rules

import com.allfolio.reconciliation.application.RuleDiff
import com.allfolio.reconciliation.domain.DiffType
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * 브로커별 assetId 결정론 파생 (v2 스펙 §4 정정).
 *
 * 자산 마스터 테이블이 없어 내부(position_daily) asset_id는 심볼의 단방향 해시다.
 * 외부(ua_assets) 심볼 × 계좌 provider로 기대 assetId를 파생해 매칭한다.
 * 원본 규칙(변경 시 양쪽 동기화 필요 — 데이터 계약):
 * - KIS     → "KIS:{code}"            (broker/kis/KisTradeMapper)
 * - TOSS    → "toss-asset:{code}"     (broker/toss/TossTradeMapper)
 * - SAMSUNG → "samsung-asset:{isin}"  (broker/samsung/SamsungTradeMapper — ua_assets symbol이 ISIN이 아니면 미매칭 한계)
 * - BINANCE → "binance-asset:{base}"  (external/crypto/BinanceTradeMapper)
 */
object AssetIdDeriver {
    fun derive(provider: String, symbol: String): UUID? {
        val prefix = when (provider.uppercase()) {
            "KIS" -> "KIS:"
            "TOSS" -> "toss-asset:"
            "SAMSUNG" -> "samsung-asset:"
            "BINANCE" -> "binance-asset:"
            else -> return null
        }
        return UUID.nameUUIDFromBytes("$prefix$symbol".toByteArray(Charsets.UTF_8))
    }

    /** trade_raw 파이프라인이 있어 내부 포지션과 대사 가능한 provider 집합. */
    val RECONCILABLE_PROVIDERS = setOf("KIS", "TOSS", "SAMSUNG", "BINANCE")
}

/** 외부(브로커 동기화) 측 user×symbol 집계 포지션. providers = 이 심볼을 보유한 계좌들의 provider. */
data class ExternalPosition(val symbol: String, val providers: Set<String>, val quantity: BigDecimal)

/**
 * 직접 비교 대사 (v2 스펙 §4 — 해시 단계 없는 단일 패스, P2 #14/15).
 * 수량 0 포지션은 양쪽 모두 제외(청산 vs 브로커 미표시 오탐 방지). 비교 필드는 quantity만.
 */
object PositionComparator {

    fun compare(externals: List<ExternalPosition>, internals: Map<UUID, BigDecimal>): List<RuleDiff> {
        val remaining = internals.filterValues { it.signum() != 0 }.toMutableMap()
        val diffs = mutableListOf<RuleDiff>()

        externals
            .map { it.copy(symbol = it.symbol.trim().uppercase()) }
            .filter { it.quantity.signum() != 0 }
            .forEach { ext ->
                val candidates = ext.providers.mapNotNull { AssetIdDeriver.derive(it, ext.symbol) }
                val matched = candidates.filter { remaining.containsKey(it) }
                if (matched.isEmpty()) {
                    diffs += RuleDiff(
                        symbol = ext.symbol, fieldName = "quantity", diffType = DiffType.MISSING_INTERNAL,
                        externalValue = ext.quantity,
                        extras = mapOf("providers" to ext.providers.sorted().joinToString(",")),
                    )
                    return@forEach
                }
                val internalQty = matched.fold(BigDecimal.ZERO) { acc, id -> acc + remaining.remove(id)!! }
                if (normalize(internalQty).compareTo(normalize(ext.quantity)) != 0) {
                    diffs += RuleDiff(
                        symbol = ext.symbol, fieldName = "quantity", diffType = DiffType.VALUE_MISMATCH,
                        internalValue = internalQty, externalValue = ext.quantity,
                        diffValue = ext.quantity.subtract(internalQty),
                    )
                }
            }

        remaining.forEach { (assetId, qty) ->
            diffs += RuleDiff(
                fieldName = "quantity", diffType = DiffType.MISSING_EXTERNAL,
                internalValue = qty,
                extras = mapOf("assetId" to assetId.toString()),
            )
        }
        return diffs
    }

    private fun normalize(v: BigDecimal): BigDecimal = v.setScale(10, RoundingMode.HALF_UP)
}
