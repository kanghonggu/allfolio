# R-06 특이거래 (SCR-RPT-09 ⑥) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 현금흐름 보고서에 특이거래(대규모 이동 + 미분류 흐름)를 추가한다 — 개별 이동이 총자산의 임계비율(기본 10%) 이상이면 대규모 이동, 미매핑 거래유형(MARGIN 등)은 미분류로 분류.

**Architecture:** 신규 순수 `SpecialTransactionCalculator`가 기간 flows·trades를 총자산 기준 임계와 유형 매핑으로 분류한다. `CashflowReportGenerator`는 #54가 주입한 `assetRepository`를 1회 조회로 hoist해 총자산+actualCash를 공유하고 `specialTransactions` body를 추가한다. FE는 특이거래 섹션. **스키마 변경 없음.**

**Tech Stack:** Kotlin, Spring Boot 6, JUnit5, Next.js/React/TS.

**Spec:** `docs/superpowers/specs/2026-07-31-cashflow-special-transactions-design.md`
**Branch:** `feat/cashflow-special-transactions` (main에서 분기, #54 포함)

---

## Reference: 현재 상태 & 관례

- `CashflowReportGenerator`(`application/usecase/CashflowReportGenerator.kt`, #54 버전): 생성자 `(cashFlowRepository, tradeSource, accountRepository, assetRepository, fx)`. `generate`에서 `flows`(기간), `trades`(기간), `acctNames = accountRepository.findByUserId(userId).associate { it.id to it.accountName }`, `buyTypes=setOf("BUY","CREDIT_BUY")`, `sellTypes=setOf("SELL","CREDIT_SELL")`. actualCash 계산부(hoist 대상):
  ```kotlin
  val actualCash = assetRepository.findByUserId(userId)
      .filter { it.type == AssetType.CASH }
      .fold(BigDecimal.ZERO) { a, asset -> a + asset.currentValueInKrw(fx) }
  ```
  body `mapOf("summary", "byType", "monthly", "details", "reconciliation")`.
- `CashFlow`: `accountId: UUID?`, `flowDate: LocalDate`, `type: FlowType`(DEPOSIT/WITHDRAWAL), `amountKrw: BigDecimal`, `memo: String?`. `CashFlow.create(userId, accountId, flowDate, type, amount, currency, amountKrw, memo)`.
- `TradeCashRecord(tradeDate: LocalDate, tradeType: String, stockName: String, accountName: String, totalAmount: BigDecimal, fee: BigDecimal, tax: BigDecimal)`.
- `Asset.currentValueInKrw(fx): BigDecimal`, `Asset.type: AssetType`, `AssetType.CASH`.
- 테스트(`CashflowReportGeneratorTest`, #54): `generator(flows, trades, cashAssets: List<Asset> = emptyList())`, `deposit(day, krw)`, `withdrawal(day, krw)`, `trade(day, type, name, total, fee, tax)`, `cashAsset(krw)`(CASH 자산), `flowOn(date, type, krw)`, `tradeOn(date, type, total)`, `account()`, `standardFlows()`, `standardTrades()`. `FakeAssetRepo`, `fx`(KRW=1:1, else ×1000). `userId`, `acctId`, `period=monthly(2026,6)`.
- FE: `types/cashflow-report.ts`(`CashflowReportBody`에 reconciliation? 등). `[id]/page.tsx`가 `CashflowSummary`/reconciliation/`CashflowByType`/`MonthlyCashflowChart`/`CashflowDetails` 렌더. `@/lib/report-format`의 `fmtKrw`/`pctColor`.

**공통 규칙:** BE 테스트 `cd allfolio-backend && ./gradlew :unified-asset:test --tests '<FQCN>'`. FE `cd frontend/allfolio_app && npx tsc --noEmit`.

---

## File Structure

**Backend — unified-asset**
- (신규) `application/usecase/SpecialTransactionCalculator.kt`
- (수정) `application/usecase/CashflowReportGenerator.kt`
- (test 신규) `application/usecase/SpecialTransactionCalculatorTest.kt`
- (test 수정) `application/usecase/CashflowReportGeneratorTest.kt`

**Frontend**
- (수정) `types/cashflow-report.ts`
- (신규) `components/cashflow-report/SpecialTransactions.tsx`
- (수정) `app/unified/reports/cashflow-report/[id]/page.tsx`

---

## Task 1: SpecialTransactionCalculator (TDD)

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/SpecialTransactionCalculator.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/SpecialTransactionCalculatorTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

Create `SpecialTransactionCalculatorTest.kt`:
```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.TradeCashRecord
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class SpecialTransactionCalculatorTest {

    private val userId = UUID.randomUUID()
    private val acctId = UUID.randomUUID()
    private val acctNames = mapOf(acctId to "한투")

    private fun flow(type: FlowType, krw: String, day: Int = 5) = CashFlow.create(
        userId = userId, accountId = acctId, flowDate = LocalDate.of(2026, 6, day),
        type = type, amount = BigDecimal(krw), currency = "KRW", amountKrw = BigDecimal(krw), memo = "메모",
    )
    private fun trade(type: String, total: String, day: Int = 5) =
        TradeCashRecord(LocalDate.of(2026, 6, day), type, "종목", "한투", BigDecimal(total), BigDecimal.ZERO, BigDecimal.ZERO)

    @Test
    fun `총자산 대비 임계 이상 이동은 대규모 이동으로 잡힌다`() {
        val s = SpecialTransactionCalculator.build(
            listOf(flow(FlowType.DEPOSIT, "150000"), flow(FlowType.DEPOSIT, "50000", day = 6)),
            emptyList(), acctNames, BigDecimal("1000000"), // threshold = 100000
        )
        assertThat(s.largeMovements).hasSize(1)
        assertThat(s.largeMovements[0].amountKrw).isEqualByComparingTo("150000")
        assertThat(s.largeMovements[0].type).isEqualTo("입금")
    }

    @Test
    fun `총자산 0이면 대규모 이동은 없다`() {
        val s = SpecialTransactionCalculator.build(
            listOf(flow(FlowType.DEPOSIT, "150000")), emptyList(), acctNames, BigDecimal.ZERO,
        )
        assertThat(s.largeMovements).isEmpty()
    }

    @Test
    fun `출금과 매수는 음수 부호로 표시된다`() {
        val s = SpecialTransactionCalculator.build(
            listOf(flow(FlowType.WITHDRAWAL, "200000")),
            listOf(trade("BUY", "300000")),
            acctNames, BigDecimal("1000000"),
        )
        val byType = s.largeMovements.associateBy { it.type }
        assertThat(byType["출금"]!!.amountKrw).isEqualByComparingTo("-200000")
        assertThat(byType["매수대금"]!!.amountKrw).isEqualByComparingTo("-300000")
    }

    @Test
    fun `미매핑 거래유형은 미분류로 잡히고 알려진 유형은 아니다`() {
        val s = SpecialTransactionCalculator.build(
            emptyList(),
            listOf(trade("MARGIN", "10000"), trade("BUY", "10000"), trade("DIVIDEND", "10000")),
            acctNames, BigDecimal("1000000"),
        )
        assertThat(s.unclassified).hasSize(1)
        assertThat(s.unclassified[0].tradeType).isEqualTo("MARGIN")
    }

    @Test
    fun `대규모 이동은 금액 절대값 내림차순 정렬`() {
        val s = SpecialTransactionCalculator.build(
            listOf(flow(FlowType.DEPOSIT, "150000"), flow(FlowType.WITHDRAWAL, "300000", day = 7)),
            emptyList(), acctNames, BigDecimal("1000000"),
        )
        assertThat(s.largeMovements.map { it.amountKrw.abs().toInt() }).containsExactly(300000, 150000)
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests 'com.allfolio.unifiedasset.application.usecase.SpecialTransactionCalculatorTest' -q`
Expected: 컴파일 에러(`SpecialTransactionCalculator` 미존재).

- [ ] **Step 3: 계산기 구현**

Create `SpecialTransactionCalculator.kt`:
```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.TradeCashRecord
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class SpecialMovement(val date: LocalDate, val account: String, val type: String, val description: String, val amountKrw: BigDecimal)
data class UnclassifiedFlow(val date: LocalDate, val account: String, val tradeType: String, val amountKrw: BigDecimal)
data class SpecialTransactions(val largeMovements: List<SpecialMovement>, val unclassified: List<UnclassifiedFlow>)

/**
 * 특이거래 산출 (순수). 대규모 이동(|금액| ≥ 총자산×ratio) + 미분류 흐름(미매핑 거래유형).
 * 총자산 0이면 대규모 이동 생략. 미결제·환전/이체는 데이터 부재로 범위 밖.
 */
object SpecialTransactionCalculator {
    private val BUY = setOf("BUY", "CREDIT_BUY")
    private val SELL = setOf("SELL", "CREDIT_SELL")
    private val KNOWN = BUY + SELL + setOf("DIVIDEND")

    fun build(
        flows: List<CashFlow>,
        trades: List<TradeCashRecord>,
        acctNames: Map<UUID, String>,
        totalAssetsKrw: BigDecimal,
        thresholdRatio: BigDecimal = BigDecimal("0.10"),
    ): SpecialTransactions {
        val threshold = totalAssetsKrw.multiply(thresholdRatio)
        val large = mutableListOf<SpecialMovement>()
        if (threshold.signum() > 0) {
            flows.forEach { f ->
                if (f.amountKrw.abs() >= threshold) {
                    val acct = f.accountId?.let { acctNames[it] } ?: "-"
                    val (type, amt) = if (f.type == FlowType.DEPOSIT) "입금" to f.amountKrw else "출금" to f.amountKrw.negate()
                    large += SpecialMovement(f.flowDate, acct, type, f.memo ?: type, amt)
                }
            }
            trades.forEach { t ->
                if (t.totalAmount.abs() >= threshold) {
                    val (type, amt) = when {
                        t.tradeType in BUY -> "매수대금" to t.totalAmount.negate()
                        t.tradeType in SELL -> "매도대금" to t.totalAmount
                        t.tradeType == "DIVIDEND" -> "배당·이자" to t.totalAmount
                        else -> "기타" to t.totalAmount
                    }
                    large += SpecialMovement(t.tradeDate, t.accountName, type, t.stockName, amt)
                }
            }
        }
        val unclassified = trades.filter { it.tradeType !in KNOWN }
            .map { UnclassifiedFlow(it.tradeDate, it.accountName, it.tradeType, it.totalAmount) }
        return SpecialTransactions(
            large.sortedByDescending { it.amountKrw.abs() },
            unclassified.sortedBy { it.date },
        )
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests 'com.allfolio.unifiedasset.application.usecase.SpecialTransactionCalculatorTest' -q`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/SpecialTransactionCalculator.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/SpecialTransactionCalculatorTest.kt
git commit -m "feat(cashflow): add SpecialTransactionCalculator (대규모 이동·미분류)"
```

---

## Task 2: 생성기 통합 (TDD)

**Files:**
- Modify: `application/usecase/CashflowReportGenerator.kt`
- Test: `application/usecase/CashflowReportGeneratorTest.kt`

- [ ] **Step 1: 신규 실패 테스트 추가**

Modify `CashflowReportGeneratorTest.kt` — 클래스 끝 `}` 앞에 추가(기존 헬퍼 `generator`/`flowOn`/`tradeOn`/`cashAsset`/`deposit` 재사용):
```kotlin
    @Test
    fun `특이거래 - 대규모 이동과 미분류 흐름`() {
        // 총자산 1,000,000(cashAsset) → threshold 100,000. 입금 150,000(대규모) + MARGIN 거래(미분류).
        val flows = listOf(deposit(2, "150000"))
        val trades = listOf(tradeOn(LocalDate.of(2026, 6, 5), "MARGIN", "10000"))
        val body = mapper.readTree(
            generator(flows, trades, cashAssets = listOf(cashAsset("1000000"))).generate(userId, period).bodyJson,
        )
        val st = body["specialTransactions"]
        assertEquals(1, st["largeMovements"].size())
        assertEquals(150000.0, st["largeMovements"][0]["amountKrw"].asDouble(), 0.01)
        assertEquals(1, st["unclassified"].size())
        assertEquals("MARGIN", st["unclassified"][0]["tradeType"].asText())
    }

    @Test
    fun `특이거래 - 총자산 없으면 대규모 이동 비어있다`() {
        val body = mapper.readTree(generator(standardFlows(), standardTrades()).generate(userId, period).bodyJson)
        val st = body["specialTransactions"]
        assertEquals(0, st["largeMovements"].size()) // cashAssets 없음 → 총자산 0 → threshold 0
    }
```

- [ ] **Step 2: 실패 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests 'com.allfolio.unifiedasset.application.usecase.CashflowReportGeneratorTest' -q`
Expected: FAIL (`specialTransactions` 키 부재).

- [ ] **Step 3: 생성기 수정**

Modify `CashflowReportGenerator.kt`:
1. actualCash 계산부(3줄)를 assets hoist로 치환:
```kotlin
        val assets = assetRepository.findByUserId(userId)
        val totalAssetsKrw = assets.fold(BigDecimal.ZERO) { a, x -> a + x.currentValueInKrw(fx) }
        val actualCash = assets.filter { it.type == AssetType.CASH }.fold(BigDecimal.ZERO) { a, x -> a + x.currentValueInKrw(fx) }
```
2. `reconciled` 계산 다음(또는 body 직전)에 추가:
```kotlin
        val special = SpecialTransactionCalculator.build(flows, trades, acctNames, totalAssetsKrw)
```
3. body mapOf에 추가(`"reconciliation" to mapOf(...)` 다음):
```kotlin
            "specialTransactions" to mapOf(
                "thresholdRatio" to BigDecimal("0.10"),
                "largeMovements" to special.largeMovements.map {
                    mapOf("date" to it.date.toString(), "account" to it.account, "type" to it.type,
                        "description" to it.description, "amountKrw" to it.amountKrw)
                },
                "unclassified" to special.unclassified.map {
                    mapOf("date" to it.date.toString(), "account" to it.account, "tradeType" to it.tradeType, "amountKrw" to it.amountKrw)
                },
            ),
```
4. 클래스 KDoc "v1 제외: 환전·계좌간이체, 특이거래." → "특이거래(대규모 이동·미분류) 포함. 후속: 미결제·환전·계좌간이체."

- [ ] **Step 4: 통과 확인(신규 + 기존)**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests 'com.allfolio.unifiedasset.application.usecase.CashflowReportGeneratorTest' -q`
Expected: PASS (기존 + 신규 2). 기존 테스트는 cashAssets 없음 → 총자산 0 → largeMovements 빈 배열, standardTrades에 MARGIN 없음 → unclassified 빈 배열. reconciliation 등 불변.

- [ ] **Step 5: Commit**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/CashflowReportGenerator.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/CashflowReportGeneratorTest.kt
git commit -m "feat(cashflow): integrate special transactions (대규모 이동·미분류) into report body"
```

---

## Task 3: 백엔드 전체 회귀

**Files:** (없음 — 검증)

- [ ] **Step 1: 전체 테스트**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test :backend-app:test -q`
Expected: BUILD SUCCESSFUL. 신규 `SpecialTransactionCalculatorTest`(5) + 확장 `CashflowReportGeneratorTest`, 회귀 없음.

- [ ] **Step 2: 실패 시 진단 후 수정 → 재실행. Commit(수정 시)**

```bash
git add -A && git commit -m "test(cashflow): fix regressions"
```

---

## Task 4: FE — 타입 + 특이거래 컴포넌트 + 렌더

**Files:**
- Modify: `frontend/allfolio_app/types/cashflow-report.ts`
- Create: `frontend/allfolio_app/components/cashflow-report/SpecialTransactions.tsx`
- Modify: `frontend/allfolio_app/app/unified/reports/cashflow-report/[id]/page.tsx`

- [ ] **Step 1: 타입 확장**

Modify `types/cashflow-report.ts` — 신규 인터페이스 + body 필드:
```ts
export interface CashflowLargeMovement { date: string; account: string; type: string; description: string; amountKrw: number }
export interface CashflowUnclassified { date: string; account: string; tradeType: string; amountKrw: number }
export interface CashflowSpecialTransactions {
  thresholdRatio: number
  largeMovements: CashflowLargeMovement[]
  unclassified: CashflowUnclassified[]
}
```
`CashflowReportBody`에 추가:
```ts
  specialTransactions?: CashflowSpecialTransactions
```

- [ ] **Step 2: 특이거래 컴포넌트 생성**

Create `frontend/allfolio_app/components/cashflow-report/SpecialTransactions.tsx`:
```tsx
import { fmtKrw, pctColor } from '@/lib/report-format'
import type { CashflowSpecialTransactions } from '@/types/cashflow-report'

export function SpecialTransactions({ data }: { data: CashflowSpecialTransactions }) {
  const empty = data.largeMovements.length === 0 && data.unclassified.length === 0
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">특이거래</h2>
      {empty ? (
        <div className="rounded-xl border border-gray-700 bg-gray-900 p-6 text-center text-sm text-gray-500">특이거래 없음</div>
      ) : (
        <div className="space-y-4">
          {data.largeMovements.length > 0 && (
            <div className="rounded-xl border border-gray-800 bg-gray-900 p-4">
              <h3 className="mb-2 text-sm font-medium text-amber-300">
                대규모 이동 <span className="text-xs text-gray-500">(자산 {Math.round(data.thresholdRatio * 100)}% 이상)</span>
              </h3>
              <table className="w-full text-sm">
                <tbody>
                  {data.largeMovements.map((m, i) => (
                    <tr key={`${m.date}-${m.type}-${i}`} className="border-b border-gray-800 last:border-b-0">
                      <td className="p-2 tabular-nums text-gray-400">{m.date}</td>
                      <td className="p-2 text-gray-300">{m.type}</td>
                      <td className="p-2 text-gray-500">{m.description}</td>
                      <td className={`p-2 text-right tabular-nums ${pctColor(m.amountKrw)}`}>{fmtKrw(m.amountKrw)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {data.unclassified.length > 0 && (
            <div className="rounded-xl border border-gray-800 bg-gray-900 p-4">
              <h3 className="mb-2 text-sm font-medium text-gray-300">미분류 흐름 <span className="text-xs text-gray-500">(유형 매핑 실패)</span></h3>
              <table className="w-full text-sm">
                <tbody>
                  {data.unclassified.map((u, i) => (
                    <tr key={`${u.date}-${u.tradeType}-${i}`} className="border-b border-gray-800 last:border-b-0">
                      <td className="p-2 tabular-nums text-gray-400">{u.date}</td>
                      <td className="p-2"><span className="rounded bg-gray-800 px-2 py-0.5 text-xs text-gray-300">{u.tradeType}</span></td>
                      <td className="p-2 text-gray-500">{u.account}</td>
                      <td className="p-2 text-right tabular-nums text-gray-300">{fmtKrw(u.amountKrw)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </section>
  )
}
```

- [ ] **Step 3: 상세 페이지에 렌더**

Modify `app/unified/reports/cashflow-report/[id]/page.tsx`:
1. import 추가(다른 cashflow 컴포넌트 import 옆):
```tsx
import { SpecialTransactions } from '@/components/cashflow-report/SpecialTransactions'
```
2. `<CashflowDetails rows={body.details} />` 다음 줄에 추가:
```tsx
      {body.specialTransactions && <SpecialTransactions data={body.specialTransactions} />}
```

- [ ] **Step 4: 타입 체크**

Run: `cd frontend/allfolio_app && npx tsc --noEmit`
Expected: 에러 없음.

- [ ] **Step 5: Commit**

```bash
git add frontend/allfolio_app/types/cashflow-report.ts \
        frontend/allfolio_app/components/cashflow-report/SpecialTransactions.tsx \
        "frontend/allfolio_app/app/unified/reports/cashflow-report/[id]/page.tsx"
git commit -m "feat(cashflow): render special-transactions section (대규모 이동·미분류)"
```

---

## Task 5: 통합 검증

**Files:** (없음 — 검증)

- [ ] **Step 1: 백엔드 + FE 최종 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test :backend-app:test -q` → BUILD SUCCESSFUL
Run: `cd frontend/allfolio_app && npx tsc --noEmit` → 에러 없음

- [ ] **Step 2: 커버리지 요약 보고**

계산기(대규모 임계·총자산0·부호·미분류·정렬 5케이스) + 생성기(특이거래 섹션) + 기존 회귀 검증. 스키마 변경 없어 DB 검증 불필요. 결과 요약 보고.

- [ ] **Step 3: (커밋 불필요)**

---

## Rollout (배포 시 — 사용자 실행)
- **스키마 변경 없음** → 운영 마이그레이션 불필요.
- main 병합 → Render 자동배포(BE) → FE 배포.
- 검증: 자산 대비 큰 입출금/매매 또는 MARGIN 거래 있는 계정 → CASHFLOW 리포트 → 특이거래 확인.

---

## Notes / 주의
- 대규모 임계 = 총자산×0.10. 총자산 0이면 largeMovements 생략(0 나눗셈/무의미 회피).
- 미분류 = `tradeType ∉ {BUY, CREDIT_BUY, SELL, CREDIT_SELL, DIVIDEND}`(MARGIN 등). cash_flow는 전부 매핑되어 미분류 없음.
- `assetRepository.findByUserId`는 1회만 조회(hoist)해 총자산+actualCash 공유(#54 reconciliation 동작 불변).
- specialTransactions body는 옵셔널(FE) → 구 아카이브 호환.
- 범위 밖(후속): 미결제(결제일 부재), 환전·계좌간이체(원장/FlowType 부재), 임계치 설정 UI.
```
