# 원자재 시세 탭 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 시장 화면에 「원자재」 탭을 더해 에너지 3종(일간)·금(D+1)·월간 지표 13종을 보여준다.

**Architecture:** 수집은 기존 `RateSource`/`RateCollectService` 패턴을 그대로 복제한다 — 가져오기만 소스별이고 저장은 공용. FRED는 기존 클라이언트를 값 정책 인자를 받도록 일반화해 재사용하고, 금은 이미 붙어 있는 공공데이터포털 인증·베이스를 재사용한다. 화면은 신선도가 다른 층을 섹션으로 가른다.

**Tech Stack:** Kotlin / Spring Boot / JUnit 5 + Mockito / Next.js App Router / GitHub Actions

**설계 문서:** `docs/superpowers/specs/2026-08-16-commodity-quotes-design.md`

---

## 사전 필독 — 이걸 모르면 조용히 틀린다

**1. `PERCENT` 정책이 원자재를 죽인다.** `FredApiClient.fetch()`의 마지막 줄이 `parser.parse(body, RateValuePolicy.PERCENT)`이고 `PERCENT`는 `|value| ≤ 100`을 요구한다. 구리(~9,000)·금(~150,000)·종합지수(~180)가 전부 파싱 단계에서 버려진다. **WTI(~70)는 우연히 통과한다** — 그래서 WTI만으로 테스트하면 이 문제를 못 본다.

**2. 상한을 없애면 단위 오인을 못 잡는다.** `PRICE`는 0 초과만 본다. 소스가 USD/MT를 USD/kg로 바꿔도 여전히 양수다. `RateValuePolicy.PERCENT`의 KDoc이 *"반대 방향 단위 오인은 구조적으로 못 잡는다"*고 적은 것과 같다. **그래서 Task 1의 마지막 단계가 FRED 16종을 한 번씩 호출해 눈으로 대조하는 것이다.**

**3. FRED 인증키가 쿼리 파라미터에 실린다.** `FredApiClient`가 URL 로깅 금지·`cause` 금지·본문 미리보기 금지 세 가지로 방어한다. 그 파일 주석이 왜 그런지 길게 적어 뒀다. **일반화하면서 그 방어를 건드리지 말 것.**

**4. `0`과 `null`은 다르다.** AF-104가 `change`를 truthy로 검사해 "무변동(0)"이 "직전 값 없음(대시)"으로 표시된 사고를 겪었다. `prev_close`가 없으면 `change_*`는 `null`이고, 변동이 없으면 `0`이다.

**5. 응답 필드를 추측하지 말 것.** AF-101이 등락률 단위·부호 규약을 맞힌 이유는 *"파서를 쓰기 전에 원본 응답 한 건을 눈으로 봤기 때문"*이다. 금시세 오퍼레이션은 **원본을 보고 나서** 파서를 쓴다(Task 4).

**6. 로컬에 FRED 키가 있다.** `.env`의 `FRED_API_KEY`. **값을 로그·커밋·PR에 남기지 말 것.**

---

## 파일 구조

| 파일 | 책임 | 태스크 |
|---|---|---|
| `backend-app/.../fx/RateValuePolicy.kt` | `PRICE` 추가 | 1 |
| `backend-app/.../market/rate/fred/FredApiClient.kt` | 정책을 인자로 | 1 |
| `docs/superpowers/migrations/2026-08-16-market-commodity-quote.sql` | 테이블 | 2 |
| `backend-app/.../market/commodity/CommoditySource.kt` | 포트 (신규) | 3 |
| `backend-app/.../market/commodity/CommodityProperties.kt` | 설정 바인딩 | 3 |
| `backend-app/.../market/commodity/fred/FredCommoditySource.kt` | 에너지·월간 | 3 |
| `backend-app/.../market/commodity/fsc/FscCommoditySource.kt` | 금 | 4 |
| `backend-app/.../market/commodity/CommodityCollectService.kt` | 공용 저장·요약 | 5 |
| `backend-app/.../api/admin/CommodityAdminController.kt` | 수집·진단 | 5 |
| `backend-app/.../api/scheduler/SchedulerTriggerController.kt` | 크론 트리거 | 5 |
| `backend-app/.../market/query/MarketSnapshot.kt` · `MarketQueryService.kt` | 조회에 편입 | 6 |
| `frontend/.../types/market.ts` · `lib/market-labels.ts` · `components/market/CommodityPanel.tsx` · `app/unified/market/page.tsx` | 탭 | 7 |
| `.github/workflows/collect-commodity.yml` | 크론 | 8 |

---

## Task 1: 값 정책 일반화

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/RateValuePolicy.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/rate/fred/FredApiClient.kt`
- Modify: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/rate/fred/` (기존 테스트)

- [ ] **Step 1: `PRICE` 정책을 더한다**

`RateValuePolicy.kt`의 `PERCENT` 아래에 추가:

```kotlin
    /**
     * 시세(원자재 등) — **상한을 걸지 않는다.** 구리 ~9,000 USD/MT · 금 ~150,000 KRW/g ·
     * 종합지수 ~180이 전부 정상값이라 어떤 상한도 진짜 값을 자른다.
     *
     * **[PERCENT]를 쓰면 안 된다.** `|value| <= 100`이라 위 셋이 전부 버려지고,
     * WTI(~70)만 우연히 통과했다가 유가가 100달러를 넘는 날 조용히 사라진다.
     *
     * **[POSITIVE]를 재사용하지 않는 이유**: 술어는 같지만 뜻이 다르다. POSITIVE의 KDoc은
     * "0원짜리 환율은 없다"는 환율 도메인의 진술이고, 그쪽 판단이 바뀌면 원자재가 따라 움직인다.
     *
     * **못 잡는 것**: 반대 방향 단위 오인. USD/MT를 USD/kg로 주면 값이 1000분의 1이 되는데
     * 그것도 양수다. [PERCENT]의 KDoc이 적은 것과 같은 한계이고, 시리즈를 확정할 때
     * 눈으로 한 번 보는 것이 유일한 방어다.
     */
    PRICE {
        override fun accepts(value: BigDecimal): Boolean = value > BigDecimal.ZERO
    },
```

