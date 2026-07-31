package com.allfolio.reconciliation.infrastructure.entity

import com.allfolio.reconciliation.domain.ReconTrigger
import com.allfolio.reconciliation.domain.RunStatus
import com.allfolio.reconciliation.domain.RunType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "recon_run")
class ReconRunEntity(
    @Id @Column(columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    val userId: UUID,

    @Column(name = "run_date", nullable = false)
    val runDate: LocalDate,

    @Enumerated(EnumType.STRING)
    @Column(name = "run_type", nullable = false, length = 20)
    val runType: RunType,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: RunStatus,

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    val triggerType: ReconTrigger,

    /** 내부 측 기준 시점 (position_daily date) */
    @Column(name = "internal_as_of")
    var internalAsOf: LocalDate? = null,

    /** 외부 측 기준 시점 (min lastSyncedAt — ua_assets는 현재 상태 테이블) */
    @Column(name = "external_as_of")
    var externalAsOf: LocalDateTime? = null,

    @Column(name = "started_at", nullable = false)
    val startedAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "finished_at")
    var finishedAt: LocalDateTime? = null,
)
