# 벤치마크 KOSPI를 FSC로 이관 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 벤치마크 KOSPI 종가를 Yahoo 비공식 엔드포인트가 아니라 공공데이터포털 지수시세정보(재배포 제한 없음)에서 받아 `benchmark_daily`에 채운다. SPX·BTC는 Yahoo에 그대로 둔다.

**Architecture:** `benchmark_daily`를 **읽는 쪽은 하나도 안 바꾼다.** 그 표를 채우는 주체만 KOSPI에 한해 Yahoo → FSC로 바꾼다. 새 포트도, 소비자 재배선도 없다. 수집기는 원자재의 `CommoditySource` 패턴을 따르고 저장은 기존 `BenchmarkDailyStore.upsert`를 그대로 쓴다.

**Tech Stack:** Kotlin / Spring Boot / JUnit 5 / GitHub Actions

**설계 문서:** `docs/superpowers/specs/2026-08-17-benchmark-source-migration-design.md` (커밋 `4106620`)

---

## 사전 필독 — 이걸 모르면 조용히 틀린다

**1. 두 소스가 같은 행을 번갈아 덮는다.** `BenchmarkSyncService.syncAll()`이 `BenchmarkType.entries`를 전부 돌며 `store.upsert(type, rows)`를 한다. FSC 수집기를 붙이고 **거기서 KOSPI를 안 빼면** Yahoo와 FSC가 같은 `(KOSPI, date)` 행을 서로 덮어써 **값이 실행마다 흔들린다.** 오류도 로그도 안 난다. Task 3이 이걸 막는다.

**2. `idxNm`만으로는 지수가 유일하지 않다.** 실측에서 `"IT 서비스"`가 `KOSPI시리즈`와 `KOSDAQ시리즈`에 **둘 다** 있었다(1주 조회 `totalCount=672`). `idxNm=코스피`가 지금 1건인 것은 그 이름이 마침 유일해서다. **설정 단위는 `(idxNm, idxCsf)` 쌍**이고, 응답에서도 그 쌍으로 거른다.

**3. 안 쓰는 필드가 멀쩡한 종가를 죽이지 않게 할 것.** `fltRt`가 앞의 0을 생략하지만(`.73`·`-.6`) **그건 위험이 아니다** — `BigDecimal(".05")`는 `0.05`로 정상 파싱된다(2026-08-17 실측 확인). 진짜 위험은 그 필드가 **빈 값이나 `-`** 로 올 때이고, 그때 파싱이 터지면 **종가가 멀쩡한 행이 통째로 버려진다.**

> **초안이 "앞의 0 때문에 죽는다"고 적었던 것은 틀렸다.** `decimalOrNull`이 막는 것은 앞의 0이 아니라 빈 값·쓰레기다. 테스트도 `.05` 픽스처가 아니라 **`fltRt`가 `-`·빈 문자열인 행**으로 짜야 문다.

**가장 강한 방어는 아예 안 읽는 것이다.** 반환 타입이 `List<Pair<LocalDate, BigDecimal>>`라 `fltRt`·`vs`를 실을 곳이 없다 — 읽으면 즉시 버려지는 죽은 코드가 된다. 나중에 실을 일이 생기면 그때 `FscCommodityClient.decimalOrNull`을 가져올 것.

**4. 인증키가 쿼리 파라미터에 실린다.** `FscCommodityClient`가 URL 로깅 금지·예외에 `cause` 금지·본문 미리보기 금지 세 방어를 한다. 그 파일 주석이 이유를 적어 뒀다. **그대로 따를 것.**

**5. `items`가 0건이면 빈 문자열로 온다.** 공공데이터포털 관례다. 배열이 아닐 때 예외로 죽지 말고 빈 목록을 줄 것.

**8. `FscApiException`은 `com.allfolio.market.fsc`에 있다** — 포털 공용이라 원자재 도메인에서 꺼냈다(Task 1 리뷰). 오퍼레이션마다 예외 타입을 새로 만들지 말 것 — 수집 서비스가 그만큼을 다 알아야 한다.

