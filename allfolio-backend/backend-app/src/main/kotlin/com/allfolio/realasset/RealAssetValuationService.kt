package com.allfolio.realasset

import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 저장할 평가 스냅샷 한 건. `real_asset_valuation` 한 행이 된다 */
data class ValuationSnapshot(
    val realAssetId: UUID,
    val valuedOn: LocalDate,
    val unitPrice: BigDecimal,
    val priceUnit: String,
    val valuationKrw: Long,
    val priceAsOf: LocalDate,
    val stalenessDays: Int,
    val priceBasis: PriceBasis,
    val confidence: Confidence?,
)

data class RealAssetValuationSummary(
    val valuedOn: LocalDate,
    val requested: Int,
    val valued: Int,
    val inserted: Int,
    val updated: Int,
    val skipped: List<String>,
    val failed: Int,
    val failures: List<String>,
)

/**
 * 실물자산 평가 스냅샷 배치 (A1 · G5). 매일 19:30 KST에 외부 크론이 부른다.
 *
 * **휴장일에도 돈다.** 시세가 없어도 폴백해서 스냅샷은 생기고 `stalenessDays`만 늘어난다 —
 * 이래야 자산 추이 그래프에 구멍이 안 생긴다. 영업일에만 돌리면 주말마다 선이 끊긴다.
 *
 * **19:30인 이유는 수집 순서다.** 금 수집이 18:20(`collect-commodity.yml`)이므로 그보다 먼저
 * 평가하면 그날 수집분을 못 쓰고 하루 묵은 값으로 스냅샷을 만든다. 설계 문서 초안의 16:30은
 * 수집이 16:00이라는 전제에서 나온 값이었고 그 전제는 AF-108 이후 틀리다.
 *
 * **"산출 불가"(null)와 "실패"(예외)를 다른 칸에 넣는다.** 앞은 연휴·표본 부족처럼 정상 운영에서
 * 늘 있는 일이고 뒤는 코드나 상류가 터진 것이다. 합치면 요약을 보고 어디를 봐야 할지 알 수 없다.
 *
 * **[com.allfolio.market.commodity.CommodityCollectService]의 자산별 실패 격리를 그대로 옮겼다.**
 * 하나가 터져도 나머지를 저장한다 — 예외로 끝내면 살아 있던 평가까지 잃고 그날 스냅샷이 통째로
 * 비어 순자산 그래프에 구멍이 난다.
 */
@Service
class RealAssetValuationService(
    private val sources: List<ValuationSource>,
    private val store: Store,
) {
    interface Store {
        fun activeAssets(): List<RealAsset>

        fun existingAssetIds(valuedOn: LocalDate): Set<UUID>

        fun save(snapshots: List<ValuationSnapshot>, now: Instant)
    }

    fun valuate(valuedOn: LocalDate, now: Instant): RealAssetValuationSummary {
        val assets = store.activeAssets()
        val existing = store.existingAssetIds(valuedOn)

        val skipped = mutableListOf<String>()
        val failures = mutableListOf<String>()

        val snapshots = assets.mapNotNull { asset ->
            val source = sources.firstOrNull { it.supports(asset.assetType) }
            if (source == null) {
                skipped += "${asset.id}: 어댑터 없음 (${asset.assetType})"
                return@mapNotNull null
            }

            // 자산 하나의 실패가 배치 전체를 죽이지 않게 여기서 막는다. 사유는 요약으로 옮긴다 —
            // 로그만 남기면 GitHub Actions에서 어느 자산이 빠졌는지 볼 수 없다.
            val valuation = try {
                source.valuate(asset, valuedOn)
            } catch (e: RuntimeException) {
                failures += "${asset.id}: ${e.message ?: e.javaClass.simpleName}"
                return@mapNotNull null
            }

            if (valuation == null) {
                skipped += "${asset.id}: 산출 불가 (${asset.assetType})"
                return@mapNotNull null
            }
            ValuationSnapshot(
                realAssetId = asset.id,
                valuedOn = valuedOn,
                unitPrice = valuation.unitPrice,
                priceUnit = valuation.priceUnit,
                valuationKrw = valuation.valuationKrw,
                priceAsOf = valuation.priceAsOf,
                stalenessDays = (valuedOn.toEpochDay() - valuation.priceAsOf.toEpochDay()).toInt(),
                priceBasis = valuation.priceBasis,
                confidence = valuation.confidence,
            )
        }

        store.save(snapshots, now)

        return RealAssetValuationSummary(
            valuedOn = valuedOn,
            requested = assets.size,
            valued = snapshots.size,
            inserted = snapshots.count { it.realAssetId !in existing },
            updated = snapshots.count { it.realAssetId in existing },
            skipped = skipped,
            failed = failures.size,
            failures = failures,
        )
    }
}