- [ ] **Step 2: `FredApiClient.fetch`가 정책을 받게 한다**

시그니처를 바꾸고 기본값을 준다 — 기존 호출자(금리)가 안 깨지고, 새 호출자는 명시한다:

```kotlin
    fun fetch(
        seriesId: String,
        from: LocalDate,
        to: LocalDate,
        valuePolicy: RateValuePolicy = RateValuePolicy.PERCENT,
    ): RateFetch {
```

마지막 줄의 `parser.parse(body, RateValuePolicy.PERCENT)`를 `parser.parse(body, valuePolicy)`로 바꾼다.

> **기본값을 `PERCENT`로 두는 것은 의도다.** 이 클라이언트는 금리용으로 태어났고 기존 호출자가 전부 금리다. 기본을 `PRICE`로 두면 금리 쪽 단위 오인 방어가 조용히 사라진다.

**HTTP 호출부·예외 매핑·로깅은 한 글자도 건드리지 말 것.** 키 유출 방어가 거기 있다.

- [ ] **Step 3: 정책이 실제로 갈리는지 테스트**

`allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/RateValuePolicyPriceTest.kt`:

```kotlin
package com.allfolio.fx

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * PRICE가 상한을 안 걸고 PERCENT가 거는지 못 박는다.
 *
 * **WTI(~70)로만 검증하면 안 된다** — 100 이하라 PERCENT로도 통과해서
 * 정책이 바뀐 걸 못 본다. 그래서 100을 넘는 값이 반드시 들어간다.
 */
class RateValuePolicyPriceTest {

    private fun bd(v: String) = BigDecimal(v)

    @Test
    fun `PRICE는 상한이 없다 - 구리 금 지수가 다 통과한다`() {
        assertTrue(RateValuePolicy.PRICE.accepts(bd("9000")))     // 구리 USD/MT
        assertTrue(RateValuePolicy.PRICE.accepts(bd("150000")))   // 금 KRW/g
        assertTrue(RateValuePolicy.PRICE.accepts(bd("180.5")))    // 종합지수
        assertTrue(RateValuePolicy.PRICE.accepts(bd("70.25")))    // WTI
    }

    @Test
    fun `PRICE는 0 이하를 거른다`() {
        assertFalse(RateValuePolicy.PRICE.accepts(BigDecimal.ZERO))
        assertFalse(RateValuePolicy.PRICE.accepts(bd("-1")))
    }

    @Test
    fun `PERCENT였다면 구리 금 지수가 버려진다 - 이게 정책을 나눈 이유다`() {
        assertFalse(RateValuePolicy.PERCENT.accepts(bd("9000")))
        assertFalse(RateValuePolicy.PERCENT.accepts(bd("150000")))
        assertFalse(RateValuePolicy.PERCENT.accepts(bd("180.5")))
        // WTI는 통과한다 — 우연이고, 유가가 100을 넘으면 깨진다
        assertTrue(RateValuePolicy.PERCENT.accepts(bd("70.25")))
        assertFalse(RateValuePolicy.PERCENT.accepts(bd("101")))
    }
}
```

- [ ] **Step 4: 기존 금리 경로가 안 바뀌었는지**

```bash
cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :backend-app:test --tests '*Fred*' --tests '*RateValuePolicy*' --tests '*RateCollect*' --rerun-tasks
```

Expected: 전부 PASS. **`--rerun-tasks`를 반드시 붙일 것** — 캐시된 `test`는 아무것도 안 돌리고 `BUILD SUCCESSFUL`을 낸다.

- [ ] **Step 5: 변이 테스트**

| # | 변이 | 실패해야 하는 테스트 |
|---|---|---|
| 1 | `fetch`의 기본값을 `PRICE`로 바꾼다 | 금리 쪽 단위 오인 테스트(AF-102/AF-FRED). **안 잡히면 보고할 것** — 기본값 선택이 테스트로 안 지켜진다는 뜻 |
| 2 | `PRICE.accepts`를 `value.abs() <= BigDecimal("100")`으로 | `PRICE는 상한이 없다…` |

하나씩 넣고 확인 후 원복.

- [ ] **Step 6: 실제 값과 단위를 눈으로 대조한다 — 이 태스크의 핵심**

`PRICE`가 상한을 안 걸므로 단위 오인을 코드가 못 잡는다. 16종을 한 번씩 호출해 값이 상식적인지 본다 (금은 Task 4라 여기 없다).

```bash
cd /Users/hong9/IdeaProjects/allfolio && KEY=$(grep -E "^FRED_API_KEY=" .env | cut -d= -f2- | tr -d '"' | tr -d "'" | tr -d '[:space:]') && for S in DCOILWTICO DCOILBRENTEU DHHNGSP PCOPPUSDM PNICKUSDM PZINCUSDM PALUMUSDM PIORECRUSDM PCOALAUUSDM PURANUSDM PWHEAMTUSDM PMAIZMTUSDM PSOYBUSDM PSUGAISAUSDM PCOFFOTMUSDM PALLFNFINDEXM; do echo -n "$S "; curl -s "https://api.stlouisfed.org/fred/series/observations?series_id=$S&api_key=$KEY&file_type=json&sort_order=desc&limit=1" | python3 -c "import sys,json;o=json.load(sys.stdin)['observations'][0];print(o['date'], o['value'])"; done
```

