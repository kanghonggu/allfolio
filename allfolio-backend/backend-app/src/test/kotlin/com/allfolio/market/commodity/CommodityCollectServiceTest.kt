package com.allfolio.market.commodity

import com.allfolio.market.rate.fred.FredApiException
import com.allfolio.unifiedasset.infrastructure.entity.MarketCommodityQuoteEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * `RateCollectServiceTest`를 템플릿으로 삼았다 — 방어가 같으므로 회귀 테스트도 같아야 한다.
 * 원자재에만 있는 것은 전일대비 계산(직전 값이 없으면 null)과 설정에서 오는 단위·주기다.
 */
class CommodityCollectServiceTest {

    private val from = LocalDate.of(2026, 8, 10)
    private val to = LocalDate.of(2026, 8, 12)
    private val now = LocalDateTime.of(2026, 8, 12, 9, 10)

    @Test
    fun `종목별로 조회해 저장하고 건수를 보고한다`() {
        val repo = FakeRepo()
        val source = FakeSource(
            codes = listOf("WTI", "BRENT"),
            rows = mapOf(
                "WTI" to listOf(obs("2026-08-11", "70.00"), obs("2026-08-12", "71.00")),
                "BRENT" to listOf(obs("2026-08-12", "74.00")),
            ),
        )

        val summary = service(source, repo).collect(from, to, now)

        assertThat(summary.requested).isEqualTo(2)
        assertThat(summary.inserted).isEqualTo(3)
        assertThat(summary.collected).isEqualTo(3)
        assertThat(summary.updated).isZero()
        assertThat(summary.failed).isZero()
        assertThat(repo.saved).hasSize(3)
        assertThat(repo.saved.map { it.source }).allMatch { it == "FRED" }
        assertThat(repo.saved.map { it.collectedAt }).allMatch { it == now }
    }

    /**
     * 종목 하나가 터져도 나머지를 저장한다. 예외로 끝내면 살아 있던 값까지 같이 잃는다.
     * 실패한 종목의 이름이 요약에 남아야 어느 시리즈가 빠졌는지 한 번에 보인다.
     */
    @Test
    fun `한 종목이 실패해도 나머지는 저장한다`() {
        val repo = FakeRepo()
        val source = FakeSource(
            codes = listOf("WTI", "BRENT"),
            rows = mapOf("BRENT" to listOf(obs("2026-08-12", "74.00"))),
            failing = mapOf("WTI" to FredApiException("HTTP-500", "FRED가 HTTP 500 를 반환했습니다")),
        )

        val summary = service(source, repo).collect(from, to, now)

        assertThat(summary.requested).isEqualTo(2)
        assertThat(summary.collected).isEqualTo(1)
        assertThat(summary.failed).isEqualTo(1)
        assertThat(summary.failures.single()).contains("WTI").contains("HTTP 500")
        assertThat(repo.saved.single().code).isEqualTo("BRENT")
    }

    /**
     * 저장이 통째로 터지면 갱신분도 수집 건수에 들어가면 안 된다.
     *
     * 세고 나서 저장하면 이 실행이 "collected=1, failed=1"로 보고된다 — 어드민은 collected가
     * 0일 때만 502를 내므로 아무것도 안 들어간 잡이 초록으로 지나간다. 배포 직후 테이블이 없어
     * 전 종목이 같은 자리에서 터지는 날이 정확히 그 형태다.
     */
    @Test
    fun `저장이 터지면 갱신분도 수집 건수에 넣지 않는다`() {
        val repo = FakeRepo(saveFailure = IllegalStateException("relation market_commodity_quote does not exist"))
        repo.saved += entity("WTI", LocalDate.of(2026, 8, 12), "70.00")
        val source = FakeSource(codes = listOf("WTI"), rows = mapOf("WTI" to listOf(obs("2026-08-12", "71.00"))))

        val summary = service(source, repo).collect(from, to, now)

        assertThat(summary.collected).isZero()
        assertThat(summary.updated).isZero()
        assertThat(summary.unchanged).isZero()
        assertThat(summary.inserted).isZero()
        assertThat(summary.failed).isEqualTo(1)
        assertThat(summary.failures.single()).contains("WTI")
    }