**7. 이 브랜치는 `origin/main` 위에 있어야 한다.** 이 작업은 #178(`FscCommodityClient`)·#179를 토대로 삼는다. 브랜치가 그보다 뒤면 **읽으라고 지시한 파일이 존재하지 않는다.** 시작 전에 `git log --oneline HEAD..origin/main`이 비어 있는지 확인할 것.

**6. 실측값(2026-08-17)**: 경로 `GetMarketIndexInfoService/getStockMarketIndex`, 날짜 `basDt`(`yyyyMMdd`), 종가 `clpr`. 2026-08-13 KOSPI 종가 = **6,813.34**. 1년 범위 조회가 242영업일을 **한 페이지**로 준다(`numOfRows=3000` 존중).

---

## 파일 구조

| 파일 | 책임 | 태스크 |
|---|---|---|
| `backend-app/.../market/benchmark/BenchmarkIndexProperties.kt` | `(idxNm, idxCsf)` 설정 | 1 |
| `backend-app/.../market/benchmark/FscIndexClient.kt` | 호출 + 파싱 | 1 |
| `backend-app/.../market/benchmark/FscIndexCollectService.kt` | 구간 필터·중복 접기·저장 | 2 |
| `unified-asset/.../usecase/BenchmarkSyncService.kt` | KOSPI 건너뛰기 | 3 |
| `backend-app/.../api/admin/BenchmarkIndexAdminController.kt` | 수집·백필 트리거 | 4 |
| `backend-app/.../api/scheduler/SchedulerTriggerController.kt` | 크론 트리거 | 4 |
| `.github/workflows/collect-benchmark.yml` | 크론 | 4 |

---

## Task 1: FSC 지수 클라이언트 + 설정

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/benchmark/BenchmarkIndexProperties.kt`
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/benchmark/FscIndexClient.kt`
- Modify: `allfolio-backend/backend-app/src/main/resources/application.yml`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/benchmark/FscIndexClientTest.kt`

**먼저 `backend-app/src/main/kotlin/com/allfolio/market/commodity/fsc/FscCommodityClient.kt`를 읽을 것.** 같은 기관·같은 인증·같은 응답 두 겹 구조다. 키 방어 셋과 `decimalOrNull`을 그대로 가져온다.

- [ ] **Step 1: 설정 클래스**

```kotlin
package com.allfolio.market.benchmark

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * 벤치마크로 쓸 지수 한 종.
 *
 * **`idxNm` 하나로는 지수가 유일하지 않다.** 실측에서 `"IT 서비스"`가 `KOSPI시리즈`와
 * `KOSDAQ시리즈`에 둘 다 있었다. 그래서 좌표가 `(idxNm, idxCsf)` 쌍이다 —
 * 이름만으로 고르면 지수를 하나 더할 때 잘못된 시리즈를 집고, 값이 그럴듯해서 못 알아챈다.
 */
class BenchmarkIndexItem {
    /** benchmark_daily.index_type 과 일치해야 한다 (BenchmarkType.name) */
    var type: String = ""
    /** 포털 조회 파라미터이자 응답 필터. 예: 코스피 */
    var idxNm: String = ""
    /** 응답 필터. 예: KOSPI시리즈 */
    var idxCsf: String = ""
}

@Component
@ConfigurationProperties(prefix = "benchmark-index")
class BenchmarkIndexProperties {
    var fsc: List<BenchmarkIndexItem> = emptyList()

    val types: List<String> get() = fsc.map { it.type }
}
```

- [ ] **Step 2: `application.yml`에 KOSPI 한 줄**

`market-commodity` 블록 아래에 추가한다.

```yaml
# 벤치마크 지수 — Yahoo 비공식 엔드포인트 대체 (AF-107).
# KOSPI만 여기서 온다. SPX·BTC는 여전히 BenchmarkSyncService(Yahoo)가 채운다 —
# 옮겨도 KIS·Upbit 약관이 미결이라 얻는 게 없고 이력만 닷새치로 줄기 때문이다.
#
# idxCsf를 함께 적는 이유: idxNm만으로는 유일하지 않다("IT 서비스"가 KOSPI시리즈와
# KOSDAQ시리즈에 둘 다 있다). 지수를 더할 때 쌍으로 적을 것.
benchmark-index:
  fsc:
    - { type: KOSPI, idx-nm: "코스피", idx-csf: "KOSPI시리즈" }
