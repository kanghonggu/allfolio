# AF-110 해외 지수 수집 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 해외 지수 9종을 KIS 일봉으로 수집한다. 미국·유럽은 KST 익일 06:30, 아시아는 17:30.

**Architecture:** 국내(AF-101)와 저장소·엔티티·가드를 공유하되 **클라이언트·파서·수집 서비스는 따로 둔다** — 응답 필드명이 전혀 다르고(`bstp_` vs `ovrs_`), 거래일이 응답에 있어 시계로 유추하지 않으며, 진행 중인 봉을 구분할 수 있다.

**Tech Stack:** Kotlin 1.9.25 / Spring Boot 3.2.5 / WebClient / JPA / JUnit5 + 순수 Mockito·손수 만든 페이크 / GitHub Actions

**Spec:** `docs/superpowers/specs/2026-08-13-overseas-index-collection-design.md`

---

## 이미 있는 것 — 다시 만들지 말 것

`KisIndexClient.fetchOverseasRaw(iscd, from, to)`가 **PR #156으로 이미 머지·배포돼 있다.**
경로·`tr_id`·파라미터·토큰 캐시·오류 매핑이 다 들어 있고 실제 운영에서 3종을 찍어 확인했다.
이 계획은 그 위에 파서·수집 서비스·스케줄을 얹는다.

`MarketIndexQuoteEntity`·`MarketIndexQuoteJpaRepository`·`IndexGuards`·`MarketStatus`도 그대로 쓴다.
**마이그레이션 없다. 스키마 변경 없다.**

## 사전 필독 (모든 태스크 공통)

- **Gradle 테스트는 반드시 `--rerun-tasks`.** 없으면 전부 UP-TO-DATE로 보고되고 아무것도 실행되지 않는다.
- Gradle 콘솔은 개별 테스트를 나열하지 않는다. 건수는 JUnit XML을 읽을 것:
  `allfolio-backend/backend-app/build/test-results/test/TEST-<FQCN>.xml`
- **`mockito-kotlin`은 이 저장소에 없다.** `spring-boot-starter-test`뿐이다. 순수 `org.mockito.Mockito`나
  손수 만든 페이크를 쓴다. 의존성을 추가하지 말 것.
- **Kotlin final 클래스를 Mockito로 목킹할 때 non-null 파라미터에 `any()`를 쓰면 스터빙 시점에 NPE**가 난다.
  `any(X::class.java) ?: <기본값>`으로 우회한다.
- 브랜치는 `feat/af-110-overseas-index` (생성돼 있고 설계 문서 커밋이 올라가 있다).

## 실측 응답 (2026-08-13 운영 · 이 값을 픽스처로 쓴다)

지어낸 숫자를 쓰지 말 것. 아래는 `HK#HS`의 실제 응답이고 **하락일이라 부호 규약이 드러나 있다.**

```json
{
  "output1": {
    "ovrs_nmix_prpr": "25365.14", "ovrs_nmix_prdy_vrss": "-75.03",
    "prdy_vrss_sign": "5", "prdy_ctrt": "-0.29",
    "ovrs_nmix_prdy_clpr": "25440.17", "hts_kor_isnm": "항셍지수",
    "stck_shrn_iscd": "HK#HS", "acml_vol": "0"
  },
  "output2": [
    {"stck_bsop_date": "20260813", "ovrs_nmix_prpr": "25365.14", "acml_vol": "0"},
    {"stck_bsop_date": "20260812", "ovrs_nmix_prpr": "25440.17", "acml_vol": "23804413"}
  ],
  "rt_cd": "0"
}
```

검산: `25365.14 − (−75.03) = 25440.17` = `output2[1]`의 종가. `−75.03/25440.17×100 = −0.2949` ≈ `−0.29`.

## 파일 구조

| 파일 | 책임 |
|---|---|
| `market/index/MarketIndexProperties.kt` (수정) | `overseas` 목록 추가 |
| `backend-app/src/main/resources/application.yml` (수정) | 지수 9종 설정 |
| `market/index/IndexSignRule.kt` (신규) | 국내 파서에서 꺼낸 부호 규칙. 두 파서가 공유 |
| `market/index/KisIndexParser.kt` (수정) | 부호 규칙을 위로 위임 |
| `market/index/KisOverseasIndexParser.kt` (신규) | 응답 → 도메인. 여기만 `ovrs_` 필드명을 안다 |
| `market/index/IndexGuards.kt` (수정) | 전일종가 교차검증 추가 |
| `market/index/OverseasIndexCollectService.kt` (신규) | 조립 + 저장 |
| `api/admin/MarketIndexAdminController.kt` (수정) | 수동 수집 엔드포인트 |
| `api/scheduler/SchedulerTriggerController.kt` (수정) | 스케줄 트리거 |
| `.github/workflows/collect-index-overseas.yml` (신규) | 미국·아시아 cron |

