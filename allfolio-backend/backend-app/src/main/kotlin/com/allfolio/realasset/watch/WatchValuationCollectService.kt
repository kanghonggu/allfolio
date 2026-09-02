package com.allfolio.realasset.watch

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * watchpricedata 평가를 하루 한 번 복제한다 (W5).
 *
 * ## 왜 복제하나
 *
 * **사용자 요청 시점에 외부를 부르지 않는다**(설계 7절). watchpricedata는 EC2 단일
 * 인스턴스이고 ES 질의가 무겁다 — 우리 평가 배치가 그 응답 시간에 묶이면, 그쪽이 느린 날
 * 우리 배치가 통째로 밀린다. `RtmsSource`가 `rtms_deals_cache`를 읽는 것과 같은 구조다.
 *
 * ## 보유한 ref만 부른다
 *
 * 전 ref를 긁지 않는다. 사용자가 등록한 `ua_assets` 중 `WATCH`의 `symbol`만 모아
 * **중복을 접고** 부른다 — 같은 ref를 여러 사용자가 가져도 호출은 한 번이다.
 *
 * 그래서 **등록된 시계가 0건이면 호출도 0건이고 그게 정상이다.** 실패가 아니다
 * (`RealAssetValuationAdminController`가 대상 0건을 200으로 다루는 것과 같은 판단).
 *
 * ## 실패를 자산 단위로 가둔다
 *
 * ref 하나가 실패해도 나머지는 저장한다. [WatchValuationClient]가 예외 대신 null을
 * 돌려주므로 여기서는 세기만 한다 — 어느 ref가 빠졌는지는 요약에 담아 Actions 로그에서
 * 보이게 한다. 로그만 남기면 워크플로에서 안 보인다.
 */
@Service
class WatchValuationCollectService(
    private val client: WatchValuationClient,
    private val store: Store,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    interface Store {
        /** 사용자가 등록한 시계의 ref. 중복은 이미 접혀서 온다 */
        fun heldRefKeys(): List<String>

        /** `(refKey, asOf)`로 upsert. 같은 날 두 번 돌려도 행이 늘지 않는다 */
        fun upsert(response: WatchValuationResponse, collectedAt: LocalDateTime)
    }

    fun collect(now: LocalDateTime): WatchValuationCollectSummary {
        val refs = store.heldRefKeys()
        if (refs.isEmpty()) {
            log.info("[시계] 등록된 시계가 없어 수집할 ref가 없다")
            return WatchValuationCollectSummary(requested = 0, stored = 0, skipped = emptyList())
        }

        val skipped = mutableListOf<String>()
        var stored = 0

        refs.forEach { refKey ->
            val response = client.valuate(refKey)
            if (response == null) {
                // 표본 부족(서버가 null)과 조회 실패를 구분하지 않는다 — 둘 다 "오늘은 이 ref의
                // 값이 없다"이고, 폴백이 직전 값을 쓴다. 구분이 필요해지면 클라이언트가
                // 사유를 돌려주도록 고쳐야 한다.
                skipped += refKey
                return@forEach
            }
            store.upsert(response, now)
            stored++
        }

        log.info("[시계] ref {}건 중 {}건 저장, {}건 건너뜀", refs.size, stored, skipped.size)
        return WatchValuationCollectSummary(refs.size, stored, skipped)
    }
}

data class WatchValuationCollectSummary(
    val requested: Int,
    val stored: Int,
    /** 값이 없어 저장하지 않은 ref. **0건이 아닌 게 정상이다** — 표본 3건 미만이 흔하다 */
    val skipped: List<String>,
)
