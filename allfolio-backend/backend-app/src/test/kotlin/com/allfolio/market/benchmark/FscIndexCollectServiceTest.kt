package com.allfolio.market.benchmark

import com.allfolio.market.fsc.FscApiException
import com.allfolio.unifiedasset.application.port.BenchmarkDailyStore
import com.allfolio.unifiedasset.domain.benchmark.BenchmarkType
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * `CommodityCollectServiceTest`를 템플릿으로 삼았다 — 방어가 같으므로 회귀 테스트도 같아야 한다.
 * 원자재에 있고 여기 없는 것(전일대비 사다리·설정 단위/주기·주기별 기본 창)은 저장 포트가
 * (날짜, 종가) 쌍만 받기 때문이지 덜 방어해서가 아니다.
 */
class FscIndexCollectServiceTest {

    private val from = LocalDate.of(2026, 8, 10)
    private val to = LocalDate.of(2026, 8, 13)

    private val aug13: LocalDate = LocalDate.of(2026, 8, 13)
    private val aug12: LocalDate = LocalDate.of(2026, 8, 12)

    @Test
    fun `지수별로 조회해 저장하고 건수를 보고한다`() {
        val store = FakeStore()
        val client = FakeClient(
            rows = mapOf(
                "KOSPI" to listOf(aug13 to BigDecimal("6813.34"), aug12 to BigDecimal("6579.04")),
                "SPX" to listOf(aug13 to BigDecimal("7100.00")),
            ),
        )

        val summary = service(client, store).collect(from, to)

        assertThat(summary.requested).isEqualTo(2)
        assertThat(summary.saved).isEqualTo(3)
        assertThat(summary.failed).isZero()
        assertThat(summary.emptySeries).isEmpty()
        assertThat(store.upserted[BenchmarkType.KOSPI])
            .containsExactly(aug13 to BigDecimal("6813.34"), aug12 to BigDecimal("6579.04"))
        assertThat(store.upserted[BenchmarkType.SPX]).hasSize(1)
        // 클라이언트에는 요청 구간이 그대로 간다 — 창을 정하는 것은 호출자(어드민·크론)의 몫이다
        assertThat(client.fetched).containsExactly("KOSPI" to (from to to), "SPX" to (from to to))
    }

    /**
     * **테스트 1.** 소스가 구간 밖 날짜를 섞어 주면 걷어낸다.
     *
     * 포털은 `beginBasDt`·`endBasDt`를 존중하지만 그 전제를 여기서 안 믿는다 — 구간 밖 행이
     * 그대로 저장되면 `benchmark_daily`에 요청한 적 없는 날짜가 들어가고, 백필을 다시 돌려도
     * 그 행은 창 밖이라 영원히 정정되지 않는다.
     */
    @Test
    fun `요청 구간 밖 날짜는 걷어내고 센다`() {
        val store = FakeStore()
        val client = FakeClient(
            rows = mapOf(
                "KOSPI" to listOf(
                    aug13 to BigDecimal("6813.34"),
                    LocalDate.of(2026, 8, 20) to BigDecimal("6900.00"), // to(8/13) 이후
                    LocalDate.of(2026, 8, 3) to BigDecimal("6400.00"), // from(8/10) 이전
                ),
            ),
        )

        val summary = service(client, store).collect(from, to)

        assertThat(summary.outOfRange).isEqualTo(2)
        assertThat(summary.saved).isEqualTo(1)
        assertThat(store.upserted[BenchmarkType.KOSPI]).containsExactly(aug13 to BigDecimal("6813.34"))
    }

    /**
     * **테스트 2.** 같은 날짜가 두 번 오면 한 건으로 접는다. 저장은 UPSERT라 배치가 죽지는
     * 않지만, 접지 않으면 같은 배치 안에서 앞 값이 뒤 값으로 덮이면서 `saved`만 두 배로 부푼다 —
     * 실제 저장된 행 수를 세라는 규칙이 그 자리에서 거짓이 된다.
     * 뒤에 온 값을 남기는 건 정정본이 뒤에 오는 형태이기 때문이다.
     */
    @Test
    fun `같은 날짜가 중복으로 오면 마지막 값만 남긴다`() {
        val store = FakeStore()
        val client = FakeClient(
            rows = mapOf("KOSPI" to listOf(aug13 to BigDecimal("6800.00"), aug13 to BigDecimal("6813.34"))),
        )

        val summary = service(client, store).collect(from, to)

        assertThat(summary.saved).isEqualTo(1)
        assertThat(store.upserted[BenchmarkType.KOSPI]).containsExactly(aug13 to BigDecimal("6813.34"))
    }

