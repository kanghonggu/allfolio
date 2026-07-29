# 월말 보유 명세서 화면 (R-05, SCR-RPT-08) — 설계

- 날짜: 2026-07-28
- 태스크: ALLFOLIO 이식 개발 태스크 DB #40 (R2 확장, FE 부분) — **2단계: FE 화면**. 1단계 BE 엔진은 PR #44.
- 근거 문서: 리포트명세서 R-05 · 화면정의서 SCR-RPT-08 · 엔진 스펙 `docs/superpowers/specs/2026-07-28-holdings-report-engine-design.md`
- 브랜치: `feat/holdings-report-screen` (main에서 분기 — 일반화 아카이브 인프라가 이미 main. 스택 불필요)
- 선차: #37/#38/#39 FE 일반화 인프라(main — `createReportArchiveApi(token, reportType)`·`useReportArchiveApi`·`parseReportBody<T>`·`types/report-archive.ts`·인쇄 CSS), #40 BE 엔진(PR #44 — `HOLDINGS` 생성기)

## 1. 목적

#44 BE 엔진이 아카이브한 `HOLDINGS` 본문(JSON)을 렌더링하고 브라우저 인쇄(PDF)를 제공하는 **FE 화면(SCR-RPT-08 뷰어)**. #37 월간·#38 배당·#39 비용 화면과 동형(목록/생성 + 상세/인쇄).

## 2. 백엔드 계약 (기존 + #44 BE)

`/api/reports/archive` — `generate {type:"HOLDINGS", year, month}` / `list?type=HOLDINGS` / `detail/{id}`. 본문(body) JSON — #44 엔진 산출:

```json
{
  "summary": { "totalValueKrw":…, "holdingCount":…, "accountCount":…, "cashWeight":…, "unrealizedPnlKrw":… },
  "holdings": [ {"name":…, "symbol":…|null, "type":…, "account":…, "provider":…,
                 "quantity":…, "avgPrice":…, "currentValue":…, "valueKrw":…,
                 "weight":…, "unrealizedPnl":…, "returnRate":…} ],   // valueKrw 내림차순
  "byAccount": [ {"account":…, "provider":…, "valueKrw":…, "weight":…, "holdingCount":…} ],
  "byType":    [ {"type":…, "valueKrw":…, "weight":…, "holdingCount":…} ],
  "cash":      [ {"account":…, "currency":…, "valueKrw":…} ],
  "note": string
}
```

- **스케일 0~100**: `weight`·`returnRate`·`cashWeight` → `fmtPctScaled`/`.toFixed`. 금액(`*ValueKrw`·`valueKrw`·`unrealizedPnl`) → `fmtKrw`. `unrealizedPnl`은 부호 `fmtKrw`+`pctColor`
- `currentValue`(원통화 평가액)·`avgPrice`(평단)은 자산 통화 기준 원값 — 로케일 숫자 포맷

## 3. 아키텍처 (#38/#39 FE 미러, main 분기)

라우트 `/unified/reports/holdings-report`: `page.tsx`(목록/생성) + `[id]/page.tsx`(상세/인쇄).

### 공유 인프라 (재사용 + 소폭 확장)

- **`lib/report-archive-api.ts` (수정)**: `HOLDINGS` 상수 추가, `ReportType` 유니온에 `typeof HOLDINGS` 추가(2줄). 나머지 그대로.
- 재사용: `useReportArchiveApi('HOLDINGS')`, `parseReportBody<HoldingsReportBody>`, `report-format`(fmtKrw·fmtPctScaled·pctColor), 인쇄 CSS, `ArchiveMeta`/`ArchiveDetail`.

### 신규 파일

- `types/holdings-report.ts` — `HoldingsReportBody`(summary/holdings/byAccount/byType/cash) + `ArchiveMeta`/`ArchiveDetail` re-export
- `components/holdings-report/`: `HoldingsSummary`, `HoldingsGrid`, `ByAccountTable`, `ByTypeTable`, `CashTable`
- `app/unified/reports/holdings-report/page.tsx`(목록/생성) + `[id]/page.tsx`(상세/인쇄)

## 4. 상세 페이지 섹션

1. **헤더** — 기준월(YYYY년 M월)·asOf·생성일 + `status=WARNING` 노란 배너 + 인쇄 버튼(#38 패턴)
2. **요약 카드** — 총평가액(`fmtKrw`) · 종목/계좌 수(`{holdingCount}종목 / {accountCount}계좌`) · 현금 비중(`fmtPctScaled`) · 평가손익 합계(`fmtKrw`, `pctColor(unrealizedPnlKrw)`)
3. **보유 명세 그리드** — 종목(심볼)·자산군(type)·계좌·수량·평단·평가액(원통화 + KRW)·비중(`.toFixed(2)%`)·평가손익(`fmtKrw`, 부호색)·수익률(`fmtPctScaled(returnRate)`). 빈 배열이면 "보유 종목이 없습니다"
4. **계좌별 소계 / 자산군별 소계** — 2 테이블: 계좌·평가액·비중·종목수 / 자산군·평가액·비중·종목수
5. **현금 잔고** — 계좌·통화·잔액(`fmtKrw`). 빈 배열이면 "현금성 자산 없음"
6. **note** 각주(작은 회색 텍스트)

## 5. PDF·에러·네비 (#38 승계)

- 인쇄: `window.print()` + 기존 `.print-invert`/`.no-print`/`@media print` 재사용(신규 CSS 없음)
- 상세: `!api || isLoading` 가드, 파싱은 `queryFn` 내부, 404 폴백 + 목록 링크, `retry:false`
- 목록: 연·월 생성(1월 연도 롤백) + 이력 테이블(키보드 접근), 생성 오류 인라인 배너
- 허브 `app/unified/reports/page.tsx`에 "월말 보유 명세서" 카드 추가(고유 배지·색)

## 6. 테스트·검증

FE 테스트 러너 없음. `npx tsc --noEmit` + `npx next build`(보유 2라우트 + **월간·배당·비용 회귀 없음**) + 브라우저 프리뷰(로그인 세션 + 실 아카이브)로 섹션 렌더·null 폴백·인쇄 미리보기. 스모크: 생성 → 목록/상세 → 인쇄.

## 7. 제외 (후속)

당월 실현손익·월간 변동·지역별 그룹핑·Excel — v1 엔진 미산출. 그룹핑 토글(계좌/자산군/지역)은 v1에서 두 소계 테이블로 대체, 인터랙티브 토글은 후속.
