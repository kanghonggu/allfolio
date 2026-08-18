# 공시 연동 (D1) 백엔드 — 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** OpenDART 공시를 매일 수집·적재하고, 사용자 보유종목과 조회 시점에 조인해 피드로 내려주는 백엔드를 완성한다.

**Architecture:** `backend-app`에 수집 파이프라인을 둔다. GitHub Actions cron이 `/api/internal/scheduler/dart/collect`를 깨우면, `list.json`을 D-1~D 범위로 전 페이지 수집해 `dart_disclosure`에 `ON CONFLICT DO NOTHING RETURNING`으로 넣는다. 반환된 델타만 후속 처리(화이트리스트 판정·`elestock` 호출)를 소비하므로 재실행이 무해하다. 사용자별 피드는 사전 계산하지 않고 `ua_assets.symbol = dart_disclosure.stock_code` 조인으로 조회 시점에 만든다.

**Tech Stack:** Kotlin · Spring Boot · JPA + JdbcTemplate(델타 upsert) · PostgreSQL(Neon) · WebClient · JUnit5 + AssertJ · GitHub Actions

**선행 스펙:** `docs/superpowers/specs/2026-08-18-dart-disclosure-design.md` — 이 계획은 그 스펙의 S3~S9·S11을 구현한다. S12(FE 화면)는 별도 계획, S13(운영 튜닝)은 1주 운영 후.

**범위 밖:** S2 Flyway 도입, S10 `change_type` 분류기(소스 없음 — 스펙 6절), FE 화면.

---

## 작업 전 반드시 읽을 것

**이 레포는 주석이 무겁다.** 기존 파일(`FscCommodityClient.kt`, `MarketCommodityQuoteEntity.kt`, `SchedulerTriggerController.kt`)을 열어 보면 "왜 이렇게 했는지"와 "이걸 정리하지 말 것"이 KDoc으로 길게 붙어 있다. 아래 계획의 코드 블록은 **동작에 필요한 최소한**만 담았다. 구현 시 각 결정의 근거를 스펙에서 가져와 KDoc으로 남길 것 — 특히 실측 수치(`ㆍ` 2,856회 대 `·` 1회, 빈 문자열 3,273건, `status 013`)는 주석에 숫자로 남긴다. 나중에 "왜 이렇게 했지"가 반드시 다시 나온다.

**테스트 실행 위치는 `allfolio-backend/`다.**

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.dart.*"
```

**실측 원본 응답**이 필요하면 스펙 0절의 표본을 재현한다:

```bash
curl -s "https://opendart.fss.or.kr/api/list.json?crtfc_key=$DART_API_KEY&bgn_de=20260818&end_de=20260818&page_no=1&page_count=10"
```

---

## File Structure

수집은 `com.allfolio.dart` 아래 한 패키지로 모은다. 시세(`com.allfolio.market.*`)와 섞지 않는 이유는 공시가 시세가 아니고, 조인 대상도 보유자산이지 시세표가 아니기 때문이다.

| 파일 | 책임 |
|---|---|
| `dart/DartReportName.kt` | `report_nm` 3단 정규화 (trim → 접두어 제거 → 구분자 통일). 순수 함수 |
| `dart/DartWhitelist.kt` | 정규화된 이름 → `material_tier` 판정. 순수 함수 |
| `dart/DartProperties.kt` | 인증키·베이스 URL·페이지 크기 설정 바인딩 |
| `dart/list/DartListClient.kt` | `list.json` 호출·파싱. `status 013`을 빈 결과로 |
| `dart/list/DartDisclosureCollectService.kt` | 페이지 순회 · 델타 확보 · `dart_collection_run` 기록 |
| `dart/list/JdbcDisclosureStore.kt` | `ON CONFLICT DO NOTHING RETURNING` (JPA로 불가) |
| `dart/corp/DartCorpCodeClient.kt` | `corpCode.xml` ZIP 다운로드·해제·파싱 |
| `dart/corp/DartCorpMapService.kt` | `dart_corp_map` 적재 (주 1회) |
| `dart/insider/DartElestockClient.kt` | `elestock.json` 호출·파싱 |
| `dart/insider/DartInsiderCollectService.kt` | 델타 `rcept_no` 필터 → `dart_insider_trade` 적재 |
| `dart/query/DisclosureFeedService.kt` | 보유종목 조인 조회 |
| `api/admin/DartAdminController.kt` | 수동 트리거 · 요약 반환 |
| `api/dart/DisclosureFeedController.kt` | 사용자 피드 조회 API |
| `unified-asset` 모듈의 `unifiedasset/infrastructure/{entity,jpa}/Dart*.kt` | 엔티티·리포지터리 각 4개. **`backend-app`이 아니다** — 형제 22개가 그쪽에 있다 |

---

## Task 1: 마이그레이션 SQL

**Files:**
- Create: `docs/superpowers/migrations/2026-08-18-dart-disclosure.sql`

`ddl-auto: none`이라 **이 파일을 Neon에 직접 실행하지 않으면 배포 후 첫 수집이 500으로 죽는다.** 스냅샷 사고(`wf_` 테이블 부재)가 정확히 이 방식으로 났다.

- [ ] **Step 1: 마이그레이션 파일 작성**

```sql
-- D1 공시 연동 — 운영 Neon 1회성 마이그레이션
-- 실행: Neon 콘솔 SQL 편집기. 반드시 백엔드 배포 "전"에 실행 (ddl-auto: none).
-- 신규 테이블만 추가하므로 기존 백엔드엔 무해. 멱등.
--
-- rcept_no가 VARCHAR인 이유: 14자리 숫자형으로 선언하면 선행 0이 소실되어 원문 링크가
-- 깨지고 중복 판정이 무너진다. 실측 rcept_no 예: 20260818000094.
--
-- stock_code가 nullable인 이유: 비상장사(corp_cls=E) 공시가 실측 8,667건 중 3,273건 들어온다.
-- OpenDART는 이걸 NULL이 아니라 빈 문자열로 주므로 앱이 NULL로 정규화해 넣는다 —
-- 그러지 않으면 아래 부분 인덱스가 무용지물이 된다.
--
-- material_tier 5는 정기보고서다(사업·반기·분기·감사보고서). Tier 2에 두면 제출 시즌에
-- 피드가 그것만으로 찬다 — 실측 6영업일 상장사 5,394건 중 2,846건이 Tier 5였다.

