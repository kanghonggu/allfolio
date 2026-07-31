# R-03 배당 캘린더 (지급 이력 패턴, 사실형) — Design Spec

- **Date**: 2026-07-31
- **Status**: Approved (design), pending implementation
- **Scope**: 배당·이자 보고서(R-03)에 **배당 지급 캘린더**(종목별 최근 12개월 지급 이력에서 도출한 지급 주기·지급 월 패턴) 추가 — BE 계산 + `DividendInterestReportGenerator` 통합 + FE.
- **Depends on**: 없음 — `main`에서 분기(#51 세율마스터와 무관, DividendInterestReportGenerator만 수정). #51 머지 여부와 독립.
- **안전 경계(핵심)**: **미래 예측·금액 추정·수익률 전망·투자 조언 금지**. 오직 **과거 12개월 지급 이력의 사실적 서술**(언제·몇 회 지급했는가)만. "향후 지급을 보장/예측하지 않음"을 리포트·FE에 명시.

## 1. Background

`DividendInterestReportGenerator`(#38)는 당월 배당 수취·월별·종목별·국가별 집계를 산출하며, 이미 **TTM(최근 12개월) 배당(`ttm`)**을 조회해 배당수익률에 사용한다. KDoc "v1 제외: ...배당 캘린더·예상". `DividendRecord`(payDate, stockName, symbol, accountName, gross, tax, net) 보유.

**데이터 제약**: 외부 배당 일정/배당락일(ex-date) 데이터 부재. 확정된 미래 지급일을 알 수 없음. 과거 지급이력에서 미래를 추정하면 **예측(조언 인접)** 이 되므로 배제. → 제공 가능한 사실형 산출물은 **과거 지급 타이밍 패턴**(주기·지급월·횟수·최근일)뿐.

## 2. Decisions (확정)

| 항목 | 결정 |
|---|---|
| 소스 | 기존 `ttm`(period.end 기준 최근 12개월 배당 이력) 재사용 |
| 단위 | 종목((stockName, symbol)) |
| 산출 | cadence(주기 분류)·paidMonths(지급된 달 1~12)·payCount(TTM 지급 횟수)·lastPayDate·ttmNet |
| cadence 분류 | **distinct 지급 월 수** 기준(다계좌 중복 방어): ≥10→"월배당", 4→"분기배당", 2→"반기배당", 1→"연 1회/단발", 그 외→"비정기" (관찰된 빈도의 서술; 예측 아님). paidMonths와 항상 일관 |
| 정렬 | ttmNet 내림차순 |
| 명시 | "과거 12개월 지급 이력 기반, 향후 지급 보장·예측 아님" |
| 후속 | 외부 배당락일/확정 지급일 캘린더, 세율마스터 기대세율 비교(#51 Phase B) |

## 3. Backend Design (module: `unified-asset`)

### 3.1 배당 캘린더 계산기 (신규, 순수)
`application/usecase/DividendCalendarCalculator.kt`:
```kotlin
data class DividendCalendarEntry(
    val symbol: String?,
    val stockName: String,
    val cadence: String,
    val paidMonths: List<Int>,     // 1~12, 오름차순 distinct
    val payCount: Int,             // TTM 지급 횟수
    val lastPayDate: LocalDate,
    val ttmNet: BigDecimal,
)

object DividendCalendarCalculator {
    /** 최근 12개월 배당 이력 → 종목별 지급 패턴(사실형). 예측/추정 없음. */
    fun build(ttm: List<DividendRecord>): List<DividendCalendarEntry> {
        return ttm.groupBy { it.stockName to it.symbol }
            .map { (key, rs) ->
                val months = rs.map { it.payDate.monthValue }.distinct().sorted()
                val count = rs.size
                DividendCalendarEntry(
                    symbol = key.second,
                    stockName = key.first,
                    cadence = cadenceOf(count),
                    paidMonths = months,
                    payCount = count,
                    lastPayDate = rs.maxOf { it.payDate },
                    ttmNet = rs.fold(BigDecimal.ZERO) { a, r -> a + r.net },
                )
            }
            .sortedByDescending { it.ttmNet }
    }

    private fun cadenceOf(count: Int): String = when {
        count >= 10 -> "월배당"
        count == 4  -> "분기배당"
        count == 2  -> "반기배당"
        count == 1  -> "연 1회/단발"
        else        -> "비정기"
    }
}
```

### 3.2 생성기 통합 — `DividendInterestReportGenerator`
- 기존 `ttm` 재사용:
  ```kotlin
  val calendar = DividendCalendarCalculator.build(ttm)
  ```
- body에 추가:
  ```kotlin
  "dividendCalendar" to calendar.map {
      mapOf("symbol" to it.symbol, "stockName" to it.stockName, "cadence" to it.cadence,
            "paidMonths" to it.paidMonths, "payCount" to it.payCount,
            "lastPayDate" to it.lastPayDate.toString(), "ttmNet" to it.ttmNet)
  },
  ```
- KDoc "v1 제외: ...배당 캘린더·예상" → "배당 캘린더(지급 이력 패턴, 사실형) 포함. 후속: 외부 확정 지급일·기대세율 비교."

## 4. Frontend Design (dividend-report 상세)
- 타입 `types/dividend-report.ts`: `DividendCalendarEntry { symbol: string | null; stockName: string; cadence: string; paidMonths: number[]; payCount: number; lastPayDate: string; ttmNet: number }`, body에 `dividendCalendar?: DividendCalendarEntry[]`(옵셔널 — 구 아카이브 호환).
- 신규 컴포넌트 `components/dividend-report/DividendCalendar.tsx`: 종목·주기(배지)·지급 월(1~12 미니 그리드 또는 "3·6·9·12월")·TTM 횟수·최근 지급일·TTM 순수취. 소제목 아래 "최근 12개월 지급 이력 기반이며 향후 지급을 보장·예측하지 않습니다." 문구. 빈/미존재 시 섹션 생략.
- `[id]/page.tsx`: bySymbol/byCountry 근처에 `{body.dividendCalendar && body.dividendCalendar.length > 0 && <DividendCalendar rows={body.dividendCalendar} />}`. `fmtKrw`, ×100 금지.

## 5. Tests
**Backend** — `DividendCalendarCalculatorTest`(순수): 월배당(12회→월배당·paidMonths 1~12)·분기(4회, 3·6·9·12→분기배당)·반기(2회→반기배당)·연1회(1회→연 1회/단발)·비정기(3회→비정기)·같은 종목 다계좌 합산(payCount·ttmNet 합)·정렬(ttmNet 내림차순)·lastPayDate=최대. `DividendInterestReportGeneratorTest` 확장: dividendCalendar 섹션 존재·주요 필드. 기존 단언 불변.
**Frontend**: `npx tsc --noEmit` clean.

## 6. Rollout
- **스키마 변경 없음** → 마이그레이션 불필요. main 병합 → 배포.

## 7. Affected Files
**BE**: (신규) `application/usecase/DividendCalendarCalculator.kt`, (수정) `application/usecase/DividendInterestReportGenerator.kt`, (test) 신규 `DividendCalendarCalculatorTest.kt` + 수정 `DividendInterestReportGeneratorTest.kt`.
**FE**: (수정) `types/dividend-report.ts`, (신규) `components/dividend-report/DividendCalendar.tsx`, (수정) `app/unified/reports/dividend-report/[id]/page.tsx`.

## 8. Out of Scope (후속)
외부 배당락일/확정 지급일 캘린더(외부 데이터 필요), 미래 지급 예측·예상 금액, 배당성장률, #51 세율마스터 기대세율 비교(Phase B), 이자(현재 배당만).