**워크플로는 새 파일이어야 한다 — `collect-index.yml`에 cron을 추가하면 안 된다.**
GitHub은 워크플로 파일의 **어느** 스케줄이 울리든 그 파일의 **모든** 잡을 돌린다. 해외 cron을 거기 넣으면
`domestic-index` 잡도 같이 깨어나 `case "${EVENT_SCHEDULE}"`의 `*)` 분기에 걸려 매번 빨갛게 실패한다
(모르는 cron에 기본값을 안 주는 그 설계가 여기서는 정확히 이렇게 작동한다). 잡에 `if:`를 붙여 막을 수도
있지만, `collect-fx.yml`이 이미 별도 파일인 것과 같은 이유로 파일을 나누는 편이 맞다.

---

### Task 1: 설정 — 지수 목록과 검증 이름

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/index/MarketIndexProperties.kt`
- Modify: `allfolio-backend/backend-app/src/main/resources/application.yml`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/index/MarketIndexPropertiesTest.kt` (수정)

- [ ] **Step 1: 프로퍼티 클래스에 `overseas`를 추가한다**

```kotlin
    var overseas: List<OverseasIndex> = emptyList()

    /**
     * 해외 지수 한 종 (AF-110).
     *
     * [nameContains]가 이 설정의 핵심이다 — 아래 KDoc 참조.
     */
    class OverseasIndex {
        /** 우리가 정한 canonical 코드. DB의 index_code가 된다 */
        var code: String = ""
        /** KIS FID_INPUT_ISCD. 미국계는 `SPX`·`.DJI`, 아시아·유럽계는 `HK#HS` 형태 */
        var kisIscd: String = ""
        /**
         * 시장 현지 타임존. **진행 중인 봉 판별에만 쓴다** —
         * 최신 봉의 날짜가 이 타임존의 오늘이면 아직 장이 안 끝난 것이다.
         * 수집 시각(아래 [schedule])과는 별개다: 유로스톡스는 유럽 타임존이지만 미국 슬롯에 실린다.
         */
        var zoneId: String = ""
        /** 어느 cron 슬롯에 실을지. US | ASIA */
        var schedule: String = ""
        /**
         * KIS 응답의 `hts_kor_isnm`에 반드시 들어 있어야 하는 문자열.
         *
         * **틀린 코드를 넣었을 때 이것 말고는 잡을 방법이 없다.** 마스터에는 한 글자 차이인 것들이
         * 줄줄이 붙어 있다 — 나스닥100 옆 `XNDXL`(레버리지)·`XNDXS1/S2`(인버스), 항셍 옆
         * `HSCE`(홍콩H)·`HK#HSSI`(소형주), 상해의 `CH#SHA`/`CH#SHB`(A·B주), 다우 옆
         * `.DJT`(운송)·`.DJU`(유틸리티).
         *
         * `IndexGuards`는 값끼리의 정합성만 보므로 **엉뚱한 지수의 응답도 내부적으로 일관돼
         * 그대로 통과한다.** 예외도 경고도 없이 그럴듯한 숫자가 저장되고, 화면엔 "항셍"이라 쓰인 채
         * 홍콩H지수가 뜬다. 그래서 코드가 아니라 **KIS가 돌려준 이름**으로 대조한다.
         */
        var nameContains: String = ""
    }
