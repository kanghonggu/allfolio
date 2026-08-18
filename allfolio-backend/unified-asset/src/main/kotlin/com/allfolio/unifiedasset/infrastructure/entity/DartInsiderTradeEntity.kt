package com.allfolio.unifiedasset.infrastructure.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 임원·주요주주 소유 변동(elestock) 한 건 (D1).
 *
 * **변동 "사유"(매수/매도)를 담는 컬럼이 없는 이유**: elestock 응답 자체에 변동사유 필드가 없다.
 * 담을 수 있는 건 소유수량 변동 사실(증감수량·지분율)뿐이라 `changeType` 같은 컬럼을 두지 않았다.
 *
 * **PK가 `rceptNo`가 아니라 별도 `id`(대리키)인 이유**: 실측 3,922행에서 `rcept_no` 단독이
 * 이미 전건 고유였다(보고서당 1행). 그래도 `(rcept_no, repror)` UNIQUE로만 두고 대리키를 쓴 것은
 * 한 보고서에 보고자가 둘 나오는 경우가 생겨도 깨지지 않게 하려는 여유분이다
 * (UNIQUE 제약 `uq_insider`는 마이그레이션이 진다 — 엔티티엔 중복하지 않는다).
 *
 * **`isRegistered`가 nullable `Boolean`인 이유**: elestock이 등기임원/비등기임원/`"-"` 셋을 준다.
 * `"-"`가 결측이라 3-값이 필요하다 — non-null로 바꾸면 결측이 false(비등기)로 둔갑한다.
 * `officerPosition`·`majorHolderType`도 elestock이 결측을 `"-"`로 주므로 같은 이유로 nullable이다.
 *
 * **`reportDate`가 접수일(`rcept_dt`)인 이유**: elestock 응답에 별도 변동일 필드가 없다.
 *
 * **`ownedRate`·`changeRate`의 precision/scale이 `(7, 2)`인 이유**: DDL이 `NUMERIC(7,2)`다.
 * 다르게 선언하면 컴파일은 통과하지만 `ddl-auto: none`이라 첫 insert에서야 어긋남이 드러난다.
 * elestock 원문 수량은 콤마 낀 문자열이라 파싱은 이 엔티티 바깥(수집 서비스)에서 끝낸다.
 *
 * **`changeQty`가 음수를 허용하는 이유**: 소유 감소도 저장 대상이다.
 */
@Entity
@Table(name = "dart_insider_trade")
class DartInsiderTradeEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "rcept_no", nullable = false, length = 14)
    val rceptNo: String,

    @Column(name = "corp_code", nullable = false, length = 8)
    val corpCode: String,

    @Column(name = "stock_code", length = 6)
    val stockCode: String?,

    /** 보고자 */
    @Column(name = "repror", nullable = false, length = 200)
    val repror: String,

    /** "-" → NULL */
    @Column(name = "officer_position", length = 100)
    val officerPosition: String?,

    /** 등기/비등기, "-" → NULL */
    @Column(name = "is_registered")
    val isRegistered: Boolean?,

    /** 원문 보존("10%이상주주"·"사실상지배주주" 등), "-" → NULL */
    @Column(name = "major_holder_type", length = 50)
    val majorHolderType: String?,

    @Column(name = "report_date", nullable = false)
    val reportDate: LocalDate,

    @Column(name = "owned_qty")
    val ownedQty: Long?,

    @Column(name = "change_qty")
    val changeQty: Long?,

    @Column(name = "owned_rate", precision = 7, scale = 2)
    val ownedRate: BigDecimal?,

    @Column(name = "change_rate", precision = 7, scale = 2)
    val changeRate: BigDecimal?,

    @Column(name = "collected_at", nullable = false)
    val collectedAt: LocalDateTime,
)