각 값이 스펙의 단위와 맞는지 확인한다(WTI ~70 USD/bbl · 구리 ~9,000 USD/MT · 설탕 ~20 US cents/lb 등). **어긋나면 스펙의 단위 표기를 고치고 보고할 것** — 화면에 잘못된 단위를 붙이면 숫자가 맞아도 거짓말이다.

**키를 출력하지 말 것.** 위 명령은 키를 변수로만 쓴다.

- [ ] **Step 7: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/RateValuePolicy.kt allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/rate/fred/FredApiClient.kt allfolio-backend/backend-app/src/test/kotlin/com/allfolio/fx/
git commit -m "feat(commodity): 값 정책에 PRICE를 더하고 FRED 클라이언트가 정책을 받게 한다

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 2: 마이그레이션

**Files:**
- Create: `docs/superpowers/migrations/2026-08-16-market-commodity-quote.sql`

- [ ] **Step 1: 마이그레이션 파일**

```sql
-- 원자재 시세 (AF-108 이후 소스 재선정). 지수와 다른 테이블인 이유는 slot과 unit이다.
--
-- slot이 없다: 세 소스(FRED/EIA 일간·FRED/IMF 월간·공공데이터포털 금) 모두 하루(또는 한 달)
-- 한 값이라 OPEN/MID/CLOSE 개념이 없다. market_index_quote를 재사용하면 slot에 CLOSE를
-- 억지로 채우게 되고, 그러면 "종가"라는 말이 원자재 행에서만 다른 뜻이 된다.
--
-- unit과 frequency를 행에 저장한다: 코드에 상수로 들고 있으면 소스가 바꾼 날 저장은
-- 멀쩡한데 화면만 조용히 틀린다. 관측과 함께 온 속성이므로 관측과 함께 남긴다.
--
-- prev_close·change_*는 nullable이다: 첫 관측이거나 직전 값이 없으면 채울 것이 없다.
-- 0(무변동)과 null(직전 값 없음)은 다르다 — AF-104가 이 구분을 놓쳐 사고를 냈다.
--
-- 월간 행의 trade_date는 그 달의 1일이다(IMF 관측일 규약 그대로). 월말로 옮기지 않는다.
--
-- id를 대리키로 쓴다: 형제 시세 표 둘(market_rate·market_index_quote)이 같은 패턴이고,
-- Task 5에서 옮겨 오는 수집 서비스가 그 리포지터리 모양에 붙어 있다. 자연키 유일성은 uk_가 진다.
-- 코드별 최신 조회도 PK가 아니라 그 uk_ btree가 받는다 (PK 선두 열은 랜덤 UUID다).

CREATE TABLE IF NOT EXISTS market_commodity_quote (
    id            UUID           NOT NULL,
    code          VARCHAR(20)    NOT NULL,
    trade_date    DATE           NOT NULL,
    price         NUMERIC(18,4)  NOT NULL,
    unit          VARCHAR(20)    NOT NULL,
    frequency     VARCHAR(1)     NOT NULL,   -- D | M
    prev_close    NUMERIC(18,4),
    change_value  NUMERIC(18,4),
    change_rate   NUMERIC(9,4),
    source        VARCHAR(20)    NOT NULL,   -- FRED | FSC. EIA/IMF 구분은 frequency가 진다
    collected_at  TIMESTAMP      NOT NULL,   -- 앱이 항상 채운다. DEFAULT를 두면 빠뜨렸을 때 조용히 틀린 값이 들어간다
    CONSTRAINT pk_market_commodity_quote PRIMARY KEY (id),
    CONSTRAINT uk_market_commodity_quote UNIQUE (code, trade_date)
);

COMMENT ON TABLE  market_commodity_quote            IS '원자재 시세 — 에너지(일간)·금(D+1)·월간 지표';
COMMENT ON COLUMN market_commodity_quote.unit       IS 'USD/bbl · USD/MMBtu · USD/MT · USD/lb · USc/lb · KRW/g · index — USD/lb와 USc/lb는 100배 다르다';
COMMENT ON COLUMN market_commodity_quote.frequency  IS 'D=일간 M=월간. 화면이 섹션을 가르는 기준';

SELECT count(*) AS existing_rows FROM market_commodity_quote;
```

- [ ] **Step 2: 커밋**

```bash
git add docs/superpowers/migrations/2026-08-16-market-commodity-quote.sql
git commit -m "feat(commodity): market_commodity_quote 마이그레이션 — slot 없이 unit·frequency를 행에 남긴다

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 3: 포트 + FRED 소스 + 설정

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/commodity/CommoditySource.kt`
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/commodity/CommodityProperties.kt`
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/commodity/fred/FredCommoditySource.kt`
- Modify: `allfolio-backend/backend-app/src/main/resources/application.yml`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/commodity/`

**먼저 `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/rate/RateSource.kt`와 `MarketRateProperties`를 읽을 것.** 이 태스크는 그 둘을 원자재로 옮긴 것이고, KDoc의 논리도 그대로 따른다.

- [ ] **Step 1: 포트**

```kotlin
package com.allfolio.market.commodity

import java.math.BigDecimal
import java.time.LocalDate

/** 하루(또는 한 달)치 시세 한 건 */
data class CommodityObservation(val quoteDate: LocalDate, val value: BigDecimal)

/** @param skipped 소스가 파싱 단계에서 버린 행 수 — 요약의 skippedRows로 그대로 나간다 */
data class CommodityFetch(val rows: List<CommodityObservation>, val skipped: Int)

