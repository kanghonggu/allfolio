# 벤치마크 소스 이관 — Yahoo 제거, 사내 수집분으로 통합

- 작성일: 2026-08-17
- 노션: AF-107 (P1 가시화, 규모 M, 영역 BE·FE)
- 선행 근거: [AF-108 재배포 약관 검토](2026-08-13-market-data-redistribution-review.md) 및 그 2026-08-16 추가 조사(#175)

## 이 문서가 다루는 것과 다루지 않는 것

AF-107 티켓의 본체는 **"TWR 차트에 지수 겹치기"**(칩 토글·보유 시장만 노출·배당 각주)다.
**이 문서는 그 앞단만 다룬다** — 벤치마크 시계열을 어디서 받아오는가.

본체를 지금 하지 않는 이유는 티켓이 스스로 적어 둔 조건 때문이다.

> **지금은 데이터가 없다.** NAV 스냅샷이 자정에만 쌓여서 본계정도 6일치뿐이고
> 1M/3M/YTD/1Y가 전부 null이다. (…) 스냅샷이 최소 1개월 쌓인 뒤 착수한다.

2026-08-17 기준 마감 워크플로 성공 이력은 **2026-08-15·16 이틀치**다(스냅샷 공백 수정 #168·#171이
2026-08-16에 들어갔다). 한 달과 거리가 크다. 오버레이를 지금 만들면 **빈 차트에 빈 선을 겹치는 것**이고
검증이 불가능하다.

반면 **소스 이관은 스냅샷 누적과 무관하게 지금 가치가 있다.** AF-108이 미결로 남겨 둔 항목을 닫는다.

## 왜 지금인가

벤치마크 지수 시계열은 **Yahoo Finance 비공식 엔드포인트**에서 온다
(`YahooBenchmarkHistoryClient`, `BenchmarkSyncService` — 기동 시 + 매일 01:10).

AF-108 검토 문서가 이것을 명시적으로 미결로 남겼다.

> | `benchmark_daily` (Yahoo) | 리포트에서 사용 중 | 별도 검토 필요. 비공식 엔드포인트다 |

그리고 #175(2026-08-16)가 **재배포 제한 없는 대체 경로**를 확인했다 — 공공데이터포털
[금융위원회_지수시세정보](https://www.data.go.kr/data/15094807/openapi.do)(제공기관 금융위원회,
한국거래소 연계, 무료, **이용허락범위 제한 없음**). 국내 지수만 있고 해외 지수는 없다.

## 현황 (코드 실측)

이 기능은 **백지가 아니다.** ABOR 이식분으로 전 층이 이미 있다.

| 층 | 파일 | 상태 |
|---|---|---|
| 도메인 | `BenchmarkType` (`SPX`·`KOSPI`·`BTC`) | 있음. `yahooTicker` 필드 보유 |
| 포트 | `BenchmarkDailyStore` (`latestDate`·`upsert`·`series`) | 있음 |
| 어댑터 | `JdbcBenchmarkDailyStore` → `benchmark_daily` | 있음 |
| 수집 | `BenchmarkSyncService` + `YahooBenchmarkHistoryClient` | 있음 (**제거 대상**) |
| 조립 | `ReportService.benchmark()` · `buildBenchmarkSeries()` | 있음 |
| 조립 | `GetReturnsAnalysisUseCase` | 있음 |
| 화면 | `app/unified/reports/benchmark/page.tsx` (227줄, 막대 + 선형) | 있음 |

**`GetDashboardUseCase`는 포트를 안 거친다.** `BenchmarkDailyJpaRepository`로 `benchmark_daily`를
직접 읽어 KOSPI YTD를 만든다(`GetDashboardUseCase.kt:111`). 이관 시 여기도 같이 옮겨야 한다 —
안 옮기면 대시보드만 옛 테이블을 보고 값이 갈린다.

## 결정 (2026-08-17 확정)

**KOSPI만 옮긴다. SPX·BTC는 Yahoo에 둔다.**

| 벤치마크 | 이번에 | 출처 | 이유 |
|---|---|---|---|
| `KOSPI` | **옮긴다** | 신규 FSC 수집기 → `benchmark_daily` | 약관 깨끗 + **백필 확인됨**(아래) |
| `SPX` | **안 옮긴다** | Yahoo 유지 | 옮겨도 KIS 약관이 미결이라 **얻는 게 없고**, 이력이 닷새치로 줄어 화면이 나빠진다 |
| `BTC` | **안 옮긴다** | Yahoo 유지 | 같음 (Upbit 약관 미결) |

### 셋을 다 옮기지 않기로 한 근거

초안은 "Yahoo 클라이언트를 통째로 걷어낸다"였다. 그 대가를 재 보니 **SPX·BTC는 손해만 남는다.**

- **약관 이득이 없다.** Yahoo(비공식) → KIS·Upbit(**둘 다 미결**)은 미해결을 미해결로 바꾸는 것이다. 깨끗해지는 건 FSC로 가는 KOSPI뿐이다.
- **이력을 잃는다.** `market_index_quote`·`fx_rate_daily`가 2026-08-12부터라 닷새치다. 옮기면 `vs S&P 500`·`vs Bitcoin`의 YTD·1Y가 **당분간 빈다.** KOSPI는 백필이 되므로 이 문제가 없다.

**남은 Yahoo 의존은 KIS·Upbit 약관이 정리되거나 이력이 쌓인 뒤에 걷는다.** 그때는 잃는 것이 없다.

### 이 결정이 설계를 크게 줄인다

`benchmark_daily`를 **읽는 쪽은 아무것도 안 바뀐다.** 셋 다 같은 표를 계속 보고, 바뀌는 것은 KOSPI 행을 **채우는 주체**뿐이다. 초안의 다음 항목이 전부 불필요해진다:

- `BenchmarkSeriesSource` 읽기 포트 신설 · `CompositeBenchmarkSeriesSource` — 분기할 대상이 없다
- 소비자 3곳(`ReportService`·`GetReturnsAnalysisUseCase`·`GetDashboardUseCase`) 재배선
- `GetDashboardUseCase`가 포트를 안 거치고 JPA로 직접 읽는 문제 — 사실이지만(실측 확인) **이번 범위 밖**이다. 읽는 표가 안 바뀌므로 값이 갈릴 일이 없다
- `YahooBenchmarkHistoryClient`·`BenchmarkHistoryClient`·`BenchmarkType.yahooTicker` 제거 — SPX·BTC가 계속 쓴다
- **BTC 기준 표기 변경(BTC-USD → BTC-KRW)** — BTC가 Yahoo에 남으므로 기준이 안 바뀐다. 초안의 「표기 변경」 절은 통째로 무효다

## 구조

```
BenchmarkSyncService (Yahoo)  →  benchmark_daily   [SPX · BTC]   ← 그대로
FscIndexCollectService (신규) →  benchmark_daily   [KOSPI]       ← 새로
                                       ↓
        ReportService · GetReturnsAnalysisUseCase · GetDashboardUseCase   ← 안 바뀜
```

바꿀 것은 넷뿐이다.

1. **`FscIndexCollectService` 신설** — 공공데이터포털 지수시세정보에서 KOSPI 확정 종가를 `benchmark_daily`에 UPSERT
2. **`BenchmarkSyncService`가 KOSPI를 건너뛴다** — 지금은 `BenchmarkType.entries`를 전부 돈다. 안 막으면 **두 소스가 같은 행을 번갈아 덮어써** 값이 실행마다 흔들린다
3. **KOSPI 1회 삭제 + 백필** (아래)
4. `BenchmarkType.KOSPI.yahooTicker`가 쓰이지 않게 된다 — **지우지 말 것.** enum이 셋을 함께 들고 있고 SPX·BTC는 계속 쓴다. 안 쓰인다는 사실을 KDoc에 남긴다

## FSC 수집기

원자재의 `CommoditySource` 패턴을 따른다 — **가져오기만 소스별이고 저장은 공용이다.**

### 실측으로 확정 (2026-08-17, 직접 호출)

초안은 이 절을 "구현 1단계에서 실측한다"로 열어 뒀다. **실측했고 아래는 추측이 아니다.**

```
GET {BASE}/GetMarketIndexInfoService/getStockMarketIndex
    ?serviceKey=… &resultType=json &numOfRows=3000 &pageNo=1
    &idxNm=코스피 &beginBasDt=YYYYMMDD &endBasDt=YYYYMMDD
```

| 항목 | 값 |
|---|---|
| 날짜 필드 | `basDt` (`yyyyMMdd`) |
| 종가 필드 | `clpr` — 2026-08-13 = **6,813.34** |
| KOSPI 선별 | `idxNm=코스피` (**정확 일치**, `totalCount=1`, `idxCsf='KOSPI시리즈'`) |
| 범위 조회 | **지원.** 1년 = 242영업일이 **한 페이지**에 (`numOfRows=3000` 존중, 잘림 없음) |

### 🔴 `idxNm`만으로는 지수가 유일하지 않다

같은 응답에 **`"IT 서비스"`가 `KOSPI시리즈`와 `KOSDAQ시리즈`에 둘 다** 있었다(1주 조회에 `totalCount=672`). `idxNm=코스피`가 지금 1건인 것은 그 이름이 마침 유일해서다.

**설정 단위는 `(idxNm, idxCsf)` 쌍으로 둔다.** 이름만으로 고르면 지수를 하나 더할 때 **잘못된 시리즈를 집는다** — 값이 그럴듯해서 숫자로는 못 알아챈다. AF-110이 해외 지수 이름 검사를 강화한 것(#162)과 같은 방향이다.

### 🔴 `fltRt`가 앞의 0을 생략한다

원문이 `.73` · `-.6`이다. **금 수집기(#178)와 똑같은 함정**이고 그쪽에 이미 방어와 테스트가 있다 — 파서를 재사용하거나 같은 형식을 테스트로 고정할 것. (등락률을 저장하진 않지만 파싱 단계에서 죽으면 행이 통째로 버려진다.)

### 스케줄

기존 수집 스케줄러(AF-103, GitHub Actions cron)에 붙인다. D+1 확정 종가라 하루 1회면 충분하다.

## 백필 — 조건부가 아니다, 된다

**초안의 "지원하지 않으면 오늘부터 쌓인다" 분기는 무효다.** 범위 조회가 실측으로 확인됐다 — 2025-08-18 ~ 2026-08-13 요청에 **242영업일이 한 페이지로** 돌아왔다.

1년치를 채워 YTD·1Y를 살린다. 실행 경로는 기존 백필 전례(`scripts/fx-backfill.sh` + admin 엔드포인트)를 따른다.

### 교차 검증 — 소스를 바꿔도 숫자가 안 흔들린다

FSC 데이터로 KOSPI YTD를 계산하니 **61.68%**, 현재 화면(Yahoo 기반)이 **+61.92%**. **차이 0.24%p**다. 61% 움직임에서 상대 오차 0.4%이고, 기준일 규약 차이로 설명된다(역산한 화면의 기준값 4,207.84 vs FSC의 2025년 마지막 종가 4,214.17).

**이관 후 사용자가 보는 숫자가 사실상 그대로라는 뜻이다.** 이 검증 없이 소스를 바꾸면 값이 달라졌을 때 그게 이관 탓인지 원래 차이인지 가릴 수 없다.

## Yahoo 행 1회 정리 — KOSPI만

`benchmark_daily`는 `(index_type, date)`가 키다. FSC 백필은 **겹치는 날짜만** 덮는다. 범위 밖 옛 Yahoo 행이 남으면 **한 시계열 안에 두 소스가 말없이 섞인다.**

→ 백필 전에 **`DELETE FROM benchmark_daily WHERE index_type = 'KOSPI'`** 를 1회 실행한다.

**`SPX`·`BTC` 행은 절대 지우지 않는다.** 그쪽은 Yahoo가 계속 채우는 살아 있는 시계열이다. (초안은 "아무도 안 읽으니 그대로 둔다"고 적었는데, 셋을 다 옮긴다는 전제에서 나온 문장이라 지금은 틀렸다.)

**순서가 안전한 이유**: 백필이 된다는 것이 실측으로 확인됐다. 안 됐다면 지우고 채울 것이 없어졌을 것이다 — 지우기 전에 백필 한 번을 드라이런해 건수를 확인할 것.

## 표기 변경 — 이번엔 없다

초안은 **BTC 기준이 BTC-USD → BTC-KRW로 바뀌므로 각주가 필요하다**고 적었다. **BTC를 안 옮기기로 했으므로 무효다.** 기준이 안 바뀌니 라벨도 각주도 그대로 둔다.

`BenchmarkType.yahooTicker`도 **지우지 않는다** — SPX·BTC가 계속 쓴다. KOSPI 것만 쓰이지 않게 되므로 그 사실을 KDoc에 남긴다.

가격지수/TR 각주(배당 문제)는 오버레이 본체 몫이라 이번 범위 밖이다.

## 테스트

- **FSC 파서**: `(idxNm, idxCsf)` 쌍으로 고르는지 — **두 시리즈에 같은 이름이 섞인 픽스처**를 쓸 것. 한 종목만 있으면 필터를 지워도 통과한다(#178에서 같은 함정을 겪었다)
- `fltRt`의 `.73`·`-.6` 형식 파싱
- `basDt` → `LocalDate` (`yyyyMMdd`)
- `items`가 배열이 아닐 때(0건) 빈 목록 — 공공데이터포털 관례
- **숫자 런타임 검증**: AF-104에서 타입 선언이 런타임을 검사하지 않아 자릿수가 날아가고 0이 대시로 표시된 전례가 있다
- **`BenchmarkSyncService`가 KOSPI를 안 건드리는지** — 이게 이번 변경의 핵심 회귀다. 안 막으면 두 소스가 같은 행을 번갈아 덮어써 값이 실행마다 흔들린다. **변이로 확인할 것**: 건너뛰기를 지우면 실패해야 한다
- 회귀: `ReportServiceTest` · `GetReturnsAnalysisUseCaseTest` · `MonthlyReportGeneratorTest` · `ReportControllerReturnsPercentTest` · `GetDashboardUseCase*Test` — **이번 설계는 읽는 쪽을 안 건드리므로 전부 그대로 통과해야 한다.** 하나라도 깨지면 범위를 넘은 것이다

## 미결 — 설계로 없앨 수 없는 것

1. ~~FSC 지수 오퍼레이션 경로·파라미터 미확인~~ → **닫힘.** 2026-08-17 실측
2. ~~`FSC_API_KEY`의 15094807 활용신청 승인~~ → **닫힘.** 2026-08-17 승인 완료, 호출 성공
3. **KIS·Upbit 약관 미결은 이 작업으로 안 닫힌다.** SPX·BTC가 Yahoo에 남으므로 **Yahoo 비공식 엔드포인트 의존도 남는다.** 이번 결정은 그것을 없애는 대신 **미루는** 것이고, 미루는 이유는 지금 옮기면 손해만 나기 때문이다(결정 절 참조)
4. **KOSPI가 두 곳에서 나온다** — 시장 화면은 KIS(하루 세 슬롯, 실시간성), 벤치마크·대시보드는 FSC(D+1 확정 종가, 이력 정합성). 용도가 실제로 달라 결함이 아니지만, 코드 주석과 화면 표기에 남긴다

## 범위 밖

- AF-107 본체(TWR 차트 오버레이) — 스냅샷 1개월 누적 후
- 국내 지수 5종 전체의 FSC 이관 — 시장 화면이 D+1로 후퇴한다. 별도 티켓
- 가격지수/TR 배당 각주 — 오버레이 본체와 함께
