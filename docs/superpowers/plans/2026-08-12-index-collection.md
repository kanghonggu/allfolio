# AF-101 지수 수집 Implementation Plan (1단계 — 국내/KIS)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 국내 지수(KOSPI·KOSDAQ·KOSPI200)를 KIS 공식 API로 하루 3지점(개장직후·장중·종가) 수집해 저장한다.

**Architecture:** AF-99 하나은행 수집기의 구조를 그대로 따른다 — 클라이언트 / 파서 / 안전장치 / 수집 서비스를 각각 분리하고, AF-103이 만든 `X-Scheduler-Token` 트리거로 GitHub Actions cron이 호출한다. 저장 키는 `(지수코드, 거래일, 슬롯)`이라 cron이 밀려도 그날 그 지점의 한 건으로 수렴한다.

**Tech Stack:** Kotlin 1.9.25 / Spring Boot 3.2.5 / WebClient / JPA(unified-asset) / JUnit5 + 순수 Mockito / GitHub Actions

**Spec:** `docs/superpowers/specs/2026-08-12-index-collection-design.md`

---

## 이 계획의 범위 — 해외는 들어 있지 않다

스펙이 정한 대로 **국내(KIS)만** 완결시킨다. 해외(Twelve Data)는 별도 계획으로 뺀다. 이유:

1. Twelve Data API 키 발급은 계정 생성이라 **사용자 작업**이다
2. **무료 플랜에 지수 심볼이 포함되는지 확인할 방법이 없다** — 키가 있어야 확인되고, 키는 사용자만 만든다

키가 막혀도 국내 절반은 돌아가야 한다.

## 사전 필독 (모든 태스크 공통)

- **Gradle 테스트는 반드시 `--rerun-tasks`.** 없으면 전부 UP-TO-DATE로 보고되고 아무것도 실행되지 않는다.
- Gradle 콘솔은 개별 테스트를 나열하지 않는다. 건수는 JUnit XML을 읽을 것:
  `allfolio-backend/backend-app/build/test-results/test/TEST-<FQCN>.xml`
- **`mockito-kotlin`은 이 저장소에 없다.** 테스트 의존성은 `spring-boot-starter-test`뿐이고
  기존 테스트는 순수 `org.mockito.Mockito`나 손으로 쓴 페이크 클래스를 쓴다. 의존성을 추가하지 말 것.
- **Kotlin final 클래스를 Mockito로 목킹할 때 non-null 파라미터에 `any()`를 쓰면 스터빙 시점에 NPE가 난다.**
  inline mock maker가 `checkNotNullParameter` intrinsic을 남기기 때문. `any(X::class.java) ?: <기본값>` 형태로 우회한다
  (AF-103에서 실제로 물린 함정).
- 브랜치는 `feat/af-101-index-collection` (생성돼 있고 설계 문서 커밋 2개가 올라가 있다).
- 엔티티·리포지토리는 **`unified-asset` 모듈**에 둔다(이 저장소에서 모든 JPA 엔티티가 사는 곳이며 H2가 있는 유일한 모듈).
  클라이언트·파서·서비스·컨트롤러는 **`backend-app`**.

## 파일 구조

| 파일 | 책임 |
|---|---|
| `docs/superpowers/migrations/2026-08-12-market-index-quote.sql` (신규) | 운영 Neon 수동 마이그레이션 |
| `unified-asset/.../infrastructure/entity/MarketIndexQuoteEntity.kt` (신규) | 엔티티 |
| `unified-asset/.../infrastructure/jpa/MarketIndexQuoteJpaRepository.kt` (신규) | 조회·중복확인 |
| `backend-app/.../market/index/IndexCode.kt` (신규) | 지수 식별자 + 슬롯 enum |
| `backend-app/.../market/index/KisIndexClient.kt` (신규) | KIS HTTP 호출. 파싱 안 함 |
| `backend-app/.../market/index/KisIndexParser.kt` (신규) | 응답 → 도메인. 여기만 필드명을 안다 |
| `backend-app/.../market/index/IndexGuards.kt` (신규) | 안전장치 판정. 순수 함수 |
| `backend-app/.../market/index/IndexCollectService.kt` (신규) | 조립 + 저장 |
| `backend-app/.../api/admin/MarketIndexAdminController.kt` (신규) | 원본 덤프 + 수동 수집 |
| `backend-app/.../api/scheduler/SchedulerTriggerController.kt` (수정) | 국내 지수 트리거 추가 |
| `backend-app/src/main/resources/application.yml` (수정) | 지수 코드 목록 설정 |
| `.github/workflows/collect-index.yml` (신규) | cron 3지점 |

---