/**
 * 원자재 한 소스.
 *
 * **가져오기만 소스별이고 저장하기는 공용이다** — 구간 밖 날짜 제거·0건 처리·중복 접기·
 * inserted/updated/unchanged 계수·종목별 실패 격리는 [CommodityCollectService]가 한 벌만 갖는다.
 * `RateSource`(AF-FRED)·`HistoricalRateSource`(AF-100)와 같은 판단이다.
 *
 * 단위와 주기는 관측이 아니라 **설정**에서 온다([CommodityProperties]) — 소스가 그것을
 * 응답에 싣지 않기 때문이다. 그래서 코드가 아니라 설정을 고쳐 바꾼다.
 */
interface CommoditySource {
    /** `market_commodity_quote.source`에 들어갈 값 */
    val sourceName: String

    /** 이 소스가 담당하는 canonical 코드. 설정에서 온다 */
    val codes: List<String>

    /**
     * `from..to`는 포함 범위이고 범위 밖 날짜가 섞여 와도 된다 — 서비스가 걸러낸다.
     * 실패는 예외로 알린다 — 서비스가 종목별로 잡아 요약의 failures로 옮긴다.
     */
    fun fetch(code: String, from: LocalDate, to: LocalDate): CommodityFetch
}
```

> **⚠️ 아래 스니펫의 `seriesId` 주석은 틀렸다 (2026-08-17 정정).** `FSC는 오퍼레이션 코드`라고 적혀 있지만 실제로는 **종목 단축코드(`srtnCd`, `04020000`)** 다 — 오퍼레이션 코드(`getGoldPriceInfo`)는 클라이언트가 경로에 고정으로 들고 있다. **Task 4의 Step 1 실측이 뒤집었다.** 이 자리를 남겨 두는 것은 "측정 전 추정이 무엇이었는지"의 기록이고, 구현할 때는 Task 4를 따를 것. 그대로 옮겨 적으면 yml을 `series-id: getGoldPriceInfo`로 쓰게 되고 그러면 조용히 0건이 된다.

- [ ] **Step 2: 설정 바인딩**

`MarketRateProperties`를 열어 `@ConfigurationProperties` 바인딩 방식과 `allCodes` 관례를 확인하고 같은 모양으로 만든다.

```kotlin
package com.allfolio.market.commodity

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/** 원자재 한 종목의 수집 설정 */
class CommodityItem {
    lateinit var code: String
    lateinit var seriesId: String   // FRED series_id. FSC는 오퍼레이션 코드  ← ⚠️ 틀렸다, Task 4 참조
    lateinit var unit: String       // USD/bbl · USD/MT · KRW/g · index
    lateinit var frequency: String  // D | M
}

/**
 * **코드 목록은 [allCodes] 하나만 본다.** 수집과 조회가 각자 `fredDaily + fredMonthly + fsc`를
 * 더하면 소스가 넷이 되는 날 한쪽만 고쳐지고, 증상은 "수집은 되는데 화면에 없다"이다 —
 * 오류도 로그도 없다. AF-FRED가 정확히 이 실수를 했다.
 */
@Component
@ConfigurationProperties(prefix = "market-commodity")
class CommodityProperties {
    var fredDaily: List<CommodityItem> = emptyList()
    var fredMonthly: List<CommodityItem> = emptyList()
    var fsc: List<CommodityItem> = emptyList()

    val allItems: List<CommodityItem> get() = fredDaily + fredMonthly + fsc
    val allCodes: List<String> get() = allItems.map { it.code }
}
```

- [ ] **Step 3: FRED 소스**

```kotlin
package com.allfolio.market.commodity.fred

import com.allfolio.fx.RateValuePolicy
import com.allfolio.market.commodity.CommodityFetch
import com.allfolio.market.commodity.CommodityObservation
import com.allfolio.market.commodity.CommodityProperties
import com.allfolio.market.commodity.CommoditySource
import com.allfolio.market.rate.fred.FredApiClient
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * FRED 원자재 소스 — 일간 에너지(EIA)와 월간 지표(IMF)를 모두 담당한다.
 *
 * **[RateValuePolicy.PRICE]를 명시한다.** 클라이언트 기본값은 PERCENT이고, 그대로 두면
 * 구리·금·지수가 파싱 단계에서 버려진다(WTI만 우연히 통과한다).
 *
 * **`sourceName`은 "FRED" 하나다.** 실제 발행처는 EIA(일간)와 IMF(월간)로 갈리고 신선도도
 * 영업일 3일 대 두 달로 완전히 다르지만, 그 구분은 `frequency`(D|M)가 이미 진다 —
 * `(frequency, source)` 짝이 EIA(D,FRED)·IMF(M,FRED)·금(D,FSC)을 그대로 가른다.
 * `sourceName`을 항목별로 쪼개려면 포트의 `val sourceName` 모양을 바꿔야 하는데,
 * 이미 있는 열로 갈리는 것을 위해 치를 값이 아니다. `FredRateSource`도 "FRED" 하나다.
 */
