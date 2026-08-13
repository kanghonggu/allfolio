package com.allfolio.market.index

import com.allfolio.unifiedasset.infrastructure.entity.MarketIndexQuoteEntity
import com.allfolio.unifiedasset.infrastructure.jpa.MarketIndexQuoteJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 해외 지수 수집 한 번의 결과 (AF-110).
 *
 * 지수 하나가 터져도 나머지는 저장하므로 "5건 요청, 4건 수집, 1건 실패"가 될 수 있다.
 * 예외로 끝내면 살아 있던 네 건까지 같이 잃는다 — 국내(3종)보다 잃는 양이 크다.
 *
 * @param schedule 이번에 돈 슬롯(US | ASIA). 요약만 보고 어느 cron의 결과인지 알아야 한다
 * @param requested 이 슬롯에 속한 지수 수. 설정 전체가 아니다
 * @param collected 저장까지 끝난 수 (inserted + updated)
 * @param failures "HANGSENG: <사유>" 형태. 운영자가 어느 지수가 왜 빠졌는지 한 번에 봐야 한다
 * @param names **KIS가 돌려준 `hts_kor_isnm`을 code별로 실어 보낸다.** 값은 `"항셍지수(HK#HS)"`
 *        처럼 **iscd를 괄호로 붙인다** — 확인해야 할 것이 "이 코드가 무엇을 내놓는가"라서
 *        이름만으로는 판단이 안 된다. `NASDAQ`·`NASDAQ100`은 `nameContains`가 둘 다 "나스닥"이고
 *        `DOW`는 `.DJT`·`.DJU`와 접두사를 공유해서, 요약을 읽는 사람이 iscd를 보려면 yml을 열어
 *        대조해야 했다. 한 줄에 같이 실으면 요약 하나로 끝난다.
 *
 * 9종 중 실측으로 확인된 것은 `SPX`·`.DJI`·`HK#HS` 셋뿐이고 **나머지 여섯은 KIS 마스터
 * 파일에서 읽었을 뿐**이다. 틀린 코드는 [IndexGuards]를 그대로 통과한다 — 엉뚱한 지수의
 * 응답도 그 지수 기준으로는 내부적으로 일관돼서 값끼리의 정합성 검사에 걸릴 것이 없다.
 * 그래서 `hts_kor_isnm` 대조가 유일한 확인 수단인데, 요약에 실어 두면 **예약 실행 한 번의
 * JSON이 9종을 한꺼번에 답해 준다** — 지수마다 raw 덤프 엔드포인트를 찔러 볼 필요가 없다.
 * 워크플로가 응답 본문을 잡 요약에 그대로 붙이므로 사람이 바로 본다.
 *
 * **저장된 것만 싣지 않는다.** 이름 대조나 가드에 걸려 저장되지 않은 지수도 여기 남는다 —
 * 이 맵의 목적은 "무엇이 저장됐나"가 아니라 "이 코드로 KIS가 무엇을 돌려주나"이고,
 * 코드를 잘못 골라 대조가 깨진 순간이야말로 그 답이 가장 필요한 때다.
 */
data class OverseasIndexCollectSummary(
    val schedule: String,
    val requested: Int,
    val collected: Int,
    val inserted: Int,
    val updated: Int,
    val failed: Int,
    val failures: List<String>,
    val names: Map<String, String>,
)

