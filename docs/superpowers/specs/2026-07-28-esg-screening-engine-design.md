# 투자배제·ESG 스크리닝 생성 엔진 (R-07) — 설계

- 날짜: 2026-07-28
- 태스크: ALLFOLIO 이식 개발 태스크 DB #42 (R2 확장, 규모 M, BE 부분) — **1단계: BE 엔진**. FE 화면(SCR-RPT-10)은 엔진 머지 후 2단계 별도.
- 근거 문서: 리포트명세서 R-07 · 화면정의서 SCR-RPT-10/11 (참조 원본: abor warCrmCmp 전범기업 모니터링)
- 브랜치: `feat/esg-screening-engine` (main에서 분기)
- 선행: #32 리포트 공통 기반(main), `ReportType.ESG_SCREENING` enum(main), 기존 `EsgEngine`(esg 모듈, main)

## 1. 목적

R-07 투자배제·ESG 스크리닝의 **생성 엔진(BE)**. #32 프레임의 `ReportBodyGenerator`(type=ESG_SCREENING)로 등록되어 `POST /api/reports/archive/generate {type: ESG_SCREENING, year, month}`로 확정본을 생성·보관한다. 기존 `EsgEngine` 재사용한 ESG 스코어 + 코드 내장 프리셋 기반 배제 스크리닝을 본문에 고정한다. 화면+PDF는 #42 FE 단계.

## 2. 데이터 현실에 따른 v1 섹션 판정

명세 R-07(SCR-RPT-10/11) 대비:

| 명세 요소 | v1 | 근거 |
|---|---|---|
| ESG 스코어(포트·종목별 E/S/G·등급) | ✅ | 기존 `EsgEngine` 재사용(자산유형 기반) |
| 위반 요약(위반 종목수·평가액·비중) | ✅ 프리셋 | 보유 심볼 ∩ 코드 내장 프리셋 |
| 위반 내역 그리드(종목·리스트·사유·평가액·비중) | ✅ 프리셋 | 위 |
| **사용자 배제리스트·관리(SCR-RPT-11 CRUD·CSV·버저닝)** | ❌ 후속 | 배제리스트 테이블·데이터 부재 — 신규 서브시스템 |
| 위반 이력 타임라인·신규매수 감시로그·편입일 | ❌ 후속 | 이력·최초매수일 추적 데이터 없음 |
| 국가·ISIN·CUSIP 매칭 | ❌ 후속 | 종목 국가·식별자 마스터 부재(심볼 매칭만) |

## 3. 구조 (기존 포트·엔진 재사용)

`MonthlyReportGenerator`(#36)처럼 기존 `AssetRepository`·`FxConverter` + `EsgEngine`(object) 재사용. 배제 프리셋은 코드 정의. 순수 집계.

### 신규 파일

- **프리셋** `unified-asset/application/usecase/EsgExclusionPreset.kt`
  ```kotlin
  data class ExclusionEntry(val listName: String, val reason: String)
  /** v1 내장 배제 프리셋 — 심볼 → 배제 정보. 예시 시드(실제 큐레이션·사용자 리스트는 후속). */
  object EsgExclusionPreset {
      val entries: Map<String, ExclusionEntry> = mapOf(
          "EXCL-COAL-01" to ExclusionEntry("예시 프리셋", "석탄"),
          "EXCL-WEAPON-01" to ExclusionEntry("예시 프리셋", "논란무기"),
      )
      fun lookup(symbol: String?): ExclusionEntry? = symbol?.let { entries[it] }
  }
  ```
- **생성기** `unified-asset/application/usecase/EsgScreeningReportGenerator.kt` — `ReportBodyGenerator`:
  - `override val type = ReportType.ESG_SCREENING`
  - 주입: `AssetRepository`, `FxConverter`
  - `generate(userId, period)`:
    - `assets = assetRepository.findByUserId(userId)`, `totalKrw = Σ currentValueInKrw(fx)`
    - **빈/0자산 가드**: totalKrw ≤ 0 → ESG 0·빈 breakdown·빈 violations 유효 보고서 (`EsgEngine.calculate`는 빈 자산에서 예외 → 호출 전 가드)
    - ESG: `EsgEngine.calculate(assets.map{AssetInput(type.name, currentValueInKrw)})` → `EsgScore`; 종목별 `EsgEngine.scoreOf(type.name)` + weight
    - 배제: 각 자산 `EsgExclusionPreset.lookup(symbol)` → 위반, 평가액·비중 집계
    - 본문 JSON 조립, `asOfDate` = `period.end`

### 본문 JSON

```json
{
  "esg": { "rating":…, "totalScore":…, "environmental":…, "social":…, "governance":… },  // 0~100
  "esgBreakdown": [ {"name":…, "type":…, "weight":…, "e":…, "s":…, "g":…, "total":…, "rating":…} ],  // total 내림차순, weight 0~100
  "screening": { "violationCount":…, "violationValueKrw":…, "violationWeight":… },  // violationWeight 0~100
  "violations": [ {"name":…, "symbol":…, "listName":…, "reason":…, "valueKrw":…, "weight":…} ],  // 보유 ∩ 프리셋
  "note": "ESG 점수는 자산유형 기반 · 배제는 v1 내장 프리셋 기준"
}
```

- **금액**: KRW 환산은 `FxConverter`. **스케일**: `weight`·`violationWeight` 0~100(FE `fmtPctScaled`). ESG 점수(E/S/G/total) 0~100
- **배제 매칭**: `symbol` 정확 매칭(프리셋 심볼). 미보유 시 위반 0(녹색 ✓ 상태)
- ESG breakdown weight: `EsgEngine`/`AssetEsgRow`는 0~1 → 본문은 ×100(0~100)로 통일

### 빈 데이터 처리

자산 0(또는 총평가액 0)은 정상 → 예외 없이 ESG 0·빈 배열 유효 보고서. as-of = `period.end`.

### 검증 게이트·아카이브

#32 `GenerateReportUseCase` 상속.

## 4. 테스트·검증

`EsgScreeningReportGeneratorTest` (fake `AssetRepository`/`FxConverter`):
- ① ESG: `EsgEngine.calculate` 결과 반영(총점·등급·E/S/G), esgBreakdown 종목별·total 내림차순
- ② weight 0~100 스케일(×100), 비중 합 ≈ 100
- ③ 배제 위반: 프리셋 심볼(예: "EXCL-COAL-01") 보유 시 violations에 등장, violationValueKrw·violationWeight·listName·reason 정확
- ④ 위반 0: 프리셋 미보유 종목만 → violations 빈 배열, violationCount 0
- ⑤ USD 자산 KRW 환산(FxConverter) 위반 평가액 반영
- ⑥ 자산 0건 → 예외 없는 유효 0 보고서(ESG 0, 빈 배열)

스모크: 로컬 → 자산 시드(일반 종목 + 프리셋 심볼 1건) → `generate type=ESG_SCREENING` → 본문 검산(ESG 점수·위반 1건·비중), 재생성 upsert.

## 5. 제외 (후속)

사용자 배제리스트·관리 화면(SCR-RPT-11 CRUD·CSV·유효기간 버저닝), 위반 이력 타임라인, 신규매수 감시로그, 편입일 배지, 국가/ISIN/CUSIP 매칭, 실제 배제리스트 큐레이션. FE 화면(SCR-RPT-10)은 #42 2단계.
