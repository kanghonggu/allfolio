package com.allfolio.api.admin

import com.allfolio.realasset.RealAssetValuationService
import com.allfolio.realasset.RealAssetValuationSummary
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RestController
@RequestMapping("/api/admin/real-asset")
class RealAssetValuationAdminController(
    private val service: RealAssetValuationService,
) {
    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }

    /**
     * POST /api/admin/real-asset/valuate — 실물자산 평가 스냅샷 (어드민 전용, A1 · G5).
     *
     * **`LocalDate.now()`가 아니라 KST로 옮겨 오늘을 구한다** — Render 컨테이너는 UTC라
     * 19:30 KST 실행이 그대로면 10:30 UTC 같은 날이라 우연히 맞지만, 크론이 밀려 자정을 넘기면
     * 하루 전으로 밀린다. 형제 어드민들이 같은 자리에 같은 방어를 한다.
     *
     * **상태 코드 규칙이 시세 수집과 다르다 — 이 배치에는 상류가 없다.**
     * 평가는 우리 DB만 읽는다(`market_commodity_quote` 폴백 조회). 그래서 502가 나올 자리가
     * 없다. 전량 실패는 상류 장애가 아니라 **우리 문제**(마이그레이션 미적용·코드 오류)이고,
     * 502로 내면 운영자가 멀쩡한 공공데이터포털 상태 페이지를 확인하러 간다.
     *
     * **`requested == 0`은 실패가 아니다 — 여기도 시세 수집과 갈린다.** 원자재는 수집 대상이
     * 설정이라 0건이 곧 설정 실수지만, 평가 대상은 사용자가 등록한 자산이라 **0건이 정상**이다
     * (아무도 아직 실물자산을 안 넣은 상태). 이걸 500으로 내면 배포 첫날부터 매일 빨간 잡이 뜨고,
     * 그러면 아무도 안 보게 된다.
     *
     * 배포 직후 전량 실패가 나면 대개 두 표가 없는 것이다 —
     * `docs/superpowers/migrations/2026-08-18-real-asset-tables.sql`이 배포 전에 실행돼야 한다.
     */
    @PostMapping("/valuate")
    fun valuate(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) valuedOn: LocalDate?,
    ): ResponseEntity<RealAssetValuationSummary> {
        val target = valuedOn ?: LocalDate.now(KST)
        val summary = service.valuate(target, Instant.now())

        // 저장 0 + 실패 있음 = 우리 문제다. 500으로 내고 사유를 그대로 싣는다 —
        // 첫 배포에서 기대되는 실패(테이블 부재)가 정확히 이 경로로 온다.
        //
        // **저장 0 + 전부 건너뜀은 200이다.** 연휴라 시세가 없거나(정상) 아직 어댑터가 없는
        // 유형만 등록된 것이라(정상) 실패가 아니다. `skipped`가 이름을 대므로 조용하지도 않다.
        if (summary.valued == 0 && summary.failed > 0) {
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "실물자산 평가를 한 건도 저장하지 못했습니다 — 전량 실패 " +
                    "(대상 ${summary.requested}건, ${summary.valuedOn}): " +
                    summary.failures.joinToString("; ").ifBlank { "사유 없음" },
            )
        }

        return ResponseEntity.ok(summary)
    }
}
