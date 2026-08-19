package com.allfolio.unifiedasset.infrastructure.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 실물자산 일별 평가 스냅샷 (A1). 휴장일 포함 매일 한 건씩 쌓인다.
 *
 * **`uniqueConstraints`를 엔티티에 선언한다.** 안 하면 H2에 제약이 아예 안 생겨 리포지터리
 * 테스트에서 중복 삽입이 조용히 커밋된다 — AF-100에서 실제로 물린 함정이고
 * `MarketCommodityQuoteEntity`가 같은 이유로 같은 선언을 갖고 있다.
 *
 * **`priceAsOf`가 `valuedOn`보다 앞서는 것이 정상이다.** 공공데이터포털 금 시세는 D+1 공표라
 * 평일에도 최소 하루 낡았고 연휴 뒤에는 4일까지 벌어진다. `stalenessDays`가 **0이면 오히려
 * 이상하다** — 폴백을 안 타고 평가일을 그대로 넣고 있다는 뜻이다.
 *
 * **`priceUnit`을 스냅샷에 남기는 이유**: AF-108은 단위를 코드 상수가 아니라 시세 행에 저장한다.
 * 평가 스냅샷이 그 단위를 안 받아 적으면 방어가 여기서 끊겨, 나중에 `unitPrice`만 보고는
 * 그게 KRW/g였는지 알 수 없어 과거 화면을 재현하지 못한다.
 *
 * **`confidence`만 nullable이다.** 나머지는 평가가 성립한 이상 전부 채워진다 —
 * 산출 불가는 행 자체를 안 만드는 것으로 표현하지 값에 null을 넣어 표현하지 않는다.
 *
 * 값 필드가 var인 이유: 같은 날 배치를 다시 돌리면(워크플로 재시도) 값만 덮는다.
 */
@Entity
@Table(
    name = "real_asset_valuation",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_valuation", columnNames = ["real_asset_id", "valued_on"]),
    ],
)
class RealAssetValuationEntity(
    @Id val id: UUID,
    @Column(name = "real_asset_id", nullable = false) val realAssetId: UUID,
    /** 평가 기준일 = 배치 실행일(KST). 러너의 UTC 시계를 싣지 않는다 */
    @Column(name = "valued_on", nullable = false) val valuedOn: LocalDate,
    @Column(name = "unit_price", nullable = false, precision = 18, scale = 4) var unitPrice: BigDecimal,
    @Column(name = "price_unit", nullable = false, length = 20) var priceUnit: String,
    /** 시세 x 수량 x 순도. 원 단위 정수 */
    @Column(name = "valuation_krw", nullable = false) var valuationKrw: Long,
    @Column(name = "price_as_of", nullable = false) var priceAsOf: LocalDate,
    /** `valuedOn - priceAsOf`. 실측 정상 범위는 1~4다 */
    @Column(name = "staleness_days", nullable = false) var stalenessDays: Short,
    /** TRADE | ASK */
    @Column(name = "price_basis", nullable = false, length = 10) var priceBasis: String,
    /** HIGH | MEDIUM | LOW */
    @Column(name = "confidence", length = 10) var confidence: String?,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
)
