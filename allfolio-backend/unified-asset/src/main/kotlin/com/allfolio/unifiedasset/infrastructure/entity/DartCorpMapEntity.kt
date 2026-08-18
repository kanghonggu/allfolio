package com.allfolio.unifiedasset.infrastructure.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * DART 고유번호(`corp_code`) ↔ 종목코드(`stock_code`) 매핑 한 건 (D1).
 *
 * OpenDART `corpCode.xml`(ZIP)에서 주 1회 통째로 갱신한다. 필드 전부가 `var`인 이유도 이것 —
 * 매주 같은 `corp_code`를 다시 받아 덮어쓰는 게 정상 동작이라, 회사명 변경이나 상장/상장폐지로
 * `stock_code`가 바뀌어도 예전 값이 굳지 않는다.
 */
@Entity
@Table(name = "dart_corp_map")
class DartCorpMapEntity(
    @Id
    @Column(name = "corp_code", length = 8)
    val corpCode: String,

    @Column(name = "corp_name", nullable = false, length = 200)
    var corpName: String,

    @Column(name = "stock_code", length = 6)
    var stockCode: String?,

    @Column(name = "modify_date")
    var modifyDate: LocalDate?,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime,
)