    /**
     * **테스트 3.** 0건은 실패가 아니라 이름으로 남고, 저장은 아예 부르지 않는다.
     * 빈 배치도 커넥션을 잡고 트랜잭션을 연다.
     *
     * 다만 `(idxNm, idxCsf)` 쌍이 틀려도 똑같이 0건이라 자동으로는 못 가른다 — 이름을 남겨
     * 사람이 보게 한다.
     */
    @Test
    fun `0건으로 돌아온 지수는 실패가 아니라 이름으로 남고 저장을 부르지 않는다`() {
        val store = FakeStore()
        val client = FakeClient(
            rows = mapOf("KOSPI" to emptyList(), "SPX" to listOf(aug13 to BigDecimal("7100.00"))),
        )

        val summary = service(client, store).collect(from, to)

        assertThat(summary.emptySeries).containsExactly("KOSPI")
        assertThat(summary.failed).isZero()
        assertThat(summary.saved).isEqualTo(1)
        assertThat(store.upsertCalls).isEqualTo(1)
    }

    /**
     * **테스트 4.** 지수 하나가 터져도 나머지를 저장한다. 예외로 끝내면 살아 있던 값까지
     * 같이 잃고, 어느 지수가 왜 빠졌는지도 요약에 안 남는다.
     */
    @Test
    fun `한 지수가 실패해도 나머지는 저장한다`() {
        val store = FakeStore()
        val client = FakeClient(
            rows = mapOf("SPX" to listOf(aug13 to BigDecimal("7100.00"))),
            failing = mapOf("KOSPI" to FscApiException("HTTP-500", "공공데이터포털이 HTTP 500 를 반환했습니다")),
        )

        val summary = service(client, store).collect(from, to)

        assertThat(summary.requested).isEqualTo(2)
        assertThat(summary.saved).isEqualTo(1)
        assertThat(summary.failed).isEqualTo(1)
        assertThat(summary.failures.single()).contains("KOSPI").contains("HTTP 500")
        assertThat(store.upserted.keys).containsExactly(BenchmarkType.SPX)
    }

    /**
     * **테스트 5.** 실패 사유는 잘라서 싣는다. 이 문자열은 어드민 JSON 응답과 GitHub Actions
     * 주석에 그대로 나간다 — 여러 줄 덤프가 통째로 실리면 요약을 읽을 수 없게 된다.
     */
    @Test
    fun `실패 사유가 길면 잘라서 싣는다`() {
        val client = FakeClient(failing = mapOf("KOSPI" to IllegalStateException("가".repeat(500))))

        val summary = FscIndexCollectService(client, properties(item("KOSPI")), FakeStore()).collect(from, to)

        assertThat(summary.failures.single()).hasSize("KOSPI: ".length + 200 + 1) // 200자 + 말줄임표
    }

    /**
     * **테스트 6 — AF-102 회귀.** 저장이 터지면 `saved`가 부풀면 안 된다.
     *
     * 세고 나서 저장하면 이 실행이 "saved=1, failed=1"로 보고된다. 어드민과 워크플로는
     * `saved == 0`으로 "한 건도 안 들어간 잡"을 가르므로, 전량 쓰기 실패가 초록으로 지나간다 —
     * AF-102가 `collected=60`에 200을 낸 사고가 정확히 그 형태였다.
     */
    @Test
    fun `저장이 터지면 saved가 부풀지 않는다`() {
        val store = FakeStore(upsertFailure = IllegalStateException("relation benchmark_daily does not exist"))
        val client = FakeClient(rows = mapOf("KOSPI" to listOf(aug13 to BigDecimal("6813.34"))))

        val summary = FscIndexCollectService(client, properties(item("KOSPI")), store).collect(from, to)

        assertThat(summary.saved).isZero()
        assertThat(summary.failed).isEqualTo(1)
        assertThat(summary.failures.single()).contains("KOSPI")
    }

    /**
     * **테스트 7.** 설정의 `type`이 `BenchmarkType`에 없는 값이면 **그 지수 하나만** 실패한다.
     *
     * `BenchmarkIndexProperties.BenchmarkIndexItem.type`의 KDoc이 *"값이 틀리면 그 지수 하나만
     * 실패로 남는다"* 고 약속한다. `valueOf`가 지수별 `try` 밖에 있으면 오타 하나로 **수집 전체가
     * 죽고** 그 주석이 거짓이 된다 — 설정 검증(`validate`)은 도메인 enum을 모르므로 여기서만 걸린다.
     */
    @Test
    fun `BenchmarkType에 없는 type은 그 지수만 실패시킨다`() {
        val store = FakeStore()
        val client = FakeClient(
            rows = mapOf(
                "KOSPPI" to listOf(aug13 to BigDecimal("6813.34")), // 오타난 설정
                "SPX" to listOf(aug13 to BigDecimal("7100.00")),
            ),
        )
        val properties = properties(item("KOSPPI", idxNm = "코스피"), item("SPX", idxNm = "S&P 500"))

        val summary = FscIndexCollectService(client, properties, store).collect(from, to)

        assertThat(summary.failed).isEqualTo(1)
        assertThat(summary.failures.single()).contains("KOSPPI")
        assertThat(summary.saved).isEqualTo(1)
        assertThat(store.upserted.keys).containsExactly(BenchmarkType.SPX)
    }