CREATE TABLE IF NOT EXISTS dart_corp_map (
    corp_code   VARCHAR(8)   PRIMARY KEY,
    corp_name   VARCHAR(200) NOT NULL,
    stock_code  VARCHAR(6),
    modify_date DATE,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_corp_map_stock
    ON dart_corp_map (stock_code) WHERE stock_code IS NOT NULL;

CREATE TABLE IF NOT EXISTS dart_disclosure (
    rcept_no       VARCHAR(14)  PRIMARY KEY,
    corp_code      VARCHAR(8)   NOT NULL,
    corp_name      VARCHAR(200) NOT NULL,
    stock_code     VARCHAR(6),
    corp_cls       VARCHAR(1),
    report_nm      TEXT         NOT NULL,
    report_nm_norm TEXT         NOT NULL,
    rcept_dt       DATE         NOT NULL,
    flr_nm         VARCHAR(200),
    rm             VARCHAR(20),
    is_material    BOOLEAN      NOT NULL DEFAULT FALSE,
    material_tier  SMALLINT,
    is_correction  BOOLEAN      NOT NULL DEFAULT FALSE,
    collected_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_disclosure_feed
    ON dart_disclosure (stock_code, rcept_dt DESC)
    WHERE is_material AND stock_code IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_disclosure_dt ON dart_disclosure (rcept_dt DESC);

CREATE TABLE IF NOT EXISTS dart_insider_trade (
    id                BIGSERIAL    PRIMARY KEY,
    rcept_no          VARCHAR(14)  NOT NULL REFERENCES dart_disclosure(rcept_no),
    corp_code         VARCHAR(8)   NOT NULL,
    stock_code        VARCHAR(6),
    repror            VARCHAR(200) NOT NULL,
    officer_position  VARCHAR(100),
    is_registered     BOOLEAN,
    major_holder_type VARCHAR(50),
    report_date       DATE         NOT NULL,
    owned_qty         BIGINT,
    change_qty        BIGINT,
    owned_rate        NUMERIC(7,2),
    change_rate       NUMERIC(7,2),
    collected_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_insider UNIQUE (rcept_no, repror)
);
CREATE INDEX IF NOT EXISTS idx_insider_feed
    ON dart_insider_trade (stock_code, report_date DESC);

CREATE TABLE IF NOT EXISTS dart_collection_run (
    id             BIGSERIAL   PRIMARY KEY,
    run_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    bgn_de         DATE        NOT NULL,
    end_de         DATE        NOT NULL,
    pages_fetched  INT         NOT NULL DEFAULT 0,
    api_calls      INT         NOT NULL DEFAULT 0,
    new_count      INT         NOT NULL DEFAULT 0,
    elestock_calls INT         NOT NULL DEFAULT 0,
    status         VARCHAR(20) NOT NULL,
    error_msg      TEXT,
    finished_at    TIMESTAMPTZ
);
```

- [ ] **Step 2: 로컬 Postgres에 적용해 문법 확인**

```bash
docker compose up -d postgres
psql "postgresql://allfolio:allfolio@localhost:5432/allfolio" \
  -f docs/superpowers/migrations/2026-08-18-dart-disclosure.sql
```

Expected: `CREATE TABLE` × 4, `CREATE INDEX` × 4, 오류 없음

- [ ] **Step 3: 멱등성 확인 — 같은 파일을 한 번 더 실행**

```bash
psql "postgresql://allfolio:allfolio@localhost:5432/allfolio" \
  -f docs/superpowers/migrations/2026-08-18-dart-disclosure.sql
```

Expected: `NOTICE: relation "dart_corp_map" already exists, skipping` 등만 나오고 오류 없음

- [ ] **Step 4: 커밋**

```bash
git add docs/superpowers/migrations/2026-08-18-dart-disclosure.sql
git commit -m "feat(d1): 공시 테이블 4개 마이그레이션 — rcept_no는 VARCHAR여야 선행 0이 산다"
```

---

## Task 2: JPA 엔티티와 리포지터리

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/entity/DartDisclosureEntity.kt`
- Create: `.../entity/DartInsiderTradeEntity.kt`
- Create: `.../entity/DartCorpMapEntity.kt`
- Create: `.../entity/DartCollectionRunEntity.kt`
- Create: `.../jpa/DartDisclosureJpaRepository.kt`
- Create: `.../jpa/DartInsiderTradeJpaRepository.kt`
- Create: `.../jpa/DartCorpMapJpaRepository.kt`
- Create: `.../jpa/DartCollectionRunJpaRepository.kt`

**모듈은 `unified-asset`이다 — `backend-app`이 아니다.** `MarketCommodityQuoteEntity.kt`를 비롯한
형제 엔티티 18개와 리포지터리 22개가 전부 `unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/`
아래에 있다. `backend-app`에 두면 같은 패키지가 두 모듈에 갈라진다(`@EntityScan(basePackages = ["com.allfolio"])`
라 런타임에 깨지진 않지만 구조가 어긋난다). 수집 서비스(`com.allfolio.dart.*`)만 `backend-app`이고
엔티티·리포지터리는 형제들 옆에 둔다.

- [ ] **Step 1: 엔티티 4개 작성**

```kotlin
package com.allfolio.unifiedasset.infrastructure.entity

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(name = "dart_disclosure")
class DartDisclosureEntity(
    @Id @Column(name = "rcept_no", length = 14)
    val rceptNo: String,

    @Column(name = "corp_code", nullable = false, length = 8)
    val corpCode: String,

    @Column(name = "corp_name", nullable = false, length = 200)
    val corpName: String,

    @Column(name = "stock_code", length = 6)
    val stockCode: String?,

    @Column(name = "corp_cls", length = 1)
    val corpCls: String?,

    @Column(name = "report_nm", nullable = false, columnDefinition = "text")
    val reportNm: String,

    @Column(name = "report_nm_norm", nullable = false, columnDefinition = "text")
    val reportNmNorm: String,

    @Column(name = "rcept_dt", nullable = false)
    val rceptDt: LocalDate,

    @Column(name = "flr_nm", length = 200)
    val flrNm: String?,

    @Column(name = "rm", length = 20)
    val rm: String?,

    @Column(name = "is_material", nullable = false)
    var isMaterial: Boolean,

    @Column(name = "material_tier")
    var materialTier: Short?,

    @Column(name = "is_correction", nullable = false)
    val isCorrection: Boolean,

    @Column(name = "collected_at", nullable = false)
    val collectedAt: LocalDateTime,
)

@Entity
@Table(name = "dart_insider_trade")
class DartInsiderTradeEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "rcept_no", nullable = false, length = 14)
    val rceptNo: String,

    @Column(name = "corp_code", nullable = false, length = 8)
    val corpCode: String,

    @Column(name = "stock_code", length = 6)
    val stockCode: String?,

    @Column(name = "repror", nullable = false, length = 200)
    val repror: String,

    @Column(name = "officer_position", length = 100)
    val officerPosition: String?,

    @Column(name = "is_registered")
    val isRegistered: Boolean?,

    @Column(name = "major_holder_type", length = 50)
    val majorHolderType: String?,

    @Column(name = "report_date", nullable = false)
    val reportDate: LocalDate,

    @Column(name = "owned_qty")
    val ownedQty: Long?,

    @Column(name = "change_qty")
    val changeQty: Long?,

    @Column(name = "owned_rate", precision = 7, scale = 2)
    val ownedRate: BigDecimal?,

    @Column(name = "change_rate", precision = 7, scale = 2)
    val changeRate: BigDecimal?,

    @Column(name = "collected_at", nullable = false)
    val collectedAt: LocalDateTime,
)

@Entity
@Table(name = "dart_corp_map")
class DartCorpMapEntity(
    @Id @Column(name = "corp_code", length = 8)
    val corpCode: String,

    @Column(name = "corp_name", nullable = false, length = 200)
    var corpName: String,

    @Column(name = "stock_code", length = 6)
    var stockCode: String?,

    @Column(name = "modify_date")
    var modifyDate: LocalDate?,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime,
)

@Entity
@Table(name = "dart_collection_run")
class DartCollectionRunEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "run_at", nullable = false)
    val runAt: LocalDateTime,

    @Column(name = "bgn_de", nullable = false)
    val bgnDe: LocalDate,

    @Column(name = "end_de", nullable = false)
    val endDe: LocalDate,

    @Column(name = "pages_fetched", nullable = false)
    var pagesFetched: Int = 0,

    @Column(name = "api_calls", nullable = false)
    var apiCalls: Int = 0,

    @Column(name = "new_count", nullable = false)
    var newCount: Int = 0,

    @Column(name = "elestock_calls", nullable = false)
    var elestockCalls: Int = 0,

    @Column(name = "status", nullable = false, length = 20)
    var status: String,

    @Column(name = "error_msg", columnDefinition = "text")
    var errorMsg: String?,

    @Column(name = "finished_at")
    var finishedAt: LocalDateTime?,
)
```

- [ ] **Step 2: 리포지터리 4개 작성**

```kotlin
package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.*
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface DartDisclosureJpaRepository : JpaRepository<DartDisclosureEntity, String> {
    /** Task 11 — 델타의 Tier 4 트리거를 찾는다 */
    fun findByRceptNoIn(rceptNos: Collection<String>): List<DartDisclosureEntity>

    /** Task 14 — 보유종목 피드 */
    fun findByStockCodeInAndRceptDtGreaterThanEqualAndIsMaterialTrue(
        stockCodes: Collection<String>, from: LocalDate,
    ): List<DartDisclosureEntity>
}

interface DartInsiderTradeJpaRepository : JpaRepository<DartInsiderTradeEntity, Long> {
    fun findByRceptNoIn(rceptNos: Collection<String>): List<DartInsiderTradeEntity>
    fun findByStockCodeInAndReportDateGreaterThanEqualOrderByReportDateDesc(
        stockCodes: Collection<String>, from: LocalDate,
    ): List<DartInsiderTradeEntity>
}

interface DartCorpMapJpaRepository : JpaRepository<DartCorpMapEntity, String>

interface DartCollectionRunJpaRepository : JpaRepository<DartCollectionRunEntity, Long>
```

- [ ] **Step 3: 컴파일 확인**

```bash
cd allfolio-backend && ./gradlew :unified-asset:compileKotlin :backend-app:compileKotlin
```

Expected: BUILD SUCCESSFUL (둘 다)

- [ ] **Step 4: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/
git commit -m "feat(d1): 공시 엔티티·리포지터리"
```

---

## Task 3: `report_nm` 정규화기

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dart/DartReportName.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/dart/DartReportNameTest.kt`

**이 태스크가 원안의 결함을 고친다.** 순서가 있는 3단이고, 순수 함수라 TDD로 간다.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
package com.allfolio.dart

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 값은 전부 2026-08-11~08-18 실측 응답 8,667건에서 가져왔다.
 * 지어낸 입력이 하나도 없다 — 지어내면 실제로 오는 형태를 못 잡는다.
 */
class DartReportNameTest {

    @Test
    fun `뒤에 붙은 공백을 떼어낸다`() {
        // 실측 887건(10%)이 이 형태다
        assertThat(DartReportName.normalize("단일판매ㆍ공급계약체결              "))
            .isEqualTo("단일판매·공급계약체결")
    }

    @Test
    fun `아래아를 가운뎃점으로 통일한다`() {
        // DART는 U+318D(ㆍ)를 쓴다. 실측 2,856회 대 U+00B7(·) 1회.
        assertThat(DartReportName.normalize("현금ㆍ현물배당결정")).isEqualTo("현금·현물배당결정")
        assertThat(DartReportName.normalize("임원ㆍ주요주주특정증권등소유상황보고서"))
            .isEqualTo("임원·주요주주특정증권등소유상황보고서")
    }

    @Test
    fun `가타카나 중점도 통일한다`() {
        assertThat(DartReportName.normalize("단일판매・공급계약체결")).isEqualTo("단일판매·공급계약체결")
    }

    @Test
    fun `이미 가운뎃점이면 그대로 둔다`() {
        assertThat(DartReportName.normalize("현금·현물배당결정")).isEqualTo("현금·현물배당결정")
    }

    @Test
    fun `접두어를 뗀다`() {
        // 실측 5종: [기재정정]875 [첨부정정]29 [첨부추가]20 [발행조건확정]4 [변경등록]1
        assertThat(DartReportName.normalize("[기재정정]반기보고서 (2026.06)")).isEqualTo("반기보고서 (2026.06)")
        assertThat(DartReportName.normalize("[첨부정정]사업보고서")).isEqualTo("사업보고서")
        assertThat(DartReportName.normalize("[발행조건확정]증권신고서")).isEqualTo("증권신고서")
        assertThat(DartReportName.normalize("[변경등록]투자설명서")).isEqualTo("투자설명서")
    }

    @Test
    fun `문서에 없던 새 접두어도 뗀다`() {
        // 열거가 아니라 패턴으로 잡는 이유. 원안은 5종 중 2종을 몰랐다.
        assertThat(DartReportName.normalize("[처음보는접두어]반기보고서")).isEqualTo("반기보고서")
    }

    @Test
    fun `접두어를 뗀 뒤 남은 앞 공백도 없앤다`() {
        assertThat(DartReportName.normalize("[기재정정] 반기보고서   ")).isEqualTo("반기보고서")
    }

    @Test
    fun `본문 중간의 대괄호는 접두어가 아니다`() {
        assertThat(DartReportName.normalize("증권신고서[지분증권]")).isEqualTo("증권신고서[지분증권]")
    }

    @Test
    fun `접두어 유무를 따로 알려준다`() {
        assertThat(DartReportName.hasCorrectionPrefix("[기재정정]반기보고서")).isTrue()
        assertThat(DartReportName.hasCorrectionPrefix("반기보고서")).isFalse()
        assertThat(DartReportName.hasCorrectionPrefix("증권신고서[지분증권]")).isFalse()
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.dart.DartReportNameTest"
```

Expected: 컴파일 실패 — `Unresolved reference: DartReportName`

- [ ] **Step 3: 구현**

```kotlin
package com.allfolio.dart

/**
 * `report_nm` 정규화. 순서가 있는 3단이고, 순서를 바꾸면 결과가 달라진다.
 *
 * 1. trim — 실측 887건(10%)에 뒤 공백이 붙어 온다
 * 2. 접두어 제거 — `^\[…\]`. 열거하지 않는 이유는 원안이 실측 5종 중 2종을 몰랐기 때문이다
 * 3. 구분자 통일 — DART는 `ㆍ`(U+318D), 화이트리스트는 `·`(U+00B7). 실측 2,856 대 1
 *
 * **3단계를 지우지 말 것.** 이것이 없으면 Tier 1의 단일판매·공급계약체결이 통째로 안 잡힌다.
 */
object DartReportName {

    private val PREFIX = Regex("""^\[[^\]]+]""")

    /** U+318D 아래아 · U+30FB 가타카나 중점 → U+00B7 가운뎃점 */
    private const val SEPARATORS = "ㆍ・"

    fun normalize(raw: String): String {
        var s = raw.trim()
        s = PREFIX.replace(s, "").trim()
        return s.map { if (it in SEPARATORS) '·' else it }.joinToString("")
    }

    fun hasCorrectionPrefix(raw: String): Boolean = PREFIX.containsMatchIn(raw.trim())
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.dart.DartReportNameTest"
```

Expected: PASS, 9 tests

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dart/DartReportName.kt \
        allfolio-backend/backend-app/src/test/kotlin/com/allfolio/dart/DartReportNameTest.kt
git commit -m "feat(d1): report_nm 3단 정규화 — DART는 가운뎃점이 아니라 아래아를 쓴다"
```

---

## Task 4: 화이트리스트 Tier 판정기

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dart/DartWhitelist.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/dart/DartWhitelistTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
package com.allfolio.dart

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DartWhitelistTest {

    private fun tierOf(raw: String) = DartWhitelist.tierOf(DartReportName.normalize(raw))

    @Test
    fun `원문을 그대로 넣으면 안 된다 — 정규화를 거쳐야 잡힌다`() {
        // 원안이 틀렸던 지점. 아래아가 든 원문은 판정기가 직접 받으면 못 잡는다.
        assertThat(DartWhitelist.tierOf("단일판매ㆍ공급계약체결")).isNull()
        assertThat(tierOf("단일판매ㆍ공급계약체결")).isEqualTo(1)
    }

    @Test
    fun `Tier 1은 주가에 직결되는 결정이다`() {
        assertThat(tierOf("유상증자결정")).isEqualTo(1)
        assertThat(tierOf("자기주식취득결정")).isEqualTo(1)
        assertThat(tierOf("유형자산취득결정              ")).isEqualTo(1)
        assertThat(tierOf("최대주주변경")).isEqualTo(1)
    }

    @Test
    fun `Tier 2는 재무와 실적이다`() {
        assertThat(tierOf("현금ㆍ현물배당결정              (분기배당)")).isEqualTo(2)
        assertThat(tierOf("매출액또는손익구조30%(대규모법인15%)이상변동")).isEqualTo(2)
    }

    @Test
    fun `Tier 3은 위험이다`() {
        assertThat(tierOf("소송등의제기ㆍ신청")).isEqualTo(3)
        assertThat(tierOf("조회공시요구(풍문또는보도)에대한답변")).isEqualTo(3)
    }

    @Test
    fun `Tier 4는 임원 소유변동 트리거다`() {
        assertThat(tierOf("임원ㆍ주요주주특정증권등소유상황보고서")).isEqualTo(4)
    }

    @Test
    fun `Tier 5는 정기보고서다`() {
        // 제출 시즌에 피드를 덮으므로 분리했다. 실측 6영업일 상장사 5,394건 중 2,846건.
        assertThat(tierOf("반기보고서 (2026.06)")).isEqualTo(5)
        assertThat(tierOf("[기재정정]반기보고서 (2026.06)")).isEqualTo(5)
        assertThat(tierOf("사업보고서 (2025.12)")).isEqualTo(5)
        assertThat(tierOf("감사보고서 (2025.12)")).isEqualTo(5)
    }

    @Test
    fun `해당 없으면 null이고 저장은 된다`() {
        // 실측 미적중 상위. 저장은 하되 피드에 안 나간다 — 설계 원칙 4.
        assertThat(tierOf("지급수단별ㆍ지급기간별지급금액및분쟁조정기구에관한사항")).isNull()
        assertThat(tierOf("기업설명회(IR)개최")).isNull()
        assertThat(tierOf("주주총회소집공고")).isNull()
        assertThat(tierOf("증권발행실적보고서")).isNull()
    }

    @Test
    fun `낮은 Tier가 이긴다`() {
        // 한 이름이 여러 Tier에 걸리면 더 중요한 쪽으로 간다
        assertThat(DartWhitelist.tierOf("자기주식취득결정 및 반기보고서")).isEqualTo(1)
    }

    @Test
    fun `isMaterial은 Tier가 있으면 참이고 Tier 5도 포함한다`() {
        assertThat(DartWhitelist.isMaterial(5)).isTrue()
        assertThat(DartWhitelist.isMaterial(null)).isFalse()
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.dart.DartWhitelistTest"
```

Expected: 컴파일 실패 — `Unresolved reference: DartWhitelist`

- [ ] **Step 3: 구현**

```kotlin
package com.allfolio.dart

/**
 * `material_tier` 판정. **입력은 반드시 [DartReportName.normalize]를 거친 이름이다** —
 * 원문을 넣으면 아래아 때문에 Tier 1이 통째로 빠진다.
 *
 * v1은 `pblntf_detail_ty` 코드가 아니라 키워드 부분일치다. 코드값이 `list.json` 응답에
 * 아예 없기도 하고(실측 필드 9개), 키워드는 로그를 보며 즉시 튜닝할 수 있다.
 */
object DartWhitelist {

    private val TIERS: List<Pair<Short, List<String>>> = listOf(
        1.toShort() to listOf(
            "유상증자결정", "무상증자결정", "감자결정",
            "전환사채권발행결정", "신주인수권부사채권발행결정", "교환사채권발행결정",
            "자기주식취득결정", "자기주식처분결정", "주식소각결정",
            "회사합병결정", "회사분할결정", "영업양수도결정",
            "단일판매·공급계약체결", "유형자산취득결정", "유형자산처분결정",
            "타법인주식및출자증권취득결정", "최대주주변경",
        ),
        2.toShort() to listOf("매출액또는손익구조", "현금·현물배당결정"),
        3.toShort() to listOf(
            "소송등의제기·신청", "부도발생", "회생절차개시신청", "자본잠식",
            "관리종목지정", "상장폐지", "매매거래정지", "횡령·배임", "조회공시요구",
        ),
        4.toShort() to listOf("임원·주요주주특정증권등소유상황보고서"),
        5.toShort() to listOf("사업보고서", "반기보고서", "분기보고서", "감사보고서"),
    )

    /** ② elestock 호출 대상을 가리는 Tier */
    const val TIER_INSIDER: Short = 4

    fun tierOf(normalized: String): Short? =
        TIERS.firstOrNull { (_, keywords) -> keywords.any { it in normalized } }?.first

    fun isMaterial(tier: Short?): Boolean = tier != null
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.dart.DartWhitelistTest"
```

Expected: PASS, 9 tests

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dart/DartWhitelist.kt \
        allfolio-backend/backend-app/src/test/kotlin/com/allfolio/dart/DartWhitelistTest.kt
git commit -m "feat(d1): Tier 판정기 — 정기보고서를 Tier 5로 분리한다"
```

---

## Task 5: 설정 바인딩

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dart/DartProperties.kt`
- Modify: `allfolio-backend/backend-app/src/main/resources/application.yml` (파일 끝에 추가)
- Modify: `.env.example` (34번째 줄 `FSC_API_KEY=` 아래)

- [ ] **Step 1: 설정 클래스 작성**

```kotlin
package com.allfolio.dart

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * @param apiKey 빈 값이면 클라이언트가 예외를 던진다 — 조용히 빈 목록을 주면
 *   "키를 안 넣었다"가 "그날 공시가 없었다"로 굳는다. `status 013`(공휴일)과 구분이 안 된다.
 * @param pageCount 최대 100. 실측 최다일(2026-08-14 반기보고서 마감)이 4,555건 = 46페이지다.
 */
@ConfigurationProperties(prefix = "dart")
data class DartProperties(
    val apiKey: String = "",
    val baseUrl: String = "https://opendart.fss.or.kr/api",
    val pageCount: Int = 100,
    val timeoutSeconds: Long = 30,
)
```

- [ ] **Step 2: `application.yml`에 추가**

```yaml
# D1 공시 연동 — OpenDART
# 인증키가 쿼리 파라미터(crtfc_key=)에 실린다. FRED·공공데이터포털과 같은 방어를 지킬 것:
# 전체 URL을 로그에 찍지 않는다 · 예외에 cause를 붙이지 않는다 · 응답 본문 미리보기를 남기지 않는다.
dart:
  api-key: ${DART_API_KEY:}
  base-url: ${DART_BASE_URL:https://opendart.fss.or.kr/api}
  page-count: 100
  timeout-seconds: 30
```

- [ ] **Step 3: `.env.example`에 추가**

```
DART_API_KEY=
```

- [ ] **Step 4: `@ConfigurationPropertiesScan` 등록 확인**

```bash
cd allfolio-backend && grep -rn "ConfigurationPropertiesScan\|EnableConfigurationProperties" backend-app/src/main/kotlin --include="*.kt" | head
```

기존 스캔이 `com.allfolio` 전체를 덮으면 추가 작업이 없다. `@EnableConfigurationProperties`로 클래스를 하나씩 등록하는 방식이면 `DartProperties::class`를 그 목록에 추가한다.

- [ ] **Step 5: 바인딩 테스트**

```kotlin
package com.allfolio.dart

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import org.springframework.boot.context.properties.source.ConfigurationPropertySources

/**
 * application.yml의 dart 블록이 실제로 [DartProperties]에 바인딩되는지 본다.
 * 클래스만 테스트하면 yml 오타(page-count를 pageCount로 적는 등)를 못 잡는다 —
 * AF-108이 series-id 따옴표 누락으로 같은 부류의 사고를 냈다.
 */
class DartPropertiesYamlTest {

    @Test
    fun `application yml의 dart 블록이 바인딩된다`() {
        val loaded = YamlPropertySourceLoader()
            .load("application.yml", ClassPathResource("application.yml"))
        val env = StandardEnvironment()
        loaded.forEach { env.propertySources.addLast(it) }

        val props = Binder(ConfigurationPropertySources.get(env))
            .bind("dart", DartProperties::class.java)
            .orElseThrow { AssertionError("dart 블록을 바인딩하지 못했다") }

        assertThat(props.baseUrl).isEqualTo("https://opendart.fss.or.kr/api")
        assertThat(props.pageCount).isEqualTo(100)
        assertThat(props.timeoutSeconds).isEqualTo(30)
    }
}
```

- [ ] **Step 6: 테스트 통과 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.dart.DartPropertiesYamlTest"
```

Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dart/DartProperties.kt \
        allfolio-backend/backend-app/src/main/resources/application.yml \
        allfolio-backend/backend-app/src/test/kotlin/com/allfolio/dart/DartPropertiesYamlTest.kt \
        .env.example
git commit -m "feat(d1): OpenDART 설정 바인딩"
```

---

## Task 6: `list.json` 클라이언트

> ### ⚠️ 테스트 방식 — MockWebServer를 쓰지 않는다
>
> **이 레포는 JDK 내장 `com.sun.net.httpserver.HttpServer`로 루프백 스텁을 띄운다.**
> MockWebServer도 WireMock도 의존성에 없고, 추가하지 않는다. 모범 사례를 그대로 따를 것:
>
> - 테스트 예시: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/commodity/fsc/FscCommodityClientTest.kt`
> - 커넥터 헬퍼: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/test/StubServerConnector.kt`의 `dedicatedConnector()`
>
> **`dedicatedConnector()`를 반드시 쓸 것.** 기본 커넥터는 reactor-netty의 JVM 전역 풀인데,
> 모듈 전체가 JVM 하나로 돌면서 스텁 서버 수십 개가 임시 포트에 떴다 죽는다. 죽은 소켓이
> 풀에 남아 `Connection prematurely closed`가 산발적으로 난다 — 클래스 격리 실행에서는
> 재현되지 않아 잡기 어렵다. 그 파일의 주석에 실측 표가 있다.
>
> **클라이언트는 커넥터를 주입받을 수 있어야 한다.** `FscCommodityClient`와 같은 모양으로:
> ```kotlin
> /** HTTP 커넥터. **운영은 null로 두고 기본값을 쓴다** */
> internal var connector: ClientHttpConnector? = null
>
> private val webClient = WebClient.builder()
>     .also { b -> connector?.let(b::clientConnector) }
>     .build()
> ```
> 아래 테스트 코드 블록은 **단언할 내용의 목록**으로 읽을 것 — 스텁을 띄우고 응답을 돌려주는
> 골격은 `FscCommodityClientTest`에서 가져온다.


**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dart/DartApiException.kt`
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dart/list/DartListClient.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/dart/list/DartListClientTest.kt`

**`DartApiException`은 `com.allfolio.dart`에 독립 파일로 둔다** — `dart.list`에 두면 Task 9(`dart/corp/`) ·
10(`dart/insider/`) · 12(`api/admin/`)가 수집 클라이언트도 아니면서 `list` 패키지를 임포트하게 된다.
`FscApiException`이 같은 이유로 `com.allfolio.market.fsc`에 독립 파일이고 두 클라이언트가 공유한다.

`FscCommodityClient`와 같은 구조다. 다른 점은 `status 013` 처리와 빈 문자열 정규화다.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
package com.allfolio.dart.list

import com.allfolio.dart.DartProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.allfolio.test.dedicatedConnector
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.LocalDate

/**
 * 응답 본문은 전부 2026-08-18 실측 원문에서 잘라 왔다.
 */
class DartListClientTest {

    private companion object { const val API_KEY = "SUPERSECRETDARTKEY1234" }

    private var server: HttpServer? = null
    private val received = java.util.concurrent.atomic.AtomicReference<String>()

    @AfterEach fun tearDown() { server?.stop(0) }

    /** 본문 하나를 200으로 돌려주는 루프백 스텁. 요청 쿼리를 [received]에 남긴다 */
    private fun serving(body: String): Int {
        val s = HttpServer.create(InetSocketAddress(0), 0)
        s.createContext("/") { ex ->
            received.set(ex.requestURI.rawQuery)
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start(); server = s
        return s.address.port
    }

    // dedicatedConnector를 쓰는 이유는 StubServerConnector.kt 주석에 있다 — 빼면 간헐적으로 깨진다
    private fun client(port: Int, key: String = API_KEY) = DartListClient(
        DartProperties(apiKey = key, baseUrl = "http://localhost:$port"),
        ObjectMapper(),
    ).apply { connector = dedicatedConnector() }

    private fun queryOf(raw: String): Map<String, String> =
        raw.split("&").associate { p ->
            val (n, v) = p.split("=", limit = 2)
            URLDecoder.decode(n, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

    @Test
    fun `정상 응답을 파싱한다`() {
        val port = serving("""
            {"status":"000","message":"정상","page_no":1,"page_count":10,"total_count":95,"total_page":10,
             "list":[{"corp_code":"00152880","corp_name":"코오롱글로벌","stock_code":"003070","corp_cls":"Y",
                      "report_nm":"단일판매ㆍ공급계약체결              ","rcept_no":"20260818800172",
                      "flr_nm":"코오롱글로벌","rcept_dt":"20260818","rm":"유"}]}
        """.trimIndent())

        val page = client(port).fetchPage(LocalDate.of(2026, 8, 18), LocalDate.of(2026, 8, 18), 1)

        assertThat(page.totalPage).isEqualTo(10)
        assertThat(page.rows).hasSize(1)
        with(page.rows.first()) {
            assertThat(rceptNo).isEqualTo("20260818800172")
            assertThat(stockCode).isEqualTo("003070")
            assertThat(rceptDt).isEqualTo(LocalDate.of(2026, 8, 18))
            assertThat(reportNm).isEqualTo("단일판매ㆍ공급계약체결")  // trim만, 정규화는 판정기가
        }
    }

    @Test
    fun `stock_code 빈 문자열은 null이 된다`() {
        // 실측 3,273건이 이 형태다(전부 corp_cls=E). NULL로 안 바꾸면 부분 인덱스가 죽는다.
        val port = serving("""
            {"status":"000","total_page":1,
             "list":[{"corp_code":"01888779","corp_name":"제이엠밸브","stock_code":"","corp_cls":"E",
                      "report_nm":"감사보고서 (2025.12)","rcept_no":"20260818000094",
                      "flr_nm":"모두공인회계사감사반(제547호)","rcept_dt":"20260818","rm":""}]}
        """.trimIndent())

        val page = client(port).fetchPage(LocalDate.of(2026, 8, 18), LocalDate.of(2026, 8, 18), 1)

        assertThat(page.rows.first().stockCode).isNull()
        assertThat(page.rows.first().rm).isNull()
    }

    @Test
    fun `status 013은 실패가 아니라 빈 결과다`() {
        // 공휴일 응답. 2026-08-17(광복절 대체공휴일)이 이것이었다.
        // 실패로 다루면 대체공휴일마다 배치가 빨갛게 된다.
        val port = serving("""{"status":"013","message":"조회된 데이타가 없습니다."}""")

        val page = client.fetchPage(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 17), 1)

        assertThat(page.rows).isEmpty()
        assertThat(page.totalPage).isZero()
        assertThat(page.emptyResult).isTrue()
    }

    @Test
    fun `그 밖의 status는 예외다`() {
        val port = serving("""{"status":"020","message":"요청 제한을 초과하였습니다."}""")

        assertThatThrownBy {
            client.fetchPage(LocalDate.of(2026, 8, 18), LocalDate.of(2026, 8, 18), 1)
        }.isInstanceOf(DartApiException::class.java)
            .hasMessageContaining("020")
    }

    @Test
    fun `인증키가 비면 호출하지 않고 예외를 던진다`() {
        // 아무도 듣지 않는 포트 — 호출이 나가면 연결 거부로 다른 예외가 된다
        val deadPort = java.net.ServerSocket(0).use { it.localPort }

        assertThatThrownBy {
            client(deadPort, key = "").fetchPage(LocalDate.of(2026, 8, 18), LocalDate.of(2026, 8, 18), 1)
        }.isInstanceOf(DartApiException::class.java).hasMessageContaining("DART_API_KEY")
    }

    @Test
    fun `예외 메시지에 인증키가 들어가지 않는다`() {
        // 이 메시지는 어드민 응답과 GitHub Actions 주석까지 나간다.
        val port = serving("""{"status":"020","message":"요청 제한을 초과하였습니다."}""")

        val thrown = runCatching {
            client.fetchPage(LocalDate.of(2026, 8, 18), LocalDate.of(2026, 8, 18), 1)
        }.exceptionOrNull()!!

        assertThat(thrown.stackTraceToString()).doesNotContain(API_KEY)
        assertThat(thrown.cause).isNull()
    }

    @Test
    fun `요청에 날짜와 페이지가 값으로 실린다`() {
        val port = serving("""{"status":"013","message":"조회된 데이타가 없습니다."}""")

        client(port).fetchPage(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 18), 3)

        // 문자열 통째 비교는 파라미터 순서가 바뀌면 깨진다 — 값으로 파싱해 본다
        val q = queryOf(received.get())
        assertThat(q["bgn_de"]).isEqualTo("20260817")
        assertThat(q["end_de"]).isEqualTo("20260818")
        assertThat(q["page_no"]).isEqualTo("3")
        assertThat(q["page_count"]).isEqualTo("100")
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.dart.list.DartListClientTest"
```

Expected: 컴파일 실패 — `Unresolved reference: DartListClient`

**의존성을 추가하지 말 것.** 위 경고대로 JDK `HttpServer`를 쓴다 — 추가할 것이 없다.

- [ ] **Step 3: 구현**

`DartApiException.kt`:

```kotlin
package com.allfolio.dart

/** OpenDART 호출 실패. **`cause`를 받지 않는다** — Reactor checkpoint 프레임에 요청 URI가
 *  통째로 들어 있고 거기 `crtfc_key=`가 실린다. 이 메시지는 어드민 응답과 Actions 주석까지 나간다 */
class DartApiException(message: String) : RuntimeException(message)
```

`DartListClient.kt`:

```kotlin
package com.allfolio.dart.list

import com.allfolio.dart.DartApiException
import com.allfolio.dart.DartProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** `list.json` 한 행. 저장 전 상태이므로 정규화·판정은 아직 안 했다 */
data class DartListRow(
    val rceptNo: String,
    val corpCode: String,
    val corpName: String,
    val stockCode: String?,
    val corpCls: String?,
    val reportNm: String,
    val rceptDt: LocalDate,
    val flrNm: String?,
    val rm: String?,
)

/** @param emptyResult status 013 — 공휴일 등 정상적으로 빈 결과 */
data class DartListPage(val rows: List<DartListRow>, val totalPage: Int, val emptyResult: Boolean)

/**
 * OpenDART 공시검색 `list.json`.
 *
 * **`status 013`은 실패가 아니다.** 공휴일에 오는 정상 응답이고(2026-08-17 대체공휴일 실측),
 * 실패로 올리면 공휴일마다 배치가 빨갛게 된다.
 *
 * **`stock_code`는 빈 문자열로 온다.** NULL이 아니다 — 실측 3,273건이 전부 `corp_cls=E`의
 * 빈 문자열이었다. 여기서 null로 정규화하지 않으면 `idx_disclosure_feed` 부분 인덱스
 * (`WHERE stock_code IS NOT NULL`)가 통째로 무용지물이 된다.
 *
 * **🔴 인증키가 쿼리 파라미터에 실린다.** 전체 URL을 로그에 찍지 않고, 예외에 `cause`를
 * 붙이지 않으며, 응답 본문 미리보기를 남기지 않는다. 이 예외 메시지는 어드민 응답과
 * GitHub Actions 주석까지 나가는 값이다.
 */
@Component
class DartListClient(
    private val props: DartProperties,
    private val objectMapper: ObjectMapper,
) {
    private val webClient = WebClient.builder().build()
    private val dateFmt = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun fetchPage(bgnDe: LocalDate, endDe: LocalDate, pageNo: Int): DartListPage {
        if (props.apiKey.isBlank()) {
            throw DartApiException("DART_API_KEY가 설정되지 않았다 — 수집을 시작할 수 없다")
        }

        val body = webClient.get()
            .uri("${props.baseUrl}/list.json") { b ->
                b.queryParam("crtfc_key", props.apiKey)
                    .queryParam("bgn_de", bgnDe.format(dateFmt))
                    .queryParam("end_de", endDe.format(dateFmt))
                    .queryParam("page_no", pageNo)
                    .queryParam("page_count", props.pageCount)
                    .build()
            }
            .retrieve()
            .bodyToMono(String::class.java)
            .block(Duration.ofSeconds(props.timeoutSeconds))
            ?: throw DartApiException("OpenDART 응답이 비었다 (page=$pageNo)")

        val node = objectMapper.readTree(body)
        return when (val status = node.path("status").asText()) {
            "000" -> DartListPage(
                rows = node.path("list").mapNotNull(::toRow),
                totalPage = node.path("total_page").asInt(0),
                emptyResult = false,
            )
            "013" -> DartListPage(emptyList(), 0, emptyResult = true)
            else -> throw DartApiException("OpenDART status=$status (page=$pageNo)")
        }
    }

    private fun toRow(n: JsonNode): DartListRow? {
        val rceptNo = n.path("rcept_no").asText().trim().ifBlank { return null }
        val rceptDt = runCatching { LocalDate.parse(n.path("rcept_dt").asText().trim(), dateFmt) }
            .getOrNull() ?: return null
        return DartListRow(
            rceptNo = rceptNo,
            corpCode = n.path("corp_code").asText().trim(),
            corpName = n.path("corp_name").asText().trim(),
            stockCode = n.path("stock_code").asText().trim().ifBlank { null },
            corpCls = n.path("corp_cls").asText().trim().ifBlank { null },
            reportNm = n.path("report_nm").asText().trim(),
            rceptDt = rceptDt,
            flrNm = n.path("flr_nm").asText().trim().ifBlank { null },
            rm = n.path("rm").asText().trim().ifBlank { null },
        )
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.dart.list.DartListClientTest"
```

Expected: PASS, 7 tests

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dart/DartApiException.kt \
        allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dart/list/DartListClient.kt \
        allfolio-backend/backend-app/src/test/kotlin/com/allfolio/dart/list/DartListClientTest.kt
git commit -m "feat(d1): list.json 클라이언트 — status 013은 공휴일이지 실패가 아니다"
```

---

## Task 7: 델타 확보 스토어 (`ON CONFLICT ... RETURNING`)

> ### ⚠️ 테스트 방식 — H2로 실행하지 않는다
>
> **H2 2.2.224는 PostgreSQL 모드에서도 `ON CONFLICT`와 `RETURNING`을 둘 다 지원하지 않는다.**
> 실측으로 확인했다:
> ```
> ON CONFLICT 미지원: Syntax error ... VALUES (?,?) [*]ON CONFLICT (k) DO NOTHING
> RETURNING  미지원: Syntax error ... VALUES (?,?) [*]ON CONFLICT (k) DO NOTHING RETURNING k
> ```
> CI(`deploy.yml`)는 순수 ubuntu + JDK 21에서 `./gradlew test`만 돌린다 — Postgres가 없다.
> 따라서 이 SQL은 **CI에서 실행할 수 없다.**
>
> **레포에 이미 같은 문제를 푼 선례가 있고, 그 방식을 따른다:**
> `unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/PerformanceSnapshotFakes.kt`의
> `CapturingJdbcTemplate`(= `JdbcTemplate()`을 상속해 `update`/`query`를 가로채고 SQL·인자를 모아 둠,
> DataSource 없이 동작). `PerformanceSnapshotDateTest`의 KDoc이 "H2 통합 경로는 막혀 있다 —
> INSERT도 Postgres 전용 ON CONFLICT다"라고 그 이유를 적어 두었다.
>
> `CapturingJdbcTemplate`은 `unified-asset` 테스트 소스라 `backend-app`에서 재사용할 수 없다.
> **같은 모양의 fake를 `backend-app` 테스트에 하나 만들 것** (예: `com.allfolio.dart.list.CapturingJdbc`).
>
> **검증 대상은 셋이다:**
> 1. **SQL 문자열** — `ON CONFLICT (rcept_no) DO NOTHING`과 `RETURNING rcept_no`가 들어 있는가
> 2. **바인딩 인자** — 14개 컬럼이 순서대로, `stockCode`가 null이면 null로
> 3. **배치 안 중복 접기와 델타 매핑** — 같은 `rcept_no`가 두 번 오면 한 번만 실행되는가,
>    `query`가 돌려준 행이 그대로 델타가 되는가 (fake의 `query` 반환값을 테스트가 정한다)
>
> **SQL 자체는 실제 Postgres로 1회 수동 검증하고 커밋 메시지에 남긴다** — Task 1의
> 마이그레이션과 같은 방식이다. 로컬에 `docker compose up -d postgres`로 띄우고,
> Task 1의 마이그레이션을 적용한 뒤 이 INSERT를 두 번 실행해 두 번째가 빈 결과를
> 돌려주는 것을 눈으로 볼 것. 그 출력을 보고에 인용할 것.


**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dart/list/JdbcDisclosureStore.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/dart/list/JdbcDisclosureStoreTest.kt`

**JPA로는 못 한다.** `saveAll`은 SELECT 후 INSERT/UPDATE라 "실제로 새로 들어간 행"을 알려주지 않는다. 델타가 곧 후속 처리의 입력이므로 이게 핵심이다. `NavCurrencyDailyStore`가 같은 이유로 `JdbcTemplate`을 쓴다.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
package com.allfolio.dart.list

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * SQL을 실행하지 않고 **가로챈다**. H2는 PostgreSQL 모드에서도 `ON CONFLICT`와 `RETURNING`을
 * 둘 다 지원하지 않고(실측), CI에는 Postgres가 없다. `PerformanceSnapshotDateTest`가 같은
 * 이유로 같은 방식을 쓴다 — 그쪽 KDoc에 근거가 있다.
 *
 * 그래서 이 테스트가 못 잡는 것이 있다: **SQL이 Postgres에서 실제로 도는지**. 그건 구현 시
 * 로컬 Postgres로 1회 확인하고 커밋 메시지에 남긴다.
 */
class JdbcDisclosureStoreTest {

    /** 실행 SQL과 바인딩 인자를 모아 두는 fake. DataSource 없이 동작한다(super 호출 없음) */
    private class CapturingJdbc(
        /** `RETURNING`이 돌려줄 rcept_no. 테스트가 "이미 있던 건"을 흉내 내려면 비운다 */
        var returning: (String) -> List<String> = { listOf(it) },
    ) : JdbcTemplate() {
        val queries = mutableListOf<Pair<String, List<Any?>>>()

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): List<T> {
            queries += sql to args.toList()
            return returning(args[0] as String) as List<T>
        }
    }

    private val now = LocalDateTime.of(2026, 8, 18, 19, 0)

    private fun row(rceptNo: String, stockCode: String? = "005930") = DisclosureInsert(
        rceptNo = rceptNo, corpCode = "00126380", corpName = "삼성전자",
        stockCode = stockCode, corpCls = "Y",
        reportNm = "단일판매ㆍ공급계약체결", reportNmNorm = "단일판매·공급계약체결",
        rceptDt = LocalDate.of(2026, 8, 18), flrNm = "삼성전자", rm = "유",
        isMaterial = true, materialTier = 1, isCorrection = false,
    )

    @Test
    fun `SQL에 ON CONFLICT DO NOTHING과 RETURNING이 들어간다`() {
        // 이 둘이 멱등성과 델타의 근거다. 하나라도 빠지면 재실행이 중복을 쌓거나
        // 델타가 전건이 되어 elestock을 매번 다시 부른다.
        val jdbc = CapturingJdbc()

        JdbcDisclosureStore(jdbc).insertIgnoringConflicts(listOf(row("A1")), now)

        val sql = jdbc.queries.single().first
        assertThat(sql).contains("INSERT INTO dart_disclosure")
        assertThat(sql).contains("ON CONFLICT (rcept_no) DO NOTHING")
        assertThat(sql).contains("RETURNING rcept_no")
    }

    @Test
    fun `14개 컬럼이 순서대로 바인딩된다`() {
        val jdbc = CapturingJdbc()

        JdbcDisclosureStore(jdbc).insertIgnoringConflicts(listOf(row("A1")), now)

        assertThat(jdbc.queries.single().second).containsExactly(
            "A1", "00126380", "삼성전자", "005930", "Y",
            "단일판매ㆍ공급계약체결", "단일판매·공급계약체결",
            LocalDate.of(2026, 8, 18), "삼성전자", "유",
            true, 1.toShort(), false, now,
        )
    }

    @Test
    fun `stock_code가 null이면 null로 바인딩된다`() {
        // 빈 문자열로 들어가면 부분 인덱스(WHERE stock_code IS NOT NULL)가 무용지물이 된다
        val jdbc = CapturingJdbc()

        JdbcDisclosureStore(jdbc).insertIgnoringConflicts(listOf(row("A1", stockCode = null)), now)

        assertThat(jdbc.queries.single().second[3]).isNull()
    }

    @Test
    fun `삽입된 행만 델타가 된다`() {
        // RETURNING이 빈 결과인 건 = 이미 있던 건
        val jdbc = CapturingJdbc(returning = { if (it == "A2") listOf(it) else emptyList() })

        val delta = JdbcDisclosureStore(jdbc)
            .insertIgnoringConflicts(listOf(row("A1"), row("A2")), now)

        assertThat(delta).containsExactly("A2")
    }

    @Test
    fun `한 배치 안의 중복은 한 번만 실행된다`() {
        // D-1과 D 범위가 겹쳐 같은 rcept_no가 두 번 올 수 있다.
        // ON CONFLICT는 같은 문(statement) 안의 중복을 못 막으므로 사전에 접어야 한다.
        val jdbc = CapturingJdbc()

        val delta = JdbcDisclosureStore(jdbc)
            .insertIgnoringConflicts(listOf(row("A1"), row("A1")), now)

        assertThat(jdbc.queries).hasSize(1)
        assertThat(delta).containsExactly("A1")
    }

    @Test
    fun `빈 목록이면 SQL을 아예 실행하지 않는다`() {
        val jdbc = CapturingJdbc()

        val delta = JdbcDisclosureStore(jdbc).insertIgnoringConflicts(emptyList(), now)

        assertThat(delta).isEmpty()
        assertThat(jdbc.queries).isEmpty()
    }

    @Test
    fun `선행 0이 붙은 rcept_no가 문자열 그대로 바인딩된다`() {
        // 숫자형으로 다루면 선행 0이 소실되어 원문 링크가 깨진다
        val jdbc = CapturingJdbc()

        JdbcDisclosureStore(jdbc).insertIgnoringConflicts(listOf(row("00260818000094")), now)

        assertThat(jdbc.queries.single().second[0]).isEqualTo("00260818000094")
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.dart.list.JdbcDisclosureStoreTest"
```

Expected: 컴파일 실패 — `Unresolved reference: JdbcDisclosureStore`

**H2를 추가하지 말 것.** 위 경고대로 `CapturingJdbcTemplate` 방식을 쓴다 — 이 SQL은 H2에서 실행 자체가 안 된다.

- [ ] **Step 3: 구현**

```kotlin
package com.allfolio.dart.list

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Types
import java.time.LocalDate
import java.time.LocalDateTime

data class DisclosureInsert(
    val rceptNo: String,
    val corpCode: String,
    val corpName: String,
    val stockCode: String?,
    val corpCls: String?,
    val reportNm: String,
    val reportNmNorm: String,
    val rceptDt: LocalDate,
    val flrNm: String?,
    val rm: String?,
    val isMaterial: Boolean,
    val materialTier: Short?,
    val isCorrection: Boolean,
)

/**
 * `ON CONFLICT (rcept_no) DO NOTHING RETURNING rcept_no`로 **실제로 삽입된 행만** 돌려준다.
 * 이 반환이 곧 델타이고, `elestock` 호출과 피드 노출은 오직 이것만 소비한다.
 * 배치를 몇 번 재실행해도 부작용이 없는 근거가 여기다.
 *
 * **JPA로는 안 된다** — `saveAll`은 SELECT 후 INSERT/UPDATE라 "새로 들어간 행"을 구분해
 * 주지 않는다. `NavCurrencyDailyStore`가 같은 이유로 JdbcTemplate을 쓴다.
 *
 * **배치 안 중복은 사전에 접는다.** `ON CONFLICT`는 같은 문 안에서 중복된 키를 막지 못한다.
 * D-1과 D 범위가 겹쳐 같은 `rcept_no`가 두 번 오는 경우가 실제로 있다.
 */
@Component
class JdbcDisclosureStore(private val jdbc: JdbcTemplate) {

    fun insertIgnoringConflicts(rows: List<DisclosureInsert>, collectedAt: LocalDateTime): List<String> {
        if (rows.isEmpty()) return emptyList()
        val deduped = rows.associateBy { it.rceptNo }.values

        return deduped.mapNotNull { r ->
            jdbc.query(
                """
                INSERT INTO dart_disclosure
                    (rcept_no, corp_code, corp_name, stock_code, corp_cls,
                     report_nm, report_nm_norm, rcept_dt, flr_nm, rm,
                     is_material, material_tier, is_correction, collected_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (rcept_no) DO NOTHING
                RETURNING rcept_no
                """.trimIndent(),
                { rs, _ -> rs.getString(1) },
                r.rceptNo, r.corpCode, r.corpName, r.stockCode, r.corpCls,
                r.reportNm, r.reportNmNorm, r.rceptDt, r.flrNm, r.rm,
                r.isMaterial, r.materialTier, r.isCorrection, collectedAt,
            ).firstOrNull()
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.dart.list.JdbcDisclosureStoreTest"
```

Expected: PASS, 7 tests

- [ ] **Step 5: SQL을 실제 Postgres로 1회 검증**

가로채기 테스트는 SQL이 Postgres에서 **실제로 도는지**를 못 잡는다. 여기서 한 번 눈으로 본다.

```bash
docker compose up -d postgres
psql "postgresql://allfolio:allfolio@localhost:5432/allfolio" \
  -f docs/superpowers/migrations/2026-08-18-dart-disclosure.sql
psql "postgresql://allfolio:allfolio@localhost:5432/allfolio" <<'SQL'
INSERT INTO dart_disclosure (rcept_no, corp_code, corp_name, stock_code, corp_cls,
    report_nm, report_nm_norm, rcept_dt, flr_nm, rm,
    is_material, material_tier, is_correction, collected_at)
VALUES ('20260818800172','00152880','코오롱글로벌','003070','Y',
    '단일판매ㆍ공급계약체결','단일판매·공급계약체결','2026-08-18','코오롱글로벌','유',
    true, 1, false, now())
ON CONFLICT (rcept_no) DO NOTHING
RETURNING rcept_no;
SQL
```

Expected: 1회차는 `20260818800172` 한 행, **2회차는 `(0 rows)`**. 그 출력을 커밋 메시지와 보고에 인용할 것. 정리는 `DELETE FROM dart_disclosure WHERE rcept_no = '20260818800172';`

- [ ] **Step 6: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dart/list/JdbcDisclosureStore.kt \
        allfolio-backend/backend-app/src/test/kotlin/com/allfolio/dart/list/JdbcDisclosureStoreTest.kt
git commit -m "feat(d1): ON CONFLICT RETURNING으로 델타를 얻는다 — JPA는 새 행을 못 알려준다"
```

---

## Task 8: 수집 서비스

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dart/list/DartDisclosureCollectService.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/dart/list/DartDisclosureCollectServiceTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
package com.allfolio.dart.list

import com.allfolio.unifiedasset.infrastructure.entity.DartCollectionRunEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

class DartDisclosureCollectServiceTest {

    private val endDe = LocalDate.of(2026, 8, 18)
    private val bgnDe = endDe.minusDays(1)
    private val now = LocalDateTime.of(2026, 8, 18, 19, 0)

    private class FakeClient(private val pages: List<DartListPage>) : ListPort {
        val requestedPages = mutableListOf<Int>()
        override fun fetchPage(bgnDe: LocalDate, endDe: LocalDate, pageNo: Int): DartListPage {
            requestedPages += pageNo
            return pages.getOrElse(pageNo - 1) { DartListPage(emptyList(), pages.size, false) }
        }
    }

    private class FakeStore : DartDisclosureCollectService.Store {
        val inserted = mutableListOf<DisclosureInsert>()
        var existing = mutableSetOf<String>()
        override fun insertIgnoringConflicts(rows: List<DisclosureInsert>, collectedAt: LocalDateTime): List<String> {
            val fresh = rows.filter { it.rceptNo !in existing }
            inserted += fresh
            existing += fresh.map { it.rceptNo }
            return fresh.map { it.rceptNo }
        }
    }

    private class FakeRuns : DartDisclosureCollectService.RunLog {
        val saved = mutableListOf<DartCollectionRunEntity>()
        override fun save(run: DartCollectionRunEntity) { saved += run }
    }

    private fun row(rceptNo: String, reportNm: String, stockCode: String? = "005930") = DartListRow(
        rceptNo = rceptNo, corpCode = "00126380", corpName = "삼성전자",
        stockCode = stockCode, corpCls = "Y", reportNm = reportNm,
        rceptDt = endDe, flrNm = "삼성전자", rm = "유",
    )

    private fun service(client: FakeClient, store: FakeStore, runs: FakeRuns) =
        DartDisclosureCollectService(client, store, runs)

    @Test
    fun `전 페이지를 순회한다`() {
        val client = FakeClient(listOf(
            DartListPage(listOf(row("A1", "유상증자결정")), totalPage = 3, emptyResult = false),
            DartListPage(listOf(row("A2", "반기보고서 (2026.06)")), 3, false),
            DartListPage(listOf(row("A3", "기업설명회(IR)개최")), 3, false),
        ))
        val store = FakeStore(); val runs = FakeRuns()

        val summary = service(client, store, runs).collect(bgnDe, endDe, now)

        assertThat(client.requestedPages).containsExactly(1, 2, 3)
        assertThat(summary.pagesFetched).isEqualTo(3)
        assertThat(summary.newCount).isEqualTo(3)
    }

    @Test
    fun `화이트리스트 판정 결과를 함께 저장한다`() {
        val client = FakeClient(listOf(DartListPage(listOf(
            row("A1", "단일판매ㆍ공급계약체결              "),
            row("A2", "반기보고서 (2026.06)"),
            row("A3", "기업설명회(IR)개최"),
        ), 1, false)))
        val store = FakeStore()

        service(client, store, FakeRuns()).collect(bgnDe, endDe, now)

        val byId = store.inserted.associateBy { it.rceptNo }
        assertThat(byId["A1"]!!.materialTier).isEqualTo(1)
        assertThat(byId["A1"]!!.reportNmNorm).isEqualTo("단일판매·공급계약체결")
        assertThat(byId["A2"]!!.materialTier).isEqualTo(5)
        assertThat(byId["A3"]!!.isMaterial).isFalse()
        assertThat(byId["A3"]!!.materialTier).isNull()
    }

    @Test
    fun `걸러낸 건도 저장한다`() {
        // 설계 원칙 4 — 무엇을 걸렀는지 되짚을 수 없으면 튜닝이 불가능하다
        val client = FakeClient(listOf(DartListPage(listOf(row("A3", "기업설명회(IR)개최")), 1, false)))
        val store = FakeStore()

        service(client, store, FakeRuns()).collect(bgnDe, endDe, now)

        assertThat(store.inserted).hasSize(1)
    }

    @Test
    fun `정정공시 접두어를 기록한다`() {
        val client = FakeClient(listOf(DartListPage(listOf(
            row("A1", "[기재정정]반기보고서 (2026.06)"),
        ), 1, false)))
        val store = FakeStore()

        service(client, store, FakeRuns()).collect(bgnDe, endDe, now)

        with(store.inserted.single()) {
            assertThat(isCorrection).isTrue()
            assertThat(reportNmNorm).isEqualTo("반기보고서 (2026.06)")
            assertThat(materialTier).isEqualTo(5)  // 접두어를 떼야 잡힌다
        }
    }

    @Test
    fun `공휴일이면 성공으로 기록하고 0건을 보고한다`() {
        val client = FakeClient(listOf(DartListPage(emptyList(), 0, emptyResult = true)))
        val runs = FakeRuns()

        val summary = service(client, FakeStore(), runs).collect(bgnDe, endDe, now)

        assertThat(summary.newCount).isZero()
        assertThat(summary.emptyResult).isTrue()
        assertThat(runs.saved.single().status).isEqualTo("SUCCESS")
    }

    @Test
    fun `실패하면 FAILED로 기록하고 예외를 올린다`() {
        val client = object : ListPort {
            override fun fetchPage(bgnDe: LocalDate, endDe: LocalDate, pageNo: Int): DartListPage =
                throw DartApiException("OpenDART status=020")
        }
        val runs = FakeRuns()

        runCatching { DartDisclosureCollectService(client, FakeStore(), runs).collect(bgnDe, endDe, now) }

        with(runs.saved.single()) {
            assertThat(status).isEqualTo("FAILED")
            assertThat(errorMsg).contains("020")
        }
    }

    @Test
    fun `이미 있는 건은 델타에서 빠진다`() {
        val client = FakeClient(listOf(DartListPage(listOf(row("A1", "유상증자결정")), 1, false)))
        val store = FakeStore().apply { existing += "A1" }

        val summary = service(client, store, FakeRuns()).collect(bgnDe, endDe, now)

        assertThat(summary.newCount).isZero()
        assertThat(summary.newRceptNos).isEmpty()
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.dart.list.DartDisclosureCollectServiceTest"
```

Expected: 컴파일 실패 — `Unresolved reference: DartDisclosureCollectService`

- [ ] **Step 3: 구현**

```kotlin
package com.allfolio.dart.list

import com.allfolio.dart.DartReportName
import com.allfolio.dart.DartWhitelist
import com.allfolio.unifiedasset.infrastructure.entity.DartCollectionRunEntity
import com.allfolio.unifiedasset.infrastructure.jpa.DartCollectionRunJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime

/** 클라이언트를 서비스에서 갈아 끼우기 위한 포트 */
interface ListPort {
    fun fetchPage(bgnDe: LocalDate, endDe: LocalDate, pageNo: Int): DartListPage
}

@Component
class DartListPortAdapter(private val client: DartListClient) : ListPort {
    override fun fetchPage(bgnDe: LocalDate, endDe: LocalDate, pageNo: Int) =
        client.fetchPage(bgnDe, endDe, pageNo)
}

data class DartCollectSummary(
    val bgnDe: LocalDate,
    val endDe: LocalDate,
    val pagesFetched: Int,
    val apiCalls: Int,
    val newCount: Int,
    val emptyResult: Boolean,
    /** 후속 처리(elestock 호출)가 소비하는 델타 */
    val newRceptNos: List<String>,
)

/**
 * `list.json`을 D-1~D 범위로 전 페이지 수집해 적재한다.
 *
 * **조기중단을 넣지 말 것.** "정렬이 항상 접수순"이라는 검증 불가능한 가정이 필요하고,
 * 아껴 봐야 하루 수십 콜이다(한도 20,000). 매 실행이 D-1을 재수집하므로 스윕도 이미 내장돼 있다.
 */
@Service
class DartDisclosureCollectService(
    private val client: ListPort,
    private val store: Store,
    private val runLog: RunLog,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    interface Store {
        fun insertIgnoringConflicts(rows: List<DisclosureInsert>, collectedAt: LocalDateTime): List<String>
    }

    interface RunLog {
        fun save(run: DartCollectionRunEntity)
    }

    fun collect(bgnDe: LocalDate, endDe: LocalDate, now: LocalDateTime): DartCollectSummary {
        val run = DartCollectionRunEntity(
            runAt = now, bgnDe = bgnDe, endDe = endDe,
            status = "FAILED", errorMsg = null, finishedAt = null,
        )

        try {
            val collected = mutableListOf<DartListRow>()
            var pageNo = 1
            var totalPage = 1
            var apiCalls = 0
            var emptyResult = false

            while (pageNo <= totalPage) {
                val page = client.fetchPage(bgnDe, endDe, pageNo)
                apiCalls++
                if (page.emptyResult) { emptyResult = true; break }
                collected += page.rows
                totalPage = page.totalPage
                pageNo++
            }

            val delta = store.insertIgnoringConflicts(collected.map(::toInsert), now)

            run.pagesFetched = if (emptyResult) 0 else pageNo - 1
            run.apiCalls = apiCalls
            run.newCount = delta.size
            run.status = "SUCCESS"
            run.finishedAt = now
            runLog.save(run)

            log.info(
                "[DART] 수집 완료 {}~{} pages={} new={} empty={}",
                bgnDe, endDe, run.pagesFetched, delta.size, emptyResult,
            )

            return DartCollectSummary(
                bgnDe = bgnDe, endDe = endDe,
                pagesFetched = run.pagesFetched, apiCalls = apiCalls,
                newCount = delta.size, emptyResult = emptyResult, newRceptNos = delta,
            )
        } catch (e: Exception) {
            run.status = "FAILED"
            run.errorMsg = e.message
            run.finishedAt = now
            runLog.save(run)
            throw e
        }
    }

    private fun toInsert(r: DartListRow): DisclosureInsert {
        val norm = DartReportName.normalize(r.reportNm)
        val tier = DartWhitelist.tierOf(norm)
        return DisclosureInsert(
            rceptNo = r.rceptNo, corpCode = r.corpCode, corpName = r.corpName,
            stockCode = r.stockCode, corpCls = r.corpCls,
            reportNm = r.reportNm, reportNmNorm = norm,
            rceptDt = r.rceptDt, flrNm = r.flrNm, rm = r.rm,
            isMaterial = DartWhitelist.isMaterial(tier),
            materialTier = tier,
            isCorrection = DartReportName.hasCorrectionPrefix(r.reportNm),
        )
    }
}

@Component
class JpaDartRunLog(
    private val repository: DartCollectionRunJpaRepository,
) : DartDisclosureCollectService.RunLog {
    override fun save(run: DartCollectionRunEntity) { repository.save(run) }
}
```

`JdbcDisclosureStore`가 `DartDisclosureCollectService.Store`를 구현하도록 선언을 고친다:

```kotlin
class JdbcDisclosureStore(private val jdbc: JdbcTemplate) : DartDisclosureCollectService.Store {
    override fun insertIgnoringConflicts(...)
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.dart.list.*"
```

Expected: PASS, 21 tests (Client 7 + Store 7 + Service 7)

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dart/list/ \
        allfolio-backend/backend-app/src/test/kotlin/com/allfolio/dart/list/
git commit -m "feat(d1): 공시 수집 서비스 — 델타만 후속 처리로 흘린다"
```

---

## Task 9: `corpCode.xml` 파서와 `dart_corp_map` 적재

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dart/corp/DartCorpCodeClient.kt`
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dart/corp/DartCorpMapService.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/dart/corp/DartCorpCodeClientTest.kt`

`corpCode.xml`은 **ZIP으로 온다** — JSON이 아니다. 응답이 `application/x-msdownload`라 Jackson으로 읽으면 깨진다.

> **⚠️ 이 테이블은 이 계획 안에서 아무도 읽지 않는다.** `list.json`이 행마다 `stock_code`를 이미 주기 때문에 수집·조회 어느 쪽도 매핑이 필요 없다. 그럼에도 만드는 이유는 둘이다:
> 1. `list.json`의 `stock_code`는 **수집 시점 스냅샷**이다. 상장폐지·코드변경이 나면 과거 행이 옛 코드를 든 채 굳는다. 권위 있는 현재 매핑이 따로 있어야 그때 되짚을 수 있다
> 2. `corp_code`만 아는 상태에서 종목을 찾는 역방향 조회(향후 종목별 공시 이력 화면)의 유일한 경로다
>
> **지금 쓰지 않는다는 사실이 불편하면 Task 9을 통째로 미뤄도 된다** — 나머지 태스크는 이것에 의존하지 않는다. 다만 나중에 붙이면 그 시점부터의 매핑만 갖게 되므로, 지금 시작해 두는 편이 싸다. 미루기로 했다면 Task 1의 `dart_corp_map` DDL과 Task 13의 corp-map 워크플로도 함께 뺀다.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
package com.allfolio.dart.corp

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DartCorpCodeClientTest {

    /** 실측 corpCode.xml 구조 그대로 */
    private fun zipOf(xml: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("CORPCODE.xml"))
            zip.write(xml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `ZIP을 풀어 매핑을 읽는다`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <result>
              <list><corp_code>00126380</corp_code><corp_name>삼성전자</corp_name>
                    <stock_code>005930</stock_code><modify_date>20260814</modify_date></list>
              <list><corp_code>01888779</corp_code><corp_name>제이엠밸브</corp_name>
                    <stock_code> </stock_code><modify_date>20260701</modify_date></list>
            </result>
        """.trimIndent()

        val rows = DartCorpCodeClient.parseZip(zipOf(xml))

        assertThat(rows).hasSize(2)
        with(rows[0]) {
            assertThat(corpCode).isEqualTo("00126380")
            assertThat(stockCode).isEqualTo("005930")
            assertThat(modifyDate).isEqualTo(java.time.LocalDate.of(2026, 8, 14))
        }
        // 비상장은 공백으로 온다 — null로 정규화해야 부분 인덱스가 산다
        assertThat(rows[1].stockCode).isNull()
    }

    @Test
    fun `modify_date가 없거나 이상하면 null로 둔다`() {
        val xml = """
            <result><list><corp_code>00000001</corp_code><corp_name>테스트</corp_name>
                    <stock_code>000001</stock_code><modify_date></modify_date></list></result>
        """.trimIndent()

        assertThat(DartCorpCodeClient.parseZip(zipOf(xml)).single().modifyDate).isNull()
    }

    @Test
    fun `corp_code가 없는 행은 버린다`() {
        val xml = """
            <result><list><corp_name>이름만있음</corp_name><stock_code>000001</stock_code></list></result>
        """.trimIndent()

        assertThat(DartCorpCodeClient.parseZip(zipOf(xml))).isEmpty()
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.dart.corp.DartCorpCodeClientTest"
```

Expected: 컴파일 실패 — `Unresolved reference: DartCorpCodeClient`

- [ ] **Step 3: 구현**

```kotlin
package com.allfolio.dart.corp

import com.allfolio.dart.DartProperties
import com.allfolio.dart.DartApiException
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

data class DartCorpRow(
    val corpCode: String,
    val corpName: String,
    val stockCode: String?,
    val modifyDate: LocalDate?,
)

/**
 * `corpCode.xml` — **ZIP으로 온다.** `application/x-msdownload`이므로 JSON 파서로 읽으면 깨진다.
 * 전 종목 매핑이라 응답이 수 MB다. 주 1회면 충분하다.
 */
@Component
class DartCorpCodeClient(private val props: DartProperties) {

    private val webClient = WebClient.builder()
        .codecs { it.defaultCodecs().maxInMemorySize(32 * 1024 * 1024) }
        .build()

    fun fetch(): List<DartCorpRow> {
        if (props.apiKey.isBlank()) throw DartApiException("DART_API_KEY가 설정되지 않았다")

        val bytes = webClient.get()
            .uri("${props.baseUrl}/corpCode.xml") { it.queryParam("crtfc_key", props.apiKey).build() }
            .retrieve()
            .bodyToMono(ByteArray::class.java)
            .block(Duration.ofSeconds(props.timeoutSeconds * 4))
            ?: throw DartApiException("corpCode 응답이 비었다")

        return parseZip(bytes)
    }

    companion object {
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")

        fun parseZip(zipBytes: ByteArray): List<DartCorpRow> {
            val xml = ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
                generateSequence { zip.nextEntry }
                    .firstOrNull { !it.isDirectory }
                    ?: throw DartApiException("corpCode ZIP이 비어 있다")
                zip.readBytes()
            }

            val doc = DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = false }
                .newDocumentBuilder()
                .parse(ByteArrayInputStream(xml))

            val nodes = doc.getElementsByTagName("list")
            return (0 until nodes.length).mapNotNull { i ->
                val el = nodes.item(i) as Element
                val corpCode = el.text("corp_code")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                DartCorpRow(
                    corpCode = corpCode,
                    corpName = el.text("corp_name").orEmpty(),
                    stockCode = el.text("stock_code")?.trim()?.ifBlank { null },
                    modifyDate = el.text("modify_date")?.trim()
                        ?.let { runCatching { LocalDate.parse(it, DATE_FMT) }.getOrNull() },
                )
            }
        }

        private fun Element.text(tag: String): String? =
            getElementsByTagName(tag).item(0)?.textContent?.trim()
    }
}
```

```kotlin
package com.allfolio.dart.corp

import com.allfolio.unifiedasset.infrastructure.entity.DartCorpMapEntity
import com.allfolio.unifiedasset.infrastructure.jpa.DartCorpMapJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

data class CorpMapSummary(val fetched: Int, val listed: Int)

/**
 * 주 1회 전량 갱신한다. 상장/폐지로 `stock_code`가 바뀌므로 upsert가 아니라 덮어쓰기다.
 */
@Service
class DartCorpMapService(
    private val client: DartCorpCodeClient,
    private val repository: DartCorpMapJpaRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun refresh(now: LocalDateTime): CorpMapSummary {
        val rows = client.fetch()
        val entities = rows.map {
            DartCorpMapEntity(
                corpCode = it.corpCode, corpName = it.corpName,
                stockCode = it.stockCode, modifyDate = it.modifyDate, updatedAt = now,
            )
        }
        repository.saveAll(entities)
        val listed = rows.count { it.stockCode != null }
        log.info("[DART] corp_map 갱신 total={} listed={}", rows.size, listed)
        return CorpMapSummary(fetched = rows.size, listed = listed)
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.dart.corp.*"
```

Expected: PASS, 3 tests

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dart/corp/ \
        allfolio-backend/backend-app/src/test/kotlin/com/allfolio/dart/corp/
git commit -m "feat(d1): corpCode.xml ZIP 파서 — JSON이 아니라 압축 파일로 온다"
```

---

## Task 10: `elestock` 클라이언트

> ### ⚠️ 테스트 방식 — MockWebServer를 쓰지 않는다
>
> **이 레포는 JDK 내장 `com.sun.net.httpserver.HttpServer`로 루프백 스텁을 띄운다.**
> MockWebServer도 WireMock도 의존성에 없고, 추가하지 않는다. 모범 사례를 그대로 따를 것:
>
> - 테스트 예시: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/commodity/fsc/FscCommodityClientTest.kt`
> - 커넥터 헬퍼: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/test/StubServerConnector.kt`의 `dedicatedConnector()`
>
> **`dedicatedConnector()`를 반드시 쓸 것.** 기본 커넥터는 reactor-netty의 JVM 전역 풀인데,
> 모듈 전체가 JVM 하나로 돌면서 스텁 서버 수십 개가 임시 포트에 떴다 죽는다. 죽은 소켓이
> 풀에 남아 `Connection prematurely closed`가 산발적으로 난다 — 클래스 격리 실행에서는
> 재현되지 않아 잡기 어렵다. 그 파일의 주석에 실측 표가 있다.
>
> **클라이언트는 커넥터를 주입받을 수 있어야 한다.** `FscCommodityClient`와 같은 모양으로:
> ```kotlin
> /** HTTP 커넥터. **운영은 null로 두고 기본값을 쓴다** */
> internal var connector: ClientHttpConnector? = null
>
> private val webClient = WebClient.builder()
>     .also { b -> connector?.let(b::clientConnector) }
>     .build()
> ```
> 아래 테스트 코드 블록은 **단언할 내용의 목록**으로 읽을 것 — 스텁을 띄우고 응답을 돌려주는
> 골격은 `FscCommodityClientTest`에서 가져온다.


**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dart/insider/DartElestockClient.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/dart/insider/DartElestockClientTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
package com.allfolio.dart.insider

import com.allfolio.dart.DartProperties
import com.allfolio.dart.DartApiException
import com.fasterxml.jackson.databind.ObjectMapper
import com.allfolio.test.dedicatedConnector
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 응답 본문은 2026-08-18 실측(한미약품 corp_code=00828497)에서 잘라 왔다.
 */
class DartElestockClientTest {

    private companion object { const val API_KEY = "SUPERSECRETDARTKEY1234" }

    private var server: HttpServer? = null

    @AfterEach fun tearDown() { server?.stop(0) }

    private fun serving(body: String): Int {
        val s = HttpServer.create(InetSocketAddress(0), 0)
        s.createContext("/") { ex ->
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start(); server = s
        return s.address.port
    }

    // dedicatedConnector를 쓰는 이유는 StubServerConnector.kt 주석에 있다 — 빼면 간헐적으로 깨진다
    private fun client(port: Int, key: String = API_KEY) = DartElestockClient(
        DartProperties(apiKey = key, baseUrl = "http://localhost:$port"),
        ObjectMapper(),
    ).apply { connector = dedicatedConnector() }

    @Test
    fun `실측 응답을 파싱한다`() {
        val port = serving("""
            {"status":"000","message":"정상","list":[
              {"rcept_no":"20241008000176","rcept_dt":"2024-10-08","corp_code":"00828497",
               "corp_name":"한미약품","repror":"국민연금공단","isu_exctv_rgist_at":"-",
               "isu_exctv_ofcps":"-","isu_main_shrholdr":"10%이상주주",
               "sp_stock_lmp_cnt":"1,291,197","sp_stock_lmp_irds_cnt":"2,924",
               "sp_stock_lmp_rate":"10.08","sp_stock_lmp_irds_rate":"0.02"}]}
        """.trimIndent())

        val rows = client(port).fetch("00828497")

        assertThat(rows).hasSize(1)
        with(rows.single()) {
            assertThat(rceptNo).isEqualTo("20241008000176")
            // list.json은 20260818, elestock은 2024-10-08 — 같은 이름에 포맷이 다르다
            assertThat(reportDate).isEqualTo(LocalDate.of(2024, 10, 8))
            assertThat(repror).isEqualTo("국민연금공단")
            assertThat(ownedQty).isEqualTo(1_291_197L)   // 콤마 제거
            assertThat(changeQty).isEqualTo(2_924L)
            assertThat(ownedRate).isEqualByComparingTo(BigDecimal("10.08"))
            // "-"는 결측이다. 빈 문자열로 저장하면 화면에 하이픈이 그대로 나간다
            assertThat(officerPosition).isNull()
            assertThat(isRegistered).isNull()
            assertThat(majorHolderType).isEqualTo("10%이상주주")
        }
    }

    @Test
    fun `음수 증감을 읽는다`() {
        val port = serving("""
            {"status":"000","list":[
              {"rcept_no":"20241010000358","rcept_dt":"2024-10-10","corp_code":"00828497",
               "corp_name":"한미약품","repror":"국민연금공단","isu_exctv_rgist_at":"-",
               "isu_exctv_ofcps":"-","isu_main_shrholdr":"10%이상주주",
               "sp_stock_lmp_cnt":"1,278,190","sp_stock_lmp_irds_cnt":"-13,007",
               "sp_stock_lmp_rate":"9.98","sp_stock_lmp_irds_rate":"-0.10"}]}
        """.trimIndent())

        with(client.fetch("00828497").single()) {
            assertThat(changeQty).isEqualTo(-13_007L)
            assertThat(changeRate).isEqualByComparingTo(BigDecimal("-0.10"))
        }
    }

    @Test
    fun `등기임원 여부를 불리언으로 읽는다`() {
        val port = serving("""
            {"status":"000","list":[
              {"rcept_no":"R1","rcept_dt":"2026-08-11","corp_code":"C1","corp_name":"회사",
               "repror":"홍길동","isu_exctv_rgist_at":"등기임원","isu_exctv_ofcps":"대표이사",
               "isu_main_shrholdr":"-","sp_stock_lmp_cnt":"100","sp_stock_lmp_irds_cnt":"10",
               "sp_stock_lmp_rate":"0.01","sp_stock_lmp_irds_rate":"0.00"}]}
        """.trimIndent())

        with(client.fetch("C1").single()) {
            assertThat(isRegistered).isTrue()
            assertThat(officerPosition).isEqualTo("대표이사")
            assertThat(majorHolderType).isNull()
        }
    }

    @Test
    fun `비등기임원은 false다`() {
        val port = serving("""
            {"status":"000","list":[
              {"rcept_no":"R1","rcept_dt":"2026-08-11","corp_code":"C1","corp_name":"회사",
               "repror":"홍길동","isu_exctv_rgist_at":"비등기임원","isu_exctv_ofcps":"상무",
               "isu_main_shrholdr":"-","sp_stock_lmp_cnt":"100","sp_stock_lmp_irds_cnt":"10",
               "sp_stock_lmp_rate":"0.01","sp_stock_lmp_irds_rate":"0.00"}]}
        """.trimIndent())

        assertThat(client.fetch("C1").single().isRegistered).isFalse()
    }

    @Test
    fun `status 013은 빈 목록이다`() {
        val port = serving("""{"status":"013","message":"조회된 데이타가 없습니다."}""")

        assertThat(client.fetch("00000000")).isEmpty()
    }

    @Test
    fun `그 밖의 status는 예외다`() {
        val port = serving("""{"status":"020","message":"요청 제한을 초과하였습니다."}""")

        assertThatThrownBy { client.fetch("C1") }
            .isInstanceOf(DartApiException::class.java).hasMessageContaining("020")
    }

    @Test
    fun `예외 메시지에 인증키가 들어가지 않는다`() {
        val port = serving("""{"status":"020","message":"요청 제한을 초과하였습니다."}""")

        val thrown = runCatching { client.fetch("C1") }.exceptionOrNull()!!
        assertThat(thrown.stackTraceToString()).doesNotContain(API_KEY)
        assertThat(thrown.cause).isNull()
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.dart.insider.DartElestockClientTest"
```

Expected: 컴파일 실패 — `Unresolved reference: DartElestockClient`

- [ ] **Step 3: 구현**

```kotlin
package com.allfolio.dart.insider

import com.allfolio.dart.DartProperties
import com.allfolio.dart.DartApiException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate

data class ElestockRow(
    val rceptNo: String,
    val corpCode: String,
    val repror: String,
    val officerPosition: String?,
    val isRegistered: Boolean?,
    val majorHolderType: String?,
    val reportDate: LocalDate,
    val ownedQty: Long?,
    val changeQty: Long?,
    val ownedRate: BigDecimal?,
    val changeRate: BigDecimal?,
)

/**
 * 임원·주요주주 특정증권등 소유상황보고 `elestock.json`.
 *
 * **변동사유 필드가 없다.** 30개사 3,922행의 필드 집합이 단일하고 취득/처분 방법에 해당하는
 * 키가 하나도 없다. 그래서 `change_type` 분류기를 만들 수 없고, 화면은 "소유수량 변동" 사실만
 * 낸다. **여기서 매수/매도를 추론하지 말 것** — 무상증자와 스톡옵션 행사가 장내매수로 둔갑한다.
 *
 * **기간 파라미터가 없어 회사 전체 이력(약 2년)이 온다.** 실측 최대 3,395행(삼성전자).
 * 신규 필터는 호출자([DartInsiderCollectService])가 델타 `rcept_no`로 건다.
 *
 * **`rcept_dt` 포맷이 `list.json`과 다르다** — 여기는 `2024-10-08`, 저기는 `20260818`.
 * 같은 파서를 돌려 쓰면 깨진다.
 */
@Component
class DartElestockClient(
    private val props: DartProperties,
    private val objectMapper: ObjectMapper,
) {
    private val webClient = WebClient.builder().build()

    fun fetch(corpCode: String): List<ElestockRow> {
        if (props.apiKey.isBlank()) throw DartApiException("DART_API_KEY가 설정되지 않았다")

        val body = webClient.get()
            .uri("${props.baseUrl}/elestock.json") { b ->
                b.queryParam("crtfc_key", props.apiKey).queryParam("corp_code", corpCode).build()
            }
            .retrieve()
            .bodyToMono(String::class.java)
            .block(Duration.ofSeconds(props.timeoutSeconds))
            ?: throw DartApiException("elestock 응답이 비었다 (corp_code=$corpCode)")

        val node = objectMapper.readTree(body)
        return when (val status = node.path("status").asText()) {
            "000" -> node.path("list").mapNotNull(::toRow)
            "013" -> emptyList()
            else -> throw DartApiException("elestock status=$status (corp_code=$corpCode)")
        }
    }

    private fun toRow(n: JsonNode): ElestockRow? {
        val rceptNo = n.path("rcept_no").asText().trim().ifBlank { return null }
        val reportDate = runCatching { LocalDate.parse(n.path("rcept_dt").asText().trim()) }
            .getOrNull() ?: return null
        return ElestockRow(
            rceptNo = rceptNo,
            corpCode = n.path("corp_code").asText().trim(),
            repror = n.path("repror").asText().trim(),
            officerPosition = n.dash("isu_exctv_ofcps"),
            isRegistered = when (n.dash("isu_exctv_rgist_at")) {
                "등기임원" -> true
                "비등기임원" -> false
                else -> null
            },
            majorHolderType = n.dash("isu_main_shrholdr"),
            reportDate = reportDate,
            ownedQty = n.longOrNull("sp_stock_lmp_cnt"),
            changeQty = n.longOrNull("sp_stock_lmp_irds_cnt"),
            ownedRate = n.decimalOrNull("sp_stock_lmp_rate"),
            changeRate = n.decimalOrNull("sp_stock_lmp_irds_rate"),
        )
    }

    /** 결측은 `"-"`로 온다. 그대로 저장하면 화면에 하이픈이 나간다 */
    private fun JsonNode.dash(field: String): String? =
        path(field).asText().trim().takeIf { it.isNotBlank() && it != "-" }

    private fun JsonNode.longOrNull(field: String): Long? =
        path(field).asText().replace(",", "").trim().toLongOrNull()

    private fun JsonNode.decimalOrNull(field: String): BigDecimal? =
        path(field).asText().replace(",", "").trim().toBigDecimalOrNull()
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.dart.insider.DartElestockClientTest"
```

Expected: PASS, 7 tests

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dart/insider/DartElestockClient.kt \
        allfolio-backend/backend-app/src/test/kotlin/com/allfolio/dart/insider/DartElestockClientTest.kt
git commit -m "feat(d1): elestock 클라이언트 — 변동사유 필드가 없어 매수·매도를 말하지 않는다"
```

---

## Task 11: 임원 소유변동 적재 서비스

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dart/insider/DartInsiderCollectService.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/dart/insider/DartInsiderCollectServiceTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
package com.allfolio.dart.insider

import com.allfolio.dart.DartApiException
import com.allfolio.unifiedasset.infrastructure.entity.DartDisclosureEntity
import com.allfolio.unifiedasset.infrastructure.entity.DartInsiderTradeEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

class DartInsiderCollectServiceTest {

    private val now = LocalDateTime.of(2026, 8, 18, 19, 0)

    private fun disclosure(rceptNo: String, corpCode: String, tier: Short?, stockCode: String? = "005930") =
        DartDisclosureEntity(
            rceptNo = rceptNo, corpCode = corpCode, corpName = "회사", stockCode = stockCode,
            corpCls = "Y", reportNm = "임원ㆍ주요주주특정증권등소유상황보고서",
            reportNmNorm = "임원·주요주주특정증권등소유상황보고서",
            rceptDt = LocalDate.of(2026, 8, 18), flrNm = "홍길동", rm = null,
            isMaterial = tier != null, materialTier = tier, isCorrection = false, collectedAt = now,
        )

    private fun elestock(rceptNo: String, corpCode: String = "C1", repror: String = "홍길동") = ElestockRow(
        rceptNo = rceptNo, corpCode = corpCode, repror = repror,
        officerPosition = "상무", isRegistered = false, majorHolderType = null,
        reportDate = LocalDate.of(2026, 8, 18), ownedQty = 1000L, changeQty = 10L,
        ownedRate = BigDecimal("0.01"), changeRate = BigDecimal("0.00"),
    )

    private class FakeClient(private val byCorp: Map<String, List<ElestockRow>>) : ElestockPort {
        val called = mutableListOf<String>()
        override fun fetch(corpCode: String): List<ElestockRow> {
            called += corpCode
            return byCorp[corpCode] ?: emptyList()
        }
    }

    private class FakeStore(val disclosures: List<DartDisclosureEntity>) : DartInsiderCollectService.Store {
        val saved = mutableListOf<DartInsiderTradeEntity>()
        var existingKeys = mutableSetOf<Pair<String, String>>()
        override fun findDisclosures(rceptNos: Collection<String>) =
            disclosures.filter { it.rceptNo in rceptNos }
        override fun findExistingKeys(rceptNos: Collection<String>) =
            existingKeys.filter { it.first in rceptNos }.toSet()
        override fun saveAll(rows: List<DartInsiderTradeEntity>) { saved += rows }
    }

    @Test
    fun `Tier 4 공시의 회사만 호출한다`() {
        val store = FakeStore(listOf(
            disclosure("R1", "C1", tier = 4),
            disclosure("R2", "C2", tier = 1),   // 유상증자 — 대상 아님
        ))
        val client = FakeClient(mapOf("C1" to listOf(elestock("R1"))))

        DartInsiderCollectService(client, store).collect(listOf("R1", "R2"), now)

        assertThat(client.called).containsExactly("C1")
    }

    @Test
    fun `회사 전체 이력 중 델타에 있는 건만 저장한다`() {
        // elestock은 기간 파라미터가 없어 약 2년치가 통째로 온다. 실측 최대 3,395행.
        val store = FakeStore(listOf(disclosure("R_NEW", "C1", tier = 4)))
        val client = FakeClient(mapOf("C1" to listOf(
            elestock("R_OLD_2024"), elestock("R_NEW"), elestock("R_OLD_2025"),
        )))

        val summary = DartInsiderCollectService(client, store).collect(listOf("R_NEW"), now)

        assertThat(store.saved.map { it.rceptNo }).containsExactly("R_NEW")
        assertThat(summary.inserted).isEqualTo(1)
    }

    @Test
    fun `같은 회사를 한 번만 호출한다`() {
        val store = FakeStore(listOf(
            disclosure("R1", "C1", tier = 4),
            disclosure("R2", "C1", tier = 4),
        ))
        val client = FakeClient(mapOf("C1" to listOf(elestock("R1"), elestock("R2", repror = "김철수"))))

        DartInsiderCollectService(client, store).collect(listOf("R1", "R2"), now)

        assertThat(client.called).containsExactly("C1")
        assertThat(store.saved).hasSize(2)
    }

    @Test
    fun `이미 저장된 조합은 다시 넣지 않는다`() {
        // uq_insider (rcept_no, repror) — 재실행해도 중복이 쌓이면 안 된다
        val store = FakeStore(listOf(disclosure("R1", "C1", tier = 4)))
            .apply { existingKeys += ("R1" to "홍길동") }
        val client = FakeClient(mapOf("C1" to listOf(elestock("R1"))))

        val summary = DartInsiderCollectService(client, store).collect(listOf("R1"), now)

        assertThat(store.saved).isEmpty()
        assertThat(summary.inserted).isZero()
    }

    @Test
    fun `공시의 stock_code를 그대로 물려준다`() {
        val store = FakeStore(listOf(disclosure("R1", "C1", tier = 4, stockCode = "494120")))
        val client = FakeClient(mapOf("C1" to listOf(elestock("R1"))))

        DartInsiderCollectService(client, store).collect(listOf("R1"), now)

        assertThat(store.saved.single().stockCode).isEqualTo("494120")
    }

    @Test
    fun `한 회사가 실패해도 나머지는 진행한다`() {
        val store = FakeStore(listOf(
            disclosure("R1", "C1", tier = 4),
            disclosure("R2", "C2", tier = 4),
        ))
        val client = object : ElestockPort {
            override fun fetch(corpCode: String): List<ElestockRow> =
                if (corpCode == "C1") throw DartApiException("elestock status=020")
                else listOf(elestock("R2", corpCode = "C2"))
        }

        val summary = DartInsiderCollectService(client, store).collect(listOf("R1", "R2"), now)

        assertThat(store.saved.map { it.rceptNo }).containsExactly("R2")
        assertThat(summary.failures).hasSize(1)
        assertThat(summary.calls).isEqualTo(2)
    }

    @Test
    fun `델타가 비면 호출하지 않는다`() {
        val client = FakeClient(emptyMap())

        val summary = DartInsiderCollectService(client, FakeStore(emptyList())).collect(emptyList(), now)

        assertThat(client.called).isEmpty()
        assertThat(summary.calls).isZero()
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.dart.insider.DartInsiderCollectServiceTest"
```

Expected: 컴파일 실패 — `Unresolved reference: DartInsiderCollectService`

- [ ] **Step 3: 구현**

```kotlin
package com.allfolio.dart.insider

import com.allfolio.dart.DartWhitelist
import com.allfolio.unifiedasset.infrastructure.entity.DartDisclosureEntity
import com.allfolio.unifiedasset.infrastructure.entity.DartInsiderTradeEntity
import com.allfolio.unifiedasset.infrastructure.jpa.DartDisclosureJpaRepository
import com.allfolio.unifiedasset.infrastructure.jpa.DartInsiderTradeJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.LocalDateTime

interface ElestockPort {
    fun fetch(corpCode: String): List<ElestockRow>
}

@Component
class ElestockPortAdapter(private val client: DartElestockClient) : ElestockPort {
    override fun fetch(corpCode: String) = client.fetch(corpCode)
}

data class InsiderCollectSummary(
    val calls: Int,
    val inserted: Int,
    val failures: List<String>,
)

/**
 * 델타 중 Tier 4(임원·주요주주 보고서) 공시의 회사만 `elestock`을 부르고, **응답 중 델타에
 * 들어 있는 `rcept_no`만** 적재한다.
 *
 * 이 필터가 핵심이다 — `elestock`은 기간 파라미터가 없어 회사 전체 이력(약 2년, 실측 최대
 * 3,395행)을 통째로 준다. 걸러내지 않으면 재호출마다 같은 이력이 다시 들어온다.
 *
 * **한 회사의 실패가 나머지를 막지 않는다.** 사유는 요약에 실려 어드민 응답과 Actions 주석까지
 * 나간다. 공시 수집(TX1)은 이미 커밋됐으므로 여기서 예외를 올려도 그쪽은 롤백되지 않는다.
 */
@Service
class DartInsiderCollectService(
    private val client: ElestockPort,
    private val store: Store,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    interface Store {
        fun findDisclosures(rceptNos: Collection<String>): List<DartDisclosureEntity>
        /** 이미 저장된 (rcept_no, repror) 조합 — uq_insider와 같은 키 */
        fun findExistingKeys(rceptNos: Collection<String>): Set<Pair<String, String>>
        fun saveAll(rows: List<DartInsiderTradeEntity>)
    }

    fun collect(deltaRceptNos: List<String>, now: LocalDateTime): InsiderCollectSummary {
        if (deltaRceptNos.isEmpty()) return InsiderCollectSummary(0, 0, emptyList())

        val triggers = store.findDisclosures(deltaRceptNos)
            .filter { it.materialTier == DartWhitelist.TIER_INSIDER }
        if (triggers.isEmpty()) return InsiderCollectSummary(0, 0, emptyList())

        val deltaSet = deltaRceptNos.toSet()
        val stockCodeByRcept = triggers.associate { it.rceptNo to it.stockCode }
        val existing = store.findExistingKeys(deltaSet).toMutableSet()

        var calls = 0
        var inserted = 0
        val failures = mutableListOf<String>()

        triggers.map { it.corpCode }.distinct().forEach { corpCode ->
            calls++
            runCatching { client.fetch(corpCode) }
                .onFailure { e ->
                    failures += "corp_code=$corpCode: ${e.message}"
                    log.warn("[DART] elestock 실패 corp_code={}: {}", corpCode, e.message)
                }
                .onSuccess { rows ->
                    val fresh = rows
                        .filter { it.rceptNo in deltaSet }
                        .filter { (it.rceptNo to it.repror) !in existing }
                    fresh.forEach { existing += it.rceptNo to it.repror }

                    val entities = fresh.map { r ->
                        DartInsiderTradeEntity(
                            rceptNo = r.rceptNo, corpCode = r.corpCode,
                            stockCode = stockCodeByRcept[r.rceptNo],
                            repror = r.repror, officerPosition = r.officerPosition,
                            isRegistered = r.isRegistered, majorHolderType = r.majorHolderType,
                            reportDate = r.reportDate, ownedQty = r.ownedQty, changeQty = r.changeQty,
                            ownedRate = r.ownedRate, changeRate = r.changeRate, collectedAt = now,
                        )
                    }
                    if (entities.isNotEmpty()) store.saveAll(entities)
                    inserted += entities.size
                }
        }

        log.info("[DART] 소유변동 적재 calls={} inserted={} failures={}", calls, inserted, failures.size)
        return InsiderCollectSummary(calls, inserted, failures)
    }
}

@Component
class JpaInsiderStore(
    private val disclosures: DartDisclosureJpaRepository,
    private val trades: DartInsiderTradeJpaRepository,
) : DartInsiderCollectService.Store {

    override fun findDisclosures(rceptNos: Collection<String>) =
        disclosures.findByRceptNoIn(rceptNos)

    override fun findExistingKeys(rceptNos: Collection<String>): Set<Pair<String, String>> =
        trades.findByRceptNoIn(rceptNos).map { it.rceptNo to it.repror }.toSet()

    override fun saveAll(rows: List<DartInsiderTradeEntity>) { trades.saveAll(rows) }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.dart.insider.*"
```

Expected: PASS, 14 tests

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dart/insider/ \
        allfolio-backend/backend-app/src/test/kotlin/com/allfolio/dart/insider/
git commit -m "feat(d1): 소유변동 적재 — elestock은 2년치를 통째로 주므로 델타로 거른다"
```

---

## Task 12: 어드민 컨트롤러와 스케줄러 트리거

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/api/admin/DartAdminController.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/api/scheduler/SchedulerTriggerController.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/dart/DartCollectOrchestrationTest.kt`

트랜잭션 경계는 스펙 2절대로 셋으로 나눈다. `elestock` 실패가 공시 수집을 롤백시키면 안 된다.

- [ ] **Step 1: 오케스트레이션 테스트 작성**

```kotlin
package com.allfolio.dart

import com.allfolio.dart.insider.InsiderCollectSummary
import com.allfolio.dart.DartApiException
import com.allfolio.dart.list.DartCollectSummary
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 공시 수집이 커밋된 뒤 elestock이 실패해도 공시는 남아야 한다.
 */
class DartCollectOrchestrationTest {

    private val endDe = LocalDate.of(2026, 8, 18)
    private val now = LocalDateTime.of(2026, 8, 18, 19, 0)

    @Test
    fun `기본 범위는 D-1부터 D까지다`() {
        var captured: Pair<LocalDate, LocalDate>? = null

        DartRunPlan.run(
            endDe, now,
            collectDisclosures = { b, e, _ ->
                captured = b to e
                DartCollectSummary(b, e, 1, 1, 0, false, emptyList())
            },
            collectInsiders = { _, _ -> InsiderCollectSummary(0, 0, emptyList()) },
        )

        assertThat(captured).isEqualTo(endDe.minusDays(1) to endDe)
    }

    @Test
    fun `elestock이 실패해도 공시 수집 결과를 돌려준다`() {
        val result = DartRunPlan.run(
            endDe, now,
            collectDisclosures = { b, e, _ -> DartCollectSummary(b, e, 2, 2, 3, false, listOf("R1")) },
            collectInsiders = { _, _ -> throw DartApiException("elestock status=020") },
        )

        assertThat(result.disclosure.newCount).isEqualTo(3)
        assertThat(result.insider.failures).hasSize(1)
    }

    @Test
    fun `델타가 비면 elestock을 부르지 않는다`() {
        var called = false

        DartRunPlan.run(
            endDe, now,
            collectDisclosures = { b, e, _ -> DartCollectSummary(b, e, 0, 1, 0, true, emptyList()) },
            collectInsiders = { _, _ -> called = true; InsiderCollectSummary(0, 0, emptyList()) },
        )

        assertThat(called).isFalse()
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.dart.DartCollectOrchestrationTest"
```

Expected: 컴파일 실패 — `Unresolved reference: DartCollectOrchestrator`

- [ ] **Step 3: 오케스트레이터 구현**

```kotlin
package com.allfolio.dart

import com.allfolio.dart.insider.DartInsiderCollectService
import com.allfolio.dart.insider.InsiderCollectSummary
import com.allfolio.dart.list.DartCollectSummary
import com.allfolio.dart.list.DartDisclosureCollectService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime

data class DartRunResult(val disclosure: DartCollectSummary, val insider: InsiderCollectSummary)

/**
 * 트랜잭션 경계를 셋으로 나눈다 — **`elestock` 실패가 공시 수집을 롤백시키면 안 된다.**
 * 각 서비스가 자기 `@Transactional`을 갖고, 여기서는 순서만 정한다.
 */
/**
 * 순수 오케스트레이션. 스프링 빈이 아니라 함수라 테스트가 서비스 스텁 없이 직접 부른다.
 *
 * **로직을 [DartCollectOrchestrator] 안으로 다시 넣지 말 것** — `@Service` 클래스에 생성자를
 * 둘 두면(하나는 서비스용, 하나는 람다용) 스프링이 어느 쪽으로 주입할지 모른다.
 */
object DartRunPlan {
    fun run(
        endDe: LocalDate,
        now: LocalDateTime,
        collectDisclosures: (LocalDate, LocalDate, LocalDateTime) -> DartCollectSummary,
        collectInsiders: (List<String>, LocalDateTime) -> InsiderCollectSummary,
        onInsiderFailure: (Exception) -> Unit = {},
    ): DartRunResult {
        val disclosure = collectDisclosures(endDe.minusDays(1), endDe, now)

        if (disclosure.newRceptNos.isEmpty()) {
            return DartRunResult(disclosure, InsiderCollectSummary(0, 0, emptyList()))
        }

        val insider = try {
            collectInsiders(disclosure.newRceptNos, now)
        } catch (e: Exception) {
            onInsiderFailure(e)
            InsiderCollectSummary(0, 0, listOf(e.message ?: "unknown"))
        }

        return DartRunResult(disclosure, insider)
    }
}

@Service
class DartCollectOrchestrator(
    private val disclosureService: DartDisclosureCollectService,
    private val insiderService: DartInsiderCollectService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(endDe: LocalDate, now: LocalDateTime): DartRunResult =
        DartRunPlan.run(
            endDe, now,
            collectDisclosures = disclosureService::collect,
            collectInsiders = insiderService::collect,
            onInsiderFailure = { e ->
                log.warn("[DART] 소유변동 단계 실패 — 공시 수집은 유지된다: {}", e.message)
            },
        )
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.dart.DartCollectOrchestrationTest"
```

Expected: PASS, 3 tests

- [ ] **Step 5: 어드민 컨트롤러 작성**

```kotlin
package com.allfolio.api.admin

import com.allfolio.dart.DartCollectOrchestrator
import com.allfolio.dart.DartRunResult
import com.allfolio.dart.corp.CorpMapSummary
import com.allfolio.dart.corp.DartCorpMapService
import com.allfolio.dart.DartApiException
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 컨테이너가 UTC라 `LocalDate.now()`를 그냥 부르면 19:00 KST 실행이 "어제"를 조회한다.
 * KST 기준으로 오늘을 정한다 — 시세 수집기들과 같은 함정이다.
 */
private val KST: ZoneId = ZoneId.of("Asia/Seoul")

@RestController
@RequestMapping("/api/admin/dart")
class DartAdminController(
    private val orchestrator: DartCollectOrchestrator,
    private val corpMapService: DartCorpMapService,
) {
    @PostMapping("/collect")
    fun collect(
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) endDe: LocalDate?,
    ): ResponseEntity<DartRunResult> {
        val target = endDe ?: LocalDate.now(KST)
        return try {
            ResponseEntity.ok(orchestrator.run(target, LocalDateTime.now(KST)))
        } catch (e: DartApiException) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, e.message)
        }
    }

    @PostMapping("/corp-map/refresh")
    fun refreshCorpMap(): ResponseEntity<CorpMapSummary> =
        try {
            ResponseEntity.ok(corpMapService.refresh(LocalDateTime.now(KST)))
        } catch (e: DartApiException) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, e.message)
        }
}
```

- [ ] **Step 6: 스케줄러 트리거에 엔드포인트 추가**

`SchedulerTriggerController`의 생성자에 `private val dartAdmin: DartAdminController,`를 추가하고, 아래 메서드를 클래스 안에 넣는다. **`endDe`를 노출하지 않는다** — 어드민 쪽이 null을 KST 오늘로 해석한다.

```kotlin
    /**
     * POST /api/internal/scheduler/dart/collect — 공시 수집 트리거
     *
     * 날짜를 노출하지 않는다. [DartAdminController.collect]가 null을 KST 오늘로 해석하고,
     * Render 컨테이너는 UTC라 이 기본값 처리가 없으면 19:00 KST 실행이 "어제"를 조회한다.
     */
    @PostMapping("/dart/collect")
    fun collectDart(
        @RequestHeader(name = TOKEN_HEADER, required = false) token: String?,
    ): ResponseEntity<com.allfolio.dart.DartRunResult> {
        authorize(token)
        return dartAdmin.collect(null)
    }

    /** POST /api/internal/scheduler/dart/corp-map — corp_code 매핑 갱신 (주 1회) */
    @PostMapping("/dart/corp-map")
    fun refreshDartCorpMap(
        @RequestHeader(name = TOKEN_HEADER, required = false) token: String?,
    ): ResponseEntity<com.allfolio.dart.corp.CorpMapSummary> {
        authorize(token)
        return dartAdmin.refreshCorpMap()
    }
```

- [ ] **Step 7: 전체 테스트와 컴파일 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:test
```

Expected: BUILD SUCCESSFUL, 기존 테스트 회귀 없음

- [ ] **Step 8: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/api/ \
        allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dart/DartCollectOrchestrator.kt \
        allfolio-backend/backend-app/src/test/kotlin/com/allfolio/dart/DartCollectOrchestrationTest.kt
git commit -m "feat(d1): 수집 트리거 엔드포인트 — elestock 실패가 공시를 롤백시키지 않는다"
```

---

## Task 13: GitHub Actions 워크플로

**Files:**
- Create: `.github/workflows/collect-dart.yml`

기존 예약 워크플로(`collect-commodity.yml`)를 템플릿으로 삼는다. **재시도 예산은 300초/retry 1/timeout 14분** — 콜드 스타트 실측이 280초이고, 재시도가 서버 수집을 겹쳐 돌려 이미 성공한 실행을 실패로 만든 전례가 있다.

- [ ] **Step 1: 워크플로 작성**

```yaml
name: Collect DART Disclosure

# Render 무료 플랜에는 크론 잡이 없고 무료 웹 서비스는 15분 유휴 시 잠들어, 인스턴스 안의
# @Scheduled만으로는 주기 실행이 성립하지 않는다. collect-commodity.yml과 같은 구조다.
#
# 19:00 KST인 이유: 그 시각이면 당일 공시가 사실상 다 반영된다. 이후 접수분과 반영 지연 건은
# 다음날 배치의 D-1 범위가 자동으로 회수한다. 별도 스윕을 만들지 말 것 — 이미 내장돼 있다.
#
# **요일 필드(1-6)가 성립하는 조건은 "UTC 15:00 이전"이다.** cron은 UTC로 해석되므로 여기 적힌
# 요일은 UTC 요일이고, 우리가 원하는 건 KST 요일이다. UTC 10:00 + 9시간 = 같은 날 KST 19:00이라
# 두 요일이 같다. **시각을 UTC 15:00 이후로 옮기면 KST가 다음 날로 넘어가 토요일 실행이
# 일요일이 되면서 1-6에서 조용히 사라진다** — 실행이 안 되는 게 아니라 "요일이 하나 없어지는"
# 방식으로 틀리기 때문에 눈에 안 띈다. 시각을 옮기려는 사람은 이 문단을 먼저 읽을 것.
#
# 공휴일에는 OpenDART가 status 013("조회된 데이타가 없습니다")을 준다. 백엔드가 이걸 정상
# 공백으로 처리하므로 잡은 초록으로 끝난다. 빨간 잡이 뜨면 그건 진짜 실패다.

on:
  schedule:
    - cron: "0 10 * * 1-6"   # UTC 10:00 = KST 19:00 (월~토)
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  # 겹치면 델타가 갈린다: 두 실행이 같은 rcept_no를 동시에 "없다"고 읽으면 ON CONFLICT가
  # 한쪽만 살리는데, 진 쪽은 델타가 비어 elestock 호출을 건너뛴다. 공시는 남고 소유변동만
  # 빠지는 조용한 결손이 된다.
  group: collect-dart
  cancel-in-progress: false

jobs:
  collect:
    runs-on: ubuntu-latest
    timeout-minutes: 14
    steps:
      - name: 공시 수집 트리거
        env:
          BACKEND_URL: ${{ secrets.BACKEND_URL }}
          SCHEDULER_TOKEN: ${{ secrets.SCHEDULER_TOKEN }}
        run: |
          set -uo pipefail

          # Render 무료 인스턴스 콜드 스타트는 실측 280초다("30~90초"는 틀린 수치였다).
          # 시도당 시간을 넉넉히 주고 횟수를 줄인다 — 재시도가 서버 수집을 겹쳐 돌리면
          # 이미 성공한 실행이 빨갛게 된다.
          MAX_TIME=300
          RETRIES=1

          for i in $(seq 0 "$RETRIES"); do
            [ "$i" -gt 0 ] && echo "재시도 $i/$RETRIES" && sleep 15

            HTTP_CODE=$(curl -sS -o /tmp/dart.json -w "%{http_code}" \
              --max-time "$MAX_TIME" \
              -X POST "${BACKEND_URL}/api/internal/scheduler/dart/collect" \
              -H "X-Scheduler-Token: ${SCHEDULER_TOKEN}" || echo "000")

            [ "$HTTP_CODE" = "200" ] && break
          done

          echo "HTTP ${HTTP_CODE}"
          cat /tmp/dart.json 2>/dev/null || true
          echo

          if [ "$HTTP_CODE" != "200" ]; then
            # 운영 Neon에 docs/superpowers/migrations/2026-08-18-dart-disclosure.sql을 적용했는지
            # 확인할 것. ddl-auto: none이라 아무도 대신 만들어 주지 않는다.
            echo "::error::공시 수집 실패 (HTTP ${HTTP_CODE}). 500=대개 dart_* 테이블 부재(docs/superpowers/migrations/2026-08-18-dart-disclosure.sql을 운영 Neon에 적용했는지 확인 — ddl-auto: none이라 아무도 대신 만들어 주지 않는다. 위 응답 본문이 말해 준다) / 502=OpenDART 응답 이상(status 013은 정상 공백이라 여기 안 온다) / 503=SCHEDULER_TOKEN 미설정 / 401=토큰 불일치(양끝 공백·개행 확인) / 404=BACKEND_URL 경로 확인 / 000=백엔드 응답 없음(콜드 스타트·네트워크)"
            exit 1
          fi

          # 요약을 잡 로그 위쪽에 남긴다 — 매일 열어 보지 않아도 눈에 띄게
          python3 -c "
          import json,sys
          d=json.load(open('/tmp/dart.json'))
          dis,ins=d['disclosure'],d['insider']
          print(f\"::notice::공시 {dis['bgnDe']}~{dis['endDe']} pages={dis['pagesFetched']} new={dis['newCount']} empty={dis['emptyResult']} / 소유변동 calls={ins['calls']} inserted={ins['inserted']} failures={len(ins['failures'])}\")
          " || true
```

- [ ] **Step 2: corp_map 갱신 워크플로 작성**

`.github/workflows/collect-dart-corpmap.yml`:

```yaml
name: Refresh DART Corp Map

# corp_code ↔ stock_code 매핑. 전 종목이라 응답이 수 MB고 ZIP으로 온다.
# 주 1회면 충분하다 — 상장/폐지가 하루 단위로 쏟아지지 않는다.
# 일요일에 도는 이유: collect-dart.yml이 월~토라 겹치지 않는다.

on:
  schedule:
    - cron: "0 10 * * 0"   # UTC 10:00 일요일 = KST 19:00 일요일
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: collect-dart-corpmap
  cancel-in-progress: false

jobs:
  refresh:
    runs-on: ubuntu-latest
    timeout-minutes: 14
    steps:
      - name: corp_map 갱신 트리거
        env:
          BACKEND_URL: ${{ secrets.BACKEND_URL }}
          SCHEDULER_TOKEN: ${{ secrets.SCHEDULER_TOKEN }}
        run: |
          set -uo pipefail
          MAX_TIME=300
          RETRIES=1

          for i in $(seq 0 "$RETRIES"); do
            [ "$i" -gt 0 ] && echo "재시도 $i/$RETRIES" && sleep 15
            HTTP_CODE=$(curl -sS -o /tmp/corpmap.json -w "%{http_code}" \
              --max-time "$MAX_TIME" \
              -X POST "${BACKEND_URL}/api/internal/scheduler/dart/corp-map" \
              -H "X-Scheduler-Token: ${SCHEDULER_TOKEN}" || echo "000")
            [ "$HTTP_CODE" = "200" ] && break
          done

          echo "HTTP ${HTTP_CODE}"
          cat /tmp/corpmap.json 2>/dev/null || true
          echo

          if [ "$HTTP_CODE" != "200" ]; then
            echo "::error::corp_map 갱신 실패 (HTTP ${HTTP_CODE}). 500=dart_corp_map 테이블 부재 확인 / 502=OpenDART 응답 이상 / 503=SCHEDULER_TOKEN 미설정 / 401=토큰 불일치 / 000=백엔드 응답 없음"
            exit 1
          fi
```

- [ ] **Step 3: YAML 문법 확인**

```bash
python3 -c "import yaml,sys; [yaml.safe_load(open(f)) for f in ['.github/workflows/collect-dart.yml','.github/workflows/collect-dart-corpmap.yml']]; print('OK')"
```

Expected: `OK`

- [ ] **Step 4: 커밋**

```bash
git add .github/workflows/collect-dart.yml .github/workflows/collect-dart-corpmap.yml
git commit -m "feat(d1): 공시 수집 예약 워크플로 — 19:00 KST, 콜드 스타트 예산 300초"
```

---

## Task 14: 피드 조회 API

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dart/query/DisclosureFeedService.kt`
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/api/dart/DisclosureFeedController.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/dart/query/DisclosureFeedServiceTest.kt`

**조인 대상은 `ua_assets`다.** `ua_assets.symbol`이 KIS `pdno`(6자리 단축코드)로 채워지고, 공공데이터포털 `srtnCd`와 같은 값이다. `position_daily`는 `assetId` 기준 일별 스냅샷이라 종목코드가 없다.

`type = 'STOCK'` 필터를 건다 — 해외주식 티커(`AAPL`)가 6자리 종목코드와 충돌할 일은 없지만, 필터가 있으면 의도가 코드에 남는다.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
package com.allfolio.dart.query

import com.allfolio.unifiedasset.infrastructure.entity.DartDisclosureEntity
import com.allfolio.unifiedasset.infrastructure.entity.DartInsiderTradeEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class DisclosureFeedServiceTest {

    private val userId = UUID.randomUUID()
    private val from = LocalDate.of(2026, 8, 1)
    private val now = LocalDateTime.of(2026, 8, 18, 19, 0)

    private fun disclosure(
        rceptNo: String, stockCode: String?, tier: Short?, rceptDt: LocalDate,
        reportNm: String = "유상증자결정", corpCode: String = "C1",
    ) = DartDisclosureEntity(
        rceptNo = rceptNo, corpCode = corpCode, corpName = "회사", stockCode = stockCode,
        corpCls = "Y", reportNm = reportNm, reportNmNorm = reportNm,
        rceptDt = rceptDt, flrNm = "회사", rm = null,
        isMaterial = tier != null, materialTier = tier, isCorrection = false, collectedAt = now,
    )

    private class FakeStore(
        val holdings: List<String>,
        val disclosures: List<DartDisclosureEntity>,
        val insiders: List<DartInsiderTradeEntity> = emptyList(),
    ) : DisclosureFeedService.Store {
        override fun findHeldStockCodes(userId: UUID) = holdings
        override fun findMaterial(stockCodes: Collection<String>, from: LocalDate) =
            disclosures.filter { it.stockCode in stockCodes && it.isMaterial && it.rceptDt >= from }
        override fun findInsiderTrades(stockCodes: Collection<String>, from: LocalDate) =
            insiders.filter { it.stockCode in stockCodes && it.reportDate >= from }
    }

    @Test
    fun `보유종목의 공시만 나온다`() {
        val store = FakeStore(
            holdings = listOf("005930"),
            disclosures = listOf(
                disclosure("R1", "005930", 1, LocalDate.of(2026, 8, 18)),
                disclosure("R2", "000660", 1, LocalDate.of(2026, 8, 18)),
            ),
        )

        val feed = DisclosureFeedService(store).feedFor(userId, from)

        assertThat(feed.items.map { it.rceptNo }).containsExactly("R1")
    }

    @Test
    fun `Tier 오름차순 다음 접수일 내림차순으로 정렬한다`() {
        val store = FakeStore(
            holdings = listOf("005930"),
            disclosures = listOf(
                disclosure("R_T5_NEW", "005930", 5, LocalDate.of(2026, 8, 18), "반기보고서"),
                disclosure("R_T1_OLD", "005930", 1, LocalDate.of(2026, 8, 11)),
                disclosure("R_T1_NEW", "005930", 1, LocalDate.of(2026, 8, 18)),
            ),
        )

        val feed = DisclosureFeedService(store).feedFor(userId, from)

        assertThat(feed.items.map { it.rceptNo })
            .containsExactly("R_T1_NEW", "R_T1_OLD", "R_T5_NEW")
    }

    @Test
    fun `보유종목이 없으면 조회하지 않고 빈 피드다`() {
        val store = FakeStore(holdings = emptyList(), disclosures = listOf(
            disclosure("R1", "005930", 1, LocalDate.of(2026, 8, 18)),
        ))

        assertThat(DisclosureFeedService(store).feedFor(userId, from).items).isEmpty()
    }

    @Test
    fun `같은 회사 같은 보고서는 최신 건만 낸다`() {
        // [기재정정]은 새 rcept_no를 받는다. 정규화가 접두어를 떼므로 같은 그룹에 들어간다.
        val store = FakeStore(
            holdings = listOf("005930"),
            disclosures = listOf(
                disclosure("R_ORIG", "005930", 5, LocalDate.of(2026, 8, 11), "반기보고서 (2026.06)"),
                disclosure("R_FIX", "005930", 5, LocalDate.of(2026, 8, 18), "반기보고서 (2026.06)"),
            ),
        )

        val feed = DisclosureFeedService(store).feedFor(userId, from)

        assertThat(feed.items.map { it.rceptNo }).containsExactly("R_FIX")
        assertThat(feed.items.single().supersededCount).isEqualTo(1)
    }

    @Test
    fun `원문 링크를 만든다`() {
        val store = FakeStore(listOf("005930"), listOf(
            disclosure("20260818800172", "005930", 1, LocalDate.of(2026, 8, 18)),
        ))

        assertThat(DisclosureFeedService(store).feedFor(userId, from).items.single().sourceUrl)
            .isEqualTo("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260818800172")
    }

    @Test
    fun `소유변동을 별도 섹션으로 낸다`() {
        val store = FakeStore(
            holdings = listOf("005930"),
            disclosures = emptyList(),
            insiders = listOf(
                DartInsiderTradeEntity(
                    id = 1L, rceptNo = "R1", corpCode = "C1", stockCode = "005930",
                    repror = "홍길동", officerPosition = "상무", isRegistered = false,
                    majorHolderType = null, reportDate = LocalDate.of(2026, 8, 18),
                    ownedQty = 1000L, changeQty = -50L,
                    ownedRate = java.math.BigDecimal("0.01"),
                    changeRate = java.math.BigDecimal("0.00"), collectedAt = now,
                ),
            ),
        )

        val feed = DisclosureFeedService(store).feedFor(userId, from)

        assertThat(feed.insiderTrades).hasSize(1)
        with(feed.insiderTrades.single()) {
            assertThat(changeQty).isEqualTo(-50L)
            assertThat(sourceUrl).isEqualTo("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=R1")
        }
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.dart.query.DisclosureFeedServiceTest"
```

Expected: 컴파일 실패 — `Unresolved reference: DisclosureFeedService`

- [ ] **Step 3: 구현**

```kotlin
package com.allfolio.dart.query

import com.allfolio.unifiedasset.infrastructure.entity.DartDisclosureEntity
import com.allfolio.unifiedasset.infrastructure.entity.DartInsiderTradeEntity
import com.allfolio.unifiedasset.infrastructure.jpa.DartDisclosureJpaRepository
import com.allfolio.unifiedasset.infrastructure.jpa.DartInsiderTradeJpaRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

private const val SOURCE_URL_PREFIX = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo="

data class DisclosureItem(
    val rceptNo: String,
    val corpName: String,
    val stockCode: String?,
    val reportNm: String,
    val rceptDt: LocalDate,
    val materialTier: Short?,
    val isCorrection: Boolean,
    val sourceUrl: String,
    /** 같은 (회사, 보고서)로 접힌 이전 건 수 — 정정공시 묶음 */
    val supersededCount: Int,
)

/**
 * **매수·매도를 말하지 않는다.** `elestock`에 변동사유가 없어 판별이 불가능하다.
 * 이 DTO에 `changeType` 같은 필드를 추가하지 말 것 — 채울 소스가 없다.
 */
data class InsiderTradeItem(
    val rceptNo: String,
    val stockCode: String?,
    val repror: String,
    val officerPosition: String?,
    val isRegistered: Boolean?,
    val majorHolderType: String?,
    val reportDate: LocalDate,
    val ownedQty: Long?,
    val changeQty: Long?,
    val ownedRate: BigDecimal?,
    val changeRate: BigDecimal?,
    val sourceUrl: String,
)

data class DisclosureFeed(
    val items: List<DisclosureItem>,
    val insiderTrades: List<InsiderTradeItem>,
)

/**
 * 사용자별 피드 테이블을 만들지 않는다 — 종목 매매마다 재계산해야 하고 사용자 수만큼 행이
 * 불어난다. 조회 시점에 보유종목과 조인한다.
 */
@Service
class DisclosureFeedService(private val store: Store) {

    interface Store {
        fun findHeldStockCodes(userId: UUID): List<String>
        fun findMaterial(stockCodes: Collection<String>, from: LocalDate): List<DartDisclosureEntity>
        fun findInsiderTrades(stockCodes: Collection<String>, from: LocalDate): List<DartInsiderTradeEntity>
    }

    fun feedFor(userId: UUID, from: LocalDate): DisclosureFeed {
        val held = store.findHeldStockCodes(userId)
        if (held.isEmpty()) return DisclosureFeed(emptyList(), emptyList())

        val items = store.findMaterial(held, from)
            // 정정공시 묶기 — 정규화가 접두어를 떼므로 원본과 정정본이 같은 그룹에 들어간다
            .groupBy { it.corpCode to it.reportNmNorm }
            .map { (_, group) ->
                val latest = group.maxWith(compareBy({ it.rceptDt }, { it.rceptNo }))
                DisclosureItem(
                    rceptNo = latest.rceptNo,
                    corpName = latest.corpName,
                    stockCode = latest.stockCode,
                    reportNm = latest.reportNm,
                    rceptDt = latest.rceptDt,
                    materialTier = latest.materialTier,
                    isCorrection = latest.isCorrection,
                    sourceUrl = SOURCE_URL_PREFIX + latest.rceptNo,
                    supersededCount = group.size - 1,
                )
            }
            .sortedWith(
                compareBy<DisclosureItem> { it.materialTier ?: Short.MAX_VALUE }
                    .thenByDescending { it.rceptDt }
                    .thenByDescending { it.rceptNo },
            )

        val insiders = store.findInsiderTrades(held, from)
            .sortedWith(compareByDescending<DartInsiderTradeEntity> { it.reportDate }.thenByDescending { it.rceptNo })
            .map {
                InsiderTradeItem(
                    rceptNo = it.rceptNo, stockCode = it.stockCode, repror = it.repror,
                    officerPosition = it.officerPosition, isRegistered = it.isRegistered,
                    majorHolderType = it.majorHolderType, reportDate = it.reportDate,
                    ownedQty = it.ownedQty, changeQty = it.changeQty,
                    ownedRate = it.ownedRate, changeRate = it.changeRate,
                    sourceUrl = SOURCE_URL_PREFIX + it.rceptNo,
                )
            }

        return DisclosureFeed(items, insiders)
    }
}

/**
 * 보유종목은 `ua_assets.symbol`이다 — KIS `pdno`(6자리 단축코드)로 채워지고,
 * 공공데이터포털 `srtnCd`와 같은 값이라 `dart_disclosure.stock_code`와 그대로 맞는다.
 *
 * **`position_daily`가 아니다.** 그쪽은 `assetId` 기준 일별 스냅샷이라 종목코드 컬럼이 없다.
 */
@Component
class JpaFeedStore(
    private val em: EntityManager,
    private val disclosures: DartDisclosureJpaRepository,
    private val insiders: DartInsiderTradeJpaRepository,
) : DisclosureFeedService.Store {

    @Suppress("UNCHECKED_CAST")
    override fun findHeldStockCodes(userId: UUID): List<String> =
        em.createNativeQuery(
            """
            SELECT DISTINCT symbol FROM ua_assets
            WHERE user_id = :userId AND type = 'STOCK'
              AND symbol IS NOT NULL AND symbol <> '' AND quantity > 0
            """.trimIndent(),
        ).setParameter("userId", userId).resultList as List<String>

    override fun findMaterial(stockCodes: Collection<String>, from: LocalDate) =
        disclosures.findByStockCodeInAndRceptDtGreaterThanEqualAndIsMaterialTrue(stockCodes, from)

    override fun findInsiderTrades(stockCodes: Collection<String>, from: LocalDate) =
        insiders.findByStockCodeInAndReportDateGreaterThanEqualOrderByReportDateDesc(stockCodes, from)
}
```

`DartDisclosureJpaRepository`에 조회 메서드를 추가한다:

```kotlin
    fun findByStockCodeInAndRceptDtGreaterThanEqualAndIsMaterialTrue(
        stockCodes: Collection<String>, from: LocalDate,
    ): List<DartDisclosureEntity>
```

- [ ] **Step 4: 컨트롤러 작성**

```kotlin
package com.allfolio.api.dart

import com.allfolio.dart.query.DisclosureFeed
import com.allfolio.dart.query.DisclosureFeedService
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RestController
@RequestMapping("/api/disclosures")
class DisclosureFeedController(private val service: DisclosureFeedService) {

    /**
     * 기본 조회 구간은 최근 30일이다. 날짜는 KST 기준으로 정한다 —
     * 컨테이너가 UTC라 UTC 자정~09시 사이 요청이 "어제"를 기준으로 잡힌다.
     */
    @GetMapping
    fun feed(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
    ): ResponseEntity<DisclosureFeed> {
        val userId = UUID.fromString(jwt.subject)
        val since = from ?: LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(30)
        return ResponseEntity.ok(service.feedFor(userId, since))
    }
}
```

> **인증 방식을 먼저 확인할 것.** 위 `@AuthenticationPrincipal Jwt`는 가정이다. 기존 사용자 API가 어떻게 `userId`를 꺼내는지 보고 그 방식을 그대로 따른다:
> ```bash
> cd allfolio-backend && grep -rn "AuthenticationPrincipal\|SecurityContextHolder" backend-app/src/main/kotlin/com/allfolio/api --include="*.kt" | head -5
> ```

- [ ] **Step 5: 테스트 통과 확인**

```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests "com.allfolio.dart.*"
```

Expected: PASS — Task 3~14 전체

- [ ] **Step 6: 전체 테스트로 회귀 확인**

```bash
cd allfolio-backend && ./gradlew test
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/dart/query/ \
        allfolio-backend/backend-app/src/main/kotlin/com/allfolio/api/dart/ \
        allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/jpa/ \
        allfolio-backend/backend-app/src/test/kotlin/com/allfolio/dart/query/
git commit -m "feat(d1): 공시 피드 조회 API — ua_assets.symbol로 조인한다"
```

---

## 배포 순서 — 순서를 지키지 않으면 첫 수집이 죽는다

1. **운영 Neon에 마이그레이션 적용** — `docs/superpowers/migrations/2026-08-18-dart-disclosure.sql`. `ddl-auto: none`이라 아무도 대신 만들어 주지 않는다
2. **Render 환경변수에 `DART_API_KEY` 추가**
3. **GitHub Secrets 확인** — `BACKEND_URL`·`SCHEDULER_TOKEN`은 기존 워크플로가 이미 쓰는 것과 같다. 새로 넣을 것은 없다
4. **백엔드 배포**
5. **`corp-map` 워크플로를 수동 실행**(`workflow_dispatch`) — 매핑이 비어 있으면 비상장 판별 근거가 없다
6. **`collect-dart` 워크플로를 수동 실행** — 응답 본문의 `pagesFetched`·`newCount`를 눈으로 확인한다
7. **DB에서 실제 값 대조** — 아래 쿼리로 Tier 분포가 실측(스펙 5절 표)과 비슷한지 본다

```sql
SELECT material_tier, count(*)
FROM dart_disclosure
WHERE stock_code IS NOT NULL AND rcept_dt >= current_date - 1
GROUP BY material_tier ORDER BY material_tier;
```

Tier 1이 0건이면 정규화가 안 걸린 것이다 — Task 3을 의심한다.

---

## 후속 (이 계획 밖)

- **S12 FE 피드 화면** — 별도 계획. 카피에서 "매수"·"매도"·"주목 종목"·"매수 신호" 금지
- **S13 화이트리스트 튜닝** — 1주 운영 후 `is_material = false` 상위 `report_nm_norm`을 세어 보고 결정
- **S2 Flyway 도입** — 별건. 지금은 수동 마이그레이션 유지
