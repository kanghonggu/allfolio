package com.allfolio.market.benchmark

import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * 벤치마크로 쓸 지수 수집 대상 (AF-107).
 *
 * **같은 KOSPI가 `market_index_quote`에도 있다.** 그쪽은 KIS 실시간(하루 세 슬롯)이고
 * 이쪽은 공공데이터포털 D+1 확정 종가(`benchmark_daily`)다. 용도가 달라 값이 다를 수 있고,
 * **그건 정상이다** — 시장 화면은 신선도가, 벤치마크·대시보드는 이력 정합성이 먼저다.
 * 둘 중 하나를 "중복"이라며 지우지 말 것.
 *
 * **소스가 FSC 하나뿐인데도 목록에 [fsc]라는 이름을 붙인 이유**는 `CommodityProperties`와 같다 —
 * 좌표의 모양이 소스마다 다르다. FSC의 좌표는 `(idxNm, idxCsf)` 쌍이지 시리즈 ID 하나가 아니다.
 *
 * **환경변수로 항목 하나만 패치할 수는 없다.** 스프링은 리스트를 병합하지 않고 우선순위가
 * 높은 쪽으로 통째로 교체한다 — `MarketRateProperties`·`CommodityProperties`와 같은 함정이다.
 * 항목을 고칠 땐 `application.yml`을 고칠 것.
 */
@Component
@ConfigurationProperties(prefix = "benchmark-index")
class BenchmarkIndexProperties {
    /** 공공데이터포털 지수시세정보에서 받는 지수. 좌표가 `(idxNm, idxCsf)` **쌍**이다 */
    var fsc: List<BenchmarkIndexItem> = emptyList()

    val types: List<String> get() = fsc.map { it.type }

    /**
     * 벤치마크로 쓸 지수 한 종.
     *
     * **`idxNm` 하나로는 지수가 유일하지 않다.** 실측에서 `"IT 서비스"`가 `KOSPI시리즈`와
     * `KOSDAQ시리즈`에 둘 다 있었다(1주 조회 `totalCount=672`). 그래서 좌표가
     * `(idxNm, idxCsf)` 쌍이다 — 이름만으로 고르면 지수를 하나 더할 때 잘못된 시리즈를 집고,
     * **값이 그럴듯해서 못 알아챈다.** `"코스피"`가 지금 1건인 것은 그 이름이 마침 유일해서지
     * 이름이 키라서가 아니다.
     *
     * 클래스를 [BenchmarkIndexProperties] 안에 두는 것은 `CommodityProperties.CommodityItem` ·
     * `MarketRateProperties.EcosSeries` · `MarketIndexProperties.DomesticIndex`의 관례다.
     */
    class BenchmarkIndexItem {
        /**
         * `benchmark_daily.index_type`과 일치해야 한다(`BenchmarkType.name`).
         *
         * 여기서 `BenchmarkType`으로 검증하지 않는 이유: 이 모듈(backend-app)의 설정이
         * unified-asset의 enum을 바인딩 시점에 끌어오면 설정 클래스가 도메인에 묶인다.
         * 값이 틀리면 수집 서비스의 `BenchmarkType.valueOf`가 그 지수 하나만 실패로 남긴다.
         */
        var type: String = ""

        /** 포털 조회 파라미터이자 **응답 필터**. 예: 코스피 */
        var idxNm: String = ""

        /** 응답 필터. 예: KOSPI시리즈. **비면 전 행이 걸러져 조용히 0건이 된다** */
        var idxCsf: String = ""
    }

    /**
     * 오타난 설정으로는 기동하지 않는다. `MarketRateProperties.validate()`·
     * `CommodityProperties.validate()`와 같은 자리·같은 방식이다.
     *
     * **여기서 막는 실패는 전부 "조용한 0건"이다.** `idx-csf`를 빼먹으면 응답 필터가 전 행을
     * 걸러 내는데, 그 증상은 오류가 아니라 `emptySeries` — "그 지수는 원래 안 나온다"로 보인다.
     * `type`이 중복이면 뒤 항목이 앞 항목의 `benchmark_daily` 행을 덮어쓸 뿐 제약조건도 안 걸린다.
     * 런타임 실패로 흘리면 매일 실패 한 줄이 쌓일 뿐이고, 기동을 실패시키면 사람이 즉시 본다.
     */
    @PostConstruct
    fun validate() {
        val itemProblems = fsc.flatMapIndexed { index, item ->
            val label = item.type.ifBlank { "benchmark-index.fsc[$index]" }
            buildList {
                if (item.type.isBlank()) add("$label: type이 비어 있습니다")
                if (item.idxNm.isBlank()) add("$label: idx-nm이 비어 있습니다")
                if (item.idxCsf.isBlank()) add("$label: idx-csf가 비어 있습니다")
            }
        }

        // 중복은 항목 하나만 봐서는 알 수 없다 — 목록 전체를 훑어야 하는 규칙이라 따로 둔다.
        // 저장 키가 (index_type, date)라 같은 실행 안에서 뒤 항목이 앞 항목을 덮어쓸 뿐이고,
        // 요약은 초록인 채 어느 쪽 값이 남았는지 알 수 없게 된다
        val duplicateProblems = types
            // 빈 type은 위에서 이미 잡았다. 남겨 두면 "빈 문자열이 중복됩니다"라는
            // 읽을 수 없는 두 번째 문제가 같이 나온다
            .filter { it.isNotBlank() }
            .groupingBy { it }.eachCount()
            .filterValues { it > 1 }.keys
            .map { "$it: type이 중복됩니다" }

        val problems = itemProblems + duplicateProblems
        require(problems.isEmpty()) { "benchmark-index 설정이 올바르지 않습니다 — " + problems.joinToString("; ") }
    }
}
