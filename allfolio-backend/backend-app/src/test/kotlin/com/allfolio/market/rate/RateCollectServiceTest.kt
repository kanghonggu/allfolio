package com.allfolio.market.rate

import com.allfolio.fx.EcosApiException
import com.allfolio.unifiedasset.infrastructure.entity.MarketRateEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

class RateCollectServiceTest {

    private val from = LocalDate.of(2026, 8, 10)
    private val to = LocalDate.of(2026, 8, 12)
    private val now = LocalDateTime.of(2026, 8, 12, 9, 10)

    @Test
    fun `종목별로 조회해 저장하고 건수를 보고한다`() {
        val repo = FakeRepo()
        val source = FakeSource(
            codes = listOf("KTB_3Y", "KTB_10Y"),
            rows = mapOf(
                "KTB_3Y" to listOf(obs("2026-08-11", "3.10"), obs("2026-08-12", "3.15")),
                "KTB_10Y" to listOf(obs("2026-08-12", "3.40")),
            ),
        )

        val summary = service(source, repo).collect(from, to, now)

        assertThat(summary.requested).isEqualTo(2)
        assertThat(summary.inserted).isEqualTo(3)
        assertThat(summary.updated).isZero()
        assertThat(summary.failed).isZero()
        assertThat(repo.saved).hasSize(3)
    }

    /**
     * 종목 하나가 터져도 나머지를 저장한다. 예외로 끝내면 살아 있던 값까지 같이 잃는다.
     */
    @Test
    fun `한 종목이 실패해도 나머지는 저장한다`() {
        val repo = FakeRepo()
        val source = FakeSource(
            codes = listOf("KTB_3Y", "KTB_10Y"),
            rows = mapOf("KTB_10Y" to listOf(obs("2026-08-12", "3.40"))),
            failing = mapOf("KTB_3Y" to EcosApiException("HTTP-500", "ECOS가 HTTP 500 을 반환했습니다")),
        )

        val summary = service(source, repo).collect(from, to, now)

        assertThat(summary.requested).isEqualTo(2)
        assertThat(summary.collected).isEqualTo(1)
        assertThat(summary.failed).isEqualTo(1)
        assertThat(summary.failures.single()).contains("KTB_3Y").contains("HTTP 500")
        assertThat(repo.saved.single().rateCode).isEqualTo("KTB_10Y")
    }

    /**
     * 같은 구간을 다시 수집하면 행이 늘지 않고 값만 덮인다.
     * 수집 창이 매번 2주를 재조회하므로 이게 깨지면 매일 행이 불어난다.
     *
     * 갱신분이 `saveAll`까지 갔는지도 같이 못 박는다. 인메모리 레포는 엔티티를 그대로 들고 있어서
     * 값만 보면 서비스가 저장을 아예 안 걸어도 통과한다 — 운영에선 detached 엔티티라 조용히 유실된다.
     */
    @Test
    fun `같은 구간을 다시 수집하면 덮어쓴다`() {
        val repo = FakeRepo()
        repo.saved += entity("KTB_3Y", LocalDate.of(2026, 8, 12), "3.10")
        val source = FakeSource(codes = listOf("KTB_3Y"), rows = mapOf("KTB_3Y" to listOf(obs("2026-08-12", "3.15"))))

        val summary = service(source, repo).collect(from, to, now)

        assertThat(summary.inserted).isZero()
        assertThat(summary.updated).isEqualTo(1)
        assertThat(repo.saved).hasSize(1)
        assertThat(repo.saved.single().rateValue).isEqualByComparingTo("3.15")
        assertThat(repo.saved.single().collectedAt).isEqualTo(now)
        assertThat(repo.submitted).containsExactly(repo.saved.single())
    }

