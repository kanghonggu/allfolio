package com.allfolio.market.benchmark

import com.allfolio.unifiedasset.application.port.BenchmarkDailyStore
import com.allfolio.unifiedasset.domain.benchmark.BenchmarkType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 지수 수집 한 번의 결과. 축은 [com.allfolio.market.commodity.CommodityCollectSummary]와 같게
 * 잡되, 저장 포트가 (날짜, 종가) 쌍만 받아 갈리지 않는 것만 뺐다 —
 * 어드민 판정과 워크플로 요약이 같은 축을 읽는다.
 *
 * @param requested 설정에 있는 지수 수. **0이면 설정이 빈 것이지 상류 문제가 아니다**
 * @param saved     실제 저장된 행 수. **upsert 뒤에 센다** — 앞에서 세면 전량 쓰기 실패인데도
 *                  숫자가 남아, AF-102가 `collected=60`에 200을 낸 그 사고가 된다.
 *                  원자재의 `inserted`/`updated`/`unchanged` 구분이 여기 없는 이유는
 *                  [BenchmarkDailyStore.upsert]가 SQL UPSERT 한 방이라 무엇이 새 행이었는지
 *                  돌려주지 않기 때문이다 — 가르려면 저장 전에 조회를 한 번 더 해야 하고,
 *                  그 왕복은 이 요약이 주는 것보다 비싸다
 * @param outOfRange 요청 구간 밖 날짜라 걷어낸 행 수
 * @param emptySeries 저장할 행이 한 건도 안 남은 지수. **그 자체로 실패는 아니다** — 다만
 *                    `(idxNm, idxCsf)` 쌍이 틀려도 똑같이 0건이라 자동으로는 못 가른다.
 *                    그래서 세지 말고 이름을 남긴다.
 *
 *                    **"휴장이라 비는 게 정상"이라고 적지 말 것.** 그건 창 길이에 달린 문제다 —
 *                    살아 있는 KOSPI는 창에 영업일이 하루라도 들어 있으면 반드시 값을 준다
 *                    (일일 크론의 14일 창이면 연휴가 껴도 영업일 5~6일이 들어온다).
 *                    창을 며칠로 줄이는 날 이 문장을 다시 볼 것 — 그때는 빈 결과가 정상이 되고,
 *                    "정상"이라 적어 둔 판본은 진짜 장애(쌍 오타·키 미승인)를 무시하게 만든다.
 *                    AF-102가 `BASE_RATE`를 "정상적으로 빈다"고 적어 낸 사고의 거울상이다.
 * @param failures "KOSPI: <사유>" 형태. 어느 지수가 왜 빠졌는지 한 번에 보여야 한다
 */
data class BenchmarkCollectSummary(
    val from: LocalDate,
    val to: LocalDate,
    val requested: Int,
    val saved: Int,
    val outOfRange: Int,
    val emptySeries: List<String>,
    val failed: Int,
    val failures: List<String>,
)

/**
 * 벤치마크 지수 수집 (AF-107).
 *
 * **[com.allfolio.market.commodity.CommodityCollectService]를 옮겨 온 것이다.** 아래 방어들
 * (구간 밖 날짜 필터·0건 명명·중복 접기·빈 배치 저장 안 함·저장 뒤 계수·지수별 실패 격리·
 * 사유 절단·인터럽트 확인)은 AF-102가 ECOS를 겪으며 네 차례에 걸쳐 붙인 것이고
 * **소스와 무관하게 옳다.** 한쪽만 고쳐진 판본이 생기지 않도록, 고칠 일이 생기면 같이 볼 것.
 *
 * **원자재보다 작다.** 전일대비(`prevClose`·`changeValue`·`changeRate`)를 계산하지 않고
 * 단위·주기도 없다 — `benchmark_daily`가 (index_type, date, close_value, created_at)뿐이고 그중 값은 close_value 하나이라
 * 저장 포트가 (날짜, 종가) 쌍만 받는다. 그래서 사다리도, 기존 행 조회도, 설정 조회도 없다.
 *
 * 일일 수집과 백필이 같은 경로를 쓴다 — 둘 다 "이 구간을 소스가 준 값으로 맞춘다"이고
 * UPSERT라 멱등하다.
 *
 * **`@Transactional`을 붙이지 않는다** — 지수마다 HTTP 호출이 하나씩 있어서 트랜잭션에 넣으면
 * 루프가 끝날 때까지 Neon 커넥션을 쥐고 앉아 있게 된다. 금리·원자재 수집과 같은 이유다.
 *
 * **수집 창은 여기서 정하지 않는다.** `from`·`to`를 받기만 한다 — 기본 창(일일 크론)과
 * 백필 구간은 호출자(어드민 컨트롤러)의 결정이고, 그쪽이 KST 기준 날짜를 만든다.
 */