@Component
class FredCommoditySource(
    private val client: FredApiClient,
    private val properties: CommodityProperties,
) : CommoditySource {

    override val sourceName = "FRED"

    override val codes: List<String>
        get() = (properties.fredDaily + properties.fredMonthly).map { it.code }

    override fun fetch(code: String, from: LocalDate, to: LocalDate): CommodityFetch {
        val item = (properties.fredDaily + properties.fredMonthly).firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("FRED 설정에 없는 원자재 코드입니다: $code")
        val fetched = client.fetch(item.seriesId, from, to, RateValuePolicy.PRICE)
        return CommodityFetch(
            rows = fetched.rows.map { CommodityObservation(it.quoteDate, it.value) },
            skipped = fetched.skipped,
        )
    }
}
```

> `RateFetch`를 `CommodityFetch`로 옮겨 담는 것이 낭비처럼 보이지만, **이 경계가 "금리 타입이 원자재 코드로 새지 않게" 막는다.** 스펙이 감수하기로 한 이름 문제를 여기서 한 번에 가둔다.

- [ ] **Step 4: 설정에 16종을 적는다**

`application.yml`의 `market-rate` 블록 아래에 추가한다. **시리즈 ID는 전부 스펙 §3의 확인된 값이다 — 추측해서 바꾸지 말 것.**

```yaml
# 원자재 시세. 신선도가 층마다 다르다 — 화면이 섹션을 가르는 근거가 frequency다.
# 일간(EIA)은 영업일 3일, 월간(IMF)은 두 달 지연이다(2026-08-16 실측).
#
# 단위를 여기 적는 이유: FRED 응답에 단위가 없다. 코드에 상수로 두면 소스가 바꾼 날
# 저장은 멀쩡한데 화면만 틀린다. 값 정책(PRICE)이 상한을 안 걸어 자동으로는 못 잡는다.
market-commodity:
  fred-daily:
    - { code: WTI,    series-id: DCOILWTICO,   unit: "USD/bbl",   frequency: D }
    - { code: BRENT,  series-id: DCOILBRENTEU, unit: "USD/bbl",   frequency: D }
    - { code: NATGAS, series-id: DHHNGSP,      unit: "USD/MMBtu", frequency: D }
  fred-monthly:
    - { code: COPPER,    series-id: PCOPPUSDM,     unit: "USD/MT",   frequency: M }
    - { code: NICKEL,    series-id: PNICKUSDM,     unit: "USD/MT",   frequency: M }
    - { code: ZINC,      series-id: PZINCUSDM,     unit: "USD/MT",   frequency: M }
    - { code: ALUMINUM,  series-id: PALUMUSDM,     unit: "USD/MT",   frequency: M }
    - { code: IRON_ORE,  series-id: PIORECRUSDM,   unit: "USD/MT",   frequency: M }
    - { code: COAL_AU,   series-id: PCOALAUUSDM,   unit: "USD/MT",   frequency: M }
    - { code: URANIUM,   series-id: PURANUSDM,     unit: "USD/lb",   frequency: M }
    - { code: WHEAT,     series-id: PWHEAMTUSDM,   unit: "USD/MT",   frequency: M }
    - { code: CORN,      series-id: PMAIZMTUSDM,   unit: "USD/MT",   frequency: M }
    - { code: SOYBEANS,  series-id: PSOYBUSDM,     unit: "USD/MT",   frequency: M }
    - { code: SUGAR,     series-id: PSUGAISAUSDM,  unit: "USc/lb",   frequency: M }
    - { code: COFFEE,    series-id: PCOFFOTMUSDM,  unit: "USc/lb",   frequency: M }
    - { code: ALL_INDEX, series-id: PALLFNFINDEXM, unit: "index",    frequency: M }
  fsc: []   # 금은 Task 4에서 채운다
```

- [ ] **Step 5: 설정이 실제로 바인딩되는지 테스트**

`CommodityPropertiesYamlTest.kt`. `MarketRatePropertiesYamlTest`를 열어 같은 방식으로 쓴다(그 파일이 시리즈 ID 넷을 못박는 그 테스트다).

담을 것:
1. `fredDaily` 3건, `fredMonthly` 13건이 바인딩된다
2. **시리즈 ID가 스펙과 정확히 일치한다** — 16개를 전부 단언한다. 오타 하나가 조용히 0건 수집이 된다
3. `allCodes`가 16개이고 중복이 없다
4. 모든 항목의 `unit`·`frequency`가 비어 있지 않다

- [ ] **Step 6: 테스트 실행**

```bash
cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :backend-app:test --tests '*Commodity*' --rerun-tasks
```

- [ ] **Step 7: 변이 테스트**

`FredCommoditySource.fetch`의 `RateValuePolicy.PRICE`를 지워 기본값(PERCENT)이 되게 한다 → **구리·금 값이 버려지는지** 확인하는 테스트가 필요하다. 페이크 `FredApiClient`로 9,000짜리 관측을 주고 결과가 비는지 본다. 없으면 그 테스트를 만들고 확인 후 원복.

- [ ] **Step 8: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/commodity/ allfolio-backend/backend-app/src/main/resources/application.yml allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/commodity/
git commit -m "feat(commodity): CommoditySource 포트 + FRED 소스 16종

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 4: 금 (공공데이터포털) — 원본을 보고 나서 파서를 쓴다

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/commodity/fsc/FscCommoditySource.kt`
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/commodity/fsc/FscCommodityClient.kt`
- Modify: `application.yml`의 `market-commodity.fsc`

- [ ] **Step 1: 원본 응답을 눈으로 본다 — 파서보다 먼저**

**이 단계를 건너뛰지 말 것.** AF-101이 등락률의 단위와 부호 규약을 맞힌 이유는 응답을 먼저 봤기 때문이다. 추측으로 필드를 고르면 그 추측 위에 테스트까지 쌓여 틀린 값이 그럴듯하게 굳는다.

`.env`의 `FSC_API_KEY`를 써서 금시세 오퍼레이션을 한 번 호출한다. 엔드포인트 경로·파라미터 이름은 **공공데이터포털 문서에서 확인할 것**(`https://www.data.go.kr/data/15094805/openapi.do`). `FscStockClient`가 쓰는 베이스는 `https://apis.data.go.kr/1160100/service`이고 인증 파라미터는 `serviceKey`, 결과 형식은 `resultType=json`이다.

