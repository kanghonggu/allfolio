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

## 결정

**Yahoo 의존을 완전히 걷어내고, 벤치마크 3종을 이미 수집 중인 데이터로 공급한다.**

| 벤치마크 | 읽는 곳 | 출처 | 약관 |
|---|---|---|---|
| `KOSPI` | `benchmark_daily` | **신규 FSC 수집기** (공공데이터포털 지수시세정보) | ✅ 이용허락 제한 없음 |
| `SPX` | `market_index_quote` (`index_code='SPX'`, `slot='CLOSE'`) | 이미 수집 중 (KIS, AF-110) | ⚠️ KIS 미결 — 노출이 늘지 않을 뿐 |
| `BTC` | `fx_rate_daily` (`currency='BTC'`) | 이미 수집 중 (Upbit 일봉, AF-113) | ⚠️ 성격 검토 미결 |

`BenchmarkType`의 이름이 `market_index_quote.index_code`와 그대로 일치한다(`KOSPI`·`SPX`) —
매핑 표를 따로 만들 필요가 없다.

### 왜 `benchmark_daily`를 남기는가

**DB 마이그레이션을 0으로 두기 위해서다.** 원자재(#176)는 마이그레이션을 배포 전에 적용하지 않으면
시장 엔드포인트 전체가 500이 되는 구조였다. 같은 위험을 만들지 않는다.

`benchmark_daily`는 `(index_type, date, close_value)` — 확정 종가 창고로 쓰기에 이미 맞는 모양이다.
테이블을 그대로 두고 **채우는 주체만 Yahoo에서 FSC로 바꾼다.**

### 왜 FSC 종가를 `market_index_quote`에 넣지 않는가

슬롯 우선순위 규칙(`CLOSE > MID > OPEN`)이 `MarketIndexQuoteJpaRepository.findLatestByCodes`의
JPQL 한 곳에 있고, 시장 화면이 그걸로 "지금 값"을 고른다. 확정 종가를 새 슬롯으로 끼워 넣으면
**시장 화면이 오염된다.** 유니크 키가 `(index_code, trade_date, slot)`이라 `CLOSE`로 넣으면 KIS 행과
충돌한다.

### 두 개의 KOSPI — 결함이 아니라 역할 분리

이관 후 KOSPI 값이 두 곳에서 나온다.

- **시장 화면** — KIS, 하루 세 슬롯, 실시간성 우선
- **벤치마크·대시보드** — FSC, D+1 확정 종가, 이력 정합성 우선

**용도가 실제로 다르다.** 벤치마크는 수익률 비교를 위한 종가 시계열이 필요하고, 화면은 지금 값이
필요하다. 이 구분을 코드 주석과 화면 표기에 남긴다.

국내 지수 5종 전체를 FSC로 옮기는 일(#175 권고)은 **이 문서 범위 밖**이다. 시장 화면이 D+1로
후퇴하고 AF-101이 #147에서 감수하기로 한 결정을 뒤집는 일이라 별도 티켓이 맞다.

## 구조

**읽기 포트를 읽기 전용으로 좁힌다.** `latestDate`·`upsert`는 Yahoo 동기화 전용이었다. 동기화가
사라지면 소비자에게 필요한 것은 `series`뿐이다.

쓰기는 없어지지 않고 **주인이 바뀐다.** `benchmark_daily`에 KOSPI 종가를 넣는 것은 이제 FSC
수집기의 일이므로, UPSERT는 수집기 쪽 저장 포트로 옮긴다. 리포트·대시보드는 쓰기를 볼 수 없다.

```
BenchmarkSeriesSource                     (읽기 전용 포트)
  └─ CompositeBenchmarkSeriesSource       (타입으로 분기)
       ├─ KOSPI → JdbcBenchmarkDailyStore (benchmark_daily)
       ├─ SPX   → MarketIndexQuote 조회   (slot='CLOSE')
       └─ BTC   → FxRateDaily 조회        (currency='BTC')

FscIndexCollectService → benchmark_daily  (KOSPI 확정 종가, UPSERT 멱등)
```

**소비자 3곳을 모두 새 포트로 옮긴다**: `ReportService`, `GetReturnsAnalysisUseCase`,
`GetDashboardUseCase`(현재 JPA 직접 접근).

**제거**: `YahooBenchmarkHistoryClient`, `BenchmarkHistoryClient` 포트, `BenchmarkSyncService`,
`BenchmarkType.yahooTicker`, 포트의 `upsert`·`latestDate`.

## FSC 수집기

원자재의 `CommoditySource` 패턴을 따른다 — **가져오기만 소스별이고 저장은 공용이다.**

### 경로를 추측하지 않는다

원자재 때 오퍼레이션 경로를 명명 규약으로 추측했다가 `getGoldPrcInfo`·`getGoldMarketPriceInfo`가
**둘 다 오답**이었고, 실측으로만 `getGoldPriceInfo`가 확정됐다. 포털은 경로가 없으면 키를 보기 전에
`NO_OPENAPI_SERVICE_ERROR`(12)를 내고, 있으면 키 검증까지 간다 — 이 차이로 경로를 가릴 수 있다.

**구현 1단계는 실측이다.** 아래를 실제 응답으로 확인한 뒤 코드에 박는다.

1. 오퍼레이션 경로
2. 과거 조회 파라미터명과 지원 여부 (`basDt` 단건인지 `beginBasDt`/`endBasDt` 범위인지)
3. KOSPI를 고르는 필드 (`idxNm` 등)와 정확한 값
4. 종가 필드명

### 지수 선별은 이름 매칭 + 설정화

AF-110에서 해외 지수 이름 검사를 강화한 전례(#162)를 따른다. 코드에 문자열을 흩지 않고
`application.yml`에 둔다.

### 스케줄

기존 수집 스케줄러(AF-103, GitHub Actions cron)에 붙인다. D+1 데이터이므로 하루 1회면 충분하다.

## 백필 — 조건부, 실패해도 설계가 무너지지 않는다

과거 기간 조회가 되면 **1년치를 채워 YTD·1Y를 살린다.** 실행 경로는 기존 백필 전례
(`scripts/fx-backfill.sh` + admin 엔드포인트)를 따른다.

**지원하지 않으면 KOSPI도 오늘부터 쌓인다.** 그 경우 SPX·BTC와 같은 처지가 되고, 이미 있는 규약이
그대로 받아낸다 — `ReportService.benchmark()`는 데이터가 없는 벤치마크를 목록에서 제외한다(QA P1 #10).
빈 값이 화면에 0으로 뜨지 않는다.

## Yahoo 행 1회 정리

`benchmark_daily`는 `(index_type, date)`가 키다. FSC 백필은 **겹치는 날짜만** 덮는다. 범위 밖 옛
Yahoo 행이 남으면 **한 시계열 안에 두 소스가 말없이 섞인다.**

→ 백필 전에 `DELETE FROM benchmark_daily WHERE index_type = 'KOSPI'`를 1회 실행한다.

`SPX`·`BTC` 행은 이관 후 아무도 읽지 않으므로 그대로 둔다(삭제해도 무방하나 이득이 없다).

## 표기 변경

**BTC 기준이 바뀐다 — BTC-USD → BTC-KRW.** Upbit 일봉은 원화 가격이다. 원화 포트폴리오와 비교하는
맥락에서는 오히려 맞지만 **숫자가 달라지므로 밝혀야 한다.** 라벨과 화면 각주에 표기한다.

`BenchmarkType.yahooTicker`는 의미를 잃으므로 지운다.

가격지수/TR 각주(티켓이 경고한 배당 문제)는 **오버레이 본체 몫이라 이번 범위에 넣지 않는다.**

## 테스트

- 합성 어댑터: 타입 라우팅, 빈 데이터, 날짜 경계(from·to 포함 여부)
- SPX: 같은 날 여러 슬롯이 있을 때 `CLOSE`만 고르는지
- BTC: `currency` 필터가 BTC 외 통화를 안 집어오는지
- FSC 파서: 이름 매칭, 결측 행, 숫자 파싱. **런타임 검증을 넣는다** — AF-104에서 타입 선언이
  런타임을 검사하지 않아 자릿수가 날아가고 0이 대시로 표시된 전례가 있다
- 회귀: Yahoo 제거 후 기존 리포트 테스트 5종이 그대로 통과하는지 —
  `ReportServiceTest` · `GetReturnsAnalysisUseCaseTest` · `MonthlyReportGeneratorTest` ·
  `ReportControllerReturnsPercentTest` · `GetDashboardUseCase*Test`

## 미결 — 설계로 없앨 수 없는 것

1. **FSC 지수 오퍼레이션 경로·파라미터 미확인.** 구현 1단계에서 실측한다
2. 🔴 **`FSC_API_KEY`의 15094807 활용신청 승인이 따로 필요하다 — 사용자 작업이다.**
   공공데이터포털은 데이터셋별로 활용신청이 별개다. 기존 키가 15094805(일반상품)에만 승인돼 있으면
   지수는 `SERVICE_KEY_IS_NOT_REGISTERED_ERROR`가 계속 난다. **승인 전에는 KOSPI 수집이 0건이다**
3. **SPX·BTC 이력이 2026-08-12부터**다. 당분간 YTD·1Y는 짧거나 빈다. 백필 경로가 없다
4. **KIS·Upbit의 약관 미결은 이 작업으로 닫히지 않는다.** SPX·BTC가 거기 걸려 있고, 노출 표면이
   늘지 않을 뿐이다

## 범위 밖

- AF-107 본체(TWR 차트 오버레이) — 스냅샷 1개월 누적 후
- 국내 지수 5종 전체의 FSC 이관 — 시장 화면이 D+1로 후퇴한다. 별도 티켓
- 가격지수/TR 배당 각주 — 오버레이 본체와 함께
