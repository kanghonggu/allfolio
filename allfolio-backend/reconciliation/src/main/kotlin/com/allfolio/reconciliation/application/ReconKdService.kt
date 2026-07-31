package com.allfolio.reconciliation.application

import com.allfolio.reconciliation.domain.KdValueType
import com.allfolio.reconciliation.infrastructure.entity.ReconKdEntity
import com.allfolio.reconciliation.infrastructure.jpa.ReconKdJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class RegisterKdCommand(
    val kdCode: String,
    val targetSymbol: String?,
    val targetField: String?,
    val valueType: KdValueType,
    val allowValue: BigDecimal,
    val reason: String,
    val apldStrtDt: LocalDate,
)

/**
 * Known Difference USER CRUD (P2 #16, v2 스펙 §5·§7).
 * 수정 = 버저닝: 같은 kdCode의 열린 행을 (신규 시작일 - 1일)로 마감 + 신규 INSERT (#51 세율마스터 패턴).
 * 삭제 = use_yn=false (소프트 — 이력 보존, 기존 detail의 kd_id 참조 유지).
 */
@Service
class ReconKdService(private val kdRepository: ReconKdJpaRepository) {

    fun list(userId: UUID): List<ReconKdEntity> = kdRepository.findByUserIdOrderByCreatedAtDesc(userId)

    @Transactional
    fun register(userId: UUID, cmd: RegisterKdCommand): ReconKdEntity {
        require(cmd.kdCode.isNotBlank()) { "kdCode는 필수입니다" }
        require(cmd.reason.isNotBlank()) { "사유는 필수입니다" }
        require(cmd.allowValue.signum() > 0) { "허용값은 0보다 커야 합니다" }

        // 같은 kdCode의 열린(무기한) 활성 행 마감 — 소급 조회 가능하게 이력 보존
        kdRepository.findByUserIdAndUseYnTrue(userId)
            .filter { it.kdCode == cmd.kdCode && it.apldEndDt == OPEN_END }
            .forEach { prev ->
                prev.apldEndDt = cmd.apldStrtDt.minusDays(1)
                kdRepository.save(prev)
            }

        return kdRepository.save(
            ReconKdEntity(
                userId = userId,
                kdCode = cmd.kdCode.trim(),
                targetSymbol = cmd.targetSymbol?.trim()?.uppercase()?.takeIf { it.isNotEmpty() },
                targetField = cmd.targetField?.trim()?.takeIf { it.isNotEmpty() },
                valueType = cmd.valueType,
                allowValue = cmd.allowValue,
                reason = cmd.reason.trim(),
                apldStrtDt = cmd.apldStrtDt,
            )
        )
    }

    @Transactional
    fun deactivate(userId: UUID, id: UUID) {
        val kd = kdRepository.findById(id).orElse(null)
        if (kd == null || kd.userId != userId) throw NoSuchElementException("KD not found: $id")
        kd.useYn = false
        kdRepository.save(kd)
    }

    companion object {
        val OPEN_END: LocalDate = LocalDate.of(9999, 12, 31)
    }
}