확인해서 보고할 것:
- 오퍼레이션 경로와 필수 파라미터
- 날짜 필드 이름과 형식(`basDt`가 `yyyyMMdd`인지)
- 가격 필드 이름(종가가 `clpr`인지)과 **단위가 원/g이 맞는지**
- 전일대비·등락률 필드가 응답에 있는지 (있으면 우리가 계산하지 않는다)
- 상품이 여러 개 상장돼 있는지(금 99.99% 1kg / 100g 등) — **어느 종목을 쓸지 정해야 한다**

> **✅ Step 1 완료 (2026-08-17 실측).** 아래는 추측이 아니라 실제 응답에서 읽은 것이다.
> 이 태스크를 열어 두었던 이유는 *"모르는 것을 아는 척하지 않는다"*였고, 이제 안다.

### Step 1 결과 — 확정 사실

**경로**: `GET {BASE}/GetGeneralProductInfoService/getGoldPriceInfo`
**파라미터**: `serviceKey` · `resultType=json` · `numOfRows` · `pageNo` · `beginBasDt` · `endBasDt`(둘 다 `yyyyMMdd`, 범위 조회 동작)

**응답 필드 12개** (한 행):
`basDt` `srtnCd` `isinCd` `itmsNm` `clpr` `vs` `fltRt` `mkp` `hipr` `lopr` `trqu` `trPrc`

| 쓸 필드 | 뜻 | 실측 예 |
|---|---|---|
| `basDt` | 기준일 `yyyyMMdd` | `20260813` |
| `clpr` | **종가 (원/g)** | `200570` |
| `vs` | 전일대비 (원/g, 부호 있음) | `100` · `-380` |
| `fltRt` | 등락률 (%, 부호 있음) | `.05` · `-.19` · `1.4` |

**단위가 원/g인 근거 — 추론이 아니라 교차 검증이다.** 같은 날 `금 99.99_1kg`과 `미니금 99.99_100g`의 `clpr`이 **거의 같다**(200,570 vs 200,240, 비율 1.0016). 계약당 가격이면 1kg이 100g의 10배여야 한다. 그램당이므로 같은 것이다.

**종목은 둘. `04020000`(금 99.99_1kg)을 쓴다.**

| srtnCd | itmsNm | isinCd | 9일 누적 거래대금 |
|---|---|---|---|
| `04020000` | 금 99.99_1kg | `KRD040200002` | **330,570,930,170** |
| `04020100` | 미니금 99.99_100g | `KRD040201000` | 33,675,800,210 |

거래대금이 **10배** 차이다. 유동성이 큰 쪽이 대표 시세다.

**신선도**: 2026-08-17(월) 시점 최신 관측이 **2026-08-13**. 8/14(금)이 없다 — 휴장인지 공표 지연인지 **한 시점만 봐서는 확정 못 한다.** 그래서 창을 D+1에 맞추지 않고 **일간 기본 창(14일)** 을 그대로 쓴다.

---

- [ ] **Step 2: 클라이언트** — `.../market/commodity/fsc/FscCommodityClient.kt`

`unified-asset/.../infrastructure/adapter/FscStockClient.kt`를 **먼저 읽고** 인증·베이스·에러 처리 관례를 그대로 따를 것. 특히 `isConfigured()`로 키 미설정을 다루는 방식.

**🔴 인증키가 쿼리 파라미터에 실린다.** `FredApiClient`와 같은 방어 셋을 지킬 것 — 전체 URL 로깅 금지 · 예외에 `cause` 금지 · 응답 본문 미리보기 금지.

**`fltRt`는 앞의 0이 없다**(`.05`, `-.19`). `BigDecimal(".05")`은 유효하지만, 정규식 검증을 넣는다면 그 형태를 반드시 포함할 것. **이 형식을 테스트에 고정한다.**

**응답이 두 겹이다**: `response.body.items.item[]`. `totalCount=0`이면 `items`가 빈 문자열 `""`로 오는 경우가 공공데이터포털에 흔하므로, 배열이 아닐 때 빈 목록으로 처리하고 예외로 죽지 말 것.

- [ ] **Step 3: 소스** — `.../market/commodity/fsc/FscCommoditySource.kt`

`FredCommoditySource`와 **같은 모양**(`CommoditySource` 구현). `sourceName = "FSC"`.

- `properties.fsc`에서 코드를 찾는다. 없으면 `IllegalArgumentException`(FRED 쪽과 같은 문구 틀)
- `CommodityItem.seriesId`에 **`srtnCd`(`04020000`)** 를 담는다 — FRED의 series-id 자리를 종목코드로 쓴다
- 응답에서 **그 `srtnCd` 행만** 고른다. 두 종목이 함께 오므로 **안 거르면 같은 날짜에 값이 둘**이 되고, 수집 서비스의 `deduped[date] = value`가 뒤에 온 것으로 덮어써 **미니금 값이 조용히 들어간다**
- 구간 밖 날짜 필터링은 하지 않는다 — 서비스가 한다

- [ ] **Step 4: 설정** — `application.yml`의 `market-commodity.fsc`

```yaml
  fsc:
    - { code: GOLD_KRX, series-id: "04020000", unit: "KRW/g", frequency: D }
```

`series-id`를 **따옴표로 감쌀 것** — `04020000`을 YAML이 숫자로 읽으면 앞의 0이 날아가 `4020000`이 된다. **그러면 종목이 안 맞아 조용히 0건이 된다.**

- [ ] **Step 5: `@PostConstruct validate()`** — `CommodityProperties`

Task 3 리뷰가 *"fsc 항목이 생기는 Task 4에서는 반드시 넣어야 한다"*고 판정한 것이다. `MarketRateProperties.validate()`를 읽고 같은 방식으로:
- `code`·`seriesId`·`unit` 공백 검사
- **`frequency !in ("D","M")`** — DB가 `VARCHAR(1)`이라 `Daily` 같은 오타는 CI 초록인데 운영 insert에서 터진다
- `allCodes` 중복 검사