### Task 1: 스키마 + 마이그레이션

**Files:**
- Create: `docs/superpowers/migrations/2026-08-12-market-index-quote.sql`

- [ ] **Step 1: 마이그레이션 파일을 쓴다**

```sql
-- AF-101 지수 시세 — 운영 Neon 1회성 마이그레이션
-- 실행: /opt/homebrew/opt/libpq/bin/psql "<NEON_DB_URL>" -f 2026-08-12-market-index-quote.sql
-- 반드시 백엔드 배포 "전"에 실행 (ddl-auto: none). 신규 테이블이라 기존 백엔드엔 무해. 멱등.

CREATE TABLE IF NOT EXISTS market_index_quote (
    id               UUID          NOT NULL,
    index_code       VARCHAR(20)   NOT NULL,   -- 우리가 정한 canonical (KOSPI, KOSDAQ, KOSPI200)
    trade_date       DATE          NOT NULL,   -- 시장 현지 거래일
    slot             VARCHAR(10)   NOT NULL,   -- OPEN | MID | CLOSE
    price            NUMERIC(18,4) NOT NULL,
    prev_close       NUMERIC(18,4) NOT NULL,   -- price - change로 역산
    change_value     NUMERIC(18,4) NOT NULL,   -- "change"는 예약어 충돌 소지가 있어 접미사를 붙인다
    change_rate      NUMERIC(9,4)  NOT NULL,   -- %
    prev_close_date  DATE,                     -- 전일 종가의 날짜. 모르면 NULL
    market_status    VARCHAR(10)   NOT NULL,   -- 장중 | 장마감 | 개장전
    source           VARCHAR(20)   NOT NULL,   -- KIS | TWELVE_DATA
    collected_at     TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_market_index_quote PRIMARY KEY (id),
    CONSTRAINT uk_market_index_quote UNIQUE (index_code, trade_date, slot)
);

-- 화면이 쓰는 "그 지수의 가장 최근 한 건" 조회
CREATE INDEX IF NOT EXISTS idx_market_index_quote_latest
    ON market_index_quote (index_code, trade_date DESC, slot DESC);

-- 검증: 테이블만 만들고 데이터는 수집 API로 채운다
SELECT COUNT(*) AS rows FROM market_index_quote;
```

- [ ] **Step 2: 커밋**

```bash
git add docs/superpowers/migrations/2026-08-12-market-index-quote.sql
git commit -m "feat(af-101): market_index_quote 스키마"
```

---

### Task 2: 엔티티 + 리포지토리

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/entity/MarketIndexQuoteEntity.kt`
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/jpa/MarketIndexQuoteJpaRepository.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/infrastructure/jpa/MarketIndexQuoteJpaRepositoryTest.kt`

**먼저 읽을 것**: `HanaFxQuoteEntity.kt`와 `HanaFxQuoteJpaRepositoryTest.kt`. 이 태스크는 그 형태를 그대로 따른다.

- [ ] **Step 1: 실패 테스트를 먼저 쓴다**

`HanaFxQuoteJpaRepositoryTest`의 하네스(@DataJpaTest 설정, TestConfig 등)를 **그대로 복사해** 쓴다.
추측하지 말고 그 파일을 읽을 것.

```kotlin
    // UNIQUE 제약이 엔티티에 선언돼 있지 않으면 H2에 제약이 아예 안 생겨
    // 중복 삽입이 조용히 커밋된다 — AF-100에서 실제로 물린 함정이다.
    @Test
    fun `같은 지수 같은 날 같은 슬롯은 두 번 못 들어간다`() {
        repository.saveAndFlush(quote("KOSPI", LocalDate.of(2026, 8, 12), "CLOSE"))

        assertThatThrownBy {
            repository.saveAndFlush(quote("KOSPI", LocalDate.of(2026, 8, 12), "CLOSE"))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `슬롯이 다르면 같은 날에도 들어간다`() {
        repository.saveAndFlush(quote("KOSPI", LocalDate.of(2026, 8, 12), "OPEN"))
        repository.saveAndFlush(quote("KOSPI", LocalDate.of(2026, 8, 12), "CLOSE"))

        assertThat(repository.findAll()).hasSize(2)
    }

    // 화면이 쓰는 조회. 다른 지수가 섞여 있어도 그 지수만 봐야 한다.
    @Test
    fun `그 지수의 가장 최근 한 건을 준다`() {
        repository.saveAndFlush(quote("KOSPI", LocalDate.of(2026, 8, 11), "CLOSE", price = "2500"))
        repository.saveAndFlush(quote("KOSPI", LocalDate.of(2026, 8, 12), "OPEN", price = "2600"))
        repository.saveAndFlush(quote("KOSDAQ", LocalDate.of(2026, 8, 12), "OPEN", price = "900"))

        assertThat(repository.findLatest("KOSPI")!!.price).isEqualByComparingTo("2600")
    }

    // 슬롯을 문자열로 정렬하면 사전순이라 CLOSE < MID < OPEN이 되어,
    // 같은 날 시가가 종가보다 최신으로 잡힌다. 화면이 종가 대신 시가를 보여주게 된다.
    // Spring Data의 파생 쿼리(findTopBy...OrderBySlotDesc)로는 이 순서를 표현할 수 없다.
    @Test
    fun `같은 날에는 종가가 시가보다 최신이다`() {
        repository.saveAndFlush(quote("KOSPI", LocalDate.of(2026, 8, 12), "OPEN", price = "2600"))
        repository.saveAndFlush(quote("KOSPI", LocalDate.of(2026, 8, 12), "CLOSE", price = "2650"))

        assertThat(repository.findLatest("KOSPI")!!.price).isEqualByComparingTo("2650")
    }
```

