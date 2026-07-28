# 배당·이자 보고서 화면 (R-03, SCR-RPT-05) — 설계

- 날짜: 2026-07-23
- 태스크: ALLFOLIO 이식 개발 태스크 DB #38 (R1 리포트 MVP, FE 부분) — **2단계: FE 화면**. 1단계 BE 엔진은 PR #38.
- 근거 문서: 리포트명세서 R-03 · 화면정의서 SCR-RPT-05 · 엔진 스펙 `docs/superpowers/specs/2026-07-23-dividend-report-engine-design.md`
- 브랜치: `feat/dividend-report-screen` (base: `feat/monthly-report-screen` — #37 FE 인프라 위 스택. #36 머지 시 GitHub이 base를 main으로 자동 재지정)
- 선행: #37 월간 FE(PR #36 — 아카이브 API 클라이언트·포맷 헬퍼·인쇄 CSS 공유), #38 BE 엔진(PR #38 — `DIVIDEND_INTEREST` 생성기)

## 1. 목적

#38 BE 엔진이 아카이브한 R-03 배당·이자 보고서 본문(JSON)을 렌더링하고 브라우저 인쇄로 PDF를 제공하는 **FE 화면(SCR-RPT-05 뷰어)**. #37 월간 화면과 동형(아카이브 프레임: 목록/생성 + 상세/인쇄). 세율 마스터(SCR-RPT-06)는 후속.

## 2. 백엔드 계약 (기존 + #38 BE)

`/api/reports/archive` (Bearer, `JwtUserIdFilter`가 X-User-Id 서버측 추출). `generate {type:"DIVIDEND_INTEREST", year, month}` / `list?type=DIVIDEND_INTEREST` / `detail/{id}`. 본문(body) JSON — #38 엔진 산출:

```json
{
  "summary": { "grossTotal":…, "withholdingTax":…, "netTotal":…, "effectiveTaxRate":…, "receiptCount":…, "ttmYield":… | null },
  "receipts": [ {"payDate":"YYYY-MM-DD", "stockName":…, "symbol":…, "account":…, "gross":…, "tax":…, "net":…} ],
  "monthly":  [ {"month":"YYYY-MM", "net":…} ],
  "bySymbol": [ {"stockName":…, "symbol":…, "gross":…, "tax":…, "net":…, "weight":…} ],
  "byCountry":[ {"country":"국내"|"해외", "gross":…, "tax":…, "net":…, "effectiveTaxRate":…} ]
}
```

- **스케일 0~100**: `effectiveTaxRate`·`weight`·`ttmYield` → `fmtPctScaled`. 금액(`gross/tax/net/*Total`) → `fmtKrw`
- `ttmYield`는 null 가능(평가액 0)

## 3. 아키텍처 (#37 인프라 일반화 + 미러)

라우트 `/unified/reports/dividend-report`: `page.tsx`(목록/생성) + `[id]/page.tsx`(상세/인쇄). 레거시 live `/reports/dividend`는 유지.

### 공유 인프라 일반화 (#37 코드 소폭 수정)

- **`types/report-archive.ts` (신규)**: `ReportStatus`·`ReportWarning`·`ArchiveMeta`·`ArchiveDetail`를 monthly-report에서 **추출·이동**(월간/배당 공용).
- **`types/monthly-report.ts` (수정)**: 위 타입은 `report-archive`에서 re-export(기존 import 경로 유지) — 나머지 월간 전용 타입은 그대로.
- **`lib/report-archive-api.ts` (수정)**: `createReportArchiveApi(accessToken, reportType: string)`로 타입 파라미터화(generate/list가 `reportType` 사용). `parseReportBody<T>(body): T` 제네릭 추가. `MONTHLY_REPORT`·`DIVIDEND_INTEREST` 상수. (기존 `parseMonthlyReportBody`는 `parseReportBody<MonthlyReportBody>` 얇은 래퍼로 유지.)
- **`lib/useApi.ts` (수정)**: `useReportArchiveApi(reportType: string)` — deps `[accessToken, reportType]`.
- **`app/unified/reports/monthly-report/{page,[id]/page}.tsx` (수정)**: `useReportArchiveApi('MONTHLY_REPORT')`로 호출 변경(2곳). 렌더 로직 불변.

### 신규 파일

- `types/dividend-report.ts` — `DividendReportBody`(summary/receipts/monthly/bySymbol/byCountry) + `ArchiveMeta`/`ArchiveDetail`는 report-archive에서 import
- `lib/dividend-report-api.ts` **불필요** — 일반화된 `createReportArchiveApi` + `useReportArchiveApi('DIVIDEND_INTEREST')` 재사용, 본문은 `parseReportBody<DividendReportBody>`
- `components/dividend-report/`: `DividendSummary`, `ReceiptsTable`, `MonthlyNetTrend`, `BySymbolTable`, `ByCountryTable`
- `app/unified/reports/dividend-report/page.tsx`(목록/생성) + `[id]/page.tsx`(상세/인쇄)

## 4. 상세 페이지 섹션 (본문 렌더)

1. **헤더** — 기간(YYYY년 M월)·asOf·생성일 + `status=WARNING`이면 노란 배너 + 인쇄 버튼(#37 패턴, `.no-print`/`.print-invert`)
2. **요약 카드** — 세전 총액(`fmtKrw`) · 원천징수(`fmtKrw`) + 실효세율(`fmtPctScaled`) · 세후 실수령(`fmtKrw`) · TTM 배당수익률(`fmtPctScaled`, null이면 "—")
3. **수취 내역 테이블** — 지급일·종목(심볼)·계좌·세전·원천징수·세후. 빈 배열이면 "수취 내역이 없습니다"
4. **월별 추이** — 월별 세후(`net`) recharts BarChart. 빈 배열이면 "데이터 없음"
5. **종목별 집계 테이블** — 종목·세전·징수·세후·비중(`weight`, `fmtPctScaled` 또는 `.toFixed`)
6. **국가별 원천징수 요약** — 국내/해외 · 세전·징수·세후·실효세율

## 5. PDF·에러·네비 (#37 승계)

- 인쇄: `window.print()` + 기존 `.print-invert`/`.no-print`/`@media print`(globals.css) 그대로 재사용 — 신규 CSS 없음. 상세 루트 wrapper에 `print-invert`, 헤더/버튼에 `no-print`
- 생성 400(데이터 부족은 배당 0건이라 사실상 없음, 그 외 오류): 목록 인라인 에러 배너
- 상세 404: "보고서를 찾을 수 없습니다" + 목록 링크. 로딩: `!api || isLoading` 가드(#37 패턴), 파싱은 `queryFn` 내부
- 허브 `app/unified/reports/page.tsx`에 "배당·이자 보고서" 카드 추가

## 6. 테스트·검증

FE 테스트 러너 없음(프로젝트 정책). `next build`(타입·컴파일·정적생성) + 브라우저 프리뷰(로그인 세션 + 실 아카이브)로 섹션 렌더·null 폴백·인쇄 미리보기 확인. 스모크: 생성 → 목록/상세 → 인쇄.

## 7. 제외 (후속)

세율 마스터(SCR-RPT-06 ADMIN), 이자 섹션, 배당 캘린더·예상, 원통화 병기 — v1 엔진이 산출하지 않음. 전년 동월 비교·필터 바(계좌/유형/통화 토글)는 후속.