```

- [ ] **Step 2: `application.yml`의 `market-index:` 블록에 `overseas`를 추가한다**

`domestic:` 목록 바로 아래에 붙인다.

```yaml
  # 해외 지수 — AF-110. 코드는 KIS 마스터(frgn_code.mst)에서 1차 확인했다.
  # nameContains는 장식이 아니다 — 틀린 코드는 가드를 통과해 그럴듯한 숫자로 저장되므로
  # KIS가 돌려준 hts_kor_isnm과 대조하는 것이 유일한 방어다.
  # ✅ 실측 확인: SPX · .DJI · HK#HS. 나머지 여섯은 마스터에서 읽었을 뿐이므로
  #    배포 후 raw-overseas로 hts_kor_isnm을 대조할 것.
  overseas:
    - { code: SPX,      kis-iscd: "SPX",       zone-id: America/New_York, schedule: US,   name-contains: "S&P500" }
    - { code: NASDAQ,   kis-iscd: "COMP",      zone-id: America/New_York, schedule: US,   name-contains: "나스닥" }
    - { code: DOW,      kis-iscd: ".DJI",      zone-id: America/New_York, schedule: US,   name-contains: "다우존스" }
    - { code: NASDAQ100, kis-iscd: "NDX",      zone-id: America/New_York, schedule: US,   name-contains: "나스닥" }
    - { code: VIX,      kis-iscd: "VIX",       zone-id: America/New_York, schedule: US,   name-contains: "VIX" }
    - { code: STOXX50,  kis-iscd: "SX5E",      zone-id: Europe/Berlin,    schedule: US,   name-contains: "STOXX" }
    - { code: NIKKEI225, kis-iscd: "JP#NI225", zone-id: Asia/Tokyo,       schedule: ASIA, name-contains: "니케이" }
    - { code: HANGSENG, kis-iscd: "HK#HS",     zone-id: Asia/Hong_Kong,   schedule: ASIA, name-contains: "항셍" }
    - { code: SHANGHAI, kis-iscd: "SHANG",     zone-id: Asia/Shanghai,    schedule: ASIA, name-contains: "상해" }
```

**`STOXX50`이 `schedule: US`인 것은 오타가 아니다.** 마감이 15:30 UTC라 아시아 슬롯(08:30 UTC)보다
7시간 늦다 — 거기 두면 늘 전일 종가를 받는다. 미국 슬롯(21:30 UTC)이 마감 6시간 후다.
`zone-id`는 여전히 유럽이다(진행 중 판별용).

**`NASDAQ`과 `NASDAQ100`의 `name-contains`가 둘 다 `나스닥`인 것**은 약한 검사다.
마스터의 한글명이 각각 `나스닥 종합`·`나스닥 100`이므로 그대로 쓰면 더 강해지지만,
KIS 응답의 `hts_kor_isnm`이 마스터와 같은 문자열인지는 **실측으로 확인되지 않았다**
(`SPX`는 마스터 `S&P500` = 응답 `S&P500`으로 일치했다). 배포 후 대조해 좁힐 것.

- [ ] **Step 3: 바인딩 테스트를 추가한다**

`application.yml`의 오타는 컴파일로 안 잡히고, 빈 리스트가 되면 수집이 조용히 0건이 된다.
기존 `MarketIndexPropertiesTest`에 추가한다.

```kotlin
    @Test
    fun `해외 지수 아홉 종이 설정에서 바인딩된다`() {
        assertThat(properties.overseas.map { it.code })
            .containsExactly("SPX", "NASDAQ", "DOW", "NASDAQ100", "VIX",
                             "STOXX50", "NIKKEI225", "HANGSENG", "SHANGHAI")
    }

    // 하이픈 표기(kis-iscd)가 카멜케이스(kisIscd)로 완화 바인딩되는지.
    // 여기가 비면 수집이 빈 문자열을 KIS에 보낸다.
    @Test
    fun `KIS 코드와 검증 이름이 비어 있지 않다`() {
        assertThat(properties.overseas).allSatisfy {
            assertThat(it.kisIscd).isNotBlank()
            assertThat(it.nameContains).isNotBlank()
            assertThat(it.zoneId).isNotBlank()
        }
    }

    // 유로스톡스는 유럽 타임존이지만 미국 슬롯에 실린다 — 마감이 아시아 슬롯보다 7시간 늦어서다.
    // 이걸 "고쳐서" ASIA로 옮기면 화면이 늘 하루 뒤처진다.
    @Test
    fun `유로스톡스는 미국 슬롯에 실린다`() {
        val stoxx = properties.overseas.single { it.code == "STOXX50" }

        assertThat(stoxx.schedule).isEqualTo("US")
        assertThat(stoxx.zoneId).isEqualTo("Europe/Berlin")
    }

    // zoneId가 실재하는 타임존인지. 오타는 런타임에야 터진다.
    @Test
    fun `모든 타임존이 실재한다`() {
        assertThat(properties.overseas).allSatisfy {
            assertThatCode { ZoneId.of(it.zoneId) }.doesNotThrowAnyException()
        }
    }