    /**
     * **직전 값이 없으면 `null`이다 — `0`이 아니다.**
     *
     * `0`은 "안 움직였다"는 뜻이고 여기서 필요한 뜻은 "직전 값이 없다"다. 둘을 섞으면 화면이
     * 첫 관측을 "보합"으로 그린다 — AF-104가 이 구분을 놓쳐 사고를 냈다.
     */
    @Test
    fun `직전 값이 없으면 전일대비는 null이다`() {
        val repo = FakeRepo()
        val source = FakeSource(codes = listOf("WTI"), rows = mapOf("WTI" to listOf(obs("2026-08-12", "71.00"))))

        service(source, repo).collect(from, to, now)

        val row = repo.saved.single()
        assertThat(row.prevClose).isNull()
        assertThat(row.changeValue).isNull()
        assertThat(row.changeRate).isNull()
    }

    /**
     * 위 테스트의 짝. **변동이 없으면 `0`이다 — `null`이 아니다.**
     * 이 둘이 함께 있어야 "없으면 null"과 "0이면 0"이 동시에 못 박힌다.
     */
    @Test
    fun `변동이 0이면 전일대비는 null이 아니라 0이다`() {
        val repo = FakeRepo()
        val source = FakeSource(
            codes = listOf("WTI"),
            rows = mapOf("WTI" to listOf(obs("2026-08-11", "70.00"), obs("2026-08-12", "70.00"))),
        )

        service(source, repo).collect(from, to, now)

        val row = repo.saved.single { it.tradeDate == LocalDate.of(2026, 8, 12) }
        assertThat(row.prevClose).isEqualByComparingTo("70.00")
        assertThat(row.changeValue).isNotNull.isEqualByComparingTo("0")
        assertThat(row.changeRate).isNotNull.isEqualByComparingTo("0")
    }

    /** 값이 오르면 변동과 변동률이 함께 채워진다. 부호와 자릿수를 여기서 못 박는다 */
    @Test
    fun `직전 값이 있으면 변동과 변동률을 채운다`() {
        val repo = FakeRepo()
        val source = FakeSource(
            codes = listOf("WTI"),
            rows = mapOf("WTI" to listOf(obs("2026-08-11", "70.00"), obs("2026-08-12", "71.40"))),
        )

        service(source, repo).collect(from, to, now)

        val row = repo.saved.single { it.tradeDate == LocalDate.of(2026, 8, 12) }
        assertThat(row.prevClose).isEqualByComparingTo("70.00")
        assertThat(row.changeValue).isEqualByComparingTo("1.40")
        assertThat(row.changeRate).isEqualByComparingTo("2.0000")
    }

    /**
     * **월간 계열의 "직전"은 한 달 전 관측이다.**
     *
     * 날짜 산술로 "어제"를 찾으면 월간은 영원히 null이 된다. 여기서는 직전 달 행이 수집 창
     * **바깥**에 있다 — 창 안만 뒤지는 구현도 같이 걸린다. 기준은 `code`가 같은 행 중
     * `trade_date < 현재`의 가장 최근이다.
     */
    @Test
    fun `월간 계열의 직전 값은 한 달 전 행에서 찾는다`() {
        val repo = FakeRepo()
        repo.saved += entity("COPPER", LocalDate.of(2026, 7, 1), "9000.0000", unit = "USD/MT", frequency = "M")
        val source = FakeSource(codes = listOf("COPPER"), rows = mapOf("COPPER" to listOf(obs("2026-08-01", "9450.0000"))))

        service(source, repo).collect(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), now)