```

- [ ] **Step 3: 클라이언트 — 실패하는 테스트 먼저**

`FscIndexClientTest.kt`. `FscCommodityClientTest`의 루프백 스텁 방식을 그대로 쓴다(그 파일을 열어 볼 것).

담을 것:

```kotlin
/** 실측 응답 그대로. 두 시리즈에 같은 이름이 섞여 있다 — 필터를 지우면 이 픽스처가 잡는다 */
private val REAL_BODY = """
{"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
"body":{"numOfRows":3000,"pageNo":1,"totalCount":4,"items":{"item":[
{"basDt":"20260813","idxNm":"코스피","idxCsf":"KOSPI시리즈","clpr":"6813.34","vs":"234.3","fltRt":"3.56"},
{"basDt":"20260813","idxNm":"IT 서비스","idxCsf":"KOSDAQ시리즈","clpr":"669.09","vs":"-5.98","fltRt":"-.89"},
{"basDt":"20260813","idxNm":"IT 서비스","idxCsf":"KOSPI시리즈","clpr":"1284.83","vs":"26.53","fltRt":"2.11"},
{"basDt":"20260812","idxNm":"코스피","idxCsf":"KOSPI시리즈","clpr":"6579.04","vs":"233.51","fltRt":"3.68"}
]}}}}
""".trimIndent()
```

1. `(idxNm, idxCsf)` 쌍으로 골라 **2건**(8/13 6813.34, 8/12 6579.04)만 준다
2. **`idxCsf`가 다르면 버린다** — `("IT 서비스", "KOSDAQ시리즈")`를 요청하면 669.09 한 건만 나온다
3. **`fltRt`가 `-`·빈 문자열인 행도 종가가 멀쩡하면 살아남는다** (`.05`·`-.89` 형식은 `BigDecimal`이 원래 읽으므로 그것만으로는 아무것도 검사하지 못한다)
4. `basDt` → `LocalDate` (`yyyyMMdd`)
5. `items`가 빈 문자열이면 빈 목록 (예외 없음)
6. `resultCode != "00"`이면 예외 (미승인 키가 HTTP 200 + 빈 items로 오므로 휴장과 구분해야 한다)
7. **키가 예외·로그 어디에도 안 남는다** — 요청 URI를 되울리는 500 본문 스텁으로 `stackTraceToString()` 전수 검사

- [ ] **Step 4: 테스트 실행 — 실패 확인**

```bash
cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :backend-app:test --tests '*FscIndexClient*' --rerun-tasks
```
Expected: 컴파일 실패(`FscIndexClient` 없음)

- [ ] **Step 5: 클라이언트 구현**

`FscCommodityClient`를 옮겨 온다. 다른 점만:
- 경로 `/GetMarketIndexInfoService/getStockMarketIndex`
- 쿼리에 `idxNm`을 싣는다(서버 필터). 응답은 **다시 `(idxNm, idxCsf)`로 거른다** — 서버 필터가 정확 일치라도 응답을 믿지 않는다
- 반환은 `List<Pair<LocalDate, BigDecimal>>` — `BenchmarkDailyStore.upsert`가 받는 타입 그대로다

- [ ] **Step 6: 테스트 실행 — 통과 확인**

```bash
cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :backend-app:test --tests '*FscIndex*' --rerun-tasks
```
Expected: PASS

- [ ] **Step 7: 변이 테스트**

| # | 변이 | 잡아야 할 테스트 |
|---|---|---|
| 1 | 응답의 `idxCsf` 필터를 지운다 | 2 (`IT 서비스` 두 건이 섞여 3건이 됨) |
| 2 | 파싱 단계에 `BigDecimal(node.path("fltRt").asText())`를 끼워 넣는다 | 3 |

> **변이 2가 "`decimalOrNull` → `BigDecimal(s)`"이 아닌 이유**: `BigDecimal(String)`은 앞의 0이 없는 소수를 정상적으로 읽으므로 그 치환으로는 아무것도 안 깨진다. 안 읽던 필드를 **읽게 만드는 것**이 진짜 변이다.

편집으로 원복할 것 — `git checkout --`은 미커밋 작업을 날린다.

- [ ] **Step 8: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/benchmark/ \
        allfolio-backend/backend-app/src/main/resources/application.yml \
        allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/benchmark/
git commit -m "feat(af-107): FSC 지수 클라이언트 — 이름만으로는 지수가 유일하지 않다

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 2: 수집 서비스

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/benchmark/FscIndexCollectService.kt`
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/benchmark/FscIndexCollectServiceTest.kt`

**`backend-app/.../market/commodity/CommodityCollectService.kt`를 읽고 방어를 그대로 옮길 것.** 다만 원자재보다 훨씬 작다 — 전일대비를 계산하지 않고(저장 안 함) 단위·주기도 없다.

- [ ] **Step 1: 실패하는 테스트**

담을 것:
1. 구간 밖 날짜를 걷어낸다
2. 같은 날짜가 두 번 오면 한 건으로 접는다
3. 0건이면 `upsert`를 **부르지 않는다**(빈 배치)
4. 종목 하나가 터져도 나머지가 저장된다(실패 격리) — 요약의 `failures`에 남는다
5. 실패 사유는 200자로 자른다
6. `upsert`가 실패하면 `saved` 계수가 부풀지 않는다 (**AF-102 회귀**)

- [ ] **Step 2: 실행 — 실패 확인**

```bash
cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :backend-app:test --tests '*FscIndexCollect*' --rerun-tasks
```

- [ ] **Step 3: 구현**

```kotlin
package com.allfolio.market.benchmark