```

- [ ] **Step 4: 통과 확인 후 커밋**

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests '*MarketIndexPropertiesTest*' --rerun-tasks --no-daemon
```

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/index/MarketIndexProperties.kt \
        allfolio-backend/backend-app/src/main/resources/application.yml \
        allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/index/MarketIndexPropertiesTest.kt
git commit -m "feat(af-110): 해외 지수 9종 설정 + 이름 검증용 nameContains"
```

---

### Task 2: 부호 규칙 추출 + 파서

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/index/IndexSignRule.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/index/KisIndexParser.kt`
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/index/KisOverseasIndexParser.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/index/KisOverseasIndexParserTest.kt`

**먼저 읽을 것**: `KisIndexParser.kt`(국내) 전문. 특히 `direction`·`magnitude`의 KDoc.

- [ ] **Step 1: 부호 규칙을 꺼내 공유 가능하게 만든다 (순수 리팩터링)**

`KisIndexParser.direction`·`magnitude`는 `private`이라 해외 파서에서 못 쓴다.
**복사하지 말 것** — 규칙이 갈라지면 한쪽만 고치는 날이 온다. `internal object IndexSignRule`로 옮긴다.

```kotlin
/**
 * KIS 지수 응답의 전일대비 부호 규칙 (AF-101에서 세우고 AF-110에서 실측 검증).
 *
 * 국내·해외 응답이 필드명은 다르지만(`bstp_` vs `ovrs_`) 부호 규약은 같다.
 * 두 파서가 이 하나를 공유한다.
 */
internal object IndexSignRule {
    /** `prdy_vrss_sign` → 방향(+1 / 0 / -1). 모르는 코드는 거부한다 */
    fun direction(sign: String): Int = ...
    /** 절댓값을 취하되, 원본 부호가 [direction]과 모순되면 거부한다 */
    fun magnitude(raw: BigDecimal, key: String, sign: String, direction: Int): BigDecimal = ...
}
```

`magnitude`의 시그니처가 바뀐다 — 원래는 `output`+`key`를 받아 안에서 `number()`를 불렀지만,
필드명 규약이 다른 두 파서가 공유하려면 **파싱된 값**을 받아야 한다. `key`는 오류 메시지용으로 남긴다.
`KisIndexParser.parse`는 `IndexSignRule.magnitude(number(output, key), key, sign, direction)`로 바꾼다.

**검증**: `KisIndexParserTest`를 **한 줄도 고치지 않고** 전부 통과해야 한다. 고쳐야 한다면 순수
리팩터링이 아니라 동작이 바뀐 것이다.

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests '*KisIndexParserTest*' --rerun-tasks --no-daemon
```

- [ ] **Step 2: 국내 파서 KDoc의 미해결 문장을 실측으로 갱신한다**

`KisIndexParser`의 KDoc에 이렇게 적혀 있다 — **AF-110이 그 답을 알아냈다.**

> 실측 응답(2026-08-12)은 상승일이라 `bstp_nmix_prdy_vrss`가 양수로 왔는데,
> **하락일에 마이너스가 붙을지는 알 수 없다**

`HK#HS`·`.DJI` 하락일 실측에서 **KIS는 값에 마이너스를 싣는다**가 확인됐다(`ovrs_nmix_prdy_vrss: "-75.03"`,
`prdy_vrss_sign: "5"`). 방어적으로 세운 규칙이 원본을 그대로 재현한다. 그 문장을 이 사실로 바꾼다 —
남겨두면 다음 사람이 이미 답이 나온 질문을 다시 조사한다.

- [ ] **Step 3: 실패 테스트를 먼저 쓴다 — 픽스처는 실측 그대로**

