# R-06 현금 조정표·정합검증 후속 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 현금흐름 보고서(R-06)에 기초/기말 현금 조정표 + 정합검증을 추가한다 — 월초 잔고 스냅샷 없이 전체 이력에서 기초잔고를 재구성하고, 기말(계산)을 실제 현금(현재 CASH 자산)과 대조.

**Architecture:** `CashflowReportGenerator`에 `AssetRepository`+`FxConverter`를 주입하고, 기존 포트를 wide 범위(EPOCH~start−1)로 조회해 기초잔고를 재구성한다. 기말(계산)=기초+netFlow, 실제현금=현재 CASH 자산 합, `reconcilable`=period.end 이후 현금활동 부재(clock 불필요). body에 `reconciliation` 추가. FE는 조정표 섹션 컴포넌트. **스키마 변경 없음.**

**Tech Stack:** Kotlin, Spring Boot 6, JUnit5, Next.js/React/TS.

**Spec:** `docs/superpowers/specs/2026-07-30-cashflow-reconciliation-design.md`
**Branch:** `feat/cashflow-reconciliation` (main에서 분기)

---

## Reference: 현재 상태 & 관례

- `CashflowReportGenerator`(`application/usecase/CashflowReportGenerator.kt`): 생성자 `(cashFlowRepository: CashFlowRepository, tradeSource: CashflowTradeSource, accountRepository: AccountRepository)`. `generate(userId, period)`가 `flows = cashFlowRepository.findByUserIdAndPeriod(userId, period.start, period.end)`, `trades = tradeSource.findTrades(...)`, 로컬 `sumFlow`/`sumTrade`로 `deposit/withdrawal/buy/sell/dividend/feesTax`, `totalInflow/totalOutflow/netFlow`, `byType`(부호 포함 행), `monthly`, `details` 계산 → body `mapOf("summary" to ..., "byType" to byType, "monthly" to monthly, "details" to details)`. `buyTypes=setOf("BUY","CREDIT_BUY")`, `sellTypes=setOf("SELL","CREDIT_SELL")` 필드 존재.
- `CashFlowRepository.findByUserIdAndPeriod(userId, from, to): List<CashFlow>` — 날짜 범위 조회(기존). `CashflowTradeSource.findTrades(userId, from, to): List<TradeCashRecord>`(tradeDate 오름차순). `TradeCashRecord(tradeDate, tradeType, stockName, accountName, totalAmount, fee, tax)`. `FlowType { DEPOSIT, WITHDRAWAL }`, `CashFlow`는 `flowDate: LocalDate`, `type: FlowType`, `amountKrw: BigDecimal`, `accountId: UUID?`.
- `AssetRepository.findByUserId(userId): List<Asset>`. `Asset.currentValueInKrw(fx): BigDecimal`, `Asset.type: AssetType`, `AssetType.CASH`. `FxConverter.toKrw(amount, currency)`.
- 테스트(`CashflowReportGeneratorTest`): fakes가 **날짜 범위 필터** — `FakeCashFlowRepo.findByUserIdAndPeriod = flows.filter { it.flowDate in from..to }`, `FakeTradeSource.findTrades = trades.filter { it.tradeDate in from..to }.sortedBy{...}`. 헬퍼 `deposit(day,krw)`/`withdrawal(day,krw)`(2026-06-day), `trade(day,type,name,total,fee,tax)`(2026-06-day), `account()`, `generator(flows, trades) = CashflowReportGenerator(FakeCashFlowRepo(flows), FakeTradeSource(trades), FakeAccountRepo(listOf(account())))`. `mapper.readTree(...generate(userId, period).bodyJson)`.
- Asset.create 관례(HoldingsTest): `Asset.create(userId, accountId, category=AssetCategory.FINANCIAL, type, sourceType=AssetSourceType.STOCK_API, name, symbol, quantity, purchasePrice, currentValue, currency, valuationMethod=ValuationMethod.MARKET_PRICE)`.
- FE cashflow 상세 `app/unified/reports/cashflow-report/[id]/page.tsx`: `CashflowSummary`/`CashflowByType`/`MonthlyCashflowChart`/`CashflowDetails` 렌더. 타입 `types/cashflow-report.ts`(`CashflowByTypeRow{type,amount,direction}`, `CashflowReportBody`). 포맷 `@/lib/report-format`의 `fmtKrw`/`pctColor`.

**공통 규칙:** BE 테스트 `cd allfolio-backend && ./gradlew :unified-asset:test --tests '<FQCN>'`. FE `cd frontend/allfolio_app && npx tsc --noEmit`.