- [ ] **Step 2: 엔티티와 리포지토리를 만든다**

`MarketIndexQuoteEntity.kt`:

```kotlin
package com.allfolio.unifiedasset.infrastructure.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 지수 시세 한 건 (AF-101).
 *
 * **키가 `(지수코드, 거래일, 슬롯)`인 이유**: KIS 지수 응답에는 기준시각이 없다.
 * 조회 시각을 키로 쓰면 GitHub cron 지연(5~30분)이 그대로 데이터에 새겨져,
 * 15:50에 돈 날과 16:20에 돈 날이 서로 다른 행이 되고 같은 종가가 두 건으로 남는다.
 * 스케줄 지점을 키에 넣으면 언제 돌든 그날 그 지점의 한 건으로 수렴한다.
 *
 * 값 필드가 var인 이유: 같은 슬롯을 다시 수집하면 값만 덮기 때문.
 *
 * `slotOrder`는 정렬 전용 파생 컬럼이 아니라 [slot]에서 계산한다 —
 * 문자열 정렬로는 OPEN·MID·CLOSE의 시간 순서를 표현할 수 없다.
 */
@Entity
@Table(
    name = "market_index_quote",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_market_index_quote",
            columnNames = ["index_code", "trade_date", "slot"],
        ),
    ],
)
class MarketIndexQuoteEntity(
    @Id val id: UUID,
    @Column(name = "index_code", nullable = false, length = 20) val indexCode: String,
    @Column(name = "trade_date", nullable = false) val tradeDate: LocalDate,
    @Column(name = "slot", nullable = false, length = 10) val slot: String,
    @Column(name = "price", nullable = false, precision = 18, scale = 4) var price: BigDecimal,
    @Column(name = "prev_close", nullable = false, precision = 18, scale = 4) var prevClose: BigDecimal,
    @Column(name = "change_value", nullable = false, precision = 18, scale = 4) var changeValue: BigDecimal,
    @Column(name = "change_rate", nullable = false, precision = 9, scale = 4) var changeRate: BigDecimal,
    @Column(name = "prev_close_date") var prevCloseDate: LocalDate?,
    @Column(name = "market_status", nullable = false, length = 10) var marketStatus: String,
    @Column(name = "source", nullable = false, length = 20) val source: String,
    @Column(name = "collected_at", nullable = false) var collectedAt: LocalDateTime,
)
```

`MarketIndexQuoteJpaRepository.kt`:

```kotlin
package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.MarketIndexQuoteEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface MarketIndexQuoteJpaRepository : JpaRepository<MarketIndexQuoteEntity, UUID> {

    /**
     * 그 지수의 가장 최근 한 건.
     *
     * **슬롯을 문자열로 정렬하면 안 된다** — 사전순은 CLOSE < MID < OPEN이라
     * 같은 날 OPEN이 종가보다 최신으로 잡힌다. CASE로 시간 순서를 명시한다.
     */
    @Query(
        """
        SELECT q FROM MarketIndexQuoteEntity q
        WHERE q.indexCode = :indexCode
        ORDER BY q.tradeDate DESC,
                 CASE q.slot WHEN 'CLOSE' THEN 3 WHEN 'MID' THEN 2 ELSE 1 END DESC
        LIMIT 1
        """,
    )
    fun findLatest(@Param("indexCode") indexCode: String): MarketIndexQuoteEntity?

    /** 수집 시 덮어쓸 대상을 가려낸다 */
    fun findByIndexCodeAndTradeDateAndSlot(
        indexCode: String,
        tradeDate: LocalDate,
        slot: String,
    ): MarketIndexQuoteEntity?
}
```

