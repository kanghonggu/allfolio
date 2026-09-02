package com.allfolio.realasset.watch

import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.infrastructure.entity.WatchValuationCacheEntity
import com.allfolio.unifiedasset.infrastructure.jpa.AssetJpaRepository
import com.allfolio.unifiedasset.infrastructure.jpa.WatchValuationCacheJpaRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * [WatchValuationCollectService.Store] 구현. `JpaValuableAssetStore`와 같은 배치다.
 */
@Component
class JpaWatchValuationStore(
    private val assets: AssetJpaRepository,
    private val cache: WatchValuationCacheJpaRepository,
) : WatchValuationCollectService.Store {

    /**
     * 등록된 시계의 ref만 모은다.
     *
     * **`symbol`이 곧 `refKey`다** — W6 선택 UI가 watchpricedata의 정규화된 base ref를
     * 그대로 넣는다. 손으로 적은 값은 매칭이 안 돼 캐시에 행이 안 생기고, 그러면
     * [com.allfolio.realasset.WatchPriceSource]가 null을 돌려준다. 그게 의도된 경로다 —
     * `RtmsSource`가 단지일련번호 없는 자산을 거르는 것과 같다.
     *
     * **빈 값을 거른다.** 공백만 든 symbol로 외부를 부르면 서버가 400을 주거나 최악의 경우
     * 전 ref를 스캔한다.
     */
    override fun heldRefKeys(): List<String> =
        assets.findByTypeIn(listOf(AssetType.WATCH))
            .mapNotNull { it.symbol?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()

    @Transactional
    override fun upsert(response: WatchValuationResponse, collectedAt: LocalDateTime) {
        // 서버가 정규화한 refKey를 우선한다. 없으면 우리가 물어본 ref를 쓴다 —
        // 둘이 갈리면 다음 조회에서 못 찾으므로 저장 키는 서버 기준이어야 한다.
        val refKey = response.refKey?.trim()?.takeIf(String::isNotEmpty)
            ?: response.ref?.trim()?.takeIf(String::isNotEmpty)
            ?: return
        val asOf = response.asOf ?: return
        val median = response.median ?: return

        // 같은 날 두 번 돌려도 행이 늘지 않는다. 값이 바뀌었으면 덮는다 —
        // 30일 창이 하루 굴러가면 같은 asOf라도 중앙값이 달라질 수 있다.
        cache.findByRefKeyAndAsOf(refKey, asOf)?.let { cache.delete(it) }

        cache.save(
            WatchValuationCacheEntity(
                id = UUID.randomUUID(),
                refKey = refKey,
                asOf = asOf,
                windowDays = (response.windowDays ?: 0).toShort(),
                sampleSize = response.sampleSize ?: 0,
                medianKrw = median,
                p25Krw = response.p25,
                p75Krw = response.p75,
                dispersion = response.dispersion,
                officialPriceKrw = response.officialPriceKrw,
                // 서버가 안 주면 낙관 쪽으로 틀리지 않는다
                confidence = response.confidence ?: "LOW",
                // 이 소스는 항상 호가다. 서버 값이 없어도 TRADE로 떨어뜨리지 않는다 —
                // 체결가로 오해되면 손익이 왜곡된다(설계 1절 원칙 4)
                priceBasis = response.priceBasis ?: "ASK",
                collectedAt = collectedAt,
            ),
        )
    }
}
