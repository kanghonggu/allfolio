# R-06 환전/계좌간이체 데이터모델 — Phase 1 (BE 기반) Design Spec

- **Date**: 2026-07-31
- **Status**: Approved (design), pending implementation
- **Scope**: 환전(FX)·계좌간이체(TRANSFER)를 1급 현금흐름으로 모델링하는 **BE 기반** — 도메인(FlowType 확장·linkId·팩토리) + 영속화/마이그레이션 + 기록 API + 기존 소비처(수익률·현금흐름 리포트) 정합 교정. **FE·전용 리포트 섹션은 Phase 2(별도 PR)**.
- **Depends on**: 없음 — `main`(804a6c6)에서 분기. 기존 `cash_flow`/`CashFlow`/`FlowType` 확장.
- **커버리지**: 계좌간이체 + 환전 둘 다. **FX는 단일 계좌 내 환전**으로 모델링(계좌간 환전은 후속).

## 1. Background / 문제

현재 `FlowType { DEPOSIT, WITHDRAWAL }`뿐이라 환전·계좌간이체를 기록하면 **외부 입출금으로 오분류**되어:
- 수익률(TWR/MWR): 내부 이동이 외부 기여(deposit/withdrawal)로 잡혀 수익률 왜곡.
- 현금흐름 리포트: 내부 이동이 유입/유출로 표시됨.
- #54 정합검증: 미포착 내부이동이 "difference"로만 드러남.

내부 이동은 **유저 총 NAV를 바꾸지 않음**(이체=−X+X, 환전=−통화A+통화B, 순변화≈스프레드/수수료뿐). 이를 1급으로 모델링해 외부흐름과 분리한다.

## 2. Decisions (확정)

| 항목 | 결정 |
|---|---|
| FlowType 확장 | `TRANSFER_IN·TRANSFER_OUT·FX_IN·FX_OUT` 추가(기존 DEPOSIT/WITHDRAWAL 유지) |
| 레그 페어링 | `CashFlow.linkId: UUID?` — 두 레그가 같은 linkId 공유 |
| signedKrw (외부흐름) | DEPOSIT +, WITHDRAWAL −, **내부유형 0** (TWR/MWR 외부 기여 아님) |
| 이체 | 동일통화·동일금액 2레그: TRANSFER_OUT@from, TRANSFER_IN@to (from≠to) |
| 환전 | 단일 계좌·이기통화 2레그: FX_OUT@fromCcy, FX_IN@toCcy (fromCcy≠toCcy). 레그별 amountKrw로 스프레드 보존 |
| 기존 데이터 | 과거 DEPOSIT/WITHDRAWAL 재분류 안 함(신규 기록만 신규 유형) |
| 리포트(Phase 1) | 외부흐름 뷰에서 내부유형 **제외**(정확성 유지). 전용 표시는 Phase 2 |
| 마이그레이션 | `ALTER TABLE cash_flow ADD COLUMN IF NOT EXISTS link_id UUID;` (추가형·멱등·무해). 배포 전 Neon 수동 실행 |

## 3. Backend Design (module: `unified-asset`)

### 3.1 도메인 — `domain/cashflow/CashFlow.kt`
```kotlin
enum class FlowType {
    DEPOSIT, WITHDRAWAL, TRANSFER_IN, TRANSFER_OUT, FX_IN, FX_OUT;

    fun isInternal(): Boolean = this in setOf(TRANSFER_IN, TRANSFER_OUT, FX_IN, FX_OUT)
    fun isInflow(): Boolean = this in setOf(DEPOSIT, TRANSFER_IN, FX_IN)
    fun isOutflow(): Boolean = this in setOf(WITHDRAWAL, TRANSFER_OUT, FX_OUT)
}
```
- `CashFlow`에 `val linkId: UUID?` 추가(생성자·`create`·`reconstruct` 모두).
- `signedKrw()` 재정의:
  ```kotlin
  fun signedKrw(): BigDecimal = when {
      type == FlowType.DEPOSIT    -> amountKrw
      type == FlowType.WITHDRAWAL -> amountKrw.negate()
      else                        -> BigDecimal.ZERO   // 내부이동: 외부흐름 아님
  }
  ```
