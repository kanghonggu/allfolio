# 비용 보고서 화면 (R-04, SCR-RPT-07) — 설계

- 날짜: 2026-07-23
- 태스크: ALLFOLIO 이식 개발 태스크 DB #39 (R1 리포트 MVP, FE 부분) — **2단계: FE 화면**. 1단계 BE 엔진은 PR #40.
- 근거 문서: 리포트명세서 R-04 · 화면정의서 SCR-RPT-07 · 엔진 스펙 `docs/superpowers/specs/2026-07-23-cost-report-engine-design.md`
- 브랜치: `feat/cost-report-screen` (base: `feat/dividend-report-screen` #41 — 일반화된 아카이브 인프라 위 스택. 체인 #36←#41←본 브랜치. 상위 머지 시 base 자동 재지정)
- 선행: #38 FE 일반화 인프라(PR #41 — `createReportArchiveApi(token, reportType)`·`useReportArchiveApi`·`parseReportBody<T>`·`types/report-archive.ts`·인쇄 CSS), #39 BE 엔진(PR #40 — `COST` 생성기)

## 1. 목적

#40 BE 엔진이 아카이브한 R-04 비용 보고서 본문(JSON)을 렌더링하고 브라우저 인쇄(PDF)를 제공하는 **FE 화면(SCR-RPT-07 뷰어)**. #37 월간·#38 배당 화면과 동형(목록/생성 + 상세/인쇄).

## 2. 백엔드 계약 (기존 + #40 BE)

`/api/reports/archive` — `generate {type:"COST", year, month}` / `list?type=COST` / `detail/{id}`. 본문(body) JSON — #40 엔진 산출:

```json
{
  "summary": { "totalCost":…, "brokerFee":…, "tradingTax":…, "tradeCount":…,
               "costRatio":… | null, "annualizedTer":… | null, "costVsProfit":… | null, "investmentPnl":… | null },
  "byType":   [ {"type":"매매수수료"|"거래세", "amount":…, "weight":…} ],
  "byBroker": [ {"broker":…, "fee":…, "tax":…, "total":…, "weight":…} ],
  "monthly":  [ {"month":"YYYY-MM", "brokerFee":…, "tradingTax":…, "total":…} ],
  "details":  [ {"date":"YYYY-MM-DD", "account":…, "provider":…, "tradeType":…, "stockName":…, "fee":…, "tax":…} ]
}
```

- **스케일 0~100**: `costRatio`·`annualizedTer`·`costVsProfit`·`weight` → `fmtPctScaled`/`.toFixed`. 금액(`totalCost`·`brokerFee`·`tradingTax`·`amount`·`fee`·`tax`·`total`) → `fmtKrw`. `investmentPnl`은 부호 있는 `fmtKrw`
- `costRatio`·`annualizedTer`·`costVsProfit`·`investmentPnl`은 null 가능(NAV 부족·무손익)

## 3. 아키텍처 (#38 FE 미러, #41 위 스택)

라우트 `/unified/reports/cost-report`: `page.tsx`(목록/생성) + `[id]/page.tsx`(상세/인쇄).

### 공유 인프라 (재사용 + 소폭 확장)

- **`lib/report-archive-api.ts` (수정)**: `COST` 상수 추가, `ReportType` 유니온에 `typeof COST` 추가. 나머지(generate/list/detail/parseReportBody) 그대로.
- 재사용: `useReportArchiveApi('COST')`, `parseReportBody<CostReportBody>`, `report-format`(fmtKrw·fmtPctScaled·pctColor), 인쇄 CSS(`.print-invert`/`.no-print`/`@media print`), `ArchiveMeta`/`ArchiveDetail`(types/report-archive).

### 신규 파일

- `types/cost-report.ts` — `CostReportBody`(summary/byType/byBroker/monthly/details) + `ArchiveMeta`/`ArchiveDetail` re-export
- `components/cost-report/`: `CostSummary`, `ByTypeTable`, `ByBrokerMatrix`, `MonthlyCostTrend`, `CostDetailsTable`
- `app/unified/reports/cost-report/page.tsx`(목록/생성) + `[id]/page.tsx`(상세/인쇄)

## 4. 상세 페이지 섹션 (본문 렌더)

1. **헤더** — 기간(YYYY년 M월)·asOf·생성일 + `status=WARNING` 노란 배너 + 인쇄 버튼(#38 패턴)
2. **요약 카드** — 총비용(`fmtKrw`) · 비용률(`fmtPctScaled`, null→"—") · 연환산 TER(`fmtPctScaled`, null→"—") · 수익 대비 비용(`fmtPctScaled`, null→"—"). 하단 부가정보: 매매수수료/거래세 분해·거래 {tradeCount}건·investmentPnl(부호 `fmtKrw`, null→"—")
3. **유형별** — 테이블: 유형·금액·비중(`weight.toFixed(2)%`). 빈 배열이면 "데이터 없음"
4. **브로커×유형 매트릭스** — 테이블: 브로커·매매수수료(fee)·거래세(tax)·합계(total)·비중. 빈 배열이면 "데이터 없음"
5. **월별 추이** — recharts 스택 BarChart(brokerFee + tradingTax, 스택). 빈 배열이면 "데이터 없음"
6. **상세 내역** — 테이블: 일자·계좌·유형(tradeType)·종목·매매수수료·거래세. 빈 배열이면 "내역이 없습니다"
7. **각주** — "매매수수료는 손익 계산에 이미 반영됨 — 비용 가시화용(수익률 재차감 아님)"(명세 처리규칙 #1)

## 5. PDF·에러·네비 (#38 승계)

- 인쇄: `window.print()` + 기존 `.print-invert`/`.no-print`/`@media print` 재사용(신규 CSS 없음)
- 상세: `!api || isLoading` 가드, 파싱은 `queryFn` 내부, 404 폴백 + 목록 링크, `retry:false`
- 목록: 연·월 생성(1월 연도 롤백) + 이력 테이블(키보드 접근), 생성 오류 인라인 배너
- 허브 `app/unified/reports/page.tsx`에 "비용 보고서" 카드 추가

## 6. 테스트·검증

FE 테스트 러너 없음. `npx tsc --noEmit` + `npx next build`(비용 2라우트 + **월간·배당 회귀 없음** 확인) + 브라우저 프리뷰(로그인 세션 + 실 아카이브)로 섹션 렌더·null 폴백·인쇄 미리보기. 스모크: 생성 → 목록/상세 → 인쇄.

## 7. 제외 (후속)

환전 비용 상세·파생 수수료·인사이트 자동문구·브로커 bp 비교 — v1 엔진 미산출. 필터 바(계좌/유형 토글)는 후속.
