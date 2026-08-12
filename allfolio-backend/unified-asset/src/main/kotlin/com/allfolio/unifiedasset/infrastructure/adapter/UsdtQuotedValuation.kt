package com.allfolio.unifiedasset.infrastructure.adapter

import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.asset.AssetCategory
import com.allfolio.unifiedasset.domain.asset.AssetSourceType
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.asset.ValuationMethod
import java.math.BigDecimal

/**
 * 해외 거래소(Binance·OKX·Bybit) 잔고를 **USDT 호가**로 평가한 자산으로 만든다.
 *
 * ## 왜 라벨이 USD가 아니라 USDT인가
 *
 * 세 어댑터가 쓰는 시세는 전부 거래소 오더북의 USDT 마켓이다(`BTCUSDT`·`BTC-USDT`).
 * 스테이블코인도 호가 조회 없이 1로 두는데, 그 1도 USD가 아니라 USDT다.
 * 즉 `currentValue`의 단위는 **USDT**다.
 *
 * 예전에는 여기 라벨이 `"USD"`였다. 그러면 AF-99가 USD 환산을 하나은행 공식 매매기준율로
 * 옮기는 순간, 거래소 USDT 평가액이 공식 고시로 환산된다 —
 * **분리로 지키려던 바로 그 계정이 안 지켜진다.** AF-99가 스테이블코인을 거래소 시세로
 * 남기는 근거는 "그 거래소에 실제 USDT를 들고 있는 사용자에게는 거래소 시세가 실현 가능한 값"이고,
 * 그 논거가 성립하는 게 정확히 이 세 어댑터가 만드는 자산이다.
 * (근거: `docs/superpowers/specs/2026-08-12-hana-fx-collector-design.md` "USDT를 분리하는 이유")
 *
 * 지갑([WalletSyncAdapter])은 Moralis 가격 오라클의 `usd_value`를 쓰므로 `"USD"`로 남는다.
 * **그 비대칭은 실수가 아니라 결정이다** — 통일하기 전에 저쪽 KDoc을 먼저 읽을 것.
 *
 * 세 어댑터가 라벨을 각자 적으면 다음에 또 한 곳만 고친다. 통화 상수는 여기 하나뿐이다.
 */
internal object UsdtQuotedValuation {

    /**
     * 평가 기준 통화. 여기 값을 바꾸면 세 어댑터가 함께 바뀐다.
     *
     * `Currencies.SUPPORTED`에 들어 있어야 한다 — `Asset.create`가 `Currencies.normalize`를
     * 태우므로, 빠지면 세 거래소 동기화가 통째로 실패한다.
     */
    const val CURRENCY = "USDT"

    /** 1 USDT로 취급하는 스테이블코인. 호가 조회 없이 액면가로 본다. */
    val STABLECOINS = setOf("USDT", "USDC", "BUSD")

    fun asset(
        account: Account,
        symbol: String,
        quantity: BigDecimal,
        usdtPrice: BigDecimal,
    ): Asset = Asset.create(
        userId          = account.userId,
        accountId       = account.id,
        category        = AssetCategory.FINANCIAL,
        type            = AssetType.CRYPTO,
        sourceType      = AssetSourceType.EXCHANGE_API,
        name            = symbol,
        symbol          = symbol,
        quantity        = quantity,
        purchasePrice   = BigDecimal.ZERO, // 평균단가 별도 조회 필요
        currentValue    = quantity.multiply(usdtPrice),
        currency        = CURRENCY,
        valuationMethod = ValuationMethod.MARKET_PRICE,
    )
}
