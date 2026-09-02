package com.allfolio.api.admin

import com.allfolio.realasset.watch.WatchValuationCollectService
import com.allfolio.realasset.watch.WatchValuationCollectSummary
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime
import java.time.ZoneOffset

@RestController
@RequestMapping("/api/admin/watch")
class WatchValuationAdminController(
    private val service: WatchValuationCollectService,
) {
    /**
     * POST /api/admin/watch/collect — watchpricedata 평가 복제 (어드민 전용, W5).
     *
     * **상태 코드 규칙**
     *
     * 이 배치는 상류(watchpricedata)를 부르지만 **502를 내지 않는다.** 형제 시세 수집들과
     * 갈리는 지점이고, 이유는 실패의 성격이 다르기 때문이다: 원자재·지수는 그날 값을 못 받으면
     * 그 날짜 행이 영영 안 생기지만, 여기는 **폴백이 직전 값을 쓴다**(설계 3절과 같은 구조).
     * 하루 못 받은 것은 장애가 아니라 신선도가 하루 낡은 것이고, `staleness`가 그걸 말한다.
     *
     * **대상 0건도 정상이다.** 수집 대상이 설정이 아니라 **사용자가 등록한 시계**라, 아무도
     * 시계를 안 넣었으면 0건이 맞다(`RealAssetValuationAdminController`와 같은 판단).
     * 이걸 실패로 내면 W6 전까지 매일 빨간 잡이 뜨고, 그러면 아무도 안 보게 된다.
     *
     * **`skipped`가 0이 아닌 게 정상이다.** 표본 3건 미만이면 서버가 값을 안 준다(W4).
     * 그 ref는 이번 회차에 저장되지 않고 폴백이 직전 값을 쓴다.
     *
     * **저장 시각은 UTC다** — `collected_at`이 `TIMESTAMPTZ`라 KST 벽시계를 넣으면 그대로
     * UTC로 라벨링돼 9시간 미래가 된다(D1에서 실제로 그랬다).
     */
    @PostMapping("/collect")
    fun collect(): ResponseEntity<WatchValuationCollectSummary> =
        ResponseEntity.ok(service.collect(LocalDateTime.now(ZoneOffset.UTC)))
}