@Service
class FscIndexCollectService(
    private val client: FscIndexClient,
    private val properties: BenchmarkIndexProperties,
    private val store: BenchmarkDailyStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /**
         * 실패 사유 길이 상한. 저장 예외 메시지는 SQL과 파라미터가 통째로 실린 여러 줄 덤프인데,
         * 이 문자열은 어드민 JSON 응답과 GitHub Actions 주석에 그대로 나간다.
         * `CommodityCollectService`·`RateCollectService`가 같은 이유로 같은 상한을 둔다.
         */
        private const val FAILURE_DETAIL_LENGTH = 200
    }

    fun collect(from: LocalDate, to: LocalDate): BenchmarkCollectSummary {
        require(!from.isAfter(to)) { "from이 to보다 늦습니다: $from ~ $to" }

        var saved = 0
        var outOfRange = 0
        val emptySeries = mutableListOf<String>()
        val failures = mutableListOf<String>()

        val items = properties.fsc

        for (item in items) {
            try {
                // **`valueOf`가 이 try 안에 있어야 한다.** 밖으로 빼면 설정 오타 하나
                // (`type: KOSPPI`)로 수집 전체가 죽고, `BenchmarkIndexItem.type`의 KDoc이 한
                // 약속("값이 틀리면 그 지수 하나만 실패로 남는다")이 거짓이 된다.
                // 설정 검증(BenchmarkIndexProperties.validate)은 backend-app이 도메인 enum에
                // 묶이지 않도록 일부러 이 값을 안 보므로, 오타가 걸리는 자리는 여기뿐이다.
                //
                // 호출보다 먼저 푸는 것은 덤이다 — 어차피 저장 못 할 지수에 HTTP 왕복을 쓰지 않는다
                val type = BenchmarkType.valueOf(item.type)

                val fetched = client.fetch(item, from, to)

                // 요청 구간 밖 날짜를 걷어낸다. 클라이언트는 날짜만 파싱되면 통과시키고
                // 포털이 `beginBasDt`·`endBasDt`를 존중한다는 전제는 여기서 믿지 않는다 —
                // 구간 밖 행이 저장되면 요청한 적 없는 날짜가 benchmark_daily에 남고,
                // 백필을 다시 돌려도 그 행은 창 밖이라 영원히 정정되지 않는다
                val inRange = fetched.filter { it.first in from..to }
                outOfRange += fetched.size - inRange.size

                // 같은 응답에 같은 날짜가 두 번 오면 접는다. 저장이 UPSERT라 배치가 죽지는 않지만
                // 안 접으면 같은 배치가 같은 행을 두 번 쓰면서 saved만 부푼다.
                // 마지막 값을 남긴다 — 정정본이 뒤에 오는 형태이기 때문이다
                val deduped = LinkedHashMap<LocalDate, BigDecimal>()
                inRange.forEach { deduped[it.first] = it.second }

                if (deduped.isEmpty()) {
                    // **0건을 실패로 만들지 않는다.** 다만 (idxNm, idxCsf) 쌍이 틀려도 똑같이
                    // 0건이라 자동으로는 못 가른다 — 이름을 남겨 사람이 보게 한다.
                    // 빈 배치에 저장을 걸지도 않는다: 빈 목록도 커넥션을 잡고 트랜잭션을 연다
                    emptySeries += item.type
                } else {
                    val rows = deduped.map { (date, close) -> date to close }
                    store.upsert(type, rows)

                    // **반드시 저장한 뒤에 센다.** 세고 나서 저장하면 upsert가 통째로 터진
                    // 실행에서도 saved가 채워지고, 어드민이 saved == 0으로 잡아내려던
                    // "한 건도 안 들어간 잡"이 초록으로 지나간다
                    saved += rows.size
                }
            } catch (e: Exception) {
                // 한 지수의 실패가 나머지를 끌고 가지 않는다
                failures += "${item.type}: ${detail(e)}"
            }

            // 종료 신호는 예외로 위장해서 온다 — FscIndexClient는 InterruptedException을 만나면
            // 플래그를 되살리고 FscApiException으로 바꿔 던지므로 위 catch가 그대로 삼킨다.
            // 플래그를 안 보면 셧다운 중에 남은 지수를 끝까지 돌며 가짜 실패만 쌓는다
            if (Thread.currentThread().isInterrupted) break
        }

        val summary = BenchmarkCollectSummary(
            from = from,
            to = to,
            requested = items.size,
            saved = saved,
            outOfRange = outOfRange,
            emptySeries = emptySeries,
            failed = failures.size,
            failures = failures,
        )

        when {
            items.isEmpty() ->
                log.warn("[벤치마크지수] 설정된 수집 대상이 없습니다 — benchmark-index 설정 확인")
            failures.isEmpty() -> log.info("[벤치마크지수] 수집 완료 {}", summary)
            else -> log.warn("[벤치마크지수] 일부 실패 {}", summary)
        }
        return summary
    }

    private fun detail(e: Exception): String {
        val message = e.message ?: return e.javaClass.simpleName
        return if (message.length <= FAILURE_DETAIL_LENGTH) message else message.take(FAILURE_DETAIL_LENGTH) + "…"
    }
}