    /**
     * 종료 신호는 예외로 위장해서 온다 — `FscIndexClient`가 `InterruptedException`을 만나면
     * 플래그를 되살리고 `FscApiException("IO")`로 바꿔 던지므로 실패 catch가 그대로 삼킨다.
     * 플래그를 안 보면 셧다운 중에 남은 지수를 끝까지 호출하며 가짜 실패만 쌓는다.
     */
    @Test
    fun `인터럽트가 걸리면 남은 지수를 돌지 않는다`() {
        val client = FakeClient(
            rows = mapOf("SPX" to listOf(aug13 to BigDecimal("7100.00"))),
            failing = mapOf("KOSPI" to FscApiException("IO", "공공데이터포털 호출에 실패했습니다")),
            interrupting = setOf("KOSPI"),
        )

        try {
            val summary = service(client, FakeStore()).collect(from, to)

            assertThat(client.fetched).hasSize(1)
            assertThat(summary.failed).isEqualTo(1)
            assertThat(summary.saved).isZero()
        } finally {
            Thread.interrupted() // 플래그를 지워 다른 테스트로 새지 않게 한다
        }
    }

    /** 설정이 비면 요청 0건으로 끝난다 — 어드민이 이 축으로 "설정이 빈 실행"을 가른다 */
    @Test
    fun `설정이 비면 요청 0건으로 끝난다`() {
        val summary = FscIndexCollectService(FakeClient(), properties(), FakeStore()).collect(from, to)

        assertThat(summary.requested).isZero()
        assertThat(summary.saved).isZero()
        assertThat(summary.failed).isZero()
    }

    /** from이 to보다 늦으면 조용히 0건이 아니라 즉시 예외다 — 호출자의 파라미터 오류다 */
    @Test
    fun `from이 to보다 늦으면 예외다`() {
        val service = service(FakeClient(), FakeStore())

        org.assertj.core.api.Assertions
            .assertThatThrownBy { service.collect(to, from) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ── 도우미 ────────────────────────────────────────────────────────────────

    private fun service(client: FakeClient, store: FakeStore) =
        FscIndexCollectService(client, properties(item("KOSPI"), item("SPX", idxNm = "S&P 500")), store)

    private fun item(type: String, idxNm: String = "코스피", idxCsf: String = "KOSPI시리즈") =
        BenchmarkIndexProperties.BenchmarkIndexItem().also {
            it.type = type
            it.idxNm = idxNm
            it.idxCsf = idxCsf
        }

    private fun properties(vararg items: BenchmarkIndexProperties.BenchmarkIndexItem) =
        BenchmarkIndexProperties().apply { fsc = items.toList() }

    /**
     * `FscCommoditySourceTest.FakeClient`와 같은 방식 — `@Component`라 all-open이므로
     * 하위 클래스로 응답을 갈아끼운다. 응답을 `type`으로 가르는 이유는 지수마다 다른 결과를
     * 줘야 실패 격리를 볼 수 있기 때문이다. [fetched]는 호출된 (type, 구간)을 모은다 —
     * "인터럽트 뒤 남은 지수를 안 돈다"는 저장 흔적으로는 못 보고 호출 자체를 세야 보인다.
     */
    private class FakeClient(
        private val rows: Map<String, List<Pair<LocalDate, BigDecimal>>> = emptyMap(),
        private val failing: Map<String, RuntimeException> = emptyMap(),
        private val interrupting: Set<String> = emptySet(),
    ) : FscIndexClient(apiKey = "test-key", baseUrl = "http://localhost", objectMapper = ObjectMapper()) {

        val fetched = mutableListOf<Pair<String, Pair<LocalDate, LocalDate>>>()

        override fun fetch(
            item: BenchmarkIndexProperties.BenchmarkIndexItem,
            from: LocalDate,
            to: LocalDate,
        ): List<Pair<LocalDate, BigDecimal>> {
            fetched += item.type to (from to to)
            if (item.type in interrupting) Thread.currentThread().interrupt()
            failing[item.type]?.let { throw it }
            return rows[item.type] ?: emptyList()
        }
    }

    /**
     * 인메모리 저장 포트. [upsertCalls]는 빈 배치에 저장을 걸지 않는지 보는 유일한 축이다 —
     * [upserted]는 빈 목록이 들어와도 빈 목록으로 보여 구분이 안 된다.
     */
    private class FakeStore(private val upsertFailure: RuntimeException? = null) : BenchmarkDailyStore {
        val upserted = linkedMapOf<BenchmarkType, List<Pair<LocalDate, BigDecimal>>>()
        var upsertCalls = 0

        override fun latestDate(type: BenchmarkType): LocalDate? = upserted[type]?.maxOfOrNull { it.first }

        override fun upsert(type: BenchmarkType, rows: List<Pair<LocalDate, BigDecimal>>) {
            upsertCalls++
            upsertFailure?.let { throw it }
            upserted[type] = (upserted[type] ?: emptyList()) + rows
        }

        override fun series(
            type: BenchmarkType,
            from: LocalDate,
            to: LocalDate,
        ): List<Pair<LocalDate, BigDecimal>> =
            upserted[type].orEmpty().filter { it.first in from..to }
    }
}
