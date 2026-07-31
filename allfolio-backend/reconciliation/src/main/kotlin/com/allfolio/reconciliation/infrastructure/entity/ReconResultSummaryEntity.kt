package com.allfolio.reconciliation.infrastructure.entity

import com.allfolio.reconciliation.domain.SummaryStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "recon_result_summary")
class ReconResultSummaryEntity(
    @Id @Column(columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "run_id", nullable = false, columnDefinition = "uuid")
    val runId: UUID,

    /** 코드 룰 식별자 (룰은 Spring 빈 — FK 없음) */
    @Column(name = "rule_code", nullable = false, length = 50)
    val ruleCode: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val status: SummaryStatus,

    @Column(name = "checked_cnt", nullable = false)
    val checkedCnt: Int = 0,

    @Column(name = "diff_cnt", nullable = false)
    val diffCnt: Int = 0,

    @Column(name = "kd_absorbed_cnt", nullable = false)
    val kdAbsorbedCnt: Int = 0,

    @Column(name = "error_msg", length = 500)
    val errorMsg: String? = null,

    @Column(name = "elapsed_ms", nullable = false)
    val elapsedMs: Long = 0,
)
