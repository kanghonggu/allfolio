# ESG 스크리닝 화면 (R-07, SCR-RPT-10) — 설계

- 날짜: 2026-07-28
- 태스크: ALLFOLIO 이식 개발 태스크 DB #42 (R2 확장, FE 부분) — **2단계: FE 화면**. 1단계 BE 엔진은 PR #47.
- 근거 문서: 리포트명세서 R-07 · 화면정의서 SCR-RPT-10 · 엔진 스펙 `docs/superpowers/specs/2026-07-28-esg-screening-engine-design.md`
- 브랜치: `feat/esg-screening-screen` (main에서 분기)
- 선행: FE 일반화 인프라(main), #42 BE 엔진(PR #47 — `ESG_SCREENING` 생성기)

## 1. 목적

#47 BE 엔진이 아카이브한 `ESG_SCREENING` 본문(JSON)을 렌더링하고 브라우저 인쇄(PDF)를 제공하는 **FE 화면(SCR-RPT-10 뷰어)**. 다른 리포트 화면과 동형(목록/생성 + 상세/인쇄).

## 2. 백엔드 계약 (기존 + #47 BE)

`/api/reports/archive` — `generate {type:"ESG_SCREENING", year, month}` / `list?type=ESG_SCREENING` / `detail/{id}`. 본문 JSON — #47 엔진 산출:

```json
{
  "esg": { "rating":…, "totalScore":…, "environmental":…, "social":…, "governance":… },  // 0~100
  "esgBreakdown": [ {"name":…, "type":…, "weight":…, "e":…, "s":…, "g":…, "total":…, "rating":…} ],  // total 내림차순, weight 0~100
  "screening": { "violationCount":…, "violationValueKrw":…, "violationWeight":… },  // violationWeight 0~100
  "violations": [ {"name":…, "symbol":…, "listName":…, "reason":…, "valueKrw":…, "weight":…} ],  // weight 0~100
  "note": string
}
```

- **스케일 0~100**: `weight`·`violationWeight` → `.toFixed(2)%`. **ESG 점수(`totalScore`·`environmental`·`social`·`governance`·breakdown `e`/`s`/`g`/`total`)는 0~100 "점"으로 표시(퍼센트 아님)**. 금액(`violationValueKrw`·`valueKrw`) → `fmtKrw`
- `rating`은 문자열("A+"~"C"), 빈 보고서는 "-"

## 3. 아키텍처 (FE 미러, main 분기)

라우트 `/unified/reports/esg-screening`: `page.tsx`(목록/생성) + `[id]/page.tsx`(상세/인쇄).

### 공유 인프라 (재사용 + 소폭 확장)

- **`lib/report-archive-api.ts` (수정)**: `ESG_SCREENING` 상수 추가, `ReportType` 유니온에 `typeof ESG_SCREENING` 추가(2줄).
- 재사용: `useReportArchiveApi('ESG_SCREENING')`, `parseReportBody<EsgScreeningReportBody>`, `report-format`(fmtKrw·pctColor), 인쇄 CSS, `ArchiveMeta`.

### 신규 파일

- `types/esg-screening.ts` — `EsgScreeningReportBody`(esg/esgBreakdown/screening/violations) + `ArchiveMeta`/`ArchiveDetail` re-export
- `components/esg-screening/`: `EsgSummary`, `EsgScoreBars`, `EsgBreakdownTable`, `ViolationsTable`
- `app/unified/reports/esg-screening/page.tsx`(목록/생성) + `[id]/page.tsx`(상세/인쇄)

## 4. 상세 페이지 섹션

1. **헤더** — 기간(YYYY년 M월)·asOf·생성일 + `status=WARNING` 노란 배너 + 인쇄 버튼(#38 패턴)
2. **요약 카드** — ESG 등급(`rating`) · 종합점수(`totalScore` 점) · 위반 종목수(`violationCount`, 0이면 녹색 ✓·>0이면 빨강) · 위반 비중(`violationWeight`, `.toFixed(2)%`)
3. **E/S/G 점수** — 환경·사회·지배구조 3개 수평 바(0~100 채움 게이지, 점수 라벨) + 종합점수
4. **ESG 종목별** — 테이블: 종목·유형·비중(`.toFixed(2)%`)·E·S·G·종합·등급. 빈 배열이면 "데이터 없음"
5. **위반 내역** — 테이블: 종목(심볼)·리스트·사유·평가액(`fmtKrw`)·비중. **위반 0이면 녹색 카드 "배제 위반 없음 ✓"**, >0이면 빨강 테두리 테이블

## 5. PDF·에러·네비 (#38 승계)

- 인쇄: `window.print()` + 기존 `.print-invert`/`.no-print`/`@media print` 재사용(신규 CSS 없음)
- 상세: `!api || isLoading` 가드, 파싱은 `queryFn` 내부, 404 폴백 + 목록 링크, `retry:false`
- 목록: 연·월 생성(1월 연도 롤백) + 이력 테이블(키보드 접근), 생성 오류 인라인 배너
- 허브 `app/unified/reports/page.tsx`에 "ESG 스크리닝" 카드 추가(고유 배지·색)

## 6. 테스트·검증

FE 테스트 러너 없음. `npx tsc --noEmit` + `npx next build`(ESG 2라우트 + **기존 화면 회귀 없음**) + 브라우저 프리뷰(로그인 세션 + 실 아카이브)로 섹션 렌더·위반 0/N·인쇄 미리보기. 스모크: 생성 → 목록/상세 → 인쇄.

## 7. 제외 (후속)

사용자 배제리스트 관리(SCR-RPT-11)·위반 이력 타임라인·신규매수 감시로그·국가/ISIN — v1 엔진 미산출. 프리셋은 v1 예시(실제 큐레이션 후속).

## 8. 머지 주의

`report-archive-api.ts` `ReportType` 유니온에 `ESG_SCREENING` 추가 — PR #45(HOLDINGS)·#48(CASHFLOW)도 같은 라인 수정. 먼저 머지되는 쪽 이후 나머지에서 유니온 사소한 충돌(모든 상수 유지로 해결).
