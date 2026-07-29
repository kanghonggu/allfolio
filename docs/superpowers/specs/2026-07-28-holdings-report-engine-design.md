# 월말 보유 명세서 생성 엔진 (R-05) — 설계

- 날짜: 2026-07-28
- 태스크: ALLFOLIO 이식 개발 태스크 DB #40 (R2 확장, 규모 M, BE 부분) — **1단계: BE 엔진**. FE 화면(SCR-RPT-08)은 엔진 머지 후 2단계 별도.
- 근거 문서: 리포트명세서 R-05 · 화면정의서 SCR-RPT-08 (참조 원본: abor osPortfolio·대외증권현황·Direct Monthly Holding)
- 브랜치: `feat/holdings-report-engine` (main에서 분기)
- 선행: #32 리포트 공통 기반(main), `ReportType.HOLDINGS` enum(main 등록됨)

## 1. 목적

R-05 월말 보유 명세서의 **생성 엔진(BE)**. #32 프레임의 `ReportBodyGenerator`(type=HOLDINGS)로 등록되어 `POST /api/reports/archive/generate {type: HOLDINGS, year, month}`로 보유 명세 확정본을 생성·보관한다. 종목별 명세(수량·평단·평가액·평가손익)와 계좌/자산군 소계를 본문에 고정한다. 화면+PDF는 #40 FE 단계.

## 2. 데이터 현실에 따른 v1 섹션 판정

명세 R-05(SCR-RPT-08) 대비:

| 명세 요소 | v1 | 근거 |
|---|---|---|
| 요약 카드 (총평가액·종목/계좌수·현금비중·평가손익합) | ✅ | `ua_assets` 집계 + `FxConverter` |
| 보유 명세 그리드 (종목·자산군·계좌·수량·평단·평가액·비중·평가손익·수익률) | ✅ | `ua_assets` + `Asset.unrealizedPnl()`·`returnRate()` |
| 계좌별 소계 | ✅ | provider·계좌별 그룹 |
| 자산군별 소계 | ✅ | `AssetType`별 그룹 (주식/코인/현금/…) |
| 현금 잔고 | ✅ 부분 | `AssetType.CASH` 자산 (원통화·KRW). 별도 예수금 원장은 없음 |
| 당월 실현손익 (FIFO) | ❌ 후속 | unified 경로에 FIFO 실현손익 엔진 미구현(레거시 pnl PositionEngine은 trade_raw 전용) |
| 월간 변동 diff (신규 편입·전량 매도) | ❌ 후속 | 전월 확정 월말 스냅샷 부재 |
| 지역별 그룹핑 (북미·유럽·아태·일본) | ❌ 후속 | 상장국·지역 마스터 부재(#38 국가 근사 한계 동일) |
| Excel 출력·ISIN | ❌ 후속 | v1 범위 밖 |

## 3. 구조 (기존 포트 재사용 — #38/#39보다 단순)

신규 포트/어댑터 불필요. `MonthlyReportGenerator`(#36)와 동일하게 기존 `AssetRepository`·`AccountRepository`·`FxConverter`를 주입받아 순수 집계한다.

### 신규 파일

- **생성기** `unified-asset/application/usecase/HoldingsReportGenerator.kt` — `ReportBodyGenerator`:
  - `override val type = ReportType.HOLDINGS`
  - 주입: `AssetRepository`, `AccountRepository`, `FxConverter`
  - `generate(userId, period)`:
    - `assets = assetRepository.findByUserId(userId)`
    - 각 자산 KRW 환산 = `asset.currentValueInKrw(fx)`, 평가손익 KRW = `asset.unrealizedPnlInKrw(fx)`(NavCalculator 확장), 원통화 평가액 = `asset.currentValue`, 평단 = `asset.purchasePrice`, 수익률 = `asset.returnRate()`
    - `accounts = accountRepository.findByUserId(userId)` (계좌명·provider 라벨)
    - 종목 명세·계좌별/자산군별 소계·현금(type=CASH) 집계, 본문 JSON 조립
    - `asOfDate` = `period.end` (스냅샷 히스토리 부재 → 생성 시점 보유, 라벨 기간의 말일)

### 본문 JSON

```json
{
  "summary": {
    "totalValueKrw": …, "holdingCount": …, "accountCount": …,
    "cashWeight": … | 0,        // 현금(type=CASH) KRW / 총 KRW × 100 (0~100)
    "unrealizedPnlKrw": …       // Σ 평가손익 KRW (부호)
  },
  "holdings": [ {"name":…, "symbol":…, "type":…, "account":…, "provider":…,
                 "quantity":…, "avgPrice":…, "currentValue":…, "valueKrw":…,
                 "weight":…, "unrealizedPnl":…, "returnRate":…} ],   // valueKrw 내림차순
  "byAccount": [ {"account":…, "provider":…, "valueKrw":…, "weight":…, "holdingCount":…} ],
  "byType":    [ {"type":…, "valueKrw":…, "weight":…, "holdingCount":…} ],
  "cash":      [ {"account":…, "currency":…, "valueKrw":…} ],       // type=CASH 자산
  "note": "보유·평가액은 보고서 생성 시점 기준"
}
```

- **금액**: KRW 환산은 `FxConverter`. 원통화 평가액(`currentValue`)·평단(`purchasePrice`)은 자산 통화 기준 병기
- **스케일**: `weight`·`returnRate`·`cashWeight`는 **0~100 스케일**(FE `fmtPctScaled`/`.toFixed`). `returnRate()`는 이미 0~100
- **자산군(type)**: `AssetType` 원값(STOCK/CRYPTO/CASH/REAL_ESTATE/…). 현금 = CASH
- **weight 분모**: 총 KRW 평가액 합 (총자산 0이면 weight 0)

### 빈 데이터 처리

자산 0건은 정상 → 예외 없이 0/빈 배열 유효 보고서. `asOfDate` = `period.end`.

### 검증 게이트·아카이브

#32 `GenerateReportUseCase` 상속 — sync 상태 게이트 warnings, `ReportArchive.create` upsert.

## 4. 테스트·검증

`HoldingsReportGeneratorTest` (fake `AssetRepository`/`AccountRepository`/`FxConverter`):
- ① 총평가액 = Σ KRW, holdingCount·accountCount 정확
- ② 평가손익 합계 = Σ(평가액 − 취득원가) KRW
- ③ 종목 명세: 평단·평가손익·수익률(0~100) 필드 정확, valueKrw 내림차순 정렬
- ④ byAccount/byType 소계: 그룹별 valueKrw·비중, 비중 합 ≈ 100
- ⑤ 현금비중: type=CASH KRW / 총 KRW × 100, cash 배열에 CASH 자산
- ⑥ USD 자산 KRW 환산(FxConverter) 반영
- ⑦ 자산 0건 → 예외 없는 유효 0 보고서(빈 배열, asOf=period.end)

스모크: 로컬 → 자산 시드(주식·현금·USD 혼재) → `generate type=HOLDINGS` → 본문 검산(소계·현금비중·환산), 재생성 upsert.

## 5. 제외 (후속)

당월 실현손익(FIFO), 월간 변동 diff, 지역별 그룹핑, Excel·ISIN, 예수금 원장. FE 화면(SCR-RPT-08)은 #40 2단계.
