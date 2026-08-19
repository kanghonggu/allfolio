package com.allfolio.realasset

import com.allfolio.unifiedasset.domain.asset.Asset
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime

data class RealAssetValuationSummary(
    val valuedOn: LocalDate,
    /** 평가 대상으로 읽어 온 자산 수. **0이 정상이다** — 아무도 실물자산을 안 넣었을 수 있다 */
    val requested: Int,
    /** 값이 실제로 바뀌어 저장한 수 */
    val updated: Int,
    /** 평가는 됐는데 값이 같아 저장하지 않은 수. 휴장일에 매일 나오는 정상 상태다 */
    val unchanged: Int,
    /** "자산id: 사유". 산출 불가 — 정상 운영에서 늘 있다 */
    val skipped: List<String>,
    val failed: Int,
    val failures: List<String>,
)

/**
 * 실물자산 평가 배치 (A1 · G5). 매일 19:30 KST에 외부 크론이 부른다.
 *
 * **표를 새로 만들지 않고 `ua_assets`를 갱신한다.** 제품에는 이미 `AssetType.GOLD`가 있고
 * 대시보드·순자산·배분 차트·리포트가 전부 그걸 쓴다. 빠져 있던 것은 표가 아니라 **자동 평가**였다 —
 * 지금 금을 등록하면 `현재 총 가치`를 사람이 손으로 입력하고 `USER_INPUT`/`LOW`로 남는다.
 * 이 배치가 그걸 `MARKET_PRICE`/`HIGH`로 바꾸고 매일 신선하게 유지한다.
 *
 * **휴장일에도 돈다.** 시세가 없어도 폴백해서 직전 영업일 값을 쓰므로, 대부분의 날은
 * `unchanged`로 끝난다 — 그게 정상이고 실패가 아니다.
 *
 * **사용자가 손으로 넣은 값을 덮어쓴다.** 이건 의도다: 시세가 있는 자산에서 수동 입력값은
 * 등록 시점의 추정치이고 시간이 지나면 반드시 틀린다. 다만 **되돌릴 수 없다**는 뜻이기도 해서,
 * 덮어쓰는 대상을 "단위를 해석할 수 있고 시세가 있는 금"으로 좁혔다 —
 * 나머지는 건드리지 않고 `skipped`에 이름을 남긴다.
 *
 * **"산출 불가"(null)와 "실패"(예외)를 다른 칸에 넣는다.** 앞은 연휴·단위 불명처럼 정상 운영에서
 * 늘 있는 일이고 뒤는 코드나 DB가 터진 것이다. 합치면 요약을 보고 어디를 봐야 할지 알 수 없다.
 */
@Service
class RealAssetValuationService(
    private val sources: List<ValuationSource>,
    private val store: Store,
) {
    interface Store {
        /** 평가 대상 자산. 전 사용자다 — 배치는 사용자별로 나뉘지 않는다 */
        fun valuableAssets(): List<Asset>

        /** 값이 바뀐 자산만 넘어온다 */
        fun apply(updates: List<ValuationUpdate>)
    }

    /** 한 자산에 적용할 평가 결과 */
    data class ValuationUpdate(
        val asset: Asset,
        val valuation: Valuation,
        val valuedAt: LocalDateTime,
    )

    fun valuate(valuedOn: LocalDate, now: LocalDateTime): RealAssetValuationSummary {
        val assets = store.valuableAssets()

        val skipped = mutableListOf<String>()
        val failures = mutableListOf<String>()
        var unchanged = 0

        val updates = assets.mapNotNull { asset ->
            val source = sources.firstOrNull { it.supports(asset.type) }
            if (source == null) {
                // 조회 단계에서 이미 걸러지지만, 대상 선정과 어댑터 목록이 갈라지는 날
                // 조용히 사라지지 않게 여기서도 이름을 남긴다.
                skipped += "${asset.id}: 어댑터 없음 (${asset.type})"
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
                skipped += "${asset.id}: 산출 불가 (${asset.type} · 단위=${asset.symbol ?: "없음"})"
                return@mapNotNull null
            }

            // **값이 같으면 쓰지 않는다.** 시세가 D+1이라 대부분의 날은 어제와 같은 값이 나오는데,
            // 그때마다 저장하면 `last_updated_at`이 매일 갱신돼 "언제 실제로 값이 바뀌었나"를
            // 알 수 없게 된다. 그 컬럼은 화면이 신선도를 말할 때 쓰는 값이다.
            if (asset.currentValue.compareTo(valuation.valuationKrw) == 0) {
                unchanged++
                return@mapNotNull null
            }

            ValuationUpdate(asset, valuation, now)
        }

        store.apply(updates)

        return RealAssetValuationSummary(
            valuedOn = valuedOn,
            requested = assets.size,
            updated = updates.size,
            unchanged = unchanged,
            skipped = skipped,
            failed = failures.size,
            failures = failures,
        )
    }
}
