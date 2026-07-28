# 월간 운용보고서 화면 + PDF (R-01) — 설계

- 날짜: 2026-07-23
- 태스크: ALLFOLIO 이식 개발 태스크 DB #37 (R1 리포트 MVP, 규모 L, FE)
- 근거 문서: 리포트명세서 R-01 · 엔진 설계 `docs/superpowers/specs/2026-07-20-monthly-report-design.md` (#36)
- 브랜치: `feat/monthly-report-screen`
- 선행: #36 `MonthlyReportGenerator` (main 머지됨, PR #35)

## 1. 목적

#36 엔진이 생성해 아카이브한 R-01 월간 운용보고서 본문(JSON)을 사용자에게 렌더링하고, 브라우저 인쇄로 PDF 출력을 제공하는 **프론트엔드 화면(FE)**. 아카이브 생성·목록·상세 API는 이미 존재(`ReportArchiveController`)하므로 신규 백엔드 작업은 없다.

## 2. 백엔드 계약 (기존, 변경 없음)

`@RequestMapping("/api/reports/archive")` — 인증은 `JwtUserIdFilter`가 `Authorization: Bearer`에서 X-User-Id를 서버측 추출(프론트는 Bearer만 전송, 기존 API와 동일).

| 메서드 | 경로 | 요청 | 응답 |
|---|---|---|---|
| POST | `/generate` | `{type, year, month}` | `ArchiveMetaResponse` |
| GET | `/` | `?type=MONTHLY_REPORT` | `ArchiveMetaResponse[]` |
| GET | `/{id}` | — | `ArchiveDetailResponse {meta, body}` (소유권 검증, 타인 것은 404) |

- `ArchiveMetaResponse`: `id, type, periodStart, periodEnd, asOfDate, status, warnings[], createdAt`
- `ArchiveDetailResponse.body`: 구조화 JSON **문자열** → 프론트에서 `JSON.parse`
- 데이터 부족 시 generate가 400(`InsufficientDataException`)

본문(body) JSON 구조 (#36 엔진 산출):

```json
{
  "performance": {
    "month": {"twr", "mwr", "startNav", "endNav", "netFlow", "investmentPnl",
              "benchmark": {"indexType", "label", "periodReturn", "excessReturn"} | null},
    "standard": {"3M": {"twr"}, "YTD": {...}, "1Y": {...}, "SI": {...}},
    "volatility": number | null
  },
  "topHoldings": [{"name", "symbol", "type", "quantity", "valueKrw", "weight", "returnRate"}],
  "exposure": {"byType": [{"type", "valueKrw", "weight"}], "byCurrency": [{"currency", "valueKrw", "weight"}]},
  "accounts": [{"accountName", "provider", "valueKrw", "weight", "assetCount"}],
  "flowDecomposition": {"startNav", "netFlow", "investmentPnl", "endNav"},
  "note": string
}
```

`standard`는 데이터 부족 기간 키가 생략될 수 있음(월간만 필수). `volatility`·`benchmark`는 null 가능.

## 3. 아키텍처

Next.js App Router, 기존 스택(react-query + axios + recharts, dark 테마). `/unified/reports/monthly-report` 아래 2라우트:

- **`page.tsx` (목록/생성)**: 연·월 선택(select) + "생성" 버튼 + 과거 아카이브 목록 테이블(기간·asOf·status·생성일, 행 클릭 → 상세). 생성 성공 시 반환된 `id`로 상세 라우트 이동. 데이터 부족 400은 인라인 에러 배너.
- **`[id]/page.tsx` (상세/인쇄)**: `detail(id)` 조회 → `body` parse → 전 섹션 렌더 + 인쇄 버튼(`window.print()`).

라우트 충돌 회피: 레거시 `/unified/reports/monthly`("월별 손익 정산", `monthlyPnl()` 기반)는 **그대로 유지**. 신규 화면은 별도 경로 `monthly-report`.

### 신규 파일

- `lib/report-archive-api.ts` — `createReportArchiveApi(accessToken)`: `generate({type, year, month})`, `list(type)`, `detail(id)` (Bearer, base `/api/reports/archive`)
- `lib/useApi.ts` — `useReportArchiveApi()` 훅 추가 (기존 패턴)
- `types/monthly-report.ts` — body JSON + 아카이브 메타 타입
- `app/unified/reports/monthly-report/page.tsx` — 목록/생성
- `app/unified/reports/monthly-report/[id]/page.tsx` — 상세/인쇄
- 상세 섹션 컴포넌트 분리(`components/monthly-report/` — 파일 비대 방지): `PerformanceSummary`, `FlowWaterfall`, `TopHoldingsTable`, `ExposureCharts`, `AccountsTable`

## 4. 상세 페이지 섹션

1. **헤더** — 기간(YYYY년 M월)·asOf·생성일. `status=WARNING`이면 상단 노란 배너에 `warnings[]` 나열. 인쇄 버튼.
2. **성과 KPI** — 월간 TWR·MWR·기말 NAV·순증(netFlow). 수익률 색상 규칙(양수 emerald / 음수 red)은 기존 화면 승계.
3. **성과 상세** — 벤치마크 비교(label·기간수익률·초과수익, benchmark null이면 생략), 표준기간(3M/YTD/1Y/SI) TWR 테이블(누락 키는 "—"), 연환산 변동성(null이면 "—").
4. **입출금 효과 분해** — 워터폴(기초NAV → +순유입 → +투자손익 → 기말NAV). #34 수익률 화면의 워터폴 패턴 재사용.
5. **Top 10 보유** — 테이블: 종목·심볼·유형·수량·평가액(KRW)·비중(%)·수익률(%).
6. **익스포저** — 유형별·통화별. 도넛(recharts Pie) + 비중 레전드. 항목 과다 시 상위 N + 기타 병합.
7. **계좌별** — 테이블: 계좌명·증권사·평가액(KRW)·비중(%)·자산수.
8. **note** — 각주(작은 회색 텍스트, "생성 시점 보유 기준" 고지).

포맷 헬퍼(KRW 통화·억/만 단축·퍼센트)는 기존 화면에서 재사용/공유.

## 5. PDF (브라우저 인쇄)

- 인쇄 버튼 = `window.print()` (상세 라우트에만).
- `@media print` CSS: dark→흰 배경 반전, 네비/버튼/링크 등 화면 전용 요소 숨김(`.no-print`), 섹션별 `break-inside: avoid`, recharts SVG는 그대로 인쇄. 파일명은 브라우저 저장 대화상자에 위임.
- 새 라이브러리 도입 없음.

## 6. 에러·빈 상태

- 생성 400(데이터 부족): 목록 페이지 인라인 에러 배너("해당 기간 데이터가 부족합니다").
- 상세 404(타인/없음): "보고서를 찾을 수 없습니다" 안내 + 목록 링크.
- 빈 목록: "아직 생성된 월간 운용보고서가 없습니다" + 연·월 선택 유도.
- 로딩: 기존 Skeleton 패턴 재사용.

## 7. 테스트·검증

프론트엔드에는 테스트 러너가 없고(기존 화면 #34 등도 유닛 테스트 없이 출하), 이 화면도 동일 패턴을 따른다. 새 테스트 인프라(vitest/RTL)는 도입하지 않는다.

- **타입 안전성**: `tsc`(next build 타입체크)로 body JSON 타입·컴포넌트 props 정합성 확인.
- **브라우저 프리뷰 검증**: `next dev` 기동 → read_page/console/스크린샷으로 렌더 확인 — 8개 섹션 존재, Top10 비중 표기, 워터폴 4단, benchmark/volatility null 폴백("—"), WARNING 배너, 빈/에러 상태.
- **인쇄**: `@media print` 레이아웃은 브라우저 프린트 미리보기로 수동 검증(배경 반전·버튼 숨김·섹션 페이지 나눔).
- **스모크**: 로컬 → 연·월 생성 → 목록/상세 → 인쇄 미리보기.

## 8. 제외 (후속)

- 서버사이드 PDF·아카이브 파일 첨부, 월 자동 생성 스케줄(#36과 함께 P3로 연기), 기여·저해/국가·섹터 익스포저(엔진 v1 미산출), 분기 요약판.