---

## File Structure

**Backend — unified-asset**
- (수정) `application/usecase/CashflowReportGenerator.kt` — assetRepository/fx 주입 + netCash 헬퍼 + 조정표/정합 계산 + reconciliation body
- (test 수정) `application/usecase/CashflowReportGeneratorTest.kt`

**Frontend**
- (수정) `types/cashflow-report.ts`
- (신규) `components/cashflow-report/CashflowReconciliation.tsx`
- (수정) `app/unified/reports/cashflow-report/[id]/page.tsx`

---

## Task 1: 생성기 조정표·정합검증 (TDD)

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/CashflowReportGenerator.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/CashflowReportGeneratorTest.kt`

- [ ] **Step 1: 테스트 확장(헬퍼 + 신규 실패 테스트)**

Modify `CashflowReportGeneratorTest.kt`:
1. import 추가:
```kotlin
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.asset.AssetCategory
import com.allfolio.unifiedasset.domain.asset.AssetSourceType
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.asset.ValuationMethod
```
2. 클래스 안에 fake asset repo + fx + 헬퍼 추가:
```kotlin
    private class FakeAssetRepo(private val assets: List<Asset>) : AssetRepository {
        override fun save(asset: Asset) = asset
        override fun saveAll(assets: List<Asset>) = assets
        override fun findById(id: UUID): Asset? = null
        override fun findByAccountId(accountId: UUID) = assets
        override fun findByUserId(userId: UUID) = assets
        override fun deleteByAccountId(accountId: UUID) {}
        override fun delete(id: UUID) {}
    }
    private val fx = object : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
            if (currency.uppercase() == "KRW") amount else amount * BigDecimal("1000")
    }
    private fun cashAsset(krw: String) = Asset.create(
        userId = userId, accountId = acctId, category = AssetCategory.FINANCIAL, type = AssetType.CASH,
        sourceType = AssetSourceType.STOCK_API, name = "현금", symbol = null,
        quantity = BigDecimal.ONE, purchasePrice = BigDecimal(krw), currentValue = BigDecimal(krw),
        currency = "KRW", valuationMethod = ValuationMethod.MARKET_PRICE,
    )
    private fun flowOn(date: LocalDate, type: FlowType, krw: String) = CashFlow.create(
        userId = userId, accountId = acctId, flowDate = date, type = type,
        amount = BigDecimal(krw), currency = "KRW", amountKrw = BigDecimal(krw), memo = "x",
    )
    private fun tradeOn(date: LocalDate, type: String, total: String) =
        TradeCashRecord(date, type, "n", "한투", BigDecimal(total), BigDecimal.ZERO, BigDecimal.ZERO)
```
3. 기존 `generator(flows, trades)` 헬퍼를 아래로 교체(cashAssets 기본 빈 → 기존 테스트 보존):
```kotlin
    private fun generator(flows: List<CashFlow>, trades: List<TradeCashRecord>, cashAssets: List<Asset> = emptyList()) =
        CashflowReportGenerator(FakeCashFlowRepo(flows), FakeTradeSource(trades), FakeAccountRepo(listOf(account())), FakeAssetRepo(cashAssets), fx)
