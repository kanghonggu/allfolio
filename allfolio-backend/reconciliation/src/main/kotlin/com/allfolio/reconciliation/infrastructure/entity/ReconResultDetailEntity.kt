package com.allfolio.reconciliation.infrastructure.entity

import com.allfolio.reconciliation.domain.DiffType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.util.UUID

@Entity
@Table(name = "recon_result_detail")
class ReconResultDetailEntity(
    @Id @Column(columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "summary_id", nullable = false, columnDefinition = "uuid")
    val summaryId: UUID,

    @Column(length = 50)
    val symbol: String? = null,

    @Column(name = "field_name", length = 30)
    val fieldName: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "diff_type", nullable = false, length = 30)
    val diffType: DiffType,

    @Column(name = "internal_value", precision = 30, scale = 10)
    val internalValue: BigDecimal? = null,

    @Column(name = "external_value", precision = 30, scale = 10)
    val externalValue: BigDecimal? = null,

    @Column(name = "diff_value", precision = 30, scale = 10)
    val diffValue: BigDecimal? = null,

    /** JSON 문자열 (브로커·계좌 문맥 등) */
    @Column(columnDefinition = "text")
    val extras: String? = null,

    /** 이 차이를 흡수한 KD (숨김 아님 — 구분 표시용) */
    @Column(name = "kd_id", columnDefinition = "uuid")
    val kdId: UUID? = null,
)
