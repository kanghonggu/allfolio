package com.allfolio.market.rate

import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * 금리 수집 대상 (AF-102).
 *
 * **맵이 아니라 리스트인 이유**: `EcosProperties.series`는 통화별 맵이라 대문자 키를
 * 환경변수로 표현할 수 없는 문제를 안고 있다(relaxed binding이 소문자화한다).
 * 여기서는 코드가 값이므로 그 문제가 아예 생기지 않는다.
 *
 * **미확인 종목은 빈 코드로 두지 말고 목록에서 뺀다.** 빈 코드를 넣으면 대상 수에는 잡히고
 * 매일 실패로 남지만, 빼면 대상 수 자체가 줄어 "아직 안 넣었다"는 사실이 그대로 드러난다.
 */
@Component
@ConfigurationProperties(prefix = "market-rate")
class MarketRateProperties {
    var series: List<RateSeries> = emptyList()

    class RateSeries {
        /** 우리가 정한 canonical 코드. DB의 rate_code가 된다 */
        var code: String = ""
        /** ECOS 통계표 코드 */
        var statCode: String = ""
        /** ECOS 항목 코드 */
        var itemCode: String = ""
        /** ECOS 주기 코드. 현재 지원은 D뿐이다 */
        var cycle: String = "D"
    }

    /**
     * 오타난 설정으로는 기동하지 않는다.
     *
     * 런타임 실패로 흘리면 매일 실패 한 줄이 쌓일 뿐이고 그 종목은 계속 비어 있다.
     * `EcosProperties.Series`가 `unit-divisor`에 같은 판단을 한다 — 바인딩 시점에 막는다.
     *
     * **`init` 블록으로는 안 된다.** 이 클래스는 setter 바인딩(`var`)이라 생성자가
     * 빈 값으로 먼저 돌고 나서 프로퍼티가 채워진다 — `EcosProperties.Series`가 쓰는
     * `require`가 여기서는 항상 빈 값에 대해 돈다. 바인딩이 끝난 뒤인 `@PostConstruct`여야 한다.
     */
    @PostConstruct
    fun validate() {
        val problems = series.flatMap { s ->
            val label = s.code.ifBlank { "(code 없음)" }
            buildList {
                if (s.code.isBlank()) add("code가 비어 있습니다")
                if (s.statCode.isBlank()) add("$label: stat-code가 비어 있습니다")
                if (s.itemCode.isBlank()) add("$label: item-code가 비어 있습니다")
                // 클라이언트도 같은 검사를 하지만 그건 호출 시점이라 종목별 실패로 흩어진다.
                // 여기서 막으면 배포가 실패해 사람이 즉시 본다
                if (s.cycle != "D") add("$label: 지원하지 않는 주기입니다: ${s.cycle} (현재 D만 지원)")
            }
        }
        require(problems.isEmpty()) { "market-rate.series 설정이 올바르지 않습니다 — " + problems.joinToString("; ") }
    }
}