    /**
     * 저장이 통째로 터지면 갱신분도 수집 건수에 들어가면 안 된다.
     *
     * 세고 나서 저장하면 이 실행이 "collected=1, failed=1"로 보고된다 — 어드민은 collected가
     * 0일 때만 502를 내므로 아무것도 안 들어간 잡이 초록으로 지나간다. 커넥션이 끊겨 전 종목이
     * 같은 자리에서 터지는 날이 정확히 그 형태다.
     */
    @Test
    fun `저장이 터지면 갱신분도 수집 건수에 넣지 않는다`() {
        val repo = FakeRepo(saveFailure = IllegalStateException("커넥션이 끊겼습니다"))
        repo.saved += entity("KTB_3Y", LocalDate.of(2026, 8, 12), "3.10")
        val source = FakeSource(codes = listOf("KTB_3Y"), rows = mapOf("KTB_3Y" to listOf(obs("2026-08-12", "3.15"))))

        val summary = service(source, repo).collect(from, to, now)

        assertThat(summary.collected).isZero()
        assertThat(summary.updated).isZero()
        assertThat(summary.unchanged).isZero()
        assertThat(summary.inserted).isZero()
        assertThat(summary.failed).isEqualTo(1)
        assertThat(summary.failures.single()).contains("KTB_3Y")
    }

    /**
     * 이미 있는 날짜가 한 응답에 두 번 오면 갱신 건수는 1이다. 마지막 값이 남는 동작은 옳지만
     * 세는 쪽이 루프를 두 번 돌면 한 행을 두 건으로 보고한다 — 요약이 DB와 어긋난다.
     */
    @Test
    fun `기존 행에 같은 날짜가 두 번 와도 갱신은 한 건이다`() {
        val repo = FakeRepo()
        repo.saved += entity("KTB_3Y", LocalDate.of(2026, 8, 12), "3.10")
        val source = FakeSource(
            codes = listOf("KTB_3Y"),
            rows = mapOf("KTB_3Y" to listOf(obs("2026-08-12", "3.15"), obs("2026-08-12", "3.20"))),
        )

        val summary = service(source, repo).collect(from, to, now)

        assertThat(summary.updated).isEqualTo(1)
        assertThat(summary.unchanged).isZero()
        assertThat(summary.collected).isEqualTo(1)
        assertThat(repo.submitted).hasSize(1)
        assertThat(repo.saved.single().rateValue).isEqualByComparingTo("3.20")
    }

    /**
     * 다른 출처로 들어와 있던 행도 ECOS로 되돌린다. FRED가 후속으로 붙으면 같은 지표를
     * 두 소스가 번갈아 채우게 되는데, 출처를 안 덮으면 정정된 값을 설명하려고 들여다볼
     * 바로 그 필드가 거짓말을 한다.
     */
    @Test
    fun `다른 출처로 들어와 있던 행의 출처를 ECOS로 덮는다`() {
        val repo = FakeRepo()
        repo.saved += entity("KTB_3Y", LocalDate.of(2026, 8, 12), "3.10", source = "FRED")
        val source = FakeSource(codes = listOf("KTB_3Y"), rows = mapOf("KTB_3Y" to listOf(obs("2026-08-12", "3.15"))))

        service(source, repo).collect(from, to, now)

        assertThat(repo.submitted.single().source).isEqualTo("ECOS")
    }

    /**
     * 출처는 소스가 말한 이름으로 쓴다 (AF-FRED).
     *
     * 위 `출처를 ECOS로 덮는다`가 이걸 못 지킨다 — 그 테스트의 페이크 소스 이름도 "ECOS"라서
     * 상수를 다시 박아도 초록이다. 여기서는 소스 둘이 서로 다른 이름을 쓰므로, 한쪽 이름으로
     * 굳히는 변이가 반드시 깨진다. 대상 수가 소스별 코드 수의 합이 되는 것도 같이 못 박는다 —
     * `requested`는 어드민이 "설정이 빈 실행"을 가르는 축이다.
     */
    @Test
    fun `소스가 여럿이면 대상이 합쳐지고 출처는 소스 이름으로 남는다`() {
        val repo = FakeRepo()
        val ecos = FakeSource(codes = listOf("KTB_3Y"), rows = mapOf("KTB_3Y" to listOf(obs("2026-08-12", "3.15"))))
        val fred = FakeSource(
            sourceName = "FRED",
            codes = listOf("US_DGS10"),
            rows = mapOf("US_DGS10" to listOf(obs("2026-08-12", "4.25"))),
        )

        val summary = RateCollectService(listOf(ecos, fred), repo).collect(from, to, now)

        assertThat(summary.requested).isEqualTo(2)
        assertThat(summary.inserted).isEqualTo(2)
        assertThat(repo.saved.map { it.rateCode to it.source })
            .containsExactlyInAnyOrder("KTB_3Y" to "ECOS", "US_DGS10" to "FRED")
    }