- [ ] **Step 6: 테스트**

1. `srtnCd`가 다른 행(미니금)을 **걸러낸다** — 두 종목이 섞인 응답을 주고 1kg 값만 나오는지
2. `fltRt`의 `.05`·`-.19` 형식이 파싱된다
3. `basDt` → `LocalDate` 변환 (`yyyyMMdd`)
4. `items`가 배열이 아닐 때(0건) 빈 목록을 준다
5. 설정에 없는 코드는 예외
6. `validate()`가 빈 code·중복 code·`frequency="Daily"`를 각각 잡는다
7. YAML 바인딩에서 `series-id`가 **문자열 `"04020000"`** 으로 들어온다 (앞의 0 보존)

**변이**: ① `srtnCd` 필터를 지운다 → 1번 실패 ② `series-id`의 따옴표를 뗀다 → 7번 실패 ③ `validate()`의 frequency 검사를 뺀다 → 6번 실패

- [ ] **Step 7: 커밋 + PR + 라이브 검증**

배포 후 수집을 한 번 돌리고 **값을 눈으로 대조**: 금 종가가 **20만 원/g대**여야 한다(2026-08-13 실측 200,570). 5만이면 원/돈을 잘못 읽은 것이고, 2억이면 원/kg이다.

---

## Task 5: 수집 서비스 + 트리거

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/commodity/CommodityCollectService.kt`
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/api/admin/CommodityAdminController.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/api/scheduler/SchedulerTriggerController.kt`

**`RateCollectService`를 읽고 그대로 옮길 것.** AF-102가 ECOS를 겪으며 만든 방어 일곱 개가 거기 있고, 소스와 무관하게 옳다.

> **영속화는 형제 시세 표 둘과 같은 모양으로 간다** — `MarketCommodityQuoteEntity(@Id val id: UUID, …)` + `uk_market_commodity_quote(code, trade_date)` 유니크 제약. `MarketRateEntity`·`MarketIndexQuoteEntity`가 둘 다 이 패턴이고, `RateCollectService`의 upsert가 그 리포지터리 모양에 붙어 있다.
>
> 설계 초안은 `(code, trade_date)` 복합 자연키였다. DB만 놓고 보면 그쪽이 낫지만, **형제 표 둘과 어긋나면 베껴 오기로 한 서비스가 안 붙는다** — 방어 일곱 개와 그 테스트 템플릿을 통째로 다시 써야 한다. 유니크 제약이 같은 보장을 주므로 치를 값이 아니다. (`nav_currency_daily`가 복합 PK + JdbcTemplate인 것은 사실이나 그건 NAV 표이고 모듈도 다르다 — 시세 표의 선례가 아니다.)

- [ ] **Step 1: 수집 서비스**

`RateCollectService`의 구조를 따른다:
- `sources: List<CommoditySource>`, `store` 주입
- 구간 밖 날짜 제거 · 0건(emptySeries) 집계 · 중복 접기 · 종목별 `runCatching` 격리
- **`updated`를 `saveAll` 전에 세지 말 것** — AF-102가 그 버그를 겪었다(전량 쓰기 실패인데 `collected=60`에 200을 냈다). 누산기로 모으고 저장 뒤에 센다
- 요약 타입 `CommodityCollectSummary`는 `RateCollectSummary`와 같은 필드 구성

**`prev_close`·`change_*`는 서비스가 계산한다** — 직전 거래일 행을 읽어 채우고, 없으면 `null`로 둔다. `0`으로 채우지 말 것.

**`unit`·`frequency`는 설정에서 가져와 행에 넣는다.**

- [ ] **Step 2: 어드민 컨트롤러**

`MarketRateAdminController`를 따른다. 수집 트리거 + 진단 조회. **날짜 기본값은 KST 오늘** — 컨테이너가 UTC라 `LocalDate.now()`를 그냥 쓰면 새벽에 하루 밀린다(이 저장소에 그 경고 주석이 세 군데 있다).

- [ ] **Step 3: 스케줄러 트리거**

`SchedulerTriggerController`에 `POST /api/internal/scheduler/commodity`를 더한다. 기존 트리거들과 **같은 토큰 인증**(`authorize(token)`)을 쓰고, **어드민 컨트롤러에 위임**한다 — 그 파일 KDoc이 *"이 위임을 정리하지 말 것"*이라고 못박아 뒀다.

**날짜를 노출하지 않는다.** 서버가 KST 오늘로 정한다.

- [ ] **Step 4~6**: 테스트(요약 계수·실패 격리·prev_close null 처리) → 변이 → 커밋

각 단계의 상세는 `RateCollectServiceTest`를 템플릿으로 삼는다.

---

