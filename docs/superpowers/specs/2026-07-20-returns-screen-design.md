# 수익률 보고서 화면 (SCR-RPT-04) — 설계

- 날짜: 2026-07-20
- 태스크: ALLFOLIO 이식 개발 태스크 DB #34 (R1 리포트 MVP, 규모 M, FE)
- 근거 문서: 노션 「R-02 수익률 보고서 — 화면 항목 정의서」 SCR-RPT-04
- 브랜치: `feat/returns-screen` (main, #33 머지 후)

## 1. 범위 판단

SCR-RPT-04는 "기간 자유 선택형 분석 화면"인데 #33의 아카이브 API는 월간 확정본 전용이다. 그래서:

- **BE 소폭 추가**: `GET /api/reports/returns?from=&to=` — 임의 기간 on-the-fly 분석 (ReturnsCalculator 재사용, 아카이브 안 함). 기존 on-the-fly 리포트들이 사는 `ReportController`에 추가
- **FE 신규 화면**: `/unified/reports/returns` — 기존 관례(react-query + useApi + recharts + Tailwind) 준수

**v1 포함** (화면 정의서 기준): ①기간 선택바(프리셋 1M/3M/6M/YTD/1Y/SI + 사용자 지정) ②요약 카드(TWR·MWR·기간 손익금액·순입출금) ③TWR vs MWR 패널 + 규칙 기반 해석 문구 ④누적 NAV 곡선 + 입출금 마커 ⑥입출금 효과 워터폴 + 입출금 내역 그리드(기록·삭제 UI 포함 — 원장 데이터 입력 수단이 이 화면뿐이므로)

**v1 제외** (의존 기능 부재): BM 카드·선(#35), ⑤월별 히트맵(월 반복 계산 — R-01 #36과 함께), ⑦계좌별 테이블(계좌별 NAV 없음 — #33 결정 승계), PDF(#37), 영업일 보정·잠정/확정 구분(마감 체계 P3), 계좌간 이체 유형(TRANSFER — 계좌별 수익률 도입 시)

## 2. BE — 인터랙티브 분석 API

`unified-asset`에 `GetReturnsAnalysisUseCase`:

- 입력 userId, from, to (from < to 검증, 최대 SI는 FE가 from=2000-01-01로 보내고 BE가 첫 관측일로 클램프)
- `NavHistorySource` + `CashFlowRepository`로 로드 → `ReturnsCalculator.calculate`
- 응답 DTO (JSON 문자열 아닌 구조체):

```json
{
  "from": "2026-01-01", "to": "2026-07-20", "asOfDate": "2026-07-19",
  "summary": {"twr": 0.081, "mwr": 0.074, "startNav": ..., "endNav": ..., "netFlow": ..., "investmentPnl": ...},
  "navSeries": [{"date": "...", "nav": ...}]
}
```

- NAV 관측 2건 미만 → `InsufficientDataException` → 400 (기존 핸들러가 ReportArchiveController에 있으므로 `ReportController`에도 동일 핸들러 추가)
- 엔드포인트: `ReportController.returns(userId, from, to)` — `GET /api/reports/returns`

## 3. FE — 화면 구성

### 파일

- `types/returns.ts` — ReturnsAnalysis·PeriodSummary·CashFlowItem 타입
- `lib/report-api.ts` — `returns(from, to)` 추가
- `lib/cashflow-api.ts` + `useApi.ts`에 `useCashFlowApi()` — record/list/remove
- `app/unified/reports/returns/page.tsx` — 본 화면
- `app/unified/reports/page.tsx` — 허브 카드 추가 ("수익률 보고서 (TWR·MWR)", 기관급 뱃지)

### 동작

- 프리셋 → from/to 계산: to=오늘, 1M/3M/6M=`subMonths`, YTD=1/1, 1Y=1년 전, SI=2000-01-01(BE 클램프), 사용자 지정=date input 2개(시작≤종료 검증)
- react-query 키 `['report','returns',from,to]` + `['cashflows',from,to]`
- 요약 카드 4: TWR(대표, ±색) · MWR · 기간 손익금액(KRW) · 순입출금(KRW)
- TWR vs MWR 패널: 두 Stat + 해석 문구 규칙 — `mwr<twr`: "상승 이후에 입금한 비중이 컸습니다 (타이밍 비용)", `mwr>twr`: "하락 구간에서 추가 매수한 효과가 있습니다", 차이 0.5%p 미만: "입출금 타이밍의 영향이 크지 않았습니다", 어느 한쪽 null: "계산 불가(데이터 부족)"
- 누적 곡선: navSeries LineChart + 입출금 마커(ReferenceDot — 입금 ▲emerald, 출금 ▼red, Tooltip에 금액)
- 워터폴: recharts BarChart의 투명 베이스 스택 기법 — [기초, +입금, −출금, ±투자손익, 기말] 5구간, 투자손익 = summary.investmentPnl (정합 규칙: 요약 카드와 동일 값 사용)
- 입출금 그리드: 일자·유형·금액(원통화+KRW)·메모·삭제 버튼. 상단 "입출금 기록" 버튼 → 모달(일자·유형·금액·통화·메모) → POST 후 두 쿼리 invalidate
- 에러 처리: 400(스냅샷 부족) → "일별 스냅샷이 2건 이상 쌓여야 수익률을 계산할 수 있습니다" 안내 + 입출금 기록은 그대로 가능

## 4. 테스트·검증

- BE: `GetReturnsAnalysisUseCaseTest` (fake NavSource/CashFlowRepo) — 정상·부족 데이터·클램프
- FE: 타입체크 + `next build` 통과 (기존 관례상 FE 단위테스트 부재)
- 스모크: 로컬 기동 + NAV 시드 → 화면에서 기간 전환·입출금 기록·워터폴 수치 = 카드 수치 일치 확인 (브라우저)
