package com.allfolio.market.commodity.fsc

import com.allfolio.market.commodity.CommodityFetch
import com.allfolio.market.commodity.CommodityObservation
import com.allfolio.market.commodity.CommodityProperties
import com.allfolio.market.commodity.CommoditySource
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 금(KRX 금시장) 소스 — 공공데이터포털 금융위 일반상품시세정보.
 *
 * `FredCommoditySource`와 같은 모양이고, 다른 것은 좌표의 뜻 하나다:
 * [CommodityProperties.CommodityItem.seriesId]가 FRED에서는 시리즈 ID지만 여기서는
 * **종목 단축코드(`srtnCd`)** 다 — `04020000`(금 99.99_1kg).
 *
 * **🔴 응답에서 그 종목의 행만 고른다.** KRX 금시장에는 상품이 둘 상장돼 있고
 * (`04020000` 금 1kg · `04020100` 미니금 100g) 한 응답에 **같은 날짜로 둘 다** 실려 온다.
 * 안 거르면 `CommodityCollectService`의 중복 접기(`deduped[date] = value`)가 뒤에 온 행으로
 * 앞을 덮어써 **미니금 값이 조용히 금으로 저장된다.** 둘 다 원/g이라 자릿수가 같고
 * (2026-08-13 실측: 200,570 vs 200,240) 값 정책(PRICE)은 상한이 없어 아무것도 막지 않는다 —
 * 즉 저장·조회·화면 어느 층에서도 이 오류는 드러나지 않는다. 거르는 자리가 여기뿐이다.
 * (`04020000`을 쓰는 이유는 유동성이다 — 9일 누적 거래대금이 10배다.)
 *
 * **걸러낸 행은 `skipped`로 세지 않는다.** `skipped`는 "형식이 이상해 버린 행"이고 요약에서
 * 0이 아니면 응답 형식이 바뀐 신호로 읽힌다. 미니금은 정상적으로 온 남의 종목이라 그 축이 아니다.
 *
 * **구간 밖 날짜를 여기서 거르지 않는다** — 서비스가 한다(포트 계약).
 */
@Component
class FscCommoditySource(
    private val client: FscCommodityClient,
    private val properties: CommodityProperties,
) : CommoditySource {

    override val sourceName = "FSC"

    override val codes: List<String>
        get() = properties.fsc.map { it.code }

    override fun fetch(code: String, from: LocalDate, to: LocalDate): CommodityFetch {
        val item = properties.fsc.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("FSC 설정에 없는 원자재 코드입니다: $code")

        val fetched = client.fetchGoldPrices(from, to)
        val mine = fetched.rows.filter { it.srtnCd == item.seriesId }

        return CommodityFetch(
            rows = mine.map { CommodityObservation(it.quoteDate, it.price) },
            skipped = fetched.skipped,
        )
    }
}
