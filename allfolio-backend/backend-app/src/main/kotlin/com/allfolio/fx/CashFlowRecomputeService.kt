package com.allfolio.fx

import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.infrastructure.entity.CashFlowEntity
import com.allfolio.unifiedasset.infrastructure.jpa.CashFlowJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * 값이 바뀌는 행 한 건. 드라이런 보고서의 "변동 폭 상위"에 실린다.
 *
 * @param delta after - before. 음수면 원화 환산액이 줄어든다는 뜻이다
 */
data class ChangeRow(
    val id: UUID,
    val flowDate: LocalDate,
    val currency: String,
    val before: BigDecimal,
    val after: BigDecimal,
    val delta: BigDecimal,
)

/** 통화별 집계. 어느 통화가 얼마나 틀려 있었는지를 한 줄로 본다 */
data class CurrencyDelta(
    val scanned: Int,
    val changed: Int,
    val totalDelta: BigDecimal,
)

/**
 * 재계산 한 번의 결과.
 *
 * @param stillEstimated **가장 중요한 값이다.** 재계산을 돌리고도 안 고쳐진 행 수 —
 *                       이걸 안 보면 "다 고쳤다"고 착각한다
 */
data class RecomputeSummary(
    val applied: Boolean,
    val scanned: Int,
    val changed: Int,
    val unchanged: Int,
    val stillEstimated: Int,
    val totalDelta: BigDecimal,
    val byCurrency: Map<String, CurrencyDelta>,
    val topChanges: List<ChangeRow>,
)

/**
 * 이미 저장된 `cash_flow.amount_krw`를 발생일 환율로 다시 계산한다 (AF-100 2단계).
 *
 * AF-100은 **앞으로 들어올** 현금흐름만 고쳤다. 이미 저장된 행은 전부 **기록 시점 환율**로
 * 환산돼 있고, 그 값이 TWR·기간수익률·대시보드 손익의 입력이 된다.
 *
 * ### 왜 전수 재계산이 안전한가
 * 재계산은 `(amount, currency, flowDate)`와 `fx_rate_daily`의 **순수 함수**다. 그래서
 * 이미 맞는 행은 **자기 자신으로 다시 계산되고**, 두 번 돌려도 결과가 같다(멱등).
 * 덕분에 "어느 행이 추정치인가"를 식별할 필요가 없어진다 — 그 표식은 애초에 없다
 * (`KrwConversion`의 `rateDate`·`estimated`를 저장 단계에서 버리기 때문이다).
 *
 * `amountKrw`는 **응답 전용 필드**다(`CashFlowController`의 요청 DTO에 없다).
 * 사용자가 손으로 넣은 값을 덮어쓸 위험이 없다.
 *
 * ### 왜 추정치 행은 건너뛰는가
 * 과거 환율이 없어 현재 환율로 근사한 행은 **값을 바꾸지 않는다.** 다시 써봐야 같은 근사치인데
 * UPDATE만 늘고, 게다가 `amountKrw`가 **그날의 오늘 환율**로 또 갱신되어 원래보다 나빠진다.
 * 대신 [RecomputeSummary.stillEstimated]로 센다 — 백필이 더 과거를 확보하면 다시 돌리면 된다.
 *
 * ### 왜 `apply`에 기본값이 없는가
 * 금융 이력을 다시 쓰는 작업이라 **호출자가 매번 명시해야 한다.** 기본값을 두면 파라미터를
 * 빠뜨린 호출이 조용히 이력을 바꾼다. 드라이런으로 보고서를 먼저 보는 것이 이 기능의 안전장치다.
 *
 * `@Transactional`을 붙이지 않는다 — 전 행을 한 트랜잭션에 담으면 Neon 커넥션을 오래 쥐고
 * 무료 단일 인스턴스에서 다른 요청이 굶는다. `save`는 Spring Data 리포지토리 레벨에서 이미
 * 트랜잭션이라 행 단위 원자성은 확보된다. [FxRateBackfillService]가 같은 판단을 했다.
 */