```kotlin
    /** 2026-08-13 운영 실측 (HK#HS, 하락일) */
    private fun realResponse(
        prpr: String = "25365.14", vrss: String = "-75.03", sign: String = "5",
        ctrt: String = "-0.29", clpr: String = "25440.17", name: String = "항셍지수",
        bars: List<Pair<String, String>> = listOf("20260813" to "25365.14", "20260812" to "25440.17"),
    ) = mapOf<String, Any?>(
        "output1" to mapOf(
            "ovrs_nmix_prpr" to prpr, "ovrs_nmix_prdy_vrss" to vrss,
            "prdy_vrss_sign" to sign, "prdy_ctrt" to ctrt,
            "ovrs_nmix_prdy_clpr" to clpr, "hts_kor_isnm" to name,
        ),
        "output2" to bars.map { (d, p) -> mapOf("stck_bsop_date" to d, "ovrs_nmix_prpr" to p) },
    )

    @Test
    fun `실측 응답을 그대로 파싱한다`() {
        val bar = parser.parse("HANGSENG", realResponse())

        assertThat(bar.quote.price).isEqualByComparingTo("25365.14")
        assertThat(bar.quote.change).isEqualByComparingTo("-75.03")
        assertThat(bar.quote.changeRate).isEqualByComparingTo("-0.29")
        assertThat(bar.quote.prevClose).isEqualByComparingTo("25440.17")
    }

    // 국내와 가장 다른 점. 시계로 유추하지 않는다.
    @Test
    fun `거래일을 응답에서 읽는다`() {
        assertThat(parser.parse("HANGSENG", realResponse()).tradeDate)
            .isEqualTo(LocalDate.of(2026, 8, 13))
    }

    // 한국 월요일 아침에 보는 S&P의 "전일"은 미국 금요일이다.
    // 이 값이 없으면 화면이 어느 날 대비인지 말할 수 없다.
    @Test
    fun `전일 종가의 날짜를 두 번째 봉에서 읽는다`() {
        assertThat(parser.parse("HANGSENG", realResponse()).prevCloseDate)
            .isEqualTo(LocalDate.of(2026, 8, 12))
    }

    // 봉이 하나뿐이면(상장 직후·긴 연휴) 전일 날짜를 알 수 없다. 지어내지 말 것.
    @Test
    fun `봉이 하나면 전일 날짜는 null이다`() {
        val one = realResponse(bars = listOf("20260813" to "25365.14"))

        assertThat(parser.parse("HANGSENG", one).prevCloseDate).isNull()
    }

    // 응답이 준 전일종가를 따로 보관한다 — 가드가 역산값과 대조한다(국내에 없던 검증).
    @Test
    fun `응답의 전일종가를 그대로 보관한다`() {
        assertThat(parser.parse("HANGSENG", realResponse()).reportedPrevClose)
            .isEqualByComparingTo("25440.17")
    }

    // 틀린 코드를 넣었을 때 이것 말고는 잡을 방법이 없다.
    @Test
    fun `KIS가 준 이름을 보관한다`() {
        assertThat(parser.parse("HANGSENG", realResponse()).nameFromKis).isEqualTo("항셍지수")
    }

    // 국내에서 세운 규칙이 해외 실측으로 검증됐다 — 값에 부호가 실려 와도 결과가 같다.
    @Test
    fun `부호가 값에 실려 있든 없든 같은 결과를 낸다`() {
        val signed = parser.parse("HANGSENG", realResponse(vrss = "-75.03", ctrt = "-0.29", sign = "5"))
        val unsigned = parser.parse("HANGSENG", realResponse(vrss = "75.03", ctrt = "0.29", sign = "5"))

        assertThat(signed.quote.change).isEqualByComparingTo(unsigned.quote.change)
        assertThat(signed.quote.changeRate).isEqualByComparingTo(unsigned.quote.changeRate)
    }

    @Test
    fun `값이 음수인데 부호가 상승이면 거부한다`() {
        assertThatThrownBy { parser.parse("HANGSENG", realResponse(vrss = "-75.03", sign = "2")) }
            .isInstanceOf(KisIndexException::class.java).hasMessageContaining("부호")
    }

    @Test
    fun `output2가 비면 거부한다`() {
        val empty = realResponse(bars = emptyList())

        assertThatThrownBy { parser.parse("HANGSENG", empty) }
            .isInstanceOf(KisIndexException::class.java)
    }

    @Test
    fun `output1이 없으면 거부한다`() {
        assertThatThrownBy { parser.parse("HANGSENG", mapOf("output2" to emptyList<Any>())) }
            .isInstanceOf(KisIndexException::class.java)
    }
```

Run — 컴파일 실패를 확인한다:
```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests '*KisOverseasIndexParserTest*' --rerun-tasks --no-daemon
```