    /**
     * 값이 그대로면 갱신이 아니라 무변동이다. 2주 창을 매일 재조회하므로 뭉쳐 세면 매 실행이
     * updated≈60이 되고, 그중 하나뿐인 ECOS 정정이 동일값 재기록에 묻힌다 — 창을 둔 이유가 사라진다.
     *
     * 무변동 쪽 값의 스케일을 일부러 어긋나게 둔다. equals로 비교하면 3.10과 3.1000이 갈려서
     * 매 실행 전건이 정정으로 보고된다.
     */
    @Test
    fun `값이 그대로면 갱신이 아니라 무변동으로 센다`() {
        val repo = FakeRepo()
        repo.saved += entity("KTB_3Y", LocalDate.of(2026, 8, 11), "3.10")
        repo.saved += entity("KTB_3Y", LocalDate.of(2026, 8, 12), "3.15")
        val source = FakeSource(
            codes = listOf("KTB_3Y"),
            rows = mapOf("KTB_3Y" to listOf(obs("2026-08-11", "3.1000"), obs("2026-08-12", "3.20"))),
        )

        val summary = service(source, repo).collect(from, to, now)

        assertThat(summary.unchanged).isEqualTo(1)
        assertThat(summary.updated).isEqualTo(1)
        assertThat(summary.collected).isEqualTo(2)
        // 값이 같아도 저장은 한다 — collectedAt("언제 확인한 값인가")이 화면에 나간다
        assertThat(repo.submitted).hasSize(2)
        assertThat(repo.submitted.map { it.collectedAt }).allMatch { it == now }
    }

    /**
     * 실패 사유는 잘라서 싣는다. 제약 위반 메시지는 SQL과 파라미터가 통째로 실린 여러 줄 덤프이고,
     * 이 문자열이 어드민 JSON 응답과 GitHub Actions 주석에 그대로 나간다.
     */
    @Test
    fun `실패 사유가 길면 잘라서 싣는다`() {
        val source = FakeSource(
            codes = listOf("KTB_3Y"),
            failing = mapOf("KTB_3Y" to IllegalStateException("가".repeat(500))),
        )

        val summary = service(source, FakeRepo()).collect(from, to, now)

        assertThat(summary.failures.single()).hasSize("KTB_3Y: ".length + 200 + 1) // 200자 + 말줄임표
    }

    /**
     * 인터럽트가 걸리면 남은 종목을 돌지 않는다. 종료 신호는 예외로 위장해 온다 —
     * EcosApiClient가 플래그를 되살리고 EcosApiException으로 바꿔 던지므로 실패 catch가 삼킨다.
     * 플래그를 안 보면 셧다운 중에 남은 종목을 끝까지 호출하며 가짜 실패만 쌓는다.
     */
    @Test
    fun `인터럽트가 걸리면 남은 종목을 돌지 않는다`() {
        val source = FakeSource(
            codes = listOf("KTB_3Y", "KTB_10Y"),
            rows = mapOf("KTB_10Y" to listOf(obs("2026-08-12", "3.40"))),
            failing = mapOf("KTB_3Y" to EcosApiException("IO", "ECOS 호출에 실패했습니다")),
            interrupting = setOf("KTB_3Y"),
        )

        try {
            val summary = service(source, FakeRepo()).collect(from, to, now)

            assertThat(source.fetched).hasSize(1)
            assertThat(summary.failed).isEqualTo(1)
            assertThat(summary.collected).isZero()
        } finally {
            Thread.interrupted() // 플래그를 지워 다른 테스트로 새지 않게 한다
        }
    }