/**
 * @param requested 설정에 있는 지수 수
 * @param saved     실제 저장된 행 수. **upsert 뒤에 센다** — 앞에서 세면 전량 쓰기 실패인데도
 *                  숫자가 남아, AF-102가 `collected=60`에 200을 낸 그 사고가 된다
 * @param outOfRange 요청 구간 밖 날짜라 걷어낸 행 수
 * @param emptySeries 저장할 행이 한 건도 안 남은 지수. **그 자체로 실패는 아니다** — 다만
 *                    (idxNm, idxCsf) 쌍이 틀려도 똑같이 0건이라 자동으로는 못 가른다
 */
data class BenchmarkCollectSummary(
    val from: LocalDate, val to: LocalDate,
    val requested: Int, val saved: Int, val outOfRange: Int,
    val emptySeries: List<String>, val failed: Int, val failures: List<String>,
)
```

`collect(from, to)`는 설정의 지수마다:
- `client.fetch(item, from, to)` → `List<Pair<LocalDate, BigDecimal>>`
- `filter { it.first in from..to }` → `outOfRange` 누산
- `associate { }`로 날짜 중복 접기
- 비면 `emptySeries += type`, 아니면 `store.upsert(BenchmarkType.valueOf(item.type), rows)`

> **🔴 `BenchmarkType.valueOf`를 지수별 `try/catch` 안에 둘 것.** 밖에 두면 설정에 오타 하나(`type: KOSPPI`)로 **수집 전체가 죽는다.** `BenchmarkIndexItem.type`의 KDoc이 *"값이 틀리면 그 지수 하나만 실패로 남는다"*고 약속했으므로, 밖에 두면 그 주석이 거짓이 된다.
- **`saved`는 `upsert` 뒤에 누산**
- 지수별 `try/catch`로 격리, 사유는 200자 절단

- [ ] **Step 4: 실행 — 통과 확인**

- [ ] **Step 5: 변이 테스트**

| # | 변이 | 잡아야 할 테스트 |
|---|---|---|
| 1 | `saved` 누산을 `upsert` **앞**으로 | 6 |
| 2 | 지수별 `catch`를 벗겨 첫 실패가 전체를 죽이게 | 4 |
| 3 | 구간 필터 제거 | 1 |

- [ ] **Step 6: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/benchmark/FscIndexCollectService.kt \
        allfolio-backend/backend-app/src/test/kotlin/com/allfolio/market/benchmark/FscIndexCollectServiceTest.kt
git commit -m "feat(af-107): 지수 수집 서비스 — 저장 뒤에 센다

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 3: `BenchmarkSyncService`가 KOSPI를 건너뛴다

**이 태스크가 이번 변경의 핵심 회귀다.** 안 하면 Yahoo와 FSC가 같은 행을 번갈아 덮어써 값이 실행마다 흔들리고, **오류도 로그도 안 난다.**

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/BenchmarkSyncService.kt`
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/domain/benchmark/BenchmarkType.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/BenchmarkSyncServiceTest.kt`

- [ ] **Step 1: 실패하는 테스트**

```kotlin
/**
 * KOSPI는 FSC 수집기(AF-107)가 채운다. Yahoo가 같이 쓰면 두 소스가 같은 (KOSPI, date) 행을
 * 번갈아 덮어써 값이 실행마다 흔들린다 — 오류도 로그도 안 난다. 그래서 여기서 막는다.
 */