**JPQL의 `LIMIT`이 안 먹으면** (Hibernate 버전에 따라 갈린다) 아래 둘 중 하나로 바꾼다.
셋 다 같은 결과이고, 중요한 건 `CASE`로 슬롯 순서를 명시한다는 점이다:

```kotlin
    // 대안 A: Pageable
    fun findLatest(indexCode: String, pageable: Pageable): List<MarketIndexQuoteEntity>
    // 호출부에서 PageRequest.of(0, 1)

    // 대안 B: nativeQuery = true 로 두고 SQL에 LIMIT 1
```

어느 쪽을 썼는지 보고할 것.

- [ ] **Step 3: 테스트를 돌린다**

Run:
```bash
cd allfolio-backend && ./gradlew :unified-asset:test --tests '*MarketIndexQuoteJpaRepositoryTest*' --rerun-tasks --no-daemon
```
Expected: PASS (4건)

- [ ] **Step 4: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/entity/MarketIndexQuoteEntity.kt \
        allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/jpa/MarketIndexQuoteJpaRepository.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/infrastructure/jpa/MarketIndexQuoteJpaRepositoryTest.kt
git commit -m "feat(af-101): 지수 시세 엔티티 + 슬롯 시간순 정렬 리포지토리"
```

---

### Task 3: 지수 식별자 + 설정

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/index/IndexCode.kt`
- Modify: `allfolio-backend/backend-app/src/main/resources/application.yml`

- [ ] **Step 1: enum과 설정 프로퍼티를 만든다**

```kotlin
package com.allfolio.market.index

/**
 * 스케줄 지점 (AF-101).
 *
 * KIS 지수 응답에 기준시각이 없어 조회 시각을 키로 쓸 수 없다.
 * 대신 "그날의 어느 지점인가"를 키에 넣어, cron이 밀려도 한 건으로 수렴시킨다.
 */
enum class IndexSlot { OPEN, MID, CLOSE }

/** 시장 상태. KIS 응답에 없어 우리가 판정한다. */
enum class MarketStatus(val label: String) {
    PRE_OPEN("개장전"),
    OPEN("장중"),
    CLOSED("장마감"),
}
```

`application.yml`에서 `kis:` 블록(215행 근처) **바로 아래**에 추가:

```yaml
# 지수 수집 — AF-101
# 코드를 소스에 박지 않는 이유: KIS 공식 샘플이 문서화한 FID_INPUT_ISCD는
# 0001(코스피)·1001(코스닥)·2001(코스피200) 셋뿐이다. KOSDAQ150·KRX300은 확인되지 않았고,
# 추측한 코드를 넣으면 KIS가 오류를 주거나 — 더 나쁘게 — 엉뚱한 업종 지수를 조용히 돌려준다.
# 코드가 확인되는 대로 여기 한 줄씩 추가한다.
market-index:
  domestic:
    - code: KOSPI
      kis-iscd: "0001"
    - code: KOSDAQ
      kis-iscd: "1001"
    - code: KOSPI200
      kis-iscd: "2001"
```

바인딩용 프로퍼티 클래스도 같은 파일에 둔다:

```kotlin
package com.allfolio.market.index

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "market-index")
class MarketIndexProperties {
    var domestic: List<DomesticIndex> = emptyList()

    class DomesticIndex {
        /** 우리가 정한 canonical 코드. DB의 index_code가 된다 */
        var code: String = ""
        /** KIS FID_INPUT_ISCD */
        var kisIscd: String = ""
    }
}
```

- [ ] **Step 2: 설정이 실제로 바인딩되는지 확인하는 테스트**

`application.yml`의 오타는 컴파일로 안 잡힌다. 빈 리스트가 되면 수집이 조용히 0건이 된다.

```kotlin
package com.allfolio.market.index

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest

// 기존 슬라이스 테스트(예: SecurityConfigAdminTest)의 애노테이션 관례를 따를 것.
// 목적은 하나다 — application.yml의 market-index 블록이 실제로 바인딩되는가.
class MarketIndexPropertiesTest {

    @Test
    fun `국내 지수 셋이 설정에서 바인딩된다`() {
        assertThat(properties.domestic.map { it.code })
            .containsExactly("KOSPI", "KOSDAQ", "KOSPI200")
        assertThat(properties.domestic.map { it.kisIscd })
            .containsExactly("0001", "1001", "2001")
    }
}
```

Run:
```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests '*MarketIndexPropertiesTest*' --rerun-tasks --no-daemon
```
Expected: PASS