    /**
     * 손대지 않은 기존 행까지 다시 저장하지 않는다. merge는 행마다 SELECT를 하나씩 내므로
     * 2주 창 x 6종목이면 헛도는 왕복이 그대로 Neon 커넥션 점유가 된다.
     */
    @Test
    fun `값이 안 온 날의 기존 행은 다시 저장하지 않는다`() {
        val repo = FakeRepo()
        repo.saved += entity("KTB_3Y", LocalDate.of(2026, 8, 11), "3.05")
        val source = FakeSource(codes = listOf("KTB_3Y"), rows = mapOf("KTB_3Y" to listOf(obs("2026-08-12", "3.15"))))

        service(source, repo).collect(from, to, now)

        assertThat(repo.submitted).extracting<LocalDate> { it.quoteDate }
            .containsExactly(LocalDate.of(2026, 8, 12))
    }

    @Test
    fun `버려진 행 수를 보고한다`() {
        val repo = FakeRepo()
        val source = FakeSource(
            codes = listOf("KTB_3Y"),
            rows = mapOf("KTB_3Y" to listOf(obs("2026-08-12", "3.15"))),
            skipped = mapOf("KTB_3Y" to 2),
        )

        val summary = service(source, repo).collect(from, to, now)

        assertThat(summary.skippedRows).isEqualTo(2)
    }

    /**
     * 소스가 구간 밖 날짜를 섞어 주면 걷어낸다. 안 걷어내면 그 행이 새 UUID로 INSERT되어
     * 유니크 제약이 배치 전체를 죽인다 — 재실행해도 똑같이 죽는다.
     */
    @Test
    fun `요청 구간 밖 날짜는 걷어내고 센다`() {
        val repo = FakeRepo()
        val source = FakeSource(
            codes = listOf("KTB_3Y"),
            rows = mapOf(
                "KTB_3Y" to listOf(
                    obs("2026-08-11", "3.10"),
                    obs("2026-08-20", "3.30"), // to(8/12) 이후
                    obs("2026-08-01", "3.05"), // from(8/10) 이전
                ),
            ),
        )

        val summary = service(source, repo).collect(from, to, now)

        assertThat(summary.outOfRange).isEqualTo(2)
        assertThat(summary.inserted).isEqualTo(1)
        assertThat(repo.saved.single().quoteDate).isEqualTo(LocalDate.of(2026, 8, 11))
    }

    /**
     * 같은 날짜가 두 번 오면 한 행으로 접는다. 그대로 저장하면 uk_market_rate가 배치를 죽인다.
     * 뒤에 온 값을 남기는 건 ECOS 정정본이 뒤에 오는 형태이기 때문이다.
     */
    @Test
    fun `같은 날짜가 중복으로 오면 마지막 값만 남긴다`() {
        val repo = FakeRepo()
        val source = FakeSource(
            codes = listOf("KTB_3Y"),
            rows = mapOf("KTB_3Y" to listOf(obs("2026-08-12", "3.10"), obs("2026-08-12", "3.15"))),
        )

        val summary = service(source, repo).collect(from, to, now)

        assertThat(summary.inserted).isEqualTo(1)
        assertThat(repo.saved.single().rateValue).isEqualByComparingTo("3.15")
    }

    /**
     * 0건은 실패가 아니다 — 기준금리처럼 변경 시에만 공표되는 계열은 2주 창이 빌 수 있다.
     * 다만 코드가 죽어도 똑같이 0건이라, 이름을 남겨 사람이 보게 한다.
     */
    @Test
    fun `0건으로 돌아온 종목은 실패가 아니라 이름으로 남는다`() {
        val repo = FakeRepo()
        val source = FakeSource(
            codes = listOf("BASE_RATE", "KTB_10Y"),
            rows = mapOf("BASE_RATE" to emptyList(), "KTB_10Y" to listOf(obs("2026-08-12", "3.40"))),
        )

        val summary = service(source, repo).collect(from, to, now)

        assertThat(summary.emptySeries).containsExactly("BASE_RATE")
        assertThat(summary.failed).isZero()
        assertThat(summary.collected).isEqualTo(1)
        // 빈 종목에는 saveAll을 걸지 않는다 — 빈 배치도 리포지토리 레벨 트랜잭션을 연다
        assertThat(repo.saveCalls).isEqualTo(1)
    }

