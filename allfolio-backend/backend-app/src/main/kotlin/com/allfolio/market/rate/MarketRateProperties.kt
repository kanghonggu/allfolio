package com.allfolio.market.rate

import com.allfolio.fx.EcosQuery
import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * 금리 수집 대상 (AF-102).
 *
 * **목록이 소스별로 나뉘어 있다.** 좌표의 모양이 소스마다 달라서다 — ECOS는 통계표·항목·주기
 * 셋으로 한 계열을 찍지만 FRED는 시리즈 ID 하나뿐이다. 한 목록에 담고 옵셔널 필드로 섞으면
 * 어느 조합이 유효한지 타입으로 말할 수 없게 된다.
 *
 * **맵이 아니라 리스트인 이유**: `EcosProperties.series`는 통화별 맵이라 대문자 키를
 * 환경변수로 표현할 수 없는 문제를 안고 있다(relaxed binding이 소문자화한다).
 * 여기서는 코드가 값이므로 그 문제가 아예 생기지 않는다.
 *
 * **미확인 종목은 빈 코드로 두지 말고 목록에서 뺀다.** 빈 코드를 넣으면 대상 수에는 잡히고
 * 매일 실패로 남지만, 빼면 대상 수 자체가 줄어 "아직 안 넣었다"는 사실이 그대로 드러난다.
 *
 * **환경변수로 항목 하나만 패치할 수는 없다.** 스프링은 리스트를 병합하지 않고 우선순위가
 * 높은 쪽으로 통째로 교체한다 — `market-rate.ecos[2].item-code`를 환경변수로 얹으면
 * `application.yml`의 나머지 다섯 항목이 통째로 사라진다. AF-101에서 겪은 "대상이 조용히
 * 줄어든다"와 같은 함정이다. 항목을 고칠 땐 `application.yml`을 고칠 것.
 */
@Component
@ConfigurationProperties(prefix = "market-rate")
class MarketRateProperties {
    var ecos: List<EcosSeries> = emptyList()

    var fred: List<FredSeries> = emptyList()

    /**
     * 수집·조회 양쪽이 쓰는 전체 코드 목록. **순서가 한국 → 미국이고, 그게 화면 순서가 된다.**
     *
     * 이 프로퍼티가 있는 이유: 코드 목록을 필요로 하는 곳이 둘(수집 서비스는 소스를 통해,
     * 조회 서비스는 여기를 통해)인데, 양쪽이 각자 `ecos + fred`를 더하면 소스가 셋이 되는 날
     * 한쪽만 고쳐진다. 그때 증상은 "수집은 되는데 화면에 없다"이고, 오류도 로그도 안 난다.
     */
    val allCodes: List<String>
        get() = ecos.map { it.code } + fred.map { it.code }

    class EcosSeries {
        /** 우리가 정한 canonical 코드. DB의 rate_code가 된다 */
        var code: String = ""
        /** ECOS 통계표 코드 */
        var statCode: String = ""
        /** ECOS 항목 코드 */
        var itemCode: String = ""
        /** ECOS 주기 코드. 현재 지원은 D뿐이다 */
        var cycle: String = EcosQuery.DAILY_CYCLE
    }

    /** FRED 시계열 한 종. 좌표가 시리즈 ID 하나뿐이라 ECOS와 모양이 다르다 */
    class FredSeries {
        /** 우리가 정한 canonical 코드. DB의 rate_code가 된다 */
        var code: String = ""
        /** FRED series_id. 예: DGS10 */
        var seriesId: String = ""
    }

    /**
     * 오타난 설정으로는 기동하지 않는다.
     *
     * 런타임 실패로 흘리면 매일 실패 한 줄이 쌓일 뿐이고 그 종목은 계속 비어 있다.
     * `EcosProperties.Series`가 `unit-divisor`에 같은 판단을 한다 — 바인딩 시점에 막는다.
     *
     * **`init` 블록으로는 안 된다.** 이유는 두 가지다. (1) 이 클래스의 모양은
     * `MarketIndexProperties`를 따른다 — 리스트를 감싼 단순 POJO이지 `EcosProperties.Series`
     * 같은 생성자 바인딩 `data class`가 아니다. (2) code 중복 검사(아래)는 항목 하나가 아니라
     * 목록 전체를 봐야 하는 규칙이라, 항목별로 도는 `init`으로는 애초에 표현할 수 없다.
     * 그래서 바인딩이 끝난 뒤 한 번 도는 `@PostConstruct`를 쓴다.
     */
    @PostConstruct
    fun validate() {
        val itemProblems = ecos.flatMapIndexed { index, s ->
            val label = s.code.ifBlank { "ecos[$index]" }
            buildList {
                if (s.code.isBlank()) add("$label: code가 비어 있습니다")
                if (s.statCode.isBlank()) add("$label: stat-code가 비어 있습니다")
                if (s.itemCode.isBlank()) add("$label: item-code가 비어 있습니다")
                // 클라이언트도 같은 검사를 하지만 그건 호출 시점이라 종목별 실패로 흩어진다.
                // 여기서 막으면 배포가 실패해 사람이 즉시 본다
                if (s.cycle != EcosQuery.DAILY_CYCLE) {
                    add("$label: 지원하지 않는 주기입니다: ${s.cycle} (현재 ${EcosQuery.DAILY_CYCLE}만 지원)")
                }
            }
        } + fred.flatMapIndexed { index, s ->
            val label = s.code.ifBlank { "fred[$index]" }
            buildList {
                if (s.code.isBlank()) add("$label: code가 비어 있습니다")
                if (s.seriesId.isBlank()) add("$label: series-id가 비어 있습니다")
            }
        }

        // code 중복은 항목 하나만 봐서는 알 수 없다 — 전체 목록을 훑어야 하는 유일한 규칙이라 따로 둔다.
        // Task 9에서 6줄짜리 블록을 복붙하다 code를 안 바꾸면 이게 생긴다. upsert가
        // (rateCode, quoteDate) 키라 같은 배치 안에서 뒤 항목이 앞 항목을 덮어쓸 뿐 제약조건은
        // 안 걸리고, 요약은 "requested=6 collected=6 failed=0"으로 초록인 채 종목 하나가 사라진다.
        //
        // **두 목록을 합쳐서 본다.** 목록이 갈렸다고 목록별로 검사하면 같은 코드가 ecos와 fred에
        // 하나씩 있는 경우를 놓치는데, 그게 정확히 위 문제의 더 나쁜 판본이다 — 소스가 둘이라
        // 값도 출처도 매 실행 뒤에 도는 쪽으로 뒤집히고, 제약조건도 요약도 여전히 조용하다.
        val duplicateProblems = buildList {
            allCodes
                // 빈 코드는 위에서 이미 잡았다. 여기 남겨 두면 "빈 문자열이 중복됩니다"라는
                // 읽을 수 없는 두 번째 문제가 같이 나온다
                .filter { it.isNotBlank() }
                .groupingBy { it }.eachCount()
                .filterValues { it > 1 }.keys
                .forEach { add("$it: code가 중복됩니다") }
        }

        val problems = itemProblems + duplicateProblems
        require(problems.isEmpty()) { "market-rate 설정이 올바르지 않습니다 — " + problems.joinToString("; ") }
    }
}
