package com.allfolio.unifiedasset.infrastructure.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * OpenDART 공시 원장 한 건 (D1).
 *
 * **`rceptNo`가 PK이자 `String`인 이유**: 14자리 숫자형으로 선언하면 선행 0이 소실되어
 * 원문 링크가 깨지고 중복 판정이 무너진다(마이그레이션 `2026-08-18-dart-disclosure.sql` 그대로).
 * 실측 예: `20260818000094`.
 *
 * **`stockCode`가 nullable인 이유**: 비상장사(`corpCls`="E") 공시가 실측 6영업일치 8,667건 중
 * 3,273건 들어온다. OpenDART는 이걸 빈 문자열로 주지만 앱이 저장 전에 NULL로 정규화한다 —
 * 그러지 않으면 보유종목 피드 전용 부분 인덱스(`idx_disclosure_feed`)가 무용지물이 된다.
 *
 * **`isMaterial`이 false인 행도 버리지 않는 이유**: 무엇을 걸렀는지 되짚을 수 없으면 화이트리스트
 * 튜닝이 불가능하다. `isMaterial`·`materialTier`가 `var`인 것도 같은 이유다 — 화이트리스트
 * 규칙이 바뀌면 이미 저장된 행을 재판정해 갱신해야 한다.
 *
 * **`materialTier`가 `Short?`인 이유**: DDL이 `SMALLINT`다. `Int?`로 선언하면 컴파일은 통과하지만
 * `ddl-auto: none`이라 타입이 어긋난 채로 기동되고 첫 쿼리에서야 드러난다. 값은 1~5, NULL=비대상,
 * 5는 정기보고서다 — 실측 상장사 5,394건 중 2,846건이 Tier 5였다. Tier 2에 두면 제출 시즌에
 * 피드가 그것만으로 찬다.
 *
 * **`reportNm`과 `reportNmNorm`을 둘 다 담는 이유**: `reportNm`은 원문 그대로(trim만),
 * `reportNmNorm`은 화이트리스트 매칭용 3단 정규화 결과(trim → 접두어 제거 → 구분자 통일)다.
 * 원문을 지우면 화면에 보여줄 표시용 제목이 사라지고, 정규화를 저장 시점에 고정하지 않으면
 * 정규화 규칙이 바뀔 때마다 과거 행을 다시 계산해야 한다.
 *
 * **`collectedAt`이 앱이 채우는 타임스탬프인 이유**: 컨테이너가 UTC이므로 아무 데서나
 * `LocalDateTime.now()`를 부르지 않고, 수집 서비스가 한 번 정한 시각을 전 행에 같은 값으로
 * 넣는다(`MarketCommodityQuoteEntity`와 같은 관례).
 */
@Entity
@Table(name = "dart_disclosure")
class DartDisclosureEntity(
    @Id
    @Column(name = "rcept_no", length = 14)
    val rceptNo: String,

    @Column(name = "corp_code", nullable = false, length = 8)
    val corpCode: String,

    @Column(name = "corp_name", nullable = false, length = 200)
    val corpName: String,

    @Column(name = "stock_code", length = 6)
    val stockCode: String?,

    /** Y=유가증권, K=코스닥, N=코넥스, E=기타(비상장) */
    @Column(name = "corp_cls", length = 1)
    val corpCls: String?,

    @Column(name = "report_nm", nullable = false, columnDefinition = "text")
    val reportNm: String,

    @Column(name = "report_nm_norm", nullable = false, columnDefinition = "text")
    val reportNmNorm: String,

    @Column(name = "rcept_dt", nullable = false)
    val rceptDt: LocalDate,

    @Column(name = "flr_nm", length = 200)
    val flrNm: String?,

    /**
     * 실측 값이 `''`·`유`·`코`·`공`·`정`·`코정`·`넥`·`공정`·`연`·`유정`·`정연`·`채` 등 조합형 12종이다.
     * 의미를 확인하지 못했으므로 해석하지 않고 원문만 보존한다 — 빠뜨린 필드가 아니라 그렇게 정한 것이다.
     */
    @Column(name = "rm", length = 20)
    val rm: String?,

    @Column(name = "is_material", nullable = false)
    var isMaterial: Boolean,

    @Column(name = "material_tier")
    var materialTier: Short?,

    @Column(name = "is_correction", nullable = false)
    val isCorrection: Boolean,

    @Column(name = "collected_at", nullable = false)
    val collectedAt: LocalDateTime,
)