```
4. 신규 테스트 추가(클래스 끝 `}` 앞):
```kotlin
    @Test
    fun `기초잔고는 기간 이전 이력에서 재구성되고 기말은 기초 더하기 순흐름이다`() {
        val before = listOf(flowOn(LocalDate.of(2026, 5, 10), FlowType.DEPOSIT, "100000"))
        val beforeT = listOf(tradeOn(LocalDate.of(2026, 5, 15), "BUY", "40000"))
        val periodFlows = before + deposit(2, "50000") // 6월 입금 +50000
        val body = mapper.readTree(generator(periodFlows, beforeT).generate(userId, period).bodyJson)
        val r = body["reconciliation"]
        assertEquals(60000.0, r["openingBalance"].asDouble(), 0.01)      // 100000 - 40000
        assertEquals(110000.0, r["closingCalculated"].asDouble(), 0.01)  // 60000 + 50000(netFlow)
    }

    @Test
    fun `기말 계산이 실제 현금과 일치하고 이후 활동 없으면 정합된다`() {
        // 기초 0(이전 이력 없음) + 6월 입금 200000 → 기말 200000. 실제현금 200000, 이후 활동 없음.
        val body = mapper.readTree(
            generator(listOf(deposit(2, "200000")), emptyList(), cashAssets = listOf(cashAsset("200000")))
                .generate(userId, period).bodyJson,
        )
        val r = body["reconciliation"]
        assertEquals(200000.0, r["closingCalculated"].asDouble(), 0.01)
        assertEquals(200000.0, r["actualCash"].asDouble(), 0.01)
        assertEquals(0.0, r["difference"].asDouble(), 0.01)
        assertTrue(r["reconcilable"].asBoolean())
        assertTrue(r["reconciled"].asBoolean())
    }

    @Test
    fun `실제 현금이 계산 기말과 다르면 정합 실패하고 차액이 표시된다`() {
        val body = mapper.readTree(
            generator(listOf(deposit(2, "200000")), emptyList(), cashAssets = listOf(cashAsset("250000")))
                .generate(userId, period).bodyJson,
        )
        val r = body["reconciliation"]
        assertEquals(50000.0, r["difference"].asDouble(), 0.01) // 실제 250000 - 계산 200000
        assertTrue(r["reconcilable"].asBoolean())
        assertEquals(false, r["reconciled"].asBoolean())
    }

    @Test
    fun `기간 이후 현금활동이 있으면 reconcilable false 이다`() {
        val flows = listOf(deposit(2, "200000"), flowOn(LocalDate.of(2026, 7, 5), FlowType.DEPOSIT, "10000"))
        val body = mapper.readTree(
            generator(flows, emptyList(), cashAssets = listOf(cashAsset("210000"))).generate(userId, period).bodyJson,
        )
        val r = body["reconciliation"]
        assertEquals(false, r["reconcilable"].asBoolean())
        assertEquals(false, r["reconciled"].asBoolean())
    }

    @Test
    fun `조정표 항등식 - 기초 더하기 증감합 등 기말`() {
        val body = mapper.readTree(generator(standardFlows(), standardTrades()).generate(userId, period).bodyJson)
        val r = body["reconciliation"]
        val opening = r["openingBalance"].asDouble()
        val changesSum = r["changes"].sumOf { it["amount"].asDouble() }
        assertEquals(r["closingCalculated"].asDouble(), opening + changesSum, 0.01)
    }
```

- [ ] **Step 2: 실패 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests 'com.allfolio.unifiedasset.application.usecase.CashflowReportGeneratorTest' -q`
Expected: 컴파일 에러(생성자 5-arg 미존재).

- [ ] **Step 3: 생성기 수정**

Modify `CashflowReportGenerator.kt`:
1. import 추가:
```kotlin
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.domain.asset.AssetType
```
2. 생성자에 파라미터 추가(마지막):
```kotlin
    private val assetRepository: AssetRepository,
    private val fx: FxConverter,
```
3. 클래스에 private 헬퍼 추가(기존 `buyTypes`/`sellTypes` 활용, `pct`/`ym` 근처):
```kotlin
    /** flows·trades → 순현금이동(KRW): 입금−출금 + 매도−매수 + 배당 − 수수료·세금. */
    private fun netCash(fs: List<CashFlow>, ts: List<TradeCashRecord>): BigDecimal {
        val dep = fs.filter { it.type == FlowType.DEPOSIT }.fold(BigDecimal.ZERO) { a, f -> a + f.amountKrw }
        val wd = fs.filter { it.type == FlowType.WITHDRAWAL }.fold(BigDecimal.ZERO) { a, f -> a + f.amountKrw }
        val buy = ts.filter { it.tradeType in buyTypes }.fold(BigDecimal.ZERO) { a, t -> a + t.totalAmount }
        val sell = ts.filter { it.tradeType in sellTypes }.fold(BigDecimal.ZERO) { a, t -> a + t.totalAmount }
        val div = ts.filter { it.tradeType == "DIVIDEND" }.fold(BigDecimal.ZERO) { a, t -> a + t.totalAmount }
        val fees = ts.fold(BigDecimal.ZERO) { a, t -> a + t.fee + t.tax }
        return dep - wd + sell - buy + div - fees
    }
```
4. `generate` 안에서 body 생성 직전(기존 `netFlow`/`byType` 계산 이후)에 조정표/정합 계산 추가:
```kotlin
        val epoch = LocalDate.of(1970, 1, 1)
        val far = LocalDate.of(9999, 12, 31)
        val beforeFlows = cashFlowRepository.findByUserIdAndPeriod(userId, epoch, period.start.minusDays(1))
        val beforeTrades = tradeSource.findTrades(userId, epoch, period.start.minusDays(1))
        val openingBalance = netCash(beforeFlows, beforeTrades)
        val closingCalculated = openingBalance + netFlow
        val actualCash = assetRepository.findByUserId(userId)
            .filter { it.type == AssetType.CASH }
            .fold(BigDecimal.ZERO) { a, asset -> a + asset.currentValueInKrw(fx) }
        val afterFlows = cashFlowRepository.findByUserIdAndPeriod(userId, period.end.plusDays(1), far)
        val afterTrades = tradeSource.findTrades(userId, period.end.plusDays(1), far)
        val reconcilable = afterFlows.isEmpty() && afterTrades.isEmpty()
        val difference = actualCash - closingCalculated
        val reconciled = reconcilable && difference.abs() < BigDecimal.ONE
```
5. body mapOf에 reconciliation 추가(`"details" to details,` 다음):
```kotlin
            "reconciliation" to mapOf(
                "openingBalance" to openingBalance,
                "changes" to byType,
                "closingCalculated" to closingCalculated,
                "actualCash" to actualCash,
                "difference" to difference,
                "reconcilable" to reconcilable,
                "reconciled" to reconciled,
            ),
```
6. 클래스 KDoc의 "v1 제외: 기초/기말 조정·정합검증(월초 잔고 부재)" 문구를 갱신:
```kotlin
 * 기초/기말 현금 조정표·정합검증(전체 이력 재구성) 포함. v1 제외: 환전·계좌간이체, 특이거래.
```