## Task 6: 조회 API

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/query/MarketSnapshot.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/query/MarketQueryService.kt`
- Modify: `application.yml`, `render.yaml`

- [ ] **Step 1: 응답에 필드를 더한다**

`MarketSnapshot`에 `commodities: List<CommodityQuoteView>?`를 더한다. **`null` = 플래그로 꺼짐, `[]` = 켜져 있으나 데이터 없음** — 그 파일 KDoc이 세 구간의 서로 다른 관례를 이미 설명하고 있으니 같은 규약을 따르고 KDoc에 원자재 줄을 추가한다.

`CommodityQuoteView`에 **`unit`과 `frequency`를 싣는다** — 화면이 섹션을 가르고 단위를 붙이는 근거다.

- [ ] **Step 2: 플래그**

`market.commodities-enabled: ${MARKET_COMMODITIES_ENABLED:true}`.

`render.yaml`에는 **`value:`가 아니라 `sync: false`** — blueprint에 값을 적으면 대시보드에서 끈 것이 다음 sync 때 조용히 되살아난다. `MARKET_INDICES_ENABLED` 옆 주석이 그 이유를 길게 적어 뒀다.

- [ ] **Step 3~5**: 조회 서비스 배선 → 테스트(플래그 off면 `null`) → 커밋

---

## Task 7: 화면

**Files:**
- Modify: `frontend/allfolio_app/types/market.ts`
- Modify: `frontend/allfolio_app/lib/market-labels.ts`
- Create: `frontend/allfolio_app/components/market/CommodityPanel.tsx`
- Modify: `frontend/allfolio_app/app/unified/market/page.tsx`

- [ ] **Step 1: 타입**

```ts
export interface CommodityQuoteView {
  code: string
  tradeDate: string
  /** percent/number — 백엔드가 JSON 숫자로 보낸다. string으로 선언하지 말 것 */
  price: number
  unit: string
  frequency: 'D' | 'M'
  changeValue: number | null
  changeRate: number | null
}
```

**AF-104의 교훈**: 타입을 `string`으로 선언했는데 백엔드가 JSON 숫자를 보내 스케일이 날아갔고, 빌드도 리뷰도 통과했으며 **화면을 봐야만 보였다.**

- [ ] **Step 2: 라벨**

`market-labels.ts`에 16종 한글 라벨을 더한다(WTI 원유 · 브렌트유 · 천연가스 · 구리 · 니켈 · 아연 · 알루미늄 · 철광석 · 석탄(호주) · 우라늄 · 밀 · 옥수수 · 대두 · 설탕 · 커피 · 원자재 종합지수).

- [ ] **Step 3: 패널**

`FxPanel`·`RatePanel`을 열어 그 구조를 따른다. **두 섹션으로 가른다**:
- 위 「시세」 — `frequency === 'D'` + 금
- 아래 「월간 지표」 — `frequency === 'M'`, 섹션 머리에 *"국제기구 월평균이라 두 달가량 늦습니다"*

**`changeValue &&`로 가르지 말 것** — `0`이 falsy라 "무변동"이 "직전 값 없음"으로 둔갑한다. `!= null`을 쓴다.

자릿수는 `lib/market-format.ts`의 `fixed()`를 쓴다. 새 포맷터를 만들지 않는다.

**각주 두 줄을 넣는다**(스펙 §6). 없애지 말 것.

- [ ] **Step 4: 탭 등록**

`page.tsx`에 다섯 번째 탭. **`commodities ?? []`를 쓰지 말 것** — `null`(플래그 off)과 `[]`(데이터 없음)이 다르다.

- [ ] **Step 5: 검증**

```bash
cd /Users/hong9/IdeaProjects/allfolio/frontend/allfolio_app && npx tsc --noEmit && npm run build
```

이 저장소엔 프런트 테스트 러너가 없다 — 타입 체크 + 빌드 + 브라우저가 검증 수단이다. `tsconfig`에 `target`이 없어 ES5로 떨어지므로 `[...new Set()]`을 쓰지 말 것(TS2802).

---

## Task 8: 크론 + PR + 배포 검증

- [ ] **Step 1: 크론 워크플로**

`.github/workflows/collect-commodity.yml`. **`collect-rate.yml`을 베낀다** — 재시도 예산(최악 9분)·`timeout-minutes: 12`·URL 검증·`concurrency` 규약을 숫자까지 그대로.

```yaml
- cron: "20 9 * * 1-5"   # UTC 09:20 = KST 18:20 (평일)
```

**요일 필터가 성립하는 조건은 "UTC 15:00 이전"이다** — `collect-rate.yml`이 그 함정을 설명해 뒀다. 09:20은 여유가 크다.

실패 안내에 **`500 = 대개 market_commodity_quote 테이블 부재`**와 마이그레이션 경로를 적는다.

- [ ] **Step 2: PR**

본문에 반드시:
- **마이그레이션 필수**: `docs/superpowers/migrations/2026-08-16-market-commodity-quote.sql`
- **`FRED_API_KEY`·`FSC_API_KEY`는 이미 있다** — 새 시크릿 없음
- **지연을 숨기지 않는다**: 일간 영업일 3일, 월간 두 달
- **은·백금은 못 싣는다** — 라이선스

- [ ] **Step 3: 배포 후 검증**

```sql
SELECT frequency, source, count(*), min(trade_date), max(trade_date)
FROM market_commodity_quote GROUP BY frequency, source ORDER BY 1,2;
```

일간 3종 · 월간 13종이 각각 쌓였는지 (금 1종은 Task 4가 풀린 뒤). 그리고 **값이 상식적인지 눈으로** — Task 1 Step 6에서 본 값과 같은 자리수인지 확인한다. 단위 오인은 코드가 못 잡는다.

- [ ] **Step 4: 화면**

`/unified/market?tab=commodities`에서 두 섹션이 갈려 보이는지, 기준일이 항목별로 찍히는지, `0`이 대시로 안 새는지, 모바일 390px에서 표가 가로 스크롤로 들어가는지.

---

## 완료 기준

- [ ] FRED 16종이 각자 주기대로 쌓인다 (금 1종은 Task 4 해제 후)
- [ ] 값과 단위를 눈으로 대조했다 (Task 1 Step 6 · Task 8 Step 3)
- [ ] 변이 테스트: `PRICE`→`PERCENT` / `fetch` 기본값 / `uppercase` 계열 — 확인했다
- [ ] 플래그 off면 탭이 안 뜬다
- [ ] `0`과 `null`이 화면에서 구별된다
- [ ] Task 4 Step 1의 원본 응답 확인 결과가 계획에 반영됐다