@Service
class CashFlowRecomputeService(
    private val repository: CashFlowJpaRepository,
    private val fxConverter: FxConverter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** 보고서에 싣는 변동 폭 상위 건수. 사람이 훑어볼 수 있는 만큼만 */
        private const val TOP_CHANGES = 20
    }

    fun recompute(apply: Boolean): RecomputeSummary {
        val rows = repository.findNonKrwOrderByFlowDate()

        var changed = 0
        var unchanged = 0
        var stillEstimated = 0
        var totalDelta = BigDecimal.ZERO
        val changes = mutableListOf<ChangeRow>()
        val perCurrency = mutableMapOf<String, MutableCurrencyDelta>()

        for (entity in rows) {
            val flow = entity.toDomain()
            val bucket = perCurrency.getOrPut(flow.currency.uppercase()) { MutableCurrencyDelta() }
            bucket.scanned++

            val conversion = fxConverter.toKrwOn(flow.amount, flow.currency, flow.flowDate)

            // 과거 환율이 없어 현재 환율로 근사한 값이다 — 덮어쓰면 오늘 환율로 또 갱신된다
            if (conversion.estimated) {
                stillEstimated++
                continue
            }

            if (conversion.amountKrw.compareTo(flow.amountKrw) == 0) {
                unchanged++
                continue
            }

            val delta = conversion.amountKrw.subtract(flow.amountKrw)
            changed++
            totalDelta = totalDelta.add(delta)
            bucket.changed++
            bucket.totalDelta = bucket.totalDelta.add(delta)
            changes += ChangeRow(
                id = flow.id,
                flowDate = flow.flowDate,
                currency = flow.currency,
                before = flow.amountKrw,
                after = conversion.amountKrw,
                delta = delta,
            )

            if (apply) repository.save(CashFlowEntity.from(recalculated(flow, conversion.amountKrw)))
        }

        val summary = RecomputeSummary(
            applied = apply,
            scanned = rows.size,
            changed = changed,
            unchanged = unchanged,
            stillEstimated = stillEstimated,
            totalDelta = totalDelta,
            byCurrency = perCurrency.mapValues { (_, v) -> CurrencyDelta(v.scanned, v.changed, v.totalDelta) },
            topChanges = changes.sortedByDescending { it.delta.abs() }.take(TOP_CHANGES),
        )

        log.info(
            "[재계산] {} 대상 {}건 · 변경 {} · 무변화 {} · 추정치잔존 {} · 총변동 {}원",
            if (apply) "적용" else "드라이런",
            summary.scanned, summary.changed, summary.unchanged, summary.stillEstimated,
            summary.totalDelta.stripTrailingZeros().toPlainString(),
        )
        if (summary.stillEstimated > 0) {
            // 조용히 넘어가면 "다 고쳤다"고 믿게 된다. 백필 범위 밖 날짜가 남아 있다는 뜻이다
            log.warn(
                "[재계산] 과거 환율이 없어 여전히 추정치인 행 {}건 — 백필 시작일을 앞당기면 줄어든다",
                summary.stillEstimated,
            )
        }
        return summary
    }

    /**
     * **같은 `id`·`createdAt`을 유지한** 새 객체를 만든다. [CashFlow]는 전 필드가 `val`이라
     * 값을 바꾸려면 이 길뿐이다 — `id`가 같으므로 JPA가 INSERT가 아니라 UPDATE로 처리한다.
     * 새 `id`를 만들면 원본이 남은 채 중복 행이 생겨 입출금이 두 배가 된다.
     *
     * `amountKrw`는 **부호 없는 크기**다. 부호는 `signedKrw()`가 `type`에서 파생하므로
     * 여기서 손대지 않는다 — 쓰기 경로와 같은 `toKrwOn`을 쓰면 규약이 저절로 보존된다.
     */
    private fun recalculated(flow: CashFlow, amountKrw: BigDecimal) = CashFlow.reconstruct(
        id = flow.id,
        userId = flow.userId,
        accountId = flow.accountId,
        flowDate = flow.flowDate,
        type = flow.type,
        amount = flow.amount,
        currency = flow.currency,
        amountKrw = amountKrw,
        memo = flow.memo,
        createdAt = flow.createdAt,
        linkId = flow.linkId,
    )

    private class MutableCurrencyDelta(
        var scanned: Int = 0,
        var changed: Int = 0,
        var totalDelta: BigDecimal = BigDecimal.ZERO,
    )
}