- [ ] **Step 3: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/index/ allfolio-backend/backend-app/src/main/resources/application.yml allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/index/
git commit -m "feat(af-101): 지수 식별자 enum + 설정 기반 지수 목록"
```

---

### Task 4: KIS 지수 클라이언트 + 원본 덤프 엔드포인트

**이 태스크는 파서를 쓰지 않는다.** 응답의 정확한 타입·형식(문자열인지 숫자인지, 등락률이
`1.23`인지 `0.0123`인지)이 확정되지 않았다. **파서를 먼저 쓰고 나중에 맞추면 잘못된 가정 위에
테스트까지 쌓게 된다** — AF-99에서 실제로 그랬다. 한 번 찍어 보고 나서 파서를 쓴다.

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/index/KisIndexClient.kt`
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/api/admin/MarketIndexAdminController.kt`

- [ ] **Step 1: 클라이언트를 만든다**

`KisApiClient`를 먼저 읽어 `webClient` 구성과 `issueToken()`을 확인할 것. 토큰은
Client Credentials(서버 공용)이므로 사용자별 토큰 경로(`resolveAccessToken(userId)`)를 쓰지 않는다.

```kotlin
package com.allfolio.market.index

import com.allfolio.broker.kis.KisApiClient
import com.allfolio.broker.kis.KisProperties
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.Duration

/** KIS 지수 응답을 신뢰할 수 없을 때. AF-99의 HanaFxParseException과 같은 뜻 — "응답이 이상하다" */
class KisIndexException(message: String) : RuntimeException(message)

/**
 * KIS 국내 업종 지수 조회 (AF-101).
 *
 * 엔드포인트·tr_id는 KIS 공식 샘플에서 확인했다:
 * https://github.com/koreainvestment/open-trading-api/tree/main/examples_llm/domestic_stock/inquire_index_price
 *
 * **이 클래스는 파싱하지 않는다.** 원본 Map을 그대로 돌려준다 —
 * 필드 형식이 확정되기 전에 파서를 쓰면 잘못된 가정 위에 테스트를 쌓게 된다.
 */
@Component
class KisIndexClient(
    private val kisProperties: KisProperties,
    private val kisApiClient: KisApiClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient = WebClient.builder()
        .baseUrl(kisProperties.baseUrl)
        .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .build()

    /** 지수 한 건의 원본 응답. output 맵을 그대로 돌려준다. */
    @Suppress("UNCHECKED_CAST")
    fun fetchRaw(kisIscd: String): Map<String, Any?> {
        if (!kisProperties.isConfigured()) {
            throw KisIndexException("KIS 인증 정보가 설정되지 않았습니다 (KIS_APP_KEY/KIS_APP_SECRET).")
        }

        val token = kisApiClient.issueToken().accessToken

        val body = try {
            webClient.get()
                .uri { b ->
                    b.path("/uapi/domestic-stock/v1/quotations/inquire-index-price")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "U")
                        .queryParam("FID_INPUT_ISCD", kisIscd)
                        .build()
                }
                .header("authorization", "Bearer $token")
                .header("appkey", kisProperties.appKey)
                .header("appsecret", kisProperties.appSecret)
                .header("tr_id", TR_ID)
                .retrieve()
                .bodyToMono<Map<String, Any?>>()
                .block(TIMEOUT)
        } catch (e: Throwable) {
            // AF-100·AF-99에서 쓴 형태. block(timeout)은 WebClientException이 아니라
            // IllegalStateException을 던져서, WebClientException만 잡으면 타임아웃이 raw로 샌다.
            if (e is Error) throw e
            throw KisIndexException("KIS 지수 조회 실패 iscd=$kisIscd: ${e.message}")
        } ?: throw KisIndexException("KIS 지수 응답이 비어 있습니다 iscd=$kisIscd")

        val output = body["output"] as? Map<String, Any?>
            ?: throw KisIndexException("KIS 지수 응답에 output이 없습니다 iscd=$kisIscd: ${body["msg1"]}")

        return output
    }

    companion object {
        private const val TR_ID = "FHPUP02100000"
        private val TIMEOUT: Duration = Duration.ofSeconds(15)
    }
}
```

**`KisTokenResponse`의 필드명이 `accessToken`이 맞는지 `KisDtos.kt`에서 확인할 것.**

- [ ] **Step 2: 원본 덤프 어드민 엔드포인트**

```kotlin
package com.allfolio.api.admin