    @Test
    fun `대상이 없으면 요청 0건으로 끝난다`() {
        val summary = service(FakeSource(codes = emptyList()), FakeRepo()).collect(from, to, now)

        assertThat(summary.requested).isZero()
        assertThat(summary.collected).isZero()
    }

    private fun service(source: RateSource, repo: FakeRepo) = RateCollectService(listOf(source), repo)

    private fun obs(date: String, value: String) = RateObservation(LocalDate.parse(date), BigDecimal(value))

    private fun entity(code: String, date: LocalDate, value: String, source: String = "ECOS") = MarketRateEntity(
        id = java.util.UUID.randomUUID(),
        rateCode = code,
        quoteDate = date,
        rateValue = BigDecimal(value),
        source = source,
        collectedAt = LocalDateTime.of(2026, 8, 11, 18, 10),
    )

    /**
     * 코드로 응답을 가른다 — 종목마다 다른 결과를 주려면 그 축이 필요하다.
     *
     * [interrupting]은 실 소스가 종료 시 하는 짓을 흉내 낸다: 인터럽트 플래그를 되살린 채
     * 예외로 바꿔 던진다 (`EcosStatisticSearchClient`가 그렇게 한다).
     *
     * [fetched]는 호출된 (코드, from, to)를 순서대로 모은다 — "인터럽트 뒤 남은 종목을 안 돈다"는
     * 저장 흔적으로는 볼 수 없고 호출 자체를 세야 보인다.
     */
    private class FakeSource(
        override val sourceName: String = "ECOS",
        override val codes: List<String>,
        private val rows: Map<String, List<RateObservation>> = emptyMap(),
        private val failing: Map<String, RuntimeException> = emptyMap(),
        private val skipped: Map<String, Int> = emptyMap(),
        private val interrupting: Set<String> = emptySet(),
    ) : RateSource {
        val fetched = mutableListOf<Triple<String, LocalDate, LocalDate>>()

        override fun fetch(code: String, from: LocalDate, to: LocalDate): RateFetch {
            fetched += Triple(code, from, to)
            if (code in interrupting) Thread.currentThread().interrupt()
            failing[code]?.let { throw it }
            return RateFetch(rows[code] ?: emptyList(), skipped[code] ?: 0)
        }
    }

    /**
     * 인메모리 레포. JPA 레포 인터페이스 전체를 구현하지 않으려고 서비스가 쓰는 두 메서드만
     * 가진 좁은 포트를 둔다 — 그 포트가 RateCollectService.Store 다.
     *
     * [submitted]는 `saveAll`에 실제로 건네진 것만 모은다. [saved]는 갱신을 제자리에서 받으므로
     * 저장을 안 걸어도 값이 맞아 보이기 때문이다 — 운영에선 detached 엔티티라 그러면 유실된다.
     *
     * [saveCalls]는 호출 횟수를 센다 — 빈 배치는 [submitted]에 아무 흔적도 안 남기므로
     * "빈 종목에 saveAll을 걸지 않는다"는 이 축으로만 볼 수 있다.
     * [saveFailure]는 Neon 커넥션이 끊긴 상황을 흉내 낸다.
     */
    private class FakeRepo(private val saveFailure: RuntimeException? = null) : RateCollectService.Store {
        val saved = mutableListOf<MarketRateEntity>()
        val submitted = mutableListOf<MarketRateEntity>()
        var saveCalls = 0

        override fun findRange(rateCode: String, from: LocalDate, to: LocalDate): List<MarketRateEntity> =
            saved.filter { it.rateCode == rateCode && it.quoteDate >= from && it.quoteDate <= to }

        override fun saveAll(entities: List<MarketRateEntity>) {
            saveCalls++
            saveFailure?.let { throw it }
            submitted += entities
            entities.forEach { entity ->
                if (saved.none { it.rateCode == entity.rateCode && it.quoteDate == entity.quoteDate }) {
                    saved += entity
                }
            }
        }
    }
}
