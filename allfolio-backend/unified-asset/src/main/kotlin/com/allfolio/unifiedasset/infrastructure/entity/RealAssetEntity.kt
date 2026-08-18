package com.allfolio.unifiedasset.infrastructure.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 사용자 보유 실물자산 한 건 (A1).
 *
 * **`userId`가 UUID다.** 설계 문서 초안은 `BIGINT`로 적었지만 이 저장소의 사용자 소유 테이블은
 * 전부 UUID다(`users.id` · `ua_assets.user_id` · `cash_flow.user_id`). BIGINT로 만들면
 * 조인이 아예 성립하지 않고, 그 사실이 조회 API를 쓸 때까지 안 드러난다.
 *
 * **`assetType`·`subType`을 문자열로 둔다.** `@Enumerated(STRING)`이 아니다 —
 * 도메인 enum(`com.allfolio.realasset.AssetType`)은 backend-app에 있고 이 모듈은 그걸 모른다.
 * 변환은 스토어 어댑터가 한다. 값이 enum에 없는 문자열이면 그 자산만 실패로 격리된다.
 *
 * **`quantity`가 NUMERIC(18,4)인 이유**: 금은 g 단위 소수가 필수다(3.75g = 1돈). INT면 1돈이 3g이 된다.
 *
 * **`includeInTwr` 기본값이 false인 것은 의도다.** 등록 API가 자산 유형을 보고 명시적으로 넣는다.
 * true를 기본으로 두면 유형이 하나 늘 때마다 그 자산이 조용히 TWR에 섞이는데, 틀리는 방향이
 * 다르다 — 빠뜨린 자산은 수익률을 과소 표시할 뿐이지만 잘못 섞인 계단식 자산은 TWR을 오염시킨다.
 *
 * **`isActive`로 비활성만 하고 물리 삭제를 안 한다.** 평가 스냅샷이 이 행을 참조하므로
 * 지우면 과거가 끊긴다.
 */
@Entity
@Table(name = "real_asset")
class RealAssetEntity(
    @Id val id: UUID,
    @Column(name = "user_id", nullable = false) val userId: UUID,
    /** GOLD | WATCH | REAL_ESTATE */
    @Column(name = "asset_type", nullable = false, length = 20) var assetType: String,
    /** KRX_ACCOUNT | BAR | JEWELRY */
    @Column(name = "sub_type", length = 30) var subType: String?,
    @Column(name = "name", nullable = false, length = 200) var name: String,
    /** 시세 조인 키. 금=시세 코드('GOLD_KRX') · 시계=ref · 부동산=단지코드+면적 */
    @Column(name = "source_ref", length = 100) var sourceRef: String?,
    @Column(name = "quantity", nullable = false, precision = 18, scale = 4) var quantity: BigDecimal,
    /** 24K=1.0 · 18K=0.75 */
    @Column(name = "purity", nullable = false, precision = 5, scale = 4) var purity: BigDecimal,
    @Column(name = "acquired_at", nullable = false) var acquiredAt: LocalDate,
    /** 원 단위 정수. 원화에 소수점을 두지 않는다 — AF-104가 자릿수를 흘린 전례가 있다 */
    @Column(name = "acquired_cost_krw", nullable = false) var acquiredCostKrw: Long,
    @Column(name = "include_in_twr", nullable = false) var includeInTwr: Boolean,
    @Column(name = "is_active", nullable = false) var isActive: Boolean,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
)