        val row = repo.saved.single { it.tradeDate == LocalDate.of(2026, 8, 1) }
        assertThat(row.prevClose).isEqualByComparingTo("9000")
        assertThat(row.changeValue).isEqualByComparingTo("450")
        assertThat(row.changeRate).isEqualByComparingTo("5.0000")
    }

    /**
     * 연휴가 끼어 나흘 전이 직전 거래일인 경우. 위 월간 테스트와 같은 규칙("가장 최근 이전 행")이
     * 일간에서도 그대로 필요하다 — 하루를 빼서 찾는 구현은 연휴마다 null을 낸다.
     */
    @Test
    fun `일간 계열도 직전 관측이 며칠 전이면 그 행을 쓴다`() {
        val repo = FakeRepo()
        repo.saved += entity("WTI", LocalDate.of(2026, 8, 7), "68.00")
        val source = FakeSource(codes = listOf("WTI"), rows = mapOf("WTI" to listOf(obs("2026-08-12", "68.68"))))

        service(source, repo).collect(from, to, now)

        val row = repo.saved.single { it.tradeDate == LocalDate.of(2026, 8, 12) }
        assertThat(row.prevClose).isEqualByComparingTo("68.00")
        assertThat(row.changeRate).isEqualByComparingTo("1.0000")
    }

    /**
     * **단위와 주기는 설정에서 와서 행에 남는다.** 소스는 그것을 응답에 싣지 않는다.
     * 상수로 박아 두면 설정을 고친 날 저장은 멀쩡한데 화면만 조용히 틀린다 —
     * `USc/lb`와 `USD/lb`는 한 글자 차이에 100배 차이다.
     *
     * 두 종목의 단위·주기를 서로 다르게 둔다. 한 종목만 보면 어느 한쪽으로 굳히는 구현도 통과한다.
     */
    @Test
    fun `단위와 주기는 설정 값 그대로 저장한다`() {
        val repo = FakeRepo()
        val properties = properties(
            item("WTI", unit = "USD/bbl", frequency = "D"),
            item("SUGAR", unit = "USc/lb", frequency = "M"),
        )
        val source = FakeSource(
            codes = listOf("WTI", "SUGAR"),
            rows = mapOf(
                "WTI" to listOf(obs("2026-08-12", "71.00")),
                "SUGAR" to listOf(obs("2026-08-12", "0.1800")),
            ),
        )

        CommodityCollectService(listOf(source), properties, repo).collect(from, to, now)

        assertThat(repo.saved.map { Triple(it.code, it.unit, it.frequency) })
            .containsExactlyInAnyOrder(
                Triple("WTI", "USD/bbl", "D"),
                Triple("SUGAR", "USc/lb", "M"),
            )
    }

    /**
     * 설정에 없는 코드를 소스가 주면 저장하지 않고 실패로 남긴다. 단위를 빈 문자열로 채우면
     * 화면이 단위 없는 숫자를 그럴듯하게 보여주고, 그건 코드가 못 잡는 종류의 오류다.
     */
    @Test
    fun `설정에 없는 코드는 저장하지 않고 실패로 남긴다`() {
        val repo = FakeRepo()
        val source = FakeSource(codes = listOf("PLATINUM"), rows = mapOf("PLATINUM" to listOf(obs("2026-08-12", "1000"))))

        val summary = CommodityCollectService(listOf(source), properties(item("WTI")), repo).collect(from, to, now)

        assertThat(summary.failed).isEqualTo(1)
        assertThat(summary.failures.single()).contains("PLATINUM").contains("market-commodity")
        assertThat(repo.saved).isEmpty()
    }

    /**
     * 소스가 구간 밖 날짜를 섞어 주면 걷어낸다. 안 걷어내면 그 행이 새 UUID로 INSERT되어
     * uk_market_commodity_quote가 배치 전체를 죽인다 — 재실행해도 똑같이 죽는다.
     */
    @Test
    fun `요청 구간 밖 날짜는 걷어내고 센다`() {
        val repo = FakeRepo()
        val source = FakeSource(
            codes = listOf("WTI"),
            rows = mapOf(
                "WTI" to listOf(
                    obs("2026-08-11", "70.00"),
                    obs("2026-08-20", "72.00"), // to(8/12) 이후
                    obs("2026-08-01", "69.00"), // from(8/10) 이전
                ),
            ),
        )

        val summary = service(source, repo).collect(from, to, now)

        assertThat(summary.outOfRange).isEqualTo(2)
        assertThat(summary.inserted).isEqualTo(1)
        assertThat(repo.saved.single().tradeDate).isEqualTo(LocalDate.of(2026, 8, 11))
    }

    /**
     * 같은 날짜가 두 번 오면 한 행으로 접는다. 그대로 저장하면 유니크 제약이 배치를 죽인다.
     * 뒤에 온 값을 남기는 건 정정본이 뒤에 오는 형태이기 때문이다.
     */
    @Test
    fun `같은 날짜가 중복으로 오면 마지막 값만 남긴다`() {
        val repo = FakeRepo()
        val source = FakeSource(
            codes = listOf("WTI"),
            rows = mapOf("WTI" to listOf(obs("2026-08-12", "70.00"), obs("2026-08-12", "71.00"))),
        )

        val summary = service(source, repo).collect(from, to, now)

        assertThat(summary.inserted).isEqualTo(1)
        assertThat(repo.saved.single().price).isEqualByComparingTo("71.00")
    }

    /**
     * 같은 구간을 다시 수집하면 행이 늘지 않고 값만 덮인다. 갱신분이 `saveAll`까지 갔는지도
     * 같이 못 박는다 — 인메모리 레포는 엔티티를 그대로 들고 있어서 값만 보면 서비스가 저장을
     * 아예 안 걸어도 통과한다. 운영에선 detached 엔티티라 조용히 유실된다.
     */
    @Test
    fun `같은 구간을 다시 수집하면 덮어쓴다`() {
        val repo = FakeRepo()
        repo.saved += entity("WTI", LocalDate.of(2026, 8, 12), "70.00", source = "OLD")
        val source = FakeSource(codes = listOf("WTI"), rows = mapOf("WTI" to listOf(obs("2026-08-12", "71.00"))))

        val summary = service(source, repo).collect(from, to, now)

        assertThat(summary.inserted).isZero()
        assertThat(summary.updated).isEqualTo(1)
        assertThat(repo.saved).hasSize(1)
        assertThat(repo.submitted).containsExactly(repo.saved.single())
        assertThat(repo.saved.single().price).isEqualByComparingTo("71.00")
        assertThat(repo.saved.single().source).isEqualTo("FRED")
        assertThat(repo.saved.single().collectedAt).isEqualTo(now)
    }

    /**
     * 값이 그대로면 갱신이 아니라 무변동이다. 창이 90일이라 매 실행이 수백 건을 다시 쓰는데,
     * 뭉쳐 세면 그중 하나뿐인 정정이 동일값 재기록에 묻힌다.
     *
     * 무변동 쪽 값의 스케일을 일부러 어긋나게 둔다. equals로 비교하면 70.00과 70.0000이 갈려서
     * 매 실행 전건이 정정으로 보고된다.
     */
    @Test
    fun `값이 그대로면 갱신이 아니라 무변동으로 센다`() {
        val repo = FakeRepo()
        repo.saved += entity("WTI", LocalDate.of(2026, 8, 11), "70.00")
        repo.saved += entity("WTI", LocalDate.of(2026, 8, 12), "71.00")
        val source = FakeSource(
            codes = listOf("WTI"),
            rows = mapOf("WTI" to listOf(obs("2026-08-11", "70.0000"), obs("2026-08-12", "72.00"))),
        )

        val summary = service(source, repo).collect(from, to, now)

        assertThat(summary.unchanged).isEqualTo(1)
        assertThat(summary.updated).isEqualTo(1)
        assertThat(summary.collected).isEqualTo(2)
        // 값이 같아도 저장은 한다 — collectedAt("언제 확인한 값인가")이 화면에 나간다
        assertThat(repo.submitted).hasSize(2)
    }

    /**
     * 같은 실행 안에서 앞 날짜가 정정되면 뒤 날짜의 전일대비도 정정된 값을 기준으로 잡아야 한다.
     * 기존 행(DB)만 보면 덮이기 전 값으로 계산해, 저장된 price와 change_value가 서로 안 맞는다.
     */
    @Test
    fun `같은 실행에서 정정된 직전 값을 기준으로 전일대비를 계산한다`() {
        val repo = FakeRepo()
        repo.saved += entity("WTI", LocalDate.of(2026, 8, 11), "60.00") // 정정 전 값
        val source = FakeSource(
            codes = listOf("WTI"),
            rows = mapOf("WTI" to listOf(obs("2026-08-11", "70.00"), obs("2026-08-12", "71.00"))),
        )

        service(source, repo).collect(from, to, now)

        val row = repo.saved.single { it.tradeDate == LocalDate.of(2026, 8, 12) }
        assertThat(row.prevClose).isEqualByComparingTo("70.00")
        assertThat(row.changeValue).isEqualByComparingTo("1.00")
    }

    /**
     * 소스가 일부 날짜만 준 실행에서도 창 안의 기존 행이 직전 값이 된다.
     * 그 행은 이번에 안 왔으므로 다시 저장하지 않는다(merge 왕복이 그대로 커넥션 점유다).
     */
    @Test
    fun `값이 안 온 날의 기존 행은 직전 값으로만 쓰고 다시 저장하지 않는다`() {
        val repo = FakeRepo()
        repo.saved += entity("WTI", LocalDate.of(2026, 8, 11), "70.00")
        val source = FakeSource(codes = listOf("WTI"), rows = mapOf("WTI" to listOf(obs("2026-08-12", "71.00"))))

        service(source, repo).collect(from, to, now)

        assertThat(repo.submitted).extracting<LocalDate> { it.tradeDate }
            .containsExactly(LocalDate.of(2026, 8, 12))
        assertThat(repo.saved.single { it.tradeDate == LocalDate.of(2026, 8, 12) }.prevClose)
            .isEqualByComparingTo("70.00")
    }

    /** 직전 값이 0이면 변화율을 계산할 수 없다. 그때도 0이 아니라 null이다 — 뜻이 "모른다"다 */
    @Test
    fun `직전 값이 0이면 변동은 채우고 변동률은 null이다`() {
        val repo = FakeRepo()
        repo.saved += entity("NATGAS", LocalDate.of(2026, 8, 11), "0.0000")
        val source = FakeSource(codes = listOf("NATGAS"), rows = mapOf("NATGAS" to listOf(obs("2026-08-12", "2.50"))))

        CommodityCollectService(listOf(source), properties(item("NATGAS")), repo).collect(from, to, now)

        val row = repo.saved.single { it.tradeDate == LocalDate.of(2026, 8, 12) }
        assertThat(row.changeValue).isEqualByComparingTo("2.50")
        assertThat(row.changeRate).isNull()
    }

    @Test
    fun `버려진 행 수를 보고한다`() {
        val repo = FakeRepo()
        val source = FakeSource(
            codes = listOf("WTI"),
            rows = mapOf("WTI" to listOf(obs("2026-08-12", "71.00"))),
            skipped = mapOf("WTI" to 2),
        )

        assertThat(service(source, repo).collect(from, to, now).skippedRows).isEqualTo(2)
    }

    /**
     * 0건은 실패가 아니라 이름으로 남는다. 다만 시리즈 ID가 죽어도 똑같이 0건이라
     * 자동으로는 못 가른다 — 사람이 보게 한다.
     */
    @Test
    fun `0건으로 돌아온 종목은 실패가 아니라 이름으로 남는다`() {
        val repo = FakeRepo()
        val source = FakeSource(
            codes = listOf("WTI", "BRENT"),
            rows = mapOf("WTI" to emptyList(), "BRENT" to listOf(obs("2026-08-12", "74.00"))),
        )

        val summary = service(source, repo).collect(from, to, now)

        assertThat(summary.emptySeries).containsExactly("WTI")
        assertThat(summary.failed).isZero()
        assertThat(summary.collected).isEqualTo(1)
        // 빈 종목에는 saveAll을 걸지 않는다 — 빈 배치도 리포지토리 레벨 트랜잭션을 연다
        assertThat(repo.saveCalls).isEqualTo(1)
    }

    /**
     * 출처는 소스가 말한 이름으로 쓴다. 소스 둘이 서로 다른 이름을 쓰므로 한쪽으로 굳히는
     * 변이가 반드시 깨진다. 대상 수가 소스별 코드 수의 합이 되는 것도 같이 못 박는다 —
     * `requested`는 어드민이 "설정이 빈 실행"을 가르는 축이다. (FSC가 붙는 날의 모양이다.)
     */
    @Test
    fun `소스가 여럿이면 대상이 합쳐지고 출처는 소스 이름으로 남는다`() {
        val repo = FakeRepo()
        val fred = FakeSource(codes = listOf("WTI"), rows = mapOf("WTI" to listOf(obs("2026-08-12", "71.00"))))
        val fsc = FakeSource(
            sourceName = "FSC",
            codes = listOf("GOLD"),
            rows = mapOf("GOLD" to listOf(obs("2026-08-12", "150000"))),
        )
        val properties = properties(item("WTI"), item("GOLD", unit = "KRW/g"))

        val summary = CommodityCollectService(listOf(fred, fsc), properties, repo).collect(from, to, now)

        assertThat(summary.requested).isEqualTo(2)
        assertThat(summary.inserted).isEqualTo(2)
        assertThat(repo.saved.map { it.code to it.source })
            .containsExactlyInAnyOrder("WTI" to "FRED", "GOLD" to "FSC")
    }

    /**
     * 실패 사유는 잘라서 싣는다. 제약 위반 메시지는 SQL과 파라미터가 통째로 실린 여러 줄 덤프이고,
     * 이 문자열이 어드민 JSON 응답과 GitHub Actions 주석에 그대로 나간다.
     */
    @Test
    fun `실패 사유가 길면 잘라서 싣는다`() {
        val source = FakeSource(
            codes = listOf("WTI"),
            failing = mapOf("WTI" to IllegalStateException("가".repeat(500))),
        )

        val summary = service(source, FakeRepo()).collect(from, to, now)

        assertThat(summary.failures.single()).hasSize("WTI: ".length + 200 + 1) // 200자 + 말줄임표
    }

    /**
     * 인터럽트가 걸리면 남은 종목을 돌지 않는다. 종료 신호는 예외로 위장해 온다 —
     * FredApiClient가 플래그를 되살리고 FredApiException으로 바꿔 던지므로 실패 catch가 삼킨다.
     * 플래그를 안 보면 셧다운 중에 남은 종목을 끝까지 호출하며 가짜 실패만 쌓는다.
     */
    @Test
    fun `인터럽트가 걸리면 남은 종목을 돌지 않는다`() {
        val source = FakeSource(
            codes = listOf("WTI", "BRENT"),
            rows = mapOf("BRENT" to listOf(obs("2026-08-12", "74.00"))),
            failing = mapOf("WTI" to FredApiException("IO", "FRED 호출에 실패했습니다")),
            interrupting = setOf("WTI"),
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

    @Test
    fun `대상이 없으면 요청 0건으로 끝난다`() {
        val summary = service(FakeSource(codes = emptyList()), FakeRepo()).collect(from, to, now)

        assertThat(summary.requested).isZero()
        assertThat(summary.collected).isZero()
    }

    // ── 도우미 ────────────────────────────────────────────────────────────────

    /** 기본 설정: 테스트에 나오는 코드를 전부 일간 USD/bbl로 둔다. 단위·주기가 쟁점인 테스트는 직접 만든다 */
    private fun service(source: CommoditySource, repo: FakeRepo) = CommodityCollectService(
        listOf(source),
        properties(item("WTI"), item("BRENT"), item("NATGAS"), item("COPPER", frequency = "M")),
        repo,
    )

    private fun item(code: String, unit: String = "USD/bbl", frequency: String = "D") =
        CommodityProperties.CommodityItem().apply {
            this.code = code
            this.seriesId = "SERIES_$code"
            this.unit = unit
            this.frequency = frequency
        }

    private fun properties(vararg items: CommodityProperties.CommodityItem) = CommodityProperties().apply {
        fredDaily = items.toList()
    }

    private fun obs(date: String, value: String) = CommodityObservation(LocalDate.parse(date), BigDecimal(value))

    private fun entity(
        code: String,
        date: LocalDate,
        price: String,
        source: String = "FRED",
        unit: String = "USD/bbl",
        frequency: String = "D",
    ) = MarketCommodityQuoteEntity(
        id = UUID.randomUUID(),
        code = code,
        tradeDate = date,
        price = BigDecimal(price),
        unit = unit,
        frequency = frequency,
        prevClose = null,
        changeValue = null,
        changeRate = null,
        source = source,
        collectedAt = LocalDateTime.of(2026, 8, 11, 18, 10),
    )

    /**
     * 코드로 응답을 가른다 — 종목마다 다른 결과를 주려면 그 축이 필요하다.
     *
     * [interrupting]은 실 소스가 종료 시 하는 짓을 흉내 낸다: 인터럽트 플래그를 되살린 채
     * 예외로 바꿔 던진다. [fetched]는 호출된 (코드, from, to)를 모은다 —
     * "인터럽트 뒤 남은 종목을 안 돈다"는 저장 흔적으로는 볼 수 없고 호출 자체를 세야 보인다.
     */
    private class FakeSource(
        override val sourceName: String = "FRED",
        override val codes: List<String>,
        private val rows: Map<String, List<CommodityObservation>> = emptyMap(),
        private val failing: Map<String, RuntimeException> = emptyMap(),
        private val skipped: Map<String, Int> = emptyMap(),
        private val interrupting: Set<String> = emptySet(),
    ) : CommoditySource {
        val fetched = mutableListOf<Triple<String, LocalDate, LocalDate>>()

        override fun fetch(code: String, from: LocalDate, to: LocalDate): CommodityFetch {
            fetched += Triple(code, from, to)
            if (code in interrupting) Thread.currentThread().interrupt()
            failing[code]?.let { throw it }
            return CommodityFetch(rows[code] ?: emptyList(), skipped[code] ?: 0)
        }
    }

    /**
     * 인메모리 레포. [submitted]는 `saveAll`에 실제로 건네진 것만 모은다 — [saved]는 갱신을
     * 제자리에서 받으므로 저장을 안 걸어도 값이 맞아 보이기 때문이다(운영에선 detached라 유실된다).
     * [saveCalls]는 빈 배치에 saveAll을 걸지 않는지 보는 유일한 축이다.
     */
    private class FakeRepo(private val saveFailure: RuntimeException? = null) : CommodityCollectService.Store {
        val saved = mutableListOf<MarketCommodityQuoteEntity>()
        val submitted = mutableListOf<MarketCommodityQuoteEntity>()
        var saveCalls = 0

        override fun findRange(code: String, from: LocalDate, to: LocalDate): List<MarketCommodityQuoteEntity> =
            saved.filter { it.code == code && it.tradeDate >= from && it.tradeDate <= to }

        override fun findLatestBefore(code: String, before: LocalDate): MarketCommodityQuoteEntity? =
            saved.filter { it.code == code && it.tradeDate < before }.maxByOrNull { it.tradeDate }

        override fun saveAll(entities: List<MarketCommodityQuoteEntity>) {
            saveCalls++
            saveFailure?.let { throw it }
            submitted += entities
            entities.forEach { entity ->
                if (saved.none { it.code == entity.code && it.tradeDate == entity.tradeDate }) {
                    saved += entity
                }
            }
        }
    }
}