@Test
fun `KOSPI는 Yahoo에서 받지 않는다`() {
    val client = FakeHistoryClient()
    val store = FakeStore()
    BenchmarkSyncService(client, store).syncAll()

    assertThat(client.requested).doesNotContain(BenchmarkType.KOSPI)
    assertThat(store.upserted.keys).doesNotContain(BenchmarkType.KOSPI)
}

@Test
fun `SPX와 BTC는 그대로 받는다`() {
    val client = FakeHistoryClient()
    BenchmarkSyncService(client, FakeStore()).syncAll()

    assertThat(client.requested).containsExactlyInAnyOrder(BenchmarkType.SPX, BenchmarkType.BTC)
}
```

- [ ] **Step 2: 실행 — 실패 확인**

```bash
cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :unified-asset:test --tests '*BenchmarkSync*' --rerun-tasks
```
Expected: FAIL — 현재는 셋을 다 돈다

- [ ] **Step 3: 구현**

`BenchmarkType`에 판별을 둔다 — 서비스에 코드 문자열을 흩지 않는다.

```kotlin
enum class BenchmarkType(val yahooTicker: String, val label: String) {
    SPX("^GSPC", "S&P 500"),

    /**
     * **`yahooTicker`가 쓰이지 않는다.** KOSPI 종가는 AF-107 이후 FSC 수집기가
     * `benchmark_daily`에 채운다. **그래도 지우지 않는다** — enum이 셋을 함께 들고 있고
     * SPX·BTC는 계속 쓴다. 값을 비우면 "티커가 없는 벤치마크"라는 새 상태가 생긴다.
     */
    KOSPI("^KS11", "KOSPI"),

    BTC("BTC-USD", "Bitcoin"),
    ;

