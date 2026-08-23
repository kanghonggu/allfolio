package com.allfolio.realasset

import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.asset.ValuationMethod
import com.allfolio.unifiedasset.infrastructure.jpa.AssetJpaRepository
import org.springframework.stereotype.Component

/**
 * `ua_assets`를 [RealAssetValuationService.Store]에 맞춘다. `JpaCommodityStore`와 같은 배치다.
 *
 * **읽기는 JPA 레포로, 쓰기는 도메인 포트로 한다.** 대상 선정은 `type IN (...)` 한 방이라
 * 파생 쿼리가 필요하고(도메인 포트에는 그런 조회가 없다), 저장은 `Asset.revalued`가 만든
 * 도메인 객체를 그대로 넘기는 편이 엔티티 조립을 두 벌 만들지 않는다.
 */
@Component
class JpaValuableAssetStore(
    private val jpa: AssetJpaRepository,
    private val assets: AssetRepository,
) : RealAssetValuationService.Store {

    override fun valuableAssets(): List<Asset> =
        jpa.findByTypeIn(VALUABLE_TYPES).map { it.toDomain() }

    override fun apply(updates: List<RealAssetValuationService.ValuationUpdate>) {
        if (updates.isEmpty()) return
        assets.saveAll(updates.map { it.asset.revalued(it.valuation, it.valuedAt) })
    }

    // internal인 이유: `VALUABLE_TYPES`가 이 클래스의 내부 사정이 아니라 **계약**이기
    // 때문이다 — 어댑터가 맡는 유형과 이 목록이 어긋나면 그 자산은 조용히 평가되지
    // 않는다. `ValuationSourceWiringTest`가 둘을 대조하려면 볼 수 있어야 한다.
    internal companion object {
        /**
         * 자동 평가할 수 있는 유형만 읽는다. **전 자산을 읽어 어댑터로 거르지 않는 이유**는
         * 수천 행짜리 주식·코인까지 매일 끌어오게 되기 때문이다 — 그쪽은 브로커 동기화가 이미
         * 값을 채우고 있어 이 배치가 볼 이유가 없다.
         *
         * **유형이 늘면 여기 한 줄 더 적을 것.** 어댑터만 추가하면 그 자산은 조회에서 빠져
         * 조용히 평가되지 않는다 — `ValuationSourceWiringTest`가 그 어긋남을 문다.
         */
        val VALUABLE_TYPES = listOf(AssetType.GOLD, AssetType.REAL_ESTATE)
    }
}

/**
 * 평가 결과를 반영한 새 [Asset]. `Asset`은 불변이라 새 인스턴스를 만든다.
 *
 * **`valuationMethod`·`confidenceLevel`을 함께 바꾼다.** 값만 갱신하고 이 둘을 `USER_INPUT`/`LOW`로
 * 두면, 화면은 자동 평가된 신선한 숫자를 "사용자가 손으로 넣은 오래된 값"이라고 설명하게 된다.
 * 셋은 같이 움직여야 하는 값이다.
 */
internal fun Asset.revalued(valuation: Valuation, valuedAt: java.time.LocalDateTime): Asset =
    Asset.reconstruct(
        // **전부 이름 인자로 넘긴다.** `Asset`은 불변이라 여기서 안 적은 필드는 기본값으로
        // 떨어지는데, 그건 "안 바꿈"이 아니라 **지움**이다. 위치 인자로 두면 필드가 늘 때
        // 조용히 빠지고, 특히 `exclusiveAreaM2`처럼 같은 타입이 이웃하면 컴파일도 통과한다.
        id = id,
        userId = userId,
        accountId = accountId,
        category = category,
        type = type,
        sourceType = sourceType,
        name = name,
        symbol = symbol,
        quantity = quantity,
        purchasePrice = purchasePrice,
        currentValue = valuation.valuationKrw,
        currency = currency,
        valuationMethod = ValuationMethod.MARKET_PRICE,
        confidenceLevel = valuation.confidence,
        lastUpdatedAt = valuedAt,
        createdAt = createdAt,
        memo = memo,
        subType = subType,
        loanAmount = loanAmount,
        maturityDate = maturityDate,
        liquidityType = liquidityType,
        areaPyeong = areaPyeong,
        priceAsOf = valuation.priceAsOf,
        // 매칭 키다 — 여기서 흘리면 다음 평가 때 그 자산을 못 찾는다
        exclusiveAreaM2 = exclusiveAreaM2,
    )