- `create(...)`에 `linkId: UUID? = null` 파라미터 추가(기존 호출 호환). 팩토리:
  ```kotlin
  fun transferPair(userId, fromAccountId: UUID, toAccountId: UUID, flowDate, amount, currency, amountKrw, memo): Pair<CashFlow, CashFlow> {
      require(fromAccountId != toAccountId) { "이체 출발·도착 계좌가 같을 수 없습니다" }
      val link = UUID.randomUUID()
      return create(userId, fromAccountId, flowDate, TRANSFER_OUT, amount, currency, amountKrw, memo, link) to
             create(userId, toAccountId,   flowDate, TRANSFER_IN,  amount, currency, amountKrw, memo, link)
  }
  fun fxPair(userId, accountId: UUID?, flowDate, fromAmount, fromCurrency, fromAmountKrw, toAmount, toCurrency, toAmountKrw, memo): Pair<CashFlow, CashFlow> {
      require(fromCurrency.uppercase() != toCurrency.uppercase()) { "환전 통화가 같을 수 없습니다" }
      val link = UUID.randomUUID()
      return create(userId, accountId, flowDate, FX_OUT, fromAmount, fromCurrency, fromAmountKrw, memo, link) to
             create(userId, accountId, flowDate, FX_IN,  toAmount,   toCurrency,   toAmountKrw,   memo, link)
  }
  ```

### 3.2 영속화 — `infrastructure/entity/CashFlowEntity.kt`
- `@Column(name = "link_id") val linkId: UUID?` 추가. `flow_type`은 `@Enumerated(STRING) length 20` → 새 값 무해. `from(domain)`/`toDomain()`에 linkId 반영.
- `infra/postgres/init.sql` `cash_flow`에 `link_id UUID` 컬럼 추가.
- 운영 마이그레이션 파일 `docs/superpowers/migrations/2026-07-31-cashflow-link-id.sql`:
  ```sql
  ALTER TABLE cash_flow ADD COLUMN IF NOT EXISTS link_id UUID;
  ```

### 3.3 기록 유스케이스 — `application/usecase/RecordInternalFlowUseCase.kt` (신규 @Service)
```kotlin
@Service
class RecordInternalFlowUseCase(private val repository: CashFlowRepository, private val fx: FxConverter) {
    @Transactional
    fun recordTransfer(userId, fromAccountId, toAccountId, flowDate, amount, currency, memo): List<CashFlow> {
        require(amount > ZERO) { "이체 금액은 양수여야 합니다" }
        val krw = fx.toKrw(amount, currency)
        val (out, inn) = CashFlow.transferPair(userId, fromAccountId, toAccountId, flowDate, amount, currency, krw, memo)
        return listOf(repository.save(out), repository.save(inn))
    }
    @Transactional
    fun recordFx(userId, accountId, flowDate, fromAmount, fromCurrency, toAmount, toCurrency, memo): List<CashFlow> {
        require(fromAmount > ZERO && toAmount > ZERO) { "환전 금액은 양수여야 합니다" }
        val (out, inn) = CashFlow.fxPair(userId, accountId, flowDate,
            fromAmount, fromCurrency, fx.toKrw(fromAmount, fromCurrency),
            toAmount,   toCurrency,   fx.toKrw(toAmount, toCurrency), memo)
        return listOf(repository.save(out), repository.save(inn))
    }
}
```

### 3.4 API — `api/CashFlowController.kt` 확장
- `POST /api/cashflows/transfer` (X-User-Id): `{fromAccountId, toAccountId, flowDate, amount, currency, memo}` → `List<CashFlowResponse>`(2레그). `CashFlowResponse`에 `linkId` 추가.
- `POST /api/cashflows/fx` (X-User-Id): `{accountId, flowDate, fromAmount, fromCurrency, toAmount, toCurrency, memo}` → 2레그.
- 검증 실패(같은 계좌/같은 통화/음수)는 400.

