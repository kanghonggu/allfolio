# 월간 운용보고서 생성 엔진 (R-01) — 설계

- 날짜: 2026-07-20
- 태스크: ALLFOLIO 이식 개발 태스크 DB #36 (R1 리포트 MVP, 규모 L, BE)
- 근거 문서: 리포트명세서 R-01 (참조 원본: Monthly_Performance_and_Exposures.xlsx, NEA-ABOR pne 모듈)
- 브랜치: `feat/monthly-report`

## 1. 목적

제품의 얼굴인 R-01 월간 운용보고서의 **생성 엔진(BE)**. #32 프레임의 `ReportBodyGenerator`(type=MONTHLY_REPORT)로 등록되어 `POST /api/reports/archive/generate`로 월간 확정본을 생성·보관한다. 화면+PDF는 #37.

## 2. 데이터 현실에 따른 v1 섹션 판정

명세 R-01의 6개 섹션 대비:

| 명세 섹션 | v1 | 근거 |
|---|---|---|
| 1. 성과 요약 | ✅ | R-02 엔진(#33) + BM(#35) 재사용. 변동성은 NAV 시계열에서 직접 계산 |
| 2. Top 10 보유 | ✅ | `ua_assets` 현재 보유 — 생성 시점 상태가 아카이브 본문 JSON에 고정됨(§0 as-of는 아카이브가 담당). "생성 시점 보유 기준" 명시 |
| 3. 기여·저해 Top 10 | ❌ 후속 | 종목별 손익 분해에 거래·포지션 시계열 필요 — unified 경로는 잔고 sync뿐 |
| 4. BM 오버/언더웨이트 | ❌ 후속 | BM 구성종목 데이터 없음 (명세도 "신규" 표기) |
| 5. 익스포저 | ✅ 부분 | 자산유형(AssetType)·통화별 비중. 국가·섹터는 종목 마스터 부재로 후속 |
| 6. 계좌별 성과 | ✅ 부분 | 계좌별 평가액·비중·자산수. 계좌별 수익률은 계좌별 NAV 시계열 부재(#33 결정 승계) |

추가: 월간 입출금 분해(flowDecomposition) — cash_flow(#33) 재사용.

## 3. 구조

`MonthlyReportGenerator` (unified-asset usecase, `ReportBodyGenerator` 구현):

- **성과**: `GetReturnsAnalysisUseCase.analyze()` 재사용 — 월간(period.start~end) + 표준기간(3M/YTD/1Y/SI, to=period.end). 월간 결과에 BM 비교 포함. 표준기간은 TWR만 수록(요약 테이블용). NAV 관측 2건 미만이면 프레임 관례대로 `InsufficientDataException` → 400
- **변동성**: 월간 navSeries의 구간 수익률 표준편차 × √252 (연환산). 관측 3건 미만이면 null
- **Top 10 보유**: `AssetRepository.findByUserId` → `currentValueInKrw` 상위 10 — name·symbol·type·quantity·valueKrw·weight(%)·returnRate
- **익스포저**: AssetType별·통화별 valueKrw 합·비중
- **계좌별**: `AccountRepository.findByUserId` × 자산 그룹핑 — accountName·provider·valueKrw·weight·assetCount
- **asOfDate**: 월간 navSeries 마지막 관측일

본문 JSON 구조:

```json
{
  "performance": {
    "month": {"twr":…, "mwr":…, "startNav":…, "endNav":…, "netFlow":…, "investmentPnl":…,
              "benchmark": {"indexType":…, "label":…, "periodReturn":…, "excessReturn":…} | null},
    "standard": {"3M": {"twr":…}, "YTD": {…}, "1Y": {…}, "SI": {…}},
    "volatility": 0.18 | null
  },
  "topHoldings": [{"name":…, "symbol":…, "type":…, "quantity":…, "valueKrw":…, "weight":…, "returnRate":…}],
  "exposure": {"byType": [{"type":…, "valueKrw":…, "weight":…}], "byCurrency": [{"currency":…, "valueKrw":…, "weight":…}]},
  "accounts": [{"accountName":…, "provider":…, "valueKrw":…, "weight":…, "assetCount":…}],
  "flowDecomposition": {"startNav":…, "netFlow":…, "investmentPnl":…, "endNav":…},
  "note": "보유·익스포저·계좌 섹션은 보고서 생성 시점 보유 기준"
}
```

- 표준기간 계산에서 `analyze()`가 관측 부족으로 실패하는 기간(예: 1Y 데이터 없음)은 **해당 키 생략** (월간만 필수)
- weight 계산의 분모는 자산 KRW 합 (NAV와 미세 차이 가능 — 같은 시점 기준이므로 자산 합을 사용)

## 4. 테스트·검증

- `MonthlyReportGeneratorTest` (fake 포트): ①본문 5개 섹션 존재+월간 twr ②Top10 정렬·비중 합≈100 ③표준기간 부족 시 키 생략 ④NAV 부족 시 예외 ⑤BM 설정 시 benchmark 포함
- 스모크: 로컬 기동 → NAV·자산·계좌 시드 → `generate type=MONTHLY_REPORT` → 아카이브 본문 검산, 재생성 upsert 확인

## 5. 제외 (후속)

기여·저해(거래 시계열 필요), BM 구성종목 오버/언더웨이트, 국가·섹터 익스포저(종목 마스터), 분기 요약판, 월 자동 생성 스케줄(익월 첫 영업일 — P3 마감 체계와 함께), 화면+PDF(#37)