import com.allfolio.market.index.KisIndexClient
import com.allfolio.market.index.KisIndexException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/admin/market-index")
class MarketIndexAdminController(
    private val kisIndexClient: KisIndexClient,
) {
    /**
     * GET /api/admin/market-index/raw?iscd=0001 — KIS 원본 응답 그대로 (AF-101).
     *
     * 파서를 쓰기 전에 필드의 실제 타입·형식을 눈으로 확인하기 위한 것이다.
     * 등락률이 `1.23`인지 `0.0123`인지, 값이 문자열인지 숫자인지는 공식 샘플로 확정되지 않았고,
     * 추측해서 파서를 쓰면 잘못된 가정 위에 테스트까지 쌓인다.
     */
    @GetMapping("/raw")
    fun raw(@RequestParam iscd: String): ResponseEntity<Map<String, Any?>> =
        try {
            ResponseEntity.ok(kisIndexClient.fetchRaw(iscd))
        } catch (e: KisIndexException) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, e.message)
        }
}
```

- [ ] **Step 3: 컴파일 + 전 모듈 테스트**

Run:
```bash
cd allfolio-backend && ./gradlew test --rerun-tasks --no-daemon
```
Expected: BUILD SUCCESSFUL

이 태스크에는 단위 테스트를 쓰지 않는다. 검증 대상이 "KIS가 실제로 뭘 주는가"이고
그건 목으로 확인할 수 없다. Step 4가 진짜 검증이다.

- [ ] **Step 4: 이 태스크는 여기서 멈춘다 — 사용자 확인이 필요하다**

이 시점에서 컨트롤러를 배포해 실제 응답을 받아봐야 파서를 쓸 수 있다.
**어드민 토큰이 필요하고 그건 사용자만 실행할 수 있다.**

보고할 것:
- `GET /api/admin/market-index/raw?iscd=0001`을 실행해 응답 JSON을 붙여달라고 요청
- 그 응답 없이는 Task 5(파서)를 시작하지 말 것

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/index/KisIndexClient.kt \
        allfolio-backend/backend-app/src/main/kotlin/com/allfolio/api/admin/MarketIndexAdminController.kt
git commit -m "feat(af-101): KIS 지수 클라이언트 + 원본 응답 덤프 엔드포인트"
```

---

## 확정된 KIS 응답 형식 (2026-08-12 운영 실측, `iscd=0001`)

```json
{
  "bstp_nmix_prpr":      "6579.04",   // 현재가
  "bstp_nmix_prdy_vrss": "233.51",    // 전일대비 (아래 참조)
  "prdy_vrss_sign":      "2",         // 부호 코드
  "bstp_nmix_prdy_ctrt": "3.68",      // 등락률
  "bstp_nmix_oprc":      "6438.50",
  "bstp_nmix_hgpr":      "6668.43",
  "bstp_nmix_lwpr":      "6413.50",
  "dryy_lwpr_vrss_prpr_rate": "-56.02"   // ← 마이너스가 붙는 필드가 실재한다
}
```

확정된 것 셋:

1. **모든 값이 문자열이다.** `"6579.04"` — 숫자가 아니다. `.toBigDecimal()`로 파싱한다.
2. **등락률은 퍼센트 단위다.** 실측 검산: `6579.04 − 233.51 = 6345.53`(전일종가),
   `233.51 / 6345.53 × 100 = 3.6799` ≈ 응답의 `3.68`. 비율(0.0368)이면 100배 차이라 오인 불가.
3. **기준시각도 장상태도 없다** — 스펙의 판단이 맞았다. `dryy_bstp_nmix_hgpr_date`는
   연중 최고가 날짜지 기준일이 아니다.

### 부호는 값에서 읽지 않는다

실측일이 상승일(`prdy_vrss_sign: "2"`)이라 `bstp_nmix_prdy_vrss`가 양수로 왔다.
**하락일에 `"-233.51"`로 올지 `"233.51"`로 올지는 이 응답으로 알 수 없다.**
그런데 같은 응답의 `dryy_lwpr_vrss_prpr_rate`가 `"-56.02"`다 — **KIS는 어떤 필드엔
마이너스를 실어 보낸다.** 필드마다 다를 수 있고, 추측하면 하락일에 틀린다.

→ **값은 절댓값으로만 쓰고 방향은 `prdy_vrss_sign`에서만 가져온다.**

```
change = 부호 × |bstp_nmix_prdy_vrss|
rate   = 부호 × |bstp_nmix_prdy_ctrt|
```

원본에 마이너스가 붙어 오든 말든 결과가 같다.

**모르는 부호 코드는 거부한다.** 기본값을 "상승"으로 두면 알 수 없는 코드가 왔을 때
하락을 상승으로 저장한다. KIS 관례는 `1`=상한 `2`=상승 `3`=보합 `4`=하한 `5`=하락으로
알려져 있으나 **실측으로 확인된 것은 `2`뿐이다** — 나머지는 관례를 따르되 그 밖의 값은 거부한다.