- [ ] **Step 2: 파서를 만든다**

```kotlin
/** 해외 지수 봉 하나. 저장 키·시장 상태는 수집 서비스가 정한다. */
data class OverseasIndexBar(
    val quote: IndexQuote,
    /** `output2[0].stck_bsop_date` — 시계로 유추하지 않는다 */
    val tradeDate: LocalDate,
    /** `output2[1].stck_bsop_date`. 봉이 하나뿐이면 null */
    val prevCloseDate: LocalDate?,
    /** 응답이 준 `ovrs_nmix_prdy_clpr`. 가드가 역산값과 대조한다 */
    val reportedPrevClose: BigDecimal,
    /** `hts_kor_isnm` — 설정의 `nameContains`와 대조해 코드 오선택을 잡는다 */
    val nameFromKis: String,
)
```

- `output1`·`output2`가 없거나 `output2`가 비면 `KisIndexException`
- 값은 전부 문자열이므로 `toBigDecimalOrNull()`로 파싱하고 실패 시 거부
- 날짜는 `yyyyMMdd` (`DateTimeFormatter.BASIC_ISO_DATE`)
- **부호는 `KisIndexParser`와 같은 규칙** — 절댓값을 취하고 `prdy_vrss_sign`의 방향을 곱한다.
  값이 음수인데 코드가 상승·보합이면 거부. **국내 파서의 `magnitude`/`direction`을 복제하지 말고
  꺼내 쓸 수 있으면 공유할 것** — 규칙이 갈라지면 한쪽만 고치는 날이 온다
- `price`는 `output2[0].ovrs_nmix_prpr`를 쓴다(`output1.ovrs_nmix_prpr`와 같지만,
  거래일과 값이 같은 봉에서 오는 게 일관된다)
- `prevClose`는 `price − change`로 계산한다. 응답값은 `reportedPrevClose`에 따로 담아
  **가드가 둘을 대조**하게 한다 — 여기서 응답값을 그대로 쓰면 교차검증이 무의미해진다

- [ ] **Step 3: 통과 확인 후 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/index/KisOverseasIndexParser.kt \
        allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/index/KisOverseasIndexParserTest.kt
git commit -m "feat(af-110): 해외 지수 파서 — 거래일·전일날짜를 응답에서 읽는다"
```

---

### Task 3: 가드에 전일종가 교차검증 추가

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/index/IndexGuards.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/index/IndexGuardsTest.kt`

- [ ] **Step 1: 실패 테스트를 먼저 쓴다**

```kotlin
    // 해외는 전일종가를 응답이 직접 준다. 역산값과 어긋나면 필드가 밀렸거나 소수점이 틀린 것이다.
    // 등락률 검사와 독립적이다 — 등락률이 맞아도 이게 틀릴 수 있다.
    @Test
    fun `응답이 준 전일종가가 역산값과 다르면 걸린다`() {
        val q = realQuote()   // price 6579.04, change 233.51 → 역산 6345.53

        assertThat(guards.check(q, reportedPrevClose = BigDecimal("6000.00")))
            .anyMatch { it.contains("전일종가") }
    }

    @Test
    fun `응답이 준 전일종가가 역산값과 같으면 통과한다`() {
        assertThat(guards.check(realQuote(), reportedPrevClose = BigDecimal("6345.53"))).isEmpty()
    }

    // 국내는 응답에 전일종가가 없다. null은 "출처가 안 준다"는 뜻이지 "우리가 빠뜨렸다"가 아니다.
    @Test
    fun `전일종가를 안 주면 그 검사를 건너뛴다`() {
        assertThat(guards.check(realQuote(), reportedPrevClose = null)).isEmpty()
    }
```

- [ ] **Step 2: 시그니처를 넓힌다**

```kotlin
    fun check(quote: IndexQuote, reportedPrevClose: BigDecimal? = null): List<String>
```

기본값 `null`을 두는 이유를 KDoc에 적을 것 — **국내 경로는 응답에 전일종가가 없어 null이 의미 있는 값**이고,
"넘기는 걸 잊었다"와 구분되지 않는 위험은 감수한다. 국내 호출부는 손대지 않는다.

비교는 `compareTo`로 한다. 스케일이 달라도 값이 같으면 통과해야 한다.