/**
 * 해외 지수 수집 (AF-110).
 *
 * 국내([IndexCollectService])와 조립 방식은 같지만 **판정의 출처가 다르다.** 해외 일별 시세
 * 응답은 봉마다 자기 날짜를 들고 오므로, 국내가 시계로 유추하던 것을 여기서는 응답에서 읽는다.
 *
 * | | 국내 | 해외 |
 * |---|---|---|
 * | 거래일 | 시계(KST 날짜) | 응답(`output2[0].stck_bsop_date`) |
 * | 슬롯 | OPEN/MID/CLOSE | `CLOSE` 고정 — 일봉이라 하루 한 건 |
 * | 시장상태 | 시계(09:00/15:30) | 최신 봉 날짜 + 시장 현지 마감 시각 |
 * | 전일 기준일 | 항상 null(응답에 없다) | `output2[1].stck_bsop_date` |
 * | 이름 대조 | 없음 | **있다 — 이 클래스의 핵심** |
 *
 * **국내의 `sameValuesAs` 휴장일 휴리스틱(직전 저장 행과 값이 전부 같으면 장마감으로 낮추기)을
 * 일부러 가져오지 않았다.** 빠뜨린 게 아니다. 국내는 휴장일에도 시계가 "장중"이라고 우기니까
 * 값의 무변동으로 그걸 눌러야 했지만, 해외는 봉이 자기 날짜를 들고 오므로 휴장일이면 최신 봉이
 * 이전 날짜로 와서 [marketStatus]가 자동으로 장마감이 된다. "국내엔 있는데 왜 없지" 하고 도로
 * 넣지 말 것 — 넣으면 값이 우연히 같은 날(드물지만 지수는 같은 종가로 마감할 수 있다)에
 * 멀쩡한 장중을 근거 없이 낮춘다.
 *
 * `@Transactional`을 붙이지 않는다 — 지수마다 HTTP 호출이 하나씩 있어서 트랜잭션 안에 넣으면
 * 루프가 끝날 때까지 Neon 커넥션을 쥐고 앉아 있게 된다. 국내와 같은 이유이고, **지수가 3종이
 * 아니라 9종이라 더 나쁘다.** 별도 빈으로 쪼개면 AF-90에서 물린 자기호출 프록시 함정이 되살아난다.
 */