### `prev_close_date`는 채울 수 없다

응답에 전일 종가의 날짜가 없다. KIS 경로에서는 **NULL로 둔다.**
국내 지수는 전일이 곧 직전 영업일이라 해외만큼 모호하지 않아 지금은 감수한다.
해외(Twelve Data)를 붙일 때 이 컬럼이 값어치를 한다.

---

### Task 5: `KisIndexParser`

**Files:**
- Create: `backend-app/.../market/index/KisIndexParser.kt`
- Test: `backend-app/src/test/kotlin/com/allfolio/market/index/KisIndexParserTest.kt`

- [ ] **Step 1: 실패 테스트를 먼저 쓴다 — 픽스처는 실측 응답 그대로**

지어낸 숫자 대신 위의 실측 응답을 쓴다. 실제 값이 통과하지 못하는 파서는 의미가 없다.

```kotlin
package com.allfolio.market.index

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class KisIndexParserTest {

    private val parser = KisIndexParser()

    /** 2026-08-12 운영 실측 (iscd=0001) */
    private fun realResponse(
        prpr: String = "6579.04",
        vrss: String = "233.51",
        sign: String = "2",
        ctrt: String = "3.68",
    ) = mapOf<String, Any?>(
        "bstp_nmix_prpr" to prpr,
        "bstp_nmix_prdy_vrss" to vrss,
        "prdy_vrss_sign" to sign,
        "bstp_nmix_prdy_ctrt" to ctrt,
    )

    @Test
    fun `실측 응답을 그대로 파싱한다`() {
        val q = parser.parse("KOSPI", realResponse())

        assertThat(q.price).isEqualByComparingTo("6579.04")
        assertThat(q.change).isEqualByComparingTo("233.51")
        assertThat(q.changeRate).isEqualByComparingTo("3.68")
        assertThat(q.prevClose).isEqualByComparingTo("6345.53")   // price - change
    }

    // KIS는 어떤 필드엔 마이너스를 실어 보낸다(dryy_lwpr_vrss_prpr_rate: "-56.02").
    // 값에 이미 부호가 있든 없든 결과가 같아야 한다 — 그래서 절댓값 + sign 조합을 쓴다.
    @Test
    fun `하락일은 원본에 부호가 있든 없든 같은 결과를 낸다`() {
        val withoutSign = parser.parse("KOSPI", realResponse(vrss = "233.51", sign = "5", ctrt = "3.68"))
        val withSign = parser.parse("KOSPI", realResponse(vrss = "-233.51", sign = "5", ctrt = "-3.68"))

        assertThat(withoutSign.change).isEqualByComparingTo("-233.51")
        assertThat(withoutSign.changeRate).isEqualByComparingTo("-3.68")
        assertThat(withSign.change).isEqualByComparingTo(withoutSign.change)
        assertThat(withSign.changeRate).isEqualByComparingTo(withoutSign.changeRate)
    }

    @Test
    fun `보합은 전일과 같다`() {
        val q = parser.parse("KOSPI", realResponse(vrss = "0", sign = "3", ctrt = "0"))

        assertThat(q.change).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(q.prevClose).isEqualByComparingTo(q.price)
    }

    // 기본값을 "상승"으로 두면 알 수 없는 코드가 왔을 때 하락을 상승으로 저장한다.
    @Test
    fun `모르는 부호 코드는 거부한다`() {
        assertThatThrownBy { parser.parse("KOSPI", realResponse(sign = "9")) }
            .isInstanceOf(KisIndexException::class.java)
            .hasMessageContaining("부호")
    }

    @Test
    fun `필드가 없으면 거부한다`() {
        assertThatThrownBy { parser.parse("KOSPI", emptyMap()) }
            .isInstanceOf(KisIndexException::class.java)
    }

    @Test
    fun `숫자가 아닌 값은 거부한다`() {
        assertThatThrownBy { parser.parse("KOSPI", realResponse(prpr = "-")) }
            .isInstanceOf(KisIndexException::class.java)
    }
}
```

Run — 컴파일 실패(`Unresolved reference: KisIndexParser`)를 확인한다:
```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests '*KisIndexParserTest*' --rerun-tasks --no-daemon
```

- [ ] **Step 2: 파서를 만든다**