### 3.5 정합 교정 — 기존 소비처
- **수익률**(`ReturnsReportGenerator`, `GetReturnsAnalysisUseCase`): `signedKrw()`가 내부→0 이므로 코드 변경 없이 외부흐름에서 자동 제외.
- **`CashflowReportGenerator`**: `netCash`·byType·monthly는 정확 타입매칭(DEPOSIT/WITHDRAWAL)이라 내부유형 자동 제외 → 무변경. 단 아래 2곳에 `!it.type.isInternal()` 필터 추가:
  - `flowRows`(상세, 현재 "DEPOSIT 아니면 출금"으로 오라벨) → 내부유형 제외.
  - `SpecialTransactionCalculator.build(flows.filter { !it.type.isInternal() }, ...)`.
  (내부흐름 전용 표시는 Phase 2.)

## 4. Tests
**Backend (unified-asset)**:
- `CashFlowTest`(도메인): FlowType.isInternal/isInflow/isOutflow; signedKrw(DEPOSIT +, WITHDRAWAL −, 내부 4종 0); transferPair(같은 linkId·OUT@from/IN@to·동일통화금액·from==to 예외); fxPair(같은 linkId·OUT fromCcy/IN toCcy·레그별 amountKrw·같은통화 예외).
- `RecordInternalFlowUseCaseTest`: recordTransfer 2레그 저장·linkId 공유·amountKrw=fx환산; recordFx 2레그·레그별 amountKrw; 음수/같은계좌/같은통화 예외.
- `CashflowReportGeneratorTest` 확장: 내부유형(TRANSFER/FX) 포함 시 유입/유출·details·special에서 제외되고 외부(DEPOSIT/WITHDRAWAL) 집계 불변. (netCash 기반 opening/closing 불변.)
- 기존 `CashFlow.create` 호출부(테스트·프로덕션)는 linkId 기본값 null로 호환.

## 5. Rollout / 배포 순서
- **스키마 변경 有(link_id 컬럼)** → 배포 전 `docs/superpowers/migrations/2026-07-31-cashflow-link-id.sql`을 Neon에 **수동 실행**(추가형·멱등·무해). 이후 main 병합 → Render 자동배포.
- 검증: `POST /api/cashflows/transfer`·`/fx` 호출 → 2레그 linkId 페어 저장 확인 → CASHFLOW/RETURNS 리포트에서 외부흐름 왜곡 없음 확인.

## 6. Affected Files
**BE — unified-asset**
- (수정) `domain/cashflow/CashFlow.kt`(FlowType 확장·linkId·signedKrw·팩토리)
- (수정) `infrastructure/entity/CashFlowEntity.kt`(link_id)
- (신규) `application/usecase/RecordInternalFlowUseCase.kt`
- (수정) `api/CashFlowController.kt`(transfer/fx 엔드포인트·linkId 응답)
- (수정) `application/usecase/CashflowReportGenerator.kt`(flowRows·special 내부 제외 필터)
- (수정) `infra/postgres/init.sql`(link_id)
- (신규) `docs/superpowers/migrations/2026-07-31-cashflow-link-id.sql`
- (test) `CashFlowTest`, `RecordInternalFlowUseCaseTest`(신규), `CashflowReportGeneratorTest`(확장)

## 7. Out of Scope (Phase 2+)
- FE 기록 폼(이체/환전 입력 UI).
- 현금흐름 리포트 **환전/이체 전용 섹션·워터폴**·정합 차액 분해.
- 계좌간 환전(FX with from≠to account), 다중 레그(수수료 별도 레그), 미결제/결제일.
- 내부이동을 SpecialTransaction에 정식 표기(현재 Phase 1은 제외).
