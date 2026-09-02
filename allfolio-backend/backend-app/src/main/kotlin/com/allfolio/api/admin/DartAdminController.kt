package com.allfolio.api.admin

import com.allfolio.dart.DartApiException
import com.allfolio.dart.DartCollectOrchestrator
import com.allfolio.dart.DartReclassifyService
import com.allfolio.dart.DartRunResult
import com.allfolio.dart.ReclassifyResult
import com.allfolio.dart.corp.CorpMapSummary
import com.allfolio.dart.corp.DartCorpMapService
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 컨테이너가 UTC라 `LocalDate.now()`를 그냥 부르면 19:00 KST 실행이 "어제"를 조회한다.
 * KST 기준으로 오늘을 정한다 — 시세 수집기들과 같은 함정이다.
 */
private val KST: ZoneId = ZoneId.of("Asia/Seoul")

@RestController
@RequestMapping("/api/admin/dart")
class DartAdminController(
    private val orchestrator: DartCollectOrchestrator,
    private val corpMapService: DartCorpMapService,
    private val reclassifyService: DartReclassifyService,
) {
    @PostMapping("/collect")
    fun collect(
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) endDe: LocalDate?,
    ): ResponseEntity<DartRunResult> {
        // **조회 날짜는 KST, 저장 시각은 UTC다 — 둘을 같은 값으로 쓰면 안 된다.**
        // 조회 날짜가 KST인 이유: 컨테이너가 UTC라 그냥 now()를 쓰면 19:00 KST 실행이
        // "어제"를 조회한다. 저장 시각이 UTC인 이유: run_at·collected_at이 TIMESTAMPTZ라
        // Postgres가 naive 값을 세션 타임존(UTC)으로 해석한다 — KST 벽시계를 넣으면
        // 그대로 UTC로 라벨링돼 9시간 미래가 된다(2026-08-19 운영에서 실제로 그랬다).
        val target = endDe ?: LocalDate.now(KST)
        return try {
            ResponseEntity.ok(orchestrator.run(target, LocalDateTime.now(ZoneOffset.UTC)))
        } catch (e: DartApiException) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, e.message)
        }
    }

    /**
     * POST /api/admin/dart/reclassify — 저장된 행을 현재 화이트리스트로 다시 판정 (S13)
     *
     * **스케줄러에 노출하지 않는다.** 화이트리스트를 고친 뒤 사람이 한 번 부르는 일회성
     * 작업이고, 스케줄러가 할 수 있어야 하는 일이 아니다 — `fx/backfill`을 어드민에만 둔
     * 것과 같은 이유다.
     *
     * `apply`의 기본값이 false인 것은 의도다. 파라미터 이름을 틀리거나 빠뜨리면 **아무것도
     * 쓰지 않고** 전이 표만 돌려준다. 반대로 기본이 true면 오타 한 번이 전 구간을 조용히
     * 덮어쓴다.
     */
    @PostMapping("/reclassify")
    fun reclassify(
        @RequestParam(required = false, defaultValue = "false") apply: Boolean,
    ): ResponseEntity<ReclassifyResult> = ResponseEntity.ok(reclassifyService.run(apply))

    @PostMapping("/corp-map/refresh")
    fun refreshCorpMap(): ResponseEntity<CorpMapSummary> =
        try {
            // 저장 시각은 UTC다 — 위 collect()의 주석 참고
            ResponseEntity.ok(corpMapService.refresh(LocalDateTime.now(ZoneOffset.UTC)))
        } catch (e: DartApiException) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, e.message)
        }
}