```kotlin
package com.allfolio.market.index

import org.springframework.stereotype.Component
import java.math.BigDecimal

/** 파싱된 지수 시세 한 건. 저장 키(거래일·슬롯)는 수집 서비스가 정한다. */
data class IndexQuote(
    val indexCode: String,
    val price: BigDecimal,
    val prevClose: BigDecimal,
    val change: BigDecimal,
    val changeRate: BigDecimal,
)

/**
 * KIS 업종 지수 응답 → 도메인 (AF-101).
 *
 * **부호를 값에서 읽지 않는다.** 실측 응답(2026-08-12)은 상승일이라
 * `bstp_nmix_prdy_vrss`가 양수로 왔는데, 하락일에 마이너스가 붙을지는 알 수 없다 —
 * 같은 응답의 `dryy_lwpr_vrss_prpr_rate`가 `"-56.02"`인 걸 보면 KIS는 어떤 필드엔
 * 부호를 싣는다. 값은 절댓값으로만 쓰고 방향은 [prdy_vrss_sign]에서만 가져오면
 * 원본에 부호가 있든 없든 결과가 같다.
 *
 * 응답의 모든 값은 **문자열**이다.
 */
@Component
class KisIndexParser {

    fun parse(indexCode: String, output: Map<String, Any?>): IndexQuote {
        val price = number(output, "bstp_nmix_prpr")
        val rawChange = number(output, "bstp_nmix_prdy_vrss").abs()
        val rawRate = number(output, "bstp_nmix_prdy_ctrt").abs()
        val direction = direction(text(output, "prdy_vrss_sign"))

        val change = rawChange.multiply(BigDecimal(direction))
        return IndexQuote(
            indexCode = indexCode,
            price = price,
            prevClose = price.subtract(change),
            change = change,
            changeRate = rawRate.multiply(BigDecimal(direction)),
        )
    }

    /**
     * KIS 관례: 1=상한 2=상승 3=보합 4=하한 5=하락.
     * **실측으로 확인된 것은 2뿐이다.** 그 밖의 값은 거부한다 —
     * 기본값을 상승으로 두면 모르는 코드가 왔을 때 하락을 상승으로 저장한다.
     */
    private fun direction(sign: String): Int = when (sign.trim()) {
        "1", "2" -> 1
        "3" -> 0
        "4", "5" -> -1
        else -> throw KisIndexException("알 수 없는 전일대비 부호 코드: '$sign'")
    }

    private fun text(output: Map<String, Any?>, key: String): String =
        output[key]?.toString()?.trim()
            ?: throw KisIndexException("KIS 지수 응답에 $key 가 없습니다")

    private fun number(output: Map<String, Any?>, key: String): BigDecimal =
        text(output, key).toBigDecimalOrNull()
            ?: throw KisIndexException("KIS 지수 응답의 $key 가 숫자가 아닙니다: '${output[key]}'")
}
```

- [ ] **Step 3: 통과 확인 후 커밋**

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests '*KisIndexParserTest*' --rerun-tasks --no-daemon
```

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/index/KisIndexParser.kt \
        allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/index/KisIndexParserTest.kt
git commit -m "feat(af-101): KIS 지수 파서 — 부호는 sign 필드에서만"
```

---

### Task 6~10 — 남은 작업

| Task | 내용 |
|---|---|
| 6 | `IndexGuards` — 등락률 자기모순(`\|change\|/prevClose×100` vs `\|rate\|`, 허용오차 0.05%p) · 0/음수 현재가 거부 · 연속 실패 카운트 |
| 7 | `IndexCollectService` — 슬롯·장상태 판정, 직전 행과 전부 같으면 `장마감`으로 낮춤, upsert |
| 8 | `SchedulerTriggerController`에 `/index/domestic` 추가 + `.github/workflows/collect-index.yml` |
| 9 | 변이 테스트 |
| 10 | 전 모듈 검증 + PR |

Task 8의 cron (국내만, 스펙 확정):

```
10 0 * * 1-5   → KST 09:10  OPEN
10 3 * * 1-5   → KST 12:10  MID
50 6 * * 1-5   → KST 15:50  CLOSE
```

Task 6의 허용오차가 0.05%p인 근거: 실측에서 역산값 `3.6799` vs 응답 `3.68`으로 차이가
`0.0001`이었다. KIS가 소수점 둘째 자리로 반올림해 주므로 그 반올림 폭만 허용하면 된다.

---

## 완료 후 사용자에게 보고할 것

- Task 4까지의 PR 링크
- **`GET /api/admin/market-index/raw?iscd=0001` 실행 요청** — 이게 없으면 Task 5부터 막힌다
- Neon 마이그레이션 실행 요청 (`docs/superpowers/migrations/2026-08-12-market-index-quote.sql`)
- **`KIS_APP_KEY`·`KIS_APP_SECRET`이 Render에 실제로 등록돼 있는지 확인 요청** —
  `RENDER_ENV_KEYS`에 없어 수동 등록분이고, 없으면 수집이 통째로 실패한다
