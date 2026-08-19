package com.allfolio.api.admin

import com.allfolio.dart.DartApiException
import com.allfolio.dart.DartCollectOrchestrator
import com.allfolio.dart.DartRunResult
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
) {
    @PostMapping("/collect")
    fun collect(
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) endDe: LocalDate?,
    ): ResponseEntity<DartRunResult> {
        val target = endDe ?: LocalDate.now(KST)
        return try {
            ResponseEntity.ok(orchestrator.run(target, LocalDateTime.now(KST)))
        } catch (e: DartApiException) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, e.message)
        }
    }

    @PostMapping("/corp-map/refresh")
    fun refreshCorpMap(): ResponseEntity<CorpMapSummary> =
        try {
            ResponseEntity.ok(corpMapService.refresh(LocalDateTime.now(KST)))
        } catch (e: DartApiException) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, e.message)
        }
}
