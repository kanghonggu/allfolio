package com.allfolio.workflow.application

import com.allfolio.workflow.domain.WfJobStatus
import java.time.LocalDate

/** 하위단계 실행 결과 이벤트 (FR-DASH-001/002 — 단계·하위단계·사유 포함). */
data class WfStepEvent(
    val ymd: LocalDate,
    val stepCd: String,
    val subStepCd: String,
    val execSeq: Int,
    val status: WfJobStatus,
    val remark: String?,
)

/** 마감 이벤트 발행 포트 — backend-app이 SSE로 구현 (P3 #31). 발행 실패는 실행에 영향 없음. */
interface WfEventPublisher {
    fun publish(event: WfStepEvent)
}
