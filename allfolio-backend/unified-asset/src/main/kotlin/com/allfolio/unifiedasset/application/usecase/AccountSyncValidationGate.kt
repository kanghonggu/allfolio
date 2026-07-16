package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.application.ReportValidationGate
import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.archive.ReportWarning
import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.domain.account.AccountStatus
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 검증 게이트 v1 (리포트명세서 §0): 계좌 동기화 상태 기반.
 * P2 대사 도입 시 "대사 미해소" 검사가 이 게이트에 추가된다.
 */
@Component
class AccountSyncValidationGate(
    private val accountRepository: AccountRepository,
) : ReportValidationGate {

    override fun check(userId: UUID, period: ReportPeriod): List<ReportWarning> {
        val periodEndExclusive = period.end.plusDays(1).atStartOfDay()
        return accountRepository.findByUserId(userId).flatMap { account ->
            val syncable = account.provider in DailyAccountSyncer.SYNC_ELIGIBLE_PROVIDERS
            when {
                account.status == AccountStatus.ERROR ->
                    listOf(ReportWarning("SYNC_ERROR", "${account.accountName} 계좌가 동기화 실패 상태입니다"))
                syncable && account.lastSyncedAt == null ->
                    listOf(ReportWarning("NEVER_SYNCED", "${account.accountName} 계좌가 한 번도 동기화되지 않았습니다"))
                syncable && account.lastSyncedAt!!.isBefore(periodEndExclusive) ->
                    listOf(ReportWarning("STALE_SYNC", "${account.accountName} 계좌가 기준기간 말 이후 동기화되지 않았습니다"))
                else -> emptyList()
            }
        }
    }
}