    /**
     * Yahoo 동기화 대상인가. **KOSPI만 false다** — FSC가 채우므로 Yahoo가 같이 쓰면
     * 같은 행을 번갈아 덮어써 값이 실행마다 흔들린다(AF-107).
     * 이 판별을 서비스로 옮기지 말 것 — 벤치마크가 늘 때 여기 한 곳만 보면 되게 둔다.
     */
    val syncedFromYahoo: Boolean get() = this != KOSPI
}
```

`BenchmarkSyncService.syncAll()`의 첫 줄:

```kotlin
        BenchmarkType.entries.filter { it.syncedFromYahoo }.forEach { type ->
```

그리고 클래스 KDoc에 한 줄 추가:

```
 * **KOSPI는 여기서 안 받는다** — FSC 수집기(AF-107)가 채운다. [BenchmarkType.syncedFromYahoo] 참조.
```

- [ ] **Step 4: 실행 — 통과 확인**

- [ ] **Step 5: 변이 테스트**

| # | 변이 | 잡아야 할 테스트 |
|---|---|---|
| 1 | `.filter { it.syncedFromYahoo }` 제거 | `KOSPI는 Yahoo에서 받지 않는다` |
| 2 | `syncedFromYahoo`를 `this != SPX`로 | 두 테스트 다 |

- [ ] **Step 6: 회귀 확인 — 읽는 쪽이 안 깨졌나**

```bash
cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :unified-asset:test :backend-app:test --rerun-tasks
```

**이번 설계는 읽는 쪽을 안 건드리므로 `ReportServiceTest`·`GetReturnsAnalysisUseCaseTest`·`MonthlyReportGeneratorTest`·`ReportControllerReturnsPercentTest`·`GetDashboardUseCase*Test`가 전부 그대로 통과해야 한다. 하나라도 깨지면 범위를 넘은 것이다 — 고치지 말고 보고할 것.**

- [ ] **Step 7: 두 개의 KOSPI를 주석에 남긴다**

이관 후 KOSPI 값이 **두 곳에서 나온다.** 결함이 아니라 역할 분리이고, 안 적어 두면 다음 사람이 "값이 안 맞는다"고 하나를 지운다.

- **시장 화면** — KIS, 하루 세 슬롯(`market_index_quote`), 실시간성 우선
- **벤치마크·대시보드** — FSC, D+1 확정 종가(`benchmark_daily`), 이력 정합성 우선

두 곳에 남긴다:

> **⚠️ 1번은 Task 1에서 이미 넣었다.** `BenchmarkIndexProperties`의 클래스 KDoc을 열어 확인하고 **중복으로 또 쓰지 말 것.** 없으면 그때 쓴다.

1. `BenchmarkIndexProperties`의 클래스 KDoc — *"같은 KOSPI가 `market_index_quote`에도 있다. 그쪽은 KIS 실시간이고 이쪽은 D+1 확정 종가다. 용도가 달라 값이 다를 수 있고, 그건 정상이다."*
2. `frontend/allfolio_app/app/unified/reports/benchmark/page.tsx` — 화면에 **기준 표기 한 줄**. 사용자가 시장 탭의 KOSPI와 다른 숫자를 봤을 때 답이 화면에 있어야 한다

- [ ] **Step 8: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/ \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/ \
        allfolio-backend/backend-app/src/main/kotlin/com/allfolio/market/benchmark/ \
        frontend/allfolio_app/app/unified/reports/benchmark/page.tsx
git commit -m "feat(af-107): KOSPI를 Yahoo 동기화에서 뺀다 — 두 소스가 같은 행을 덮는다

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 4: 트리거 + 크론

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/api/admin/BenchmarkIndexAdminController.kt`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/api/scheduler/SchedulerTriggerController.kt`
- Modify: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/config/SecurityConfigAdminTest.kt`
- Create: `.github/workflows/collect-benchmark.yml`

**`CommodityAdminController`와 `collect-commodity.yml`을 그대로 베낄 것.**

- [ ] **Step 1: 어드민 컨트롤러**

`CommodityAdminController`를 따른다.
- `POST /api/admin/benchmark-index/collect` — `from`·`to` 선택, **기본은 KST 오늘 기준 최근 14일**
- **백필용으로 `from`을 주면 그대로 쓴다.** 최대 구간은 `CommodityAdminController.MAX_RANGE_DAYS`를 참고해 같은 상한을 둔다
- **🔴 날짜 기본값은 KST 오늘이다.** 컨테이너가 UTC라 `LocalDate.now()`를 그냥 쓰면 새벽에 하루 밀린다. 이 저장소에 그 경고 주석이 세 군데 있다

- [ ] **Step 2: 스케줄러 트리거**

`SchedulerTriggerController`에 `POST /api/internal/scheduler/benchmark-index`. 기존 트리거들과 같은 `authorize(token)`, **어드민에 위임**(그 파일 KDoc이 *"이 위임을 정리하지 말 것"*이라 못박았다), **날짜를 노출하지 않는다.**

**🔴 `SecurityConfigAdminTest`도 같이 고쳐야 한다.** `@WebMvcTest`의 컨트롤러 생성자 인자가 늘면 **컨텍스트가 아예 안 떠서** 무관해 보이는 실패가 전 테스트에 걸린다. 그 파일 주석이 이 함정을 이미 경고한다. 새 경로 둘의 규칙 검증(403 / 503)도 더한다.

- [ ] **Step 3: 크론 워크플로**

`.github/workflows/collect-commodity.yml`을 **숫자까지 그대로** 베낀다(timeout 12 · max-time 120 · retry 3/20 · concurrency).

```yaml
    - cron: "30 9 * * 1-5"   # UTC 09:30 = KST 18:30 (평일)
```

18:30인 이유를 주석으로 남길 것 — `collect-rate`(18:10)·`collect-commodity`(18:20)와 10분씩 벌려 같은 콜드 스타트에 몰리지 않게 한다.

실패 안내의 500 범례에 **`benchmark_daily` 테이블은 이미 있으므로 마이그레이션 부재가 아니라 설정 없음/전 종목 0건이 원인**임을 적을 것.

- [ ] **Step 4: 테스트 + 커밋**

```bash
cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :backend-app:test --rerun-tasks
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/collect-benchmark.yml')); print('YAML OK')"
```

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/api/ \
        allfolio-backend/backend-app/src/test/kotlin/com/allfolio/config/ \
        .github/workflows/collect-benchmark.yml
git commit -m "feat(af-107): 지수 수집 트리거 + 평일 크론

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 5: PR + 배포 + 1회 정리·백필

- [ ] **Step 1: PR**

본문에 반드시:
- **마이그레이션 없음** — `benchmark_daily`를 그대로 쓴다
- **새 시크릿 없음** — `FSC_API_KEY` 재사용(15094807 승인 완료)
- **SPX·BTC는 안 옮긴다**는 결정과 근거
- **배포 후 1회 작업이 있다**(아래) — 안 하면 KOSPI가 오늘부터만 쌓인다

- [ ] **Step 2: 배포 후 — 백필 드라이런 먼저**

**지우기 전에 채울 수 있는지부터 본다.**

GitHub Actions에서 `Collect Benchmark Index` 워크플로를 `workflow_dispatch`로 한 번 돌린다(크론과 같은 경로라 이게 크론 검증도 겸한다).

```bash
gh workflow run collect-benchmark.yml --ref main
```

그 실행은 기본 창(14일)이므로 **1년 백필은 어드민으로 따로 부른다.** 정확한 경로·파라미터는 Task 4에서 정한 `POST /api/admin/benchmark-index/collect`이고, 관리자 인증이 필요하므로 **브라우저에서 로그인한 상태로 호출하거나** `scripts/fx-backfill.sh`가 쓰는 방식을 따른다(그 스크립트를 열어 인증 방법을 볼 것).

Expected: `saved`가 **240 안팎**(1년 영업일). **0이면 멈추고 원인을 볼 것 — 절대 DELETE로 넘어가지 말 것.** 0이 나오는 원인은 셋이다: `(idxNm, idxCsf)` 쌍이 틀림 · 키가 15094807에 미승인 · 구간 파라미터 오류. 응답의 `emptySeries`·`failures`가 어느 쪽인지 말해 준다.

- [ ] **Step 3: Yahoo 행 1회 삭제**

```sql
DELETE FROM benchmark_daily WHERE index_type = 'KOSPI';
```

**`SPX`·`BTC`는 절대 지우지 않는다** — Yahoo가 계속 채우는 살아 있는 시계열이다.

- [ ] **Step 4: 백필 재실행 + 검증**

```sql
SELECT index_type, count(*), min(date), max(date), max(close_value)
FROM benchmark_daily GROUP BY index_type ORDER BY 1;
```

확인할 것:
- `KOSPI` 240행 안팎, 최신 `close_value`가 **6,800 안팎**(2026-08-13 실측 6,813.34)
- `SPX`·`BTC` 행 수가 **안 줄었다**

- [ ] **Step 5: 화면 검증**

`/unified/reports/returns`에서 `vs KOSPI`를 고르고 **BM 수익률이 이관 전과 거의 같은지** 본다.

**기대: YTD 약 +61.7%** (이관 전 표시 +61.92%, FSC 계산 61.68% — 차이 0.24%p는 기준일 규약 차이다).
**크게 다르면 이관이 틀린 것이다.** 1%p 넘게 벌어지면 멈추고 원인을 볼 것.

`vs S&P 500`·`vs Bitcoin`도 **이관 전과 똑같이** 보여야 한다 — 안 건드렸다.

---

## 완료 기준

- [ ] KOSPI 종가가 FSC에서 매일 쌓인다
- [ ] Yahoo가 KOSPI를 더는 안 건드린다 (변이로 확인)
- [ ] 1년 백필이 들어갔고 `benchmark_daily`의 KOSPI가 단일 소스다
- [ ] SPX·BTC 화면이 이관 전과 같다
- [ ] 읽는 쪽 테스트 5종이 그대로 통과한다
- [ ] 두 개의 KOSPI(시장=KIS 실시간 / 벤치마크=FSC 확정종가)가 주석과 화면에 설명돼 있다