- [ ] **Step 4: 통과 확인(신규 + 기존)**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests 'com.allfolio.unifiedasset.application.usecase.CashflowReportGeneratorTest' -q`
Expected: PASS (기존 + 신규 5). 기존 테스트는 cashAssets 빈(actualCash=0)으로 reconciliation 필드만 추가되고 summary/byType/monthly/details 단언 불변.

- [ ] **Step 5: Commit**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/CashflowReportGenerator.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/CashflowReportGeneratorTest.kt
git commit -m "feat(cashflow): add opening/closing reconciliation with balance verification"
```

---

## Task 2: 백엔드 전체 회귀

**Files:** (없음 — 검증)

- [ ] **Step 1: 전체 테스트**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test :backend-app:test -q`
Expected: BUILD SUCCESSFUL. 확장 `CashflowReportGeneratorTest`(기존+5), 기존 회귀 없음.

- [ ] **Step 2: 실패 시 진단 후 수정 → 재실행. Commit(수정 시)**

```bash
git add -A && git commit -m "test(cashflow): fix regressions"
```

---

## Task 3: FE — 타입 + 조정표 컴포넌트 + 렌더

**Files:**
- Modify: `frontend/allfolio_app/types/cashflow-report.ts`
- Create: `frontend/allfolio_app/components/cashflow-report/CashflowReconciliation.tsx`
- Modify: `frontend/allfolio_app/app/unified/reports/cashflow-report/[id]/page.tsx`

- [ ] **Step 1: 타입 확장**

Modify `frontend/allfolio_app/types/cashflow-report.ts`:
1. `CashflowByTypeRow` 아래에 신규 인터페이스 추가:
```ts
export interface CashflowReconciliation {
  openingBalance: number
  changes: CashflowByTypeRow[]
  closingCalculated: number
  actualCash: number
  difference: number
  reconcilable: boolean
  reconciled: boolean
}
```
2. `CashflowReportBody`에 옵셔널 필드 추가(구 아카이브 호환):
```ts
  reconciliation?: CashflowReconciliation
```

- [ ] **Step 2: 조정표 컴포넌트 생성**

Create `frontend/allfolio_app/components/cashflow-report/CashflowReconciliation.tsx`:
```tsx
import { fmtKrw, pctColor } from '@/lib/report-format'
import type { CashflowReconciliation as Recon } from '@/types/cashflow-report'