@Service
class OverseasIndexCollectService(
    private val client: KisIndexClient,
    private val parser: KisOverseasIndexParser,
    private val guards: IndexGuards,
    private val repository: MarketIndexQuoteJpaRepository,
    private val properties: MarketIndexProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 연속 전체 실패 횟수를 **슬롯별로** 센다. 국내와 같게 프로세스 메모리에만 둔다 —
     * 하는 일이 "로그 레벨을 올린다"뿐이라 테이블을 늘릴 값어치가 없고,
     * 재시작으로 0이 되는 것도 손해가 아니다(재시작 자체가 이미 조사할 사건이다).
     *
     * **국내처럼 카운터를 하나만 두면 경보가 영영 안 울린다.** 국내는 슬롯이 셋이어도 같은
     * 시장이라 한 카운터로 충분했지만, 해외는 US와 ASIA가 **서로 다른 시장이고 매일 번갈아 돈다.**
     * 아시아가 전멸해도 그 사이에 낀 미국 성공이 `set(0)`으로 리셋해서 3회에 도달하지 못한다 —
     * 실제로 ASIA실패 → US성공 → ASIA실패 → US성공 → ASIA실패가 WARN 셋으로만 끝났다.
     * 즉 아시아 지수가 통째로 깨져도 ERROR가 뜨지 않는다. 그래서 슬롯을 키로 나눈다.
     */
    private val consecutiveFailures = ConcurrentHashMap<String, AtomicInteger>()

    companion object {
        private const val SOURCE = "KIS_OVERSEAS"

        /**
         * 해외는 일봉이라 하루 한 건이다. 국내의 OPEN/MID/CLOSE 같은 슬롯 구분이 필요 없어
         * `CLOSE`로 고정한다 — 저장 키의 세 번째 칸을 비울 수는 없고(컬럼이 NOT NULL이다),
         * 그날의 대표값이라는 뜻은 CLOSE가 정확하다. `findLatest`의 슬롯 순위에서도 최상위다.
         */
        private val SLOT = IndexSlot.CLOSE

        /**
         * 조회 구간 길이. KIS 해외 일별 시세는 `from`/`to`를 요구한다.
         *
         * 하루치만 달라고 하면 [OverseasIndexReading.prevCloseDate]를 채울 `output2[1]`이
         * 아예 없고, 이틀치로 잡으면 주말·현지 공휴일이 끼는 순간(금·토·일, 미국 추수감사절 연휴)
         * 직전 거래일이 구간 밖으로 밀려난다. 2주면 주말과 대부분의 연휴를 덮는다.
         * **파서가 최신 봉만 쓰므로 넉넉하게 잡는 비용은 응답 크기뿐이다** — 좁히면 조용히
         * prev_close_date만 비는 쪽으로 망가진다.
         *
         * 그래도 만능은 아니다: **상해 춘절은 보통 8~9일 연속 휴장**이고 일본 골든위크·연말연시도
         * 경계선이라, 그 구간에 걸리면 봉이 아예 안 와서 파서가 응답을 거부한다(그날 그 지수만
         * 실패로 남고 나머지는 저장된다). 7일이었을 때는 춘절이 그대로 구멍이었다.
         */
        private const val LOOKBACK_DAYS = 14L

        private const val FAILURE_ALERT_THRESHOLD = 3
    }

    /**
     * @param schedule `US` | `ASIA`. 설정의 `market-index.overseas[].schedule`과 대조해 이 슬롯에
     *        속한 지수만 부른다. 안 거르면 아시아 슬롯(08:30 UTC)이 미국 지수를 부르는데,
     *        그 시각 미국은 아직 장중이라 진행 중인 봉을 종가처럼 저장하게 된다.
     * @param now 현재 시각. **국내와 달리 [Instant]로 받는다** — 국내의 `collect(slot, now)`는
     *        `LocalDateTime`을 "UTC로 해석한다"는 규약이라 KDoc에 경고를 달아야 했고, 호출부가
     *        KST LocalDateTime을 넘기면 아무도 못 잡았다. 여기서는 타입이 그 모호함을 없앤다.
     */
    fun collect(schedule: String, now: Instant): OverseasIndexCollectSummary {
        val targets = properties.overseas.filter { it.schedule == schedule }
        val collectedAt = LocalDateTime.ofInstant(now, ZoneOffset.UTC)

        var inserted = 0
        var updated = 0
        val failures = mutableListOf<String>()
        val names = mutableMapOf<String, String>()

        for (cfg in targets) {
            try {
                // 시장 현지 "오늘". 조회 구간의 끝과 시장상태 판정에 **같은 값을 쓴다** —
                // 둘을 따로 계산하면 자정 근처에서 하루가 갈려, 존재하지 않는 봉을 기다리거나
                // 방금 받은 봉을 구간 밖이라고 판정하는 어긋남이 생긴다.
                val marketNow = LocalDateTime.ofInstant(now, ZoneId.of(cfg.zoneId))
                val marketToday = marketNow.toLocalDate()
                val raw = client.fetchOverseasRaw(cfg.kisIscd, marketToday.minusDays(LOOKBACK_DAYS), marketToday)
                val reading = parser.parse(cfg.code, raw)

                // 이름은 파싱만 되면 기록한다 — 아래 대조에서 걸려 저장하지 않더라도,
                // 그때야말로 KIS가 무엇을 돌려줬는지 봐야 하는 순간이다.
                // iscd를 붙이는 이유는 [OverseasIndexCollectSummary.names] 참조
                names[cfg.code] = "${reading.nameFromKis}(${cfg.kisIscd})"

                // **가드보다 먼저다.** IndexGuards는 값끼리의 정합성만 보므로 엉뚱한 지수의 응답도
                // 그대로 통과시킨다(그 응답은 그 지수 기준으로 일관되기 때문). 마스터에는 한 글자
                // 차이인 것들이 줄줄이 붙어 있어(나스닥100 옆 XNDXL/XNDXS1, 항셍 옆 HSCE,
                // 다우 옆 .DJT/.DJU) 코드를 잘못 고르면 예외도 경고도 없이 그럴듯한 숫자가 저장되고
                // 화면엔 "항셍"이라 쓰인 채 홍콩H지수가 뜬다. 그걸 막는 유일한 검사다.
                if (!reading.nameFromKis.contains(cfg.nameContains)) {
                    throw IllegalStateException(
                        // 양쪽 문자열을 다 싣는다 — 설정이 틀렸는지 코드가 틀렸는지는
                        // 운영자가 둘을 나란히 봐야 가른다
                        "KIS가 돌려준 이름이 설정과 다릅니다: hts_kor_isnm='${reading.nameFromKis}', " +
                            "기대 nameContains='${cfg.nameContains}' (iscd=${cfg.kisIscd}). " +
                            "코드를 잘못 골랐을 수 있습니다",
                    )
                }

                // **두 번째 인자를 반드시 넘긴다.** 빼면 응답 전일종가 ↔ 역산값 교차검증이 통째로
                // 꺼지는데, 등락률 검사는 여전히 통과하므로 어디에서도 티가 나지 않는다.
                // 해외 응답만 이 값을 주므로 국내에는 없던 검사다
                val anomalies = guards.check(reading.quote, reading.reportedPrevClose)
                if (anomalies.isNotEmpty()) {
                    throw IllegalStateException(
                        "안전장치에 걸려 저장하지 않았습니다: ${anomalies.joinToString("; ")}",
                    )
                }

                val status = marketStatus(reading.tradeDate, marketNow, closeTime(cfg))
                val existing = repository.findByIndexCodeAndTradeDateAndSlot(
                    cfg.code, reading.tradeDate, SLOT.name,
                )
                if (existing == null) {
                    try {
                        repository.save(newEntity(cfg.code, reading, status, collectedAt))
                        inserted++
                    } catch (e: DataIntegrityViolationException) {
                        // 읽고-나서-넣기 사이에 다른 요청이 같은 (지수, 거래일, 슬롯)을 먼저 넣었다.
                        // **경합의 원인은 우리 재시도 자체다.** 콜드 스타트로 첫 요청의
                        // `--max-time 120`이 만료돼도 서버는 collect()를 계속 돌고 있고, curl은
                        // 20초 뒤 두 번째 요청을 보내 같은 인스턴스에서 같은 루프가 겹쳐 돈다.
                        // 워크플로의 concurrency 그룹은 **잡 하나 안의 겹침**을 못 본다.
                        //
                        // 여기서 그냥 실패로 세면 지수가 다 부딪힌 순간 collected == 0이 되어
                        // 잡이 빨개진다 — 첫 요청이 행을 멀쩡히 저장했는데도. 그래서 한 번만 다시
                        // 읽어 갱신으로 돌린다. 국내에서 그대로 가져온 동작이다.
                        val winner = repository.findByIndexCodeAndTradeDateAndSlot(
                            cfg.code, reading.tradeDate, SLOT.name,
                        ) ?: throw e   // 유니크 충돌이 아니었다는 뜻이다. 삼키면 안 된다
                        repository.save(winner.apply { overwrite(reading, status, collectedAt) })
                        updated++
                    }
                } else {
                    repository.save(existing.apply { overwrite(reading, status, collectedAt) })
                    updated++
                }
            } catch (e: Exception) {
                // 한 지수의 실패가 나머지를 끌고 가지 않는다
                failures += "${cfg.code}: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        val summary = OverseasIndexCollectSummary(
            schedule = schedule,
            requested = targets.size,
            collected = inserted + updated,
            inserted = inserted,
            updated = updated,
            failed = failures.size,
            failures = failures,
            names = names,
        )

        when {
            targets.isEmpty() ->
                log.warn("[해외지수] schedule={} 에 해당하는 지수가 없습니다 — market-index.overseas 확인", schedule)
            summary.collected == 0 -> recordFailure(summary)
            else -> {
                // 이 슬롯만 리셋한다. 미국이 성공했다고 아시아의 연속 실패를 지우면 안 된다
                counterFor(schedule).set(0)
                if (failures.isEmpty()) log.info("[해외지수] 수집 완료 {}", summary)
                else log.warn("[해외지수] 일부 실패 {}", summary)
            }
        }
        return summary
    }

    /**
     * 최신 봉이 **오늘 것이고 아직 마감 전**이면 진행 중인 봉이고, 그 밖은 확정된 종가다.
     *
     * **봉 날짜만 보면 안 된다 — 그게 처음 구현이었고 틀렸다.** 예약 실행은 아시아 08:30 UTC
     * (홍콩 16:30), 미국 21:30 UTC(뉴욕 17:30)로 **둘 다 마감 이후**인데 봉 날짜는 여전히
     * "현지 오늘"이라, 평일 정상 수집 전건이 확정 종가를 `장중`으로 저장하고 `장마감`은
     * 주말·휴장에만 나왔다. 라벨이 뜻하는 것의 정반대다.
     *
     * 마감 시각은 [MarketIndexProperties.OverseasIndex.closeLocalTime]에서 온다 — 근사치이고
     * 왜 그래도 되는지는 그 KDoc에 있다. **경계는 마감 포함이다**(정각은 장마감).
     * 개장 시각은 두지 않는다: 봉이 없으면 애초에 여기까지 오지 않고, 있는데 개장 전일 수는 없다.
     *
     * **`PRE_OPEN`은 쓰지 않는다.** 해외 수집은 그 시장이 마감한 뒤에만 돈다. 개장 전에 부르지
     * 않으므로 그 상태로 저장될 일이 없고, 억지로 판정하려면 개장 시각표가 하나 더 필요해진다.
     *
     * **거래량으로 판정하지 말 것.** `acml_vol == "0"`이면 진행 중인 봉이라는 규칙은 그럴듯하지만
     * 틀렸다 — `SPX`는 **확정된 봉도** `acml_vol: "0"`으로 온다(지수라 거래량이 없다).
     * 그 규칙을 쓰면 S&P가 영원히 "장중"으로 저장된다.
     */
    private fun marketStatus(
        tradeDate: LocalDate,
        marketNow: LocalDateTime,
        closeLocalTime: LocalTime,
    ): MarketStatus =
        if (tradeDate == marketNow.toLocalDate() && marketNow.toLocalTime() < closeLocalTime) {
            MarketStatus.OPEN
        } else {
            MarketStatus.CLOSED
        }

    /**
     * 설정 문자열을 [LocalTime]으로. **루프 안(지수별 try 안)에서 부른다** — 형식이 틀린 값이
     * 하나 있어도 그 지수만 실패로 남고 나머지 여덟은 저장돼야 한다. 시작할 때 한꺼번에 파싱하면
     * 오타 하나가 그 슬롯 전체를 날린다. (형식 자체는 `MarketIndexPropertiesTest`가 배포 전에 판다)
     */
    private fun closeTime(cfg: MarketIndexProperties.OverseasIndex): LocalTime =
        try {
            LocalTime.parse(cfg.closeLocalTime)
        } catch (e: DateTimeParseException) {
            throw IllegalStateException(
                "마감 시각 설정이 시각 형식이 아닙니다: close-local-time='${cfg.closeLocalTime}' " +
                    "(code=${cfg.code}, \"16:00\" 형식이어야 합니다)",
                e,
            )
        }

    private fun MarketIndexQuoteEntity.overwrite(
        reading: OverseasIndexReading,
        status: MarketStatus,
        collectedAt: LocalDateTime,
    ) {
        price = reading.quote.price
        prevClose = reading.quote.prevClose
        changeValue = reading.quote.change
        changeRate = reading.quote.changeRate
        prevCloseDate = reading.prevCloseDate
        marketStatus = status.label
        this.collectedAt = collectedAt
    }

    private fun newEntity(
        indexCode: String,
        reading: OverseasIndexReading,
        status: MarketStatus,
        collectedAt: LocalDateTime,
    ) = MarketIndexQuoteEntity(
        id = UUID.randomUUID(),
        indexCode = indexCode,
        // 거래일은 응답의 봉에서 온다. 시계로 유추하면 한국 시각 기준으로 하루가 밀려
        // 미국 지수의 금요일 봉이 토요일 자로 박힌다
        tradeDate = reading.tradeDate,
        slot = SLOT.name,
        price = reading.quote.price,
        prevClose = reading.quote.prevClose,
        changeValue = reading.quote.change,
        changeRate = reading.quote.changeRate,
        // 국내에서 항상 null이던 칸이다. 해외는 output2[1]이 날짜를 들고 온다
        prevCloseDate = reading.prevCloseDate,
        marketStatus = status.label,
        source = SOURCE,
        collectedAt = collectedAt,
    )

    private fun counterFor(schedule: String): AtomicInteger =
        consecutiveFailures.computeIfAbsent(schedule) { AtomicInteger(0) }

    /** 한 건도 못 건진 실행만 센다. 부분 성공은 데이터가 남았으므로 카운터를 올리지 않는다 */
    private fun recordFailure(summary: OverseasIndexCollectSummary) {
        val count = counterFor(summary.schedule).incrementAndGet()
        val reason = summary.failures.joinToString("; ")
        if (count >= FAILURE_ALERT_THRESHOLD) {
            log.error("[해외지수] 연속 {}회 전체 실패 schedule={} reason={}", count, summary.schedule, reason)
        } else {
            log.warn("[해외지수] 수집 실패({}회) schedule={} reason={}", count, summary.schedule, reason)
        }
    }
}
