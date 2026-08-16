package com.allfolio.unifiedasset.infrastructure.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 원자재 시세 한 건 (AF-108).
 *
 * **키가 `(코드, 거래일)`이고 슬롯이 없다.** 세 소스(FRED/EIA 일간·FRED/IMF 월간·FSC 금) 모두
 * 하루(또는 한 달) 한 값이라 OPEN/MID/CLOSE 개념이 없다. `MarketIndexQuoteEntity`를 재사용하면
 * `slot`에 CLOSE를 억지로 채우게 되고, 그러면 "종가"라는 말이 원자재 행에서만 다른 뜻이 된다.
 *
 * **컬럼 이름이 `code`다 — `commodity_code`가 아니다.** 형제 표(`market_rate.rate_code`)를
 * 따라 접두어를 붙이면 운영 Neon에 그 컬럼이 없어 배포 후 첫 insert에서 터진다.
 * 마이그레이션(`2026-08-16-market-commodity-quote.sql`)이 `code`로 만들었고, 그건 빠뜨린
 * 접두어가 아니라 정한 이름이다.
 *
 * **`unit`·`frequency`를 행에 담는 이유**: 코드에 상수로 들고 있으면 설정이 바뀐 날 저장은
 * 멀쩡한데 화면만 조용히 틀린다. 관측과 함께 결정된 속성이므로 관측과 함께 남긴다.
 * (`USD/lb`와 `USc/lb`는 한 글자 차이에 100배 차이다 — 이 필드가 그 차이를 진다.)
 *
 * **`prevClose`·`changeValue`·`changeRate`가 nullable인 이유**: 첫 관측이거나 직전 값이 없으면
 * 채울 것이 없다. `0`(무변동)과 `null`(직전 값 없음)은 다르다 — AF-104가 이 구분을 놓쳐
 * 사고를 냈다. **비었다고 0을 넣지 말 것.** 형제 `MarketIndexQuoteEntity`가 이 셋을 non-null로
 * 두고 있는데, 그건 KIS 응답이 전일 종가를 함께 주기 때문이고 여기서는 우리가 계산한다.
 *
 * **`collectedAt`에 DB DEFAULT가 없다 — 앱이 반드시 채운다.** 컨테이너가 UTC이므로 아무 데서나
 * `LocalDateTime.now()`를 부르지 말고 호출자가 주입한 시각을 쓴다([com.allfolio.market.commodity]
 * 의 수집 서비스가 그 시각을 한 번만 정해 전 행에 같은 값으로 넣는다).
 *
 * 값 필드가 var인 이유: 같은 날짜를 다시 수집하면 값만 덮기 때문이다. `unit`·`frequency`·`source`도
 * 포함된다 — 설정이 바뀌거나(단위 표기 정정) 같은 코드를 다른 소스에서 재수집하는 날
 * 첫 수집 당시 값이 그대로 굳으면, 값을 설명하려고 들여다볼 바로 그 필드가 거짓말을 한다.
 */
@Entity
@Table(
    name = "market_commodity_quote",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_market_commodity_quote", columnNames = ["code", "trade_date"]),
    ],
)
class MarketCommodityQuoteEntity(
    @Id val id: UUID,
    @Column(name = "code", nullable = false, length = 20) val code: String,
    /** 월간 관측의 거래일은 그 달의 1일이다(IMF 관측일 규약 그대로). 월말로 옮기지 않는다 */
    @Column(name = "trade_date", nullable = false) val tradeDate: LocalDate,
    @Column(name = "price", nullable = false, precision = 18, scale = 4) var price: BigDecimal,
    @Column(name = "unit", nullable = false, length = 20) var unit: String,
    /** D | M. `(frequency, source)` 짝이 EIA(D,FRED)·IMF(M,FRED)·금(D,FSC)을 가른다 */
    @Column(name = "frequency", nullable = false, length = 1) var frequency: String,
    @Column(name = "prev_close", precision = 18, scale = 4) var prevClose: BigDecimal?,
    @Column(name = "change_value", precision = 18, scale = 4) var changeValue: BigDecimal?,
    /** % (소수 4자리). 직전 값이 0이면 계산할 수 없어 null이다 — 무변동의 0과 다르다 */
    @Column(name = "change_rate", precision = 9, scale = 4) var changeRate: BigDecimal?,
    @Column(name = "source", nullable = false, length = 20) var source: String,
    @Column(name = "collected_at", nullable = false) var collectedAt: LocalDateTime,
)