export function CashflowReconciliation({ data }: { data: Recon }) {
  return (
    <section className="rounded-xl border border-gray-700 bg-gray-900 p-5">
      <h3 className="mb-3 text-sm font-semibold text-gray-300">현금 조정표</h3>
      <table className="w-full text-sm">
        <tbody>
          <tr className="border-b border-gray-800">
            <td className="p-2 text-gray-400">기초 현금</td>
            <td className="p-2 text-right tabular-nums">{fmtKrw(data.openingBalance)}</td>
          </tr>
          {data.changes.map((c) => (
            <tr key={c.type} className="border-b border-gray-800">
              <td className="p-2 pl-4 text-gray-500">{c.type}</td>
              <td className={`p-2 text-right tabular-nums ${pctColor(c.amount)}`}>{fmtKrw(c.amount)}</td>
            </tr>
          ))}
          <tr className="border-b border-gray-700 font-semibold">
            <td className="p-2">기말 현금 (계산)</td>
            <td className="p-2 text-right tabular-nums">{fmtKrw(data.closingCalculated)}</td>
          </tr>
        </tbody>
      </table>

      {/* 정합 검증 */}
      <div className="mt-3">
        {!data.reconcilable ? (
          <div className="rounded bg-gray-800 px-3 py-2 text-xs text-gray-400">
            과거 기간 — 실제 잔고 대조 생략 (기간 이후 현금활동 존재)
          </div>
        ) : data.reconciled ? (
          <div className="rounded bg-emerald-950/40 px-3 py-2 text-xs text-emerald-300">
            ✓ 정합 — 계산 기말 = 실제 현금 ({fmtKrw(data.actualCash)})
          </div>
        ) : (
          <div className="rounded bg-red-950/40 px-3 py-2 text-xs text-red-300">
            ⚠ 불일치 — 실제 현금 {fmtKrw(data.actualCash)} · 차액{' '}
            <span className={pctColor(data.difference)}>{fmtKrw(data.difference)}</span>{' '}
            (미포착 환전·이체·특이거래 추정)
          </div>
        )}
      </div>
    </section>
  )
}
```

- [ ] **Step 3: 상세 페이지에 렌더**

Modify `frontend/allfolio_app/app/unified/reports/cashflow-report/[id]/page.tsx`:
1. import 추가(다른 cashflow 컴포넌트 import 옆):
```tsx
import { CashflowReconciliation } from '@/components/cashflow-report/CashflowReconciliation'
```
2. `<CashflowSummary summary={body.summary} />` 다음 줄에 추가:
```tsx
      {body.reconciliation && <CashflowReconciliation data={body.reconciliation} />}
```

- [ ] **Step 4: 타입 체크**

Run: `cd frontend/allfolio_app && npx tsc --noEmit`
Expected: 에러 없음.

- [ ] **Step 5: Commit**

```bash
git add frontend/allfolio_app/types/cashflow-report.ts \
        frontend/allfolio_app/components/cashflow-report/CashflowReconciliation.tsx \
        "frontend/allfolio_app/app/unified/reports/cashflow-report/[id]/page.tsx"
git commit -m "feat(cashflow): render cash reconciliation section with verification badge"
```

---

## Task 4: 통합 검증

**Files:** (없음 — 검증)

- [ ] **Step 1: 백엔드 + FE 최종 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test :backend-app:test -q` → BUILD SUCCESSFUL
Run: `cd frontend/allfolio_app && npx tsc --noEmit` → 에러 없음

- [ ] **Step 2: 커버리지 요약 보고**

생성기(기초 재구성·기말 계산·정합/불일치·reconcilable·항등식 5케이스) + 기존 회귀가 로직 검증. 스키마 변경 없어 DB 검증 불필요. 결과 요약 보고.

- [ ] **Step 3: (커밋 불필요)**

---

## Rollout (배포 시 — 사용자 실행)
- **스키마 변경 없음** → 운영 마이그레이션 불필요.
- main 병합 → Render 자동배포(BE) → FE 배포.
- 검증: 현금흐름 있는 계정 → CASHFLOW 리포트 → 조정표(기초/증감/기말)·정합검증 badge 확인. 당월(이후 활동 없음)은 정합/차액 표시, 과거월은 대조 생략 안내.

---

## Notes / 주의
- 기초잔고는 재구성값(전체 이력 순현금). 브로커 동기화 등 미기록 현금은 정합 차액으로 드러남 — 기능의 의도.
- `reconcilable` = period.end 이후 현금활동 부재 → clock 불필요·결정적. 과거월(이후 활동 존재)은 실제잔고 대조 생략.
- 조정표 항등식: openingBalance + Σ(changes.amount) == closingCalculated (byType amount는 부호 포함 → 합=netFlow). 구성상 항상 성립.
- KRW 취급(기존 관례). reconciliation body는 옵셔널(FE) — 구 아카이브 리포트엔 필드 부재.
- 범위 밖(후속): 환전·계좌간이체 유형행, 특이거래 섹션, 워터폴, 통화별 컬럼, 거래일/결제일 토글.
