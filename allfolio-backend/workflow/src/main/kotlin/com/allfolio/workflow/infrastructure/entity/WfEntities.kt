package com.allfolio.workflow.infrastructure.entity

import com.allfolio.workflow.domain.WfActionType
import com.allfolio.workflow.domain.WfJobStatus
import com.allfolio.workflow.domain.WfTermGb
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.io.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/** 마감 단계 정의 (기능명세서 5.2 AF_DAILY_STEP 간소화판). */
@Entity
@Table(name = "wf_step")
class WfStepEntity(
    @Id @Column(name = "step_cd", length = 20)
    val stepCd: String,

    @Column(name = "step_seq", nullable = false)
    var stepSeq: Int,

    @Column(name = "step_name", nullable = false, length = 100)
    var stepName: String,

    @Column(name = "step_group", length = 50)
    var stepGroup: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "term_gb", nullable = false, length = 1)
    var termGb: WfTermGb,

    @Column(name = "cutoff_start", length = 5)
    var cutoffStart: String? = null,

    @Column(name = "cutoff_end", length = 5)
    var cutoffEnd: String? = null,

    /** 선행 필수 단계 — 당일 롤업 FINISH여야 실행 (FR-STEP-001). FK 없는 soft reference. */
    @Column(name = "essential_step_cd", length = 20)
    var essentialStepCd: String? = null,

    @Column(length = 200)
    var url: String? = null,

    @Column(name = "holiday_except_yn", nullable = false)
    var holidayExceptYn: Boolean = true,

    @Column(name = "use_yn", nullable = false)
    var useYn: Boolean = true,
)

@Embeddable
data class WfSubStepId(
    @Column(name = "step_cd", length = 20) val stepCd: String = "",
    @Column(name = "sub_step_cd", length = 20) val subStepCd: String = "",
) : Serializable

/** 마감 하위단계 정의 — 실행 단위. action_ref는 WfAction 빈 매칭 문자열(코드 빈 원칙). */
@Entity
@Table(name = "wf_sub_step")
class WfSubStepEntity(
    @EmbeddedId
    val id: WfSubStepId,

    @Column(name = "sub_step_seq", nullable = false)
    var subStepSeq: Int,

    @Column(name = "sub_step_name", nullable = false, length = 100)
    var subStepName: String,

    /** A(자동)/M(수동확인) */
    @Column(name = "auto_manual", nullable = false, length = 1)
    var autoManual: String,

    /** 단계 롤업(마감판정) 포함 여부 */
    @Column(name = "closing_check_yn", nullable = false)
    var closingCheckYn: Boolean = true,

    /** M/Q 실행일 규칙 — n번째 일자(음수=역산). D 주기는 null. */
    @Column(name = "date_term")
    var dateTerm: Int? = null,

    /** S(달력일)/B(영업일) */
    @Column(name = "date_gb", length = 1)
    var dateGb: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 10)
    var actionType: WfActionType,

    /** WfAction 빈 ref (MANUAL이면 null) */
    @Column(name = "action_ref", length = 100)
    var actionRef: String? = null,

    @Column(name = "timeout_sec", nullable = false)
    var timeoutSec: Int = 300,

    @Column(name = "poll_interval_sec", nullable = false)
    var pollIntervalSec: Int = 10,

    @Column(name = "use_yn", nullable = false)
    var useYn: Boolean = true,
)

/** 일자×단계×하위단계×재작업차수 실행 로그. */
@Entity
@Table(name = "wf_job_log")
class WfJobLogEntity(
    @Id @Column(columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false)
    val ymd: LocalDate,

    @Column(name = "step_cd", nullable = false, length = 20)
    val stepCd: String,

    @Column(name = "sub_step_cd", nullable = false, length = 20)
    val subStepCd: String,

    /** 재작업 차수 — 재실행마다 +1 (FR-STEP-008) */
    @Column(name = "exec_seq", nullable = false)
    val execSeq: Int,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var status: WfJobStatus,

    @Column(name = "started_at")
    var startedAt: LocalDateTime? = null,

    @Column(name = "finished_at")
    var finishedAt: LocalDateTime? = null,

    /** 이번 실행이 자동(A)/수동(M)이었는지 */
    @Column(name = "auto_manual", nullable = false, length = 1)
    val autoManual: String,

    /** SYSTEM 또는 처리자 userId */
    @Column(nullable = false, length = 100)
    val executor: String,

    /** 수동 처리 사유(수동 시 필수, FR-AUTH-005) 또는 요약 오류 */
    @Column(length = 500)
    var remark: String? = null,

    @Column(name = "error_detail", columnDefinition = "text")
    var errorDetail: String? = null,
)

/** 정의 변경 감사 — 트리거 대신 애플리케이션 레벨 JSON 스냅샷 (간소화 판정 §3). */
@Entity
@Table(name = "wf_def_hist")
class WfDefHistEntity(
    @Id @Column(columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    /** STEP / SUB_STEP */
    @Column(name = "entity_type", nullable = false, length = 10)
    val entityType: String,

    @Column(name = "entity_key", nullable = false, length = 50)
    val entityKey: String,

    /** C/U/D */
    @Column(nullable = false, length = 1)
    val crud: String,

    /** 변경 후 엔티티 JSON (D는 변경 전) */
    @Column(columnDefinition = "text")
    val snapshot: String? = null,

    @Column(name = "changed_by", columnDefinition = "uuid")
    val changedBy: UUID? = null,

    @Column(name = "changed_at", nullable = false)
    val changedAt: LocalDateTime = LocalDateTime.now(),
)

@Embeddable
data class WfHolidayId(
    @Column val day: LocalDate = LocalDate.MIN,
    @Column(length = 2) val country: String = "KR",
) : Serializable

/** 휴일 캘린더 (FR-CMMN-002). 초기 시드는 마이그레이션, ADMIN CRUD는 후속. */
@Entity
@Table(name = "wf_holiday")
class WfHolidayEntity(
    @EmbeddedId
    val id: WfHolidayId,

    @Column(length = 100)
    val name: String? = null,
)
