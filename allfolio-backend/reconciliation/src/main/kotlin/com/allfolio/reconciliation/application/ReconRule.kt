package com.allfolio.reconciliation.application

import com.allfolio.reconciliation.domain.DiffType
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

enum class RuleKind { VALIDATION, RECONCILIATION }

data class ReconContext(val userId: UUID, val runDate: LocalDate)

data class RuleDiff(
    val symbol: String? = null,
    val fieldName: String? = null,
    val diffType: DiffType,
    val internalValue: BigDecimal? = null,
    val externalValue: BigDecimal? = null,
    val diffValue: BigDecimal? = null,
    val extras: Map<String, String> = emptyMap(),
)

data class RuleResult(val checkedCnt: Int, val diffs: List<RuleDiff>)

/**
 * 코드 룰 계약 (v2 스펙 §3) — 룰은 DB 행이 아니라 Spring 빈.
 * 룰 추가 = 이 인터페이스 구현 빈 추가(OCP, SyncAdapter·ReportBodyGenerator 패턴).
 * 원천 데이터는 읽기 전용 네이티브 쿼리로만 참조(타 모듈 코드 의존 금지).
 */
interface ReconRule {
    /** recon_result_summary.rule_code 로 기록되는 식별자 */
    val code: String
    val kind: RuleKind
    fun execute(ctx: ReconContext): RuleResult
}
