package com.allfolio.reconciliation.infrastructure.entity

import com.allfolio.reconciliation.domain.KdValueType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Known Difference — USER-scoped 허용 차이 (P2 #16에서 매칭·흡수 로직 사용).
 * 수정 = 버저닝: 기존 행 apldEndDt 마감 + 신규 INSERT (#51 세율마스터 패턴).
 */
@Entity
@Table(name = "recon_kd")
class ReconKdEntity(
    @Id @Column(columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    val userId: UUID,

    @Column(name = "kd_code", nullable = false, length = 50)
    val kdCode: String,

    /** null = 와일드카드 */
    @Column(name = "target_symbol", length = 50)
    val targetSymbol: String? = null,

    /** null = 와일드카드 */
    @Column(name = "target_field", length = 30)
    val targetField: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, length = 10)
    val valueType: KdValueType,

    @Column(name = "allow_value", nullable = false, precision = 30, scale = 10)
    val allowValue: BigDecimal,

    @Column(nullable = false, length = 300)
    val reason: String,

    @Column(name = "apld_strt_dt", nullable = false)
    val apldStrtDt: LocalDate,

    @Column(name = "apld_end_dt", nullable = false)
    var apldEndDt: LocalDate = LocalDate.of(9999, 12, 31),

    @Column(name = "use_yn", nullable = false)
    var useYn: Boolean = true,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
