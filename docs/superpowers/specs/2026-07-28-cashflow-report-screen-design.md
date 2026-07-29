# 현금흐름 보고서 화면 (R-06, SCR-RPT-09) — 설계

- 날짜: 2026-07-28
- 태스크: ALLFOLIO 이식 개발 태스크 DB #41 (R2 확장, FE 부분) — **2단계: FE 화면**. 1단계 BE 엔진은 PR #46.
- 근거 문서: 리포트명세서 R-06 · 화면정의서 SCR-RPT-09 · 엔진 스펙 `docs/superpowers/specs/2026-07-28-cashflow-report-engine-design.md`
- 브랜치: `feat/cashflow-report-screen` (main에서 분기 — 일반화 아카이브 인프라가 이미 main)
- 선행: #37~40 FE 일반화 인프라(main), #41 BE 엔진(PR #46 — `CASHFLOW` 생성기)

## 1. 목적

#46 BE 엔진이 아카이브한 `CASHFLOW` 본문(JSON)을 렌더링하고 브라우저 인쇄(PDF)를 제공하는 **FE 화면(SCR-RPT-09 뷰어)**. #37 월간·#38 배당·#39 비용·#40 보유 화면과 동형(목록/생성 + 상세/인쇄).

## 2. 백엔드 계약 (기존 + #46 BE)

`/api/reports/archive` — `generate {type:"CASHFLOW", year, month}` / `list?type=CASHFLOW` / `detail/{id}`. 본문 JSON — #46 엔진 산출:

```json
{
  "summary": { "totalInflow":…, "totalOutflow":…, "netFlow":… },
  "byType":  [ {"type":"입금"|"출금"|"매수대금"|"매도대금"|"배당·이자"|"수수료·세금", "amount": … (부호), "direction":"IN"|"OUT"} ],
  "monthly": [ {"month":"YYYY-MM", "inflow":…, "outflow":…, "net":…} ],
  "details": [ {"date":"YYYY-MM-DD", "account":…, "type":…, "description":…, "amount": … (부호)} ]
}
```

- **금액만**(퍼센트 없음) → KRW `fmtKrw`, 부호 색상 `pctColor`. 스케일(×100) 이슈 없음
- `amount`·`netFlow`는 부호 있는 값(유출 음수)

## 3. 아키텍처 (#39/#40 FE 미러, main 분기)

라우트 `/unified/reports/cashflow-report`: `page.tsx`(목록/생성) + `[id]/page.tsx`(상세/인쇄).

### 공유 인프라 (재사용 + 소폭 확장)

- **`lib/report-archive-api.ts` (수정)**: `CASHFLOW` 상수 추가, `ReportType` 유니온에 `typeof CASHFLOW` 추가(2줄).
- 재사용: `useReportArchiveApi('CASHFLOW')`, `parseReportBody<CashflowReportBody>`, `report-format`(fmtKrw·pctColor), 인쇄 CSS, `ArchiveMeta`.

### 신규 파일

- `types/cashflow-report.ts` — `CashflowReportBody`(summary/byType/monthly/details) + `ArchiveMeta`/`ArchiveDetail` re-export
- `components/cashflow-report/`: `CashflowSummary`, `CashflowByType`, `MonthlyCashflowChart`, `CashflowDetails`
- `app/unified/reports/cashflow-report/page.tsx`(목록/생성) + `[id]/page.tsx`(상세/인쇄)

## 4. 상세 페이지 섹션

1. **헤더** — 기간(YYYY년 M월)·asOf·생성일 + `status=WARNING` 노란 배너 + 인쇄 버튼(#38 패턴)
2. **요약 카드** — 총유입(`fmtKrw`) · 총유출(`fmtKrw`) · 순현금흐름(`fmtKrw`, `pctColor(netFlow)`)
3. **유형별 현금흐름** — byType 부호 바차트(recharts BarChart, `amount` 값, 유입 green/유출 red) + 테이블(유형·금액 부호색·방향). 빈 배열이면 "데이터 없음"
4. **월별 추이** — recharts: 유입/유출 그룹 BarChart + 순흐름(net) Line(ComposedChart). 빈 배열이면 "데이터 없음"
5. **상세 내역** — 일자·계좌·유형·설명·금액(`fmtKrw`, 부호색), 날짜순. 빈 배열이면 "내역이 없습니다"

## 5. PDF·에러·네비 (#38 승계)

- 인쇄: `window.print()` + 기존 `.print-invert`/`.no-print`/`@media print` 재사용(신규 CSS 없음)
- 상세: `!api || isLoading` 가드, 파싱은 `queryFn` 내부, 404 폴백 + 목록 링크, `retry:false`
- 목록: 연·월 생성(1월 연도 롤백) + 이력 테이블(키보드 접근), 생성 오류 인라인 배너
- 허브 `app/unified/reports/page.tsx`에 "현금흐름 보고서" 카드 추가(고유 배지·색)

## 6. 테스트·검증

FE 테스트 러너 없음. `npx tsc --noEmit` + `npx next build`(현금흐름 2라우트 + **월간·배당·비용·보유 회귀 없음**) + 브라우저 프리뷰(로그인 세션 + 실 아카이브)로 섹션 렌더·null 폴백·인쇄 미리보기. 스모크: 생성 → 목록/상세 → 인쇄.

## 7. 제외 (후속)

기초/기말 조정표·정합검증·환전·계좌간이체·특이거래·거래일/결제일 토글 — v1 엔진 미산출. 워터폴은 v1에서 byType 부호 바로 대체.
