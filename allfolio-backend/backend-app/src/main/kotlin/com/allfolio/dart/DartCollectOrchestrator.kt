package com.allfolio.dart

import com.allfolio.dart.insider.DartInsiderCollectService
import com.allfolio.dart.insider.InsiderCollectSummary
import com.allfolio.dart.list.DartCollectSummary
import com.allfolio.dart.list.DartDisclosureCollectService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime

data class DartRunResult(val disclosure: DartCollectSummary, val insider: InsiderCollectSummary)

/**
 * 트랜잭션 경계를 셋으로 나눈다 — **`elestock` 실패가 공시 수집을 롤백시키면 안 된다.**
 * 각 서비스가 자기 `@Transactional`을 갖고, 여기서는 순서만 정한다.
 *
 * 순수 오케스트레이션. 스프링 빈이 아니라 함수라 테스트가 서비스 스텁 없이 직접 부른다.
 *
 * **로직을 [DartCollectOrchestrator] 안으로 다시 넣지 말 것** — `@Service` 클래스에 생성자를
 * 둘 두면(하나는 서비스용, 하나는 람다용) 스프링이 어느 쪽으로 주입할지 모른다.
 */
object DartRunPlan {
    fun run(
        endDe: LocalDate,
        now: LocalDateTime,
        collectDisclosures: (LocalDate, LocalDate, LocalDateTime) -> DartCollectSummary,
        collectInsiders: (List<String>, LocalDateTime) -> InsiderCollectSummary,
        onInsiderFailure: (Exception) -> Unit = {},
    ): DartRunResult {
        val disclosure = collectDisclosures(endDe.minusDays(1), endDe, now)

        if (disclosure.newRceptNos.isEmpty()) {
            return DartRunResult(disclosure, InsiderCollectSummary(0, 0, emptyList()))
        }

        val insider = try {
            collectInsiders(disclosure.newRceptNos, now)
        } catch (e: Exception) {
            onInsiderFailure(e)
            InsiderCollectSummary(0, 0, listOf(e.message ?: "unknown"))
        }

        return DartRunResult(disclosure, insider)
    }
}

@Service
class DartCollectOrchestrator(
    private val disclosureService: DartDisclosureCollectService,
    private val insiderService: DartInsiderCollectService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(endDe: LocalDate, now: LocalDateTime): DartRunResult =
        DartRunPlan.run(
            endDe, now,
            collectDisclosures = disclosureService::collect,
            collectInsiders = insiderService::collect,
            onInsiderFailure = { e ->
                log.warn("[DART] 소유변동 단계 실패 — 공시 수집은 유지된다: {}", e.message)
            },
        )
}