- [ ] **Step 3: 통과 + 전 모듈 확인 후 커밋**

```bash
cd allfolio-backend && ./gradlew test --rerun-tasks --no-daemon
```
기존 `IndexGuardsTest`가 전부 통과해야 한다 — 국내 동작은 바뀌지 않는다.

```bash
git commit -am "feat(af-110): 가드에 전일종가 교차검증 추가"
```

---

### Task 4: 수집 서비스

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/index/OverseasIndexCollectService.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/index/OverseasIndexCollectServiceTest.kt`

**먼저 읽을 것**: `IndexCollectService.kt`. 요약 data class·지수별 격리·연속 실패 카운터·
`@Transactional` 미사용 판단·삽입 경합 재시도를 **그대로 따른다.**

- [ ] **Step 1: 실패 테스트를 먼저 쓴다**

`FakeRepo`는 `MarketIndexQuoteJpaRepository by mock(...)` 위임 패턴을 쓴다(`IndexCollectServiceTest`와 동일).
`KisIndexClient`는 `@Component`라 allopen으로 열려 있어 서브클래싱 페이크가 된다.

```
- 실측 응답으로 수집하면 저장된다              (tradeDate 2026-08-13, slot CLOSE, source KIS_OVERSEAS)
- prev_close_date가 채워진다                   (2026-08-12)
- 최신 봉이 시장 현지 오늘이면 장중이다
- 최신 봉이 어제면 장마감이다
- nameContains가 응답 이름과 안 맞으면 저장하지 않고 failed로 센다
- 가드에 걸린 지수는 저장하지 않고 failed로 센다
- 한 지수가 실패해도 나머지는 저장된다
- 같은 거래일을 다시 수집하면 새 행이 아니라 값이 갱신된다
- schedule이 US면 ASIA 지수를 부르지 않는다
```

**`nameContains` 테스트가 이 파일에서 가장 중요하다.** 그게 없으면 코드 오선택이 무증상으로 저장된다.

- [ ] **Step 2: 서비스를 만든다**

```kotlin
fun collect(schedule: String, now: Instant): OverseasIndexCollectSummary
```

- `properties.overseas.filter { it.schedule == schedule }`만 처리한다
- 요청 구간은 `now`로부터 **최근 7일** — `output2[1]`이 있어야 `prevCloseDate`를 채우고,
  연휴가 끼면 직전 거래일이 며칠 전일 수 있다
- **이름 대조를 파싱 직후, 가드 앞에** 놓는다. `bar.nameFromKis.contains(cfg.nameContains)`가
  거짓이면 그 지수를 실패로 처리하고 **저장하지 않는다.** 실패 사유에 양쪽 문자열을 다 넣을 것 —
  운영자가 어느 쪽이 틀렸는지 봐야 한다
- 시장 상태: `bar.tradeDate == LocalDate.ofInstant(now, ZoneId.of(cfg.zoneId))`면 `장중`, 아니면 `장마감`.
  **거래량으로 판정하지 말 것** — `SPX`는 확정된 봉도 `acml_vol: "0"`이라 S&P가 영원히 진행 중이 된다
- 저장 키는 `(code, tradeDate, "CLOSE")`. 있으면 값만 덮는다
- 지수별 격리 + 전멸 시 502(어드민 컨트롤러에서) + 연속 실패 카운터 — 국내와 동일
- **`@Transactional`을 붙이지 않는다.** HTTP 호출 아홉 번이 한 트랜잭션에 들어가면
  Neon 커넥션을 그동안 쥐고 있게 된다

- [ ] **Step 3: 통과 + 전 모듈 확인 후 커밋**

---

### Task 5: 변이 테스트

**Files:** 임시 변형 후 되돌린다. 커밋하지 않는다.
`git diff`로는 새 파일의 되돌림을 검증할 수 없다 — 변형 전 `shasum -a 256` 스냅샷과 대조할 것.

- [ ] **변이 A** — 이름 대조를 없앤다 → `nameContains가 응답 이름과 안 맞으면…`이 깨져야 한다.
  **가장 중요한 변이다.** 살아남으면 코드 오선택이 무증상으로 저장된다
- [ ] **변이 B** — `tradeDate`를 응답이 아니라 `now`의 KST 날짜로 바꾼다 → 거래일 테스트가 깨져야 한다
- [ ] **변이 C** — `prevCloseDate`를 `output2[0]`에서 읽는다(한 칸 밀기) → 전일 날짜 테스트
- [ ] **변이 D** — 시장 상태 판정에서 `zoneId` 대신 KST를 쓴다 → 장중/장마감 테스트.
  홍콩·뉴욕이 KST와 날짜가 갈리는 시각을 픽스처로 잡아야 잡힌다
- [ ] **변이 E** — 가드의 전일종가 비교를 없앤다 → Task 3의 테스트
- [ ] **자신만의 변이 두 개 이상.** 살펴볼 곳: `schedule` 필터, 요청 구간 7일, 부호 방향,
  upsert 판정, 요약 카운터 중복 집계

**살아남은 변이가 있으면 그게 진짜 발견이다.**

---

### Task 6: 어드민·스케줄러 엔드포인트 + cron + PR

**Files:**
- Modify: `api/admin/MarketIndexAdminController.kt`, `api/scheduler/SchedulerTriggerController.kt`
- Create: `.github/workflows/collect-index-overseas.yml`

- [ ] **Step 1: 엔드포인트**

`POST /api/admin/market-index/collect-overseas?schedule=US` — `schedule` 기본값 없음.
전멸(`requested > 0 && collected == 0`)이면 502, 설정 없음(`requested == 0`)이면 500 —
국내와 같은 규약이다.

`POST /api/internal/scheduler/index/overseas?schedule=US` — 기존 `authorize(token)` 재사용,
어드민 컨트롤러에 위임. **패턴을 "정리"하지 말 것.**

- [ ] **Step 2: 새 워크플로 파일**

`collect-index.yml`을 **템플릿으로 복사해 고친다.** 그 파일에는 값비싸게 얻은 것들이 들어 있다 —
`|| HTTP_CODE="000"`(전송 실패 시 `set -e`가 요약 전에 죽는 문제), `--max-time`이 시도당 상한이라
최악 9분 < `timeout-minutes: 12`, BACKEND_URL 슬래시·https 검사, 토큰을 절대 안 찍는 요약,
부분 실패를 `python3`로 파싱하는 `::warning::`. **하나도 빼지 말 것.**

바뀌는 곳만:

```
on.schedule:
  - cron: "30 21 * * 1-5"   # KST 익일 06:30 — 미국·유럽 (US)
  - cron: "30 8  * * 1-5"   # KST 17:30      — 아시아   (ASIA)
workflow_dispatch.inputs.schedule: choice, options: [US, ASIA], required: true, default 없음
concurrency.group: collect-index-overseas
case "${EVENT_SCHEDULE}" 의 두 분기 → SCHEDULE=US / SCHEDULE=ASIA, *)는 그대로 실패
URL: /api/internal/scheduler/index/overseas?schedule=${SCHEDULE}
```

**`1-5`는 UTC 요일이다.** 금요일 21:30 UTC는 토요일 06:30 KST에 떨어지지만 잡는 것은 금요일
미국 종가다 — 한국 요일에 맞추려고 `2-6`으로 "고치면" 금요일 종가를 통째로 놓치고 토요일엔
빈 응답을 받는다.

에러 범례는 국내와 다르게 쓸 것: 500 = `market-index.overseas` 설정 없음,
502 = KIS 응답 이상 **또는 전 지수 이름 불일치**, 503 = 토큰 미설정, 000 = 백엔드 무응답.

- [ ] **Step 3: 전 모듈 검증 + PR**

```bash
cd allfolio-backend && ./gradlew test --rerun-tasks --no-daemon
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/collect-index-overseas.yml')); print('YAML OK')"
```

cron→KST 매핑을 눈이 아니라 계산으로 확인할 것.

PR 본문에 담을 것: Twelve Data가 필요 없어진 경위, 실측 3종으로 확정한 것,
**미확인 6종은 배포 후 `hts_kor_isnm` 대조가 필요하다는 점**.

---

## 완료 후 보고할 것

- PR 링크
- Task 5 변이 결과 표 (살아남은 것이 있는지)
- **사용자 조치**: 배포 후 `Collect Index`를 `schedule=US`·`ASIA`로 한 번씩 수동 실행.
  요약의 `failures`에 **이름 불일치가 있는지**가 미확인 6종의 코드 검증이다 —
  자동 검사가 걸리면 그 지수만 `failed`로 나오고 나머지는 저장된다
