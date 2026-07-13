# KIS 잔고조회 연동 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `unified-asset`에 `KisSyncAdapter`를 추가해 KIS 실 API로 보유종목을 자산으로 수집하고, 프론트 계좌추가 폼에 KIS API 연동 경로를 붙인다.

**Architecture:** `SyncAdapter`(provider별 포트) 구현체 `KisSyncAdapter`를 신규 추가한다. HTTP는 `KisSyncClient` 인터페이스로 분리하고(실 impl은 WebClient), 어댑터는 순수 매핑/집계 로직만 담아 fake client로 단위 테스트한다. 프론트는 기존 거래소 폼 패턴을 재사용해 KIS 입력 필드를 추가한다.

**Tech Stack:** Kotlin, Spring Boot(WebFlux WebClient), JUnit5 + hand-written fakes, Next.js/TypeScript/Tailwind.

**참고 스펙:** [docs/superpowers/specs/2026-07-13-kis-balance-sync-design.md](../specs/2026-07-13-kis-balance-sync-design.md)

---

## File Structure

**백엔드 (신규, 모두 `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/adapter/`):**
- `KisSyncDtos.kt` — KIS 토큰/잔고 응답 DTO
- `KisSyncProperties.kt` — 실전/모의 base-url·tr_id 설정
- `KisSyncClient.kt` — HTTP 포트 인터페이스
- `KisSyncClientImpl.kt` — WebClient 실 구현 + 토큰 캐시
- `KisSyncAdapter.kt` — `SyncAdapter` 구현 (집계/폴백/매핑) ← 단위 테스트 대상

**백엔드 (테스트):**
- `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/infrastructure/adapter/KisSyncAdapterTest.kt`

**프론트 (수정):**
- `frontend/allfolio_app/app/unified/accounts/new/page.tsx`

**Gradle:** `./gradlew` 는 `allfolio-backend/` 에 있음. 테스트 실행은 그 디렉터리에서.

---

### Task 1: KIS DTO + 설정

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/adapter/KisSyncDtos.kt`
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/adapter/KisSyncProperties.kt`

- [ ] **Step 1: DTO 파일 생성**

`KisSyncDtos.kt`:

```kotlin
package com.allfolio.unifiedasset.infrastructure.adapter

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class KisTokenResponse(
    @JsonProperty("access_token") val accessToken: String = "",
    @JsonProperty("expires_in")   val expiresIn: Long = 0,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KisBalanceResponse(
    @JsonProperty("rt_cd")   val rtCd: String = "",
    @JsonProperty("msg1")    val msg1: String = "",
    @JsonProperty("output1") val output1: List<KisBalanceItem> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KisBalanceItem(
    @JsonProperty("pdno")          val pdno: String = "",          // 종목코드
    @JsonProperty("prdt_name")     val prdtName: String = "",      // 종목명
    @JsonProperty("hldg_qty")      val hldgQty: String = "0",      // 보유수량
    @JsonProperty("pchs_avg_pric") val pchsAvgPric: String = "0",  // 매입평균가
    @JsonProperty("pchs_amt")      val pchsAmt: String = "0",      // 매입금액
    @JsonProperty("prpr")          val prpr: String = "0",         // 현재가
    @JsonProperty("evlu_amt")      val evluAmt: String = "0",      // 평가금액
)
```

- [ ] **Step 2: 설정 파일 생성**

`KisSyncProperties.kt`:

```kotlin
package com.allfolio.unifiedasset.infrastructure.adapter

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * KIS 잔고조회 연동 설정 (실전 기본값).
 * env: KIS_SYNC_MOCK=true 로 모의투자 전환.
 */
@Component
@ConfigurationProperties(prefix = "kis-sync")
class KisSyncProperties {
    var mock: Boolean = false
    var realBaseUrl: String = "https://openapi.koreainvestment.com:9443"
    var mockBaseUrl: String = "https://openapivts.koreainvestment.com:29443"

    fun baseUrl(): String = if (mock) mockBaseUrl else realBaseUrl
    fun trIdBalance(): String = if (mock) "VTTC8434R" else "TTTC8434R"
}
```

- [ ] **Step 3: 컴파일 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/adapter/KisSyncDtos.kt \
        allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/adapter/KisSyncProperties.kt
git commit -m "feat(kis): KIS 잔고조회 DTO 및 실전/모의 설정 추가"
```

---

### Task 2: KIS HTTP 클라이언트 (포트 + WebClient 구현)

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/adapter/KisSyncClient.kt`
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/adapter/KisSyncClientImpl.kt`

> 이 태스크는 외부 I/O 경계라 단위 테스트 대상이 아니다(실측으로 검증, Task 5). 어댑터 로직은 Task 3에서 fake client로 테스트한다.

- [ ] **Step 1: 포트 인터페이스 생성**

`KisSyncClient.kt`:

```kotlin
package com.allfolio.unifiedasset.infrastructure.adapter

interface KisSyncClient {
    /** appkey/appsecret(client_credentials)로 access_token 발급. 실패 시 예외. */
    fun issueToken(appKey: String, appSecret: String): String

    /** 주식잔고조회(TTTC8434R). 첫 페이지만 조회. */
    fun fetchBalance(appKey: String, appSecret: String, cano: String, acntPrdtCd: String): KisBalanceResponse
}
```

- [ ] **Step 2: WebClient 구현 생성**

`KisSyncClientImpl.kt`:

```kotlin
package com.allfolio.unifiedasset.infrastructure.adapter

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

@Component
class KisSyncClientImpl(
    private val props: KisSyncProperties,
) : KisSyncClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val webClient = WebClient.builder().baseUrl(props.baseUrl()).build()

    // appkey -> (token, 만료 epoch ms). KIS 토큰 발급은 앱키당 1분 1회 제한이라 캐시 필수.
    private val tokenCache = ConcurrentHashMap<String, Pair<String, Long>>()

    override fun issueToken(appKey: String, appSecret: String): String {
        tokenCache[appKey]?.let { (token, expiry) ->
            if (System.currentTimeMillis() < expiry) return token
        }
        val resp = webClient.post()
            .uri("/oauth2/tokenP")
            .bodyValue(mapOf(
                "grant_type" to "client_credentials",
                "appkey"     to appKey,
                "appsecret"  to appSecret,
            ))
            .retrieve()
            .bodyToMono(KisTokenResponse::class.java)
            .block(Duration.ofSeconds(10))
            ?: throw RuntimeException("KIS 토큰 발급 실패")
        val ttlMs = (resp.expiresIn - 120).coerceAtLeast(0) * 1000
        tokenCache[appKey] = resp.accessToken to (System.currentTimeMillis() + ttlMs)
        return resp.accessToken
    }

    override fun fetchBalance(
        appKey: String, appSecret: String, cano: String, acntPrdtCd: String,
    ): KisBalanceResponse {
        val token = issueToken(appKey, appSecret)
        return webClient.get()
            .uri { ub ->
                ub.path("/uapi/domestic-stock/v1/trading/inquire-balance")
                    .queryParam("CANO", cano)
                    .queryParam("ACNT_PRDT_CD", acntPrdtCd)
                    .queryParam("AFHR_FLPR_YN", "N")
                    .queryParam("OFL_YN", "")
                    .queryParam("INQR_DVSN", "02")             // 종목별
                    .queryParam("UNPR_DVSN", "01")
                    .queryParam("FUND_STTL_ICLD_YN", "N")
                    .queryParam("FNCG_AMT_AUTO_RDPT_YN", "N")
                    .queryParam("PRCS_DVSN", "00")
                    .queryParam("CTX_AREA_FK100", "")
                    .queryParam("CTX_AREA_NK100", "")
                    .build()
            }
            .header("authorization", "Bearer $token")
            .header("appkey", appKey)
            .header("appsecret", appSecret)
            .header("tr_id", props.trIdBalance())
            .header("custtype", "P")
            .retrieve()
            .bodyToMono(KisBalanceResponse::class.java)
            .block(Duration.ofSeconds(15))
            ?: KisBalanceResponse()
    }
}
```

- [ ] **Step 3: 컴파일 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/adapter/KisSyncClient.kt \
        allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/adapter/KisSyncClientImpl.kt
git commit -m "feat(kis): KIS 잔고조회 WebClient 클라이언트 + 토큰 캐시"
```

---

### Task 3: KisSyncAdapter (집계·폴백·매핑) — TDD

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/adapter/KisSyncAdapter.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/infrastructure/adapter/KisSyncAdapterTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

`KisSyncAdapterTest.kt`:

```kotlin
package com.allfolio.unifiedasset.infrastructure.adapter

import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountType
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.asset.ValuationMethod
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class KisSyncAdapterTest {

    private fun kisAccount() = Account.create(
        userId      = java.util.UUID.randomUUID(),
        provider    = AccountProvider.KIS,
        accountType = AccountType.STOCK,
        accountName = "KIS 테스트",
        externalId  = "50123456_01",
        currency    = "KRW",
        apiKey      = "app-key",
        apiSecret   = "app-secret",
    )

    private class FakeKisSyncClient(
        var balance: KisBalanceResponse = KisBalanceResponse(rtCd = "0"),
        var tokenError: Exception? = null,
    ) : KisSyncClient {
        var lastCano: String? = null
        var lastPrdt: String? = null
        override fun issueToken(appKey: String, appSecret: String): String {
            tokenError?.let { throw it }
            return "token"
        }
        override fun fetchBalance(appKey: String, appSecret: String, cano: String, acntPrdtCd: String): KisBalanceResponse {
            lastCano = cano; lastPrdt = acntPrdtCd
            return balance
        }
    }

    @Test
    fun `단일 보유종목을 평가금액 기준 자산으로 매핑한다`() {
        val client = FakeKisSyncClient(KisBalanceResponse(
            rtCd = "0",
            output1 = listOf(KisBalanceItem(
                pdno = "005930", prdtName = "삼성전자", hldgQty = "10",
                pchsAvgPric = "70000", pchsAmt = "700000", prpr = "80000", evluAmt = "800000",
            )),
        ))
        val assets = KisSyncAdapter(client).sync(kisAccount())

        assertEquals(1, assets.size)
        val a = assets.first()
        assertEquals("삼성전자", a.name)
        assertEquals("005930", a.symbol)
        assertEquals(AssetType.STOCK, a.type)
        assertEquals(0, BigDecimal("10").compareTo(a.quantity))
        assertEquals(0, BigDecimal("70000").compareTo(a.purchasePrice))
        assertEquals(0, BigDecimal("800000.00").compareTo(a.currentValue))
        assertEquals(ValuationMethod.MARKET_PRICE, a.valuationMethod)
        // externalId 파싱 확인
        assertEquals("50123456", client.lastCano)
        assertEquals("01", client.lastPrdt)
    }

    @Test
    fun `같은 종목의 매매구분 다중 행을 합산해 한 자산으로 만든다`() {
        val client = FakeKisSyncClient(KisBalanceResponse(
            rtCd = "0",
            output1 = listOf(
                KisBalanceItem(pdno = "009150", prdtName = "삼성전기", hldgQty = "1657",
                    pchsAvgPric = "135440", pchsAmt = "224424497", prpr = "150000", evluAmt = "248550000"),
                KisBalanceItem(pdno = "009150", prdtName = "삼성전기", hldgQty = "3",
                    pchsAvgPric = "123000", pchsAmt = "369000", prpr = "150000", evluAmt = "450000"),
            ),
        ))
        val assets = KisSyncAdapter(client).sync(kisAccount())

        assertEquals(1, assets.size)
        val a = assets.first()
        assertEquals(0, BigDecimal("1660").compareTo(a.quantity))          // 1657 + 3
        assertEquals(0, BigDecimal("249000000.00").compareTo(a.currentValue)) // 248550000 + 450000
    }

    @Test
    fun `현재가와 평가금액이 0이면 매입평균가로 폴백한다`() {
        val client = FakeKisSyncClient(KisBalanceResponse(
            rtCd = "0",
            output1 = listOf(KisBalanceItem(
                pdno = "005930", prdtName = "삼성전자", hldgQty = "10",
                pchsAvgPric = "70000", pchsAmt = "700000", prpr = "0", evluAmt = "0",
            )),
        ))
        val a = KisSyncAdapter(client).sync(kisAccount()).first()
        assertEquals(0, BigDecimal("700000.00").compareTo(a.currentValue)) // 70000 * 10
        assertEquals(ValuationMethod.USER_INPUT, a.valuationMethod)
    }

    @Test
    fun `rt_cd가 0이 아니면 예외를 던진다`() {
        val client = FakeKisSyncClient(KisBalanceResponse(rtCd = "1", msg1 = "조회 실패"))
        val ex = assertThrows(RuntimeException::class.java) { KisSyncAdapter(client).sync(kisAccount()) }
        assertTrue(ex.message!!.contains("조회 실패"))
    }

    @Test
    fun `계좌번호가 없으면 빈 목록을 반환한다`() {
        val account = Account.create(
            userId = java.util.UUID.randomUUID(), provider = AccountProvider.KIS,
            accountType = AccountType.STOCK, accountName = "no-acct",
            externalId = null, currency = "KRW", apiKey = "k", apiSecret = "s",
        )
        assertTrue(KisSyncAdapter(FakeKisSyncClient()).sync(account).isEmpty())
    }

    @Test
    fun `testConnection은 토큰 발급 성공 시 success를 반환한다`() {
        val ok = KisSyncAdapter(FakeKisSyncClient()).testConnection(kisAccount())
        assertTrue(ok.success)

        val fail = KisSyncAdapter(FakeKisSyncClient(tokenError = RuntimeException("잘못된 앱키")))
            .testConnection(kisAccount())
        assertTrue(!fail.success)
        assertTrue(fail.message.contains("잘못된 앱키"))
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests "com.allfolio.unifiedasset.infrastructure.adapter.KisSyncAdapterTest"`
Expected: FAIL (KisSyncAdapter 미존재 → 컴파일 에러)

- [ ] **Step 3: KisSyncAdapter 구현**

`KisSyncAdapter.kt`:

```kotlin
package com.allfolio.unifiedasset.infrastructure.adapter

import com.allfolio.unifiedasset.application.port.SyncAdapter
import com.allfolio.unifiedasset.application.usecase.ConnectionTestResult
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.asset.AssetCategory
import com.allfolio.unifiedasset.domain.asset.AssetSourceType
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.asset.ValuationMethod
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 한국투자증권(KIS) 잔고조회 동기화 어댑터.
 *
 * 계좌의 apiKey(앱키)/apiSecret(앱시크릿)로 토큰 발급 → 주식잔고조회(TTTC8434R) →
 * 보유종목을 Asset으로 변환한다.
 * - externalId = "{CANO}_{상품코드}" (예: "50123456_01")
 * - 같은 pdno가 매매구분별 다중 행으로 오므로 pdno 기준 합산
 * - prpr/evlu_amt가 0(장 시간외 등)이면 매입평균가로 폴백
 */
@Component
class KisSyncAdapter(
    private val client: KisSyncClient,
) : SyncAdapter {
    private val log = LoggerFactory.getLogger(javaClass)

    override val supportedProvider = AccountProvider.KIS

    override fun sync(account: Account): List<Asset> {
        val appKey     = account.apiKey
        val appSecret  = account.apiSecret
        val externalId = account.externalId
        if (appKey.isNullOrBlank() || appSecret.isNullOrBlank() || externalId.isNullOrBlank()) {
            log.warn("KIS 자격증명/계좌번호 누락 account={}", account.id)
            return emptyList()
        }

        val (cano, prdt) = parseAccountNo(externalId)
        val resp = client.fetchBalance(appKey, appSecret, cano, prdt)
        if (resp.rtCd != "0") {
            throw RuntimeException("KIS 잔고조회 실패: ${resp.msg1}")
        }

        return resp.output1
            .filter { it.pdno.isNotBlank() }
            .groupBy { it.pdno }
            .mapNotNull { (pdno, rows) -> toAsset(account, pdno, rows) }
    }

    override fun testConnection(account: Account): ConnectionTestResult {
        val appKey    = account.apiKey
        val appSecret = account.apiSecret
        if (appKey.isNullOrBlank() || appSecret.isNullOrBlank())
            return ConnectionTestResult(false, "앱키와 앱시크릿을 입력하세요.")
        return try {
            client.issueToken(appKey, appSecret)
            ConnectionTestResult(true, "연결 성공! 앱키 인증 완료")
        } catch (e: Exception) {
            ConnectionTestResult(false, e.message ?: "연결 실패")
        }
    }

    private fun toAsset(account: Account, pdno: String, rows: List<KisBalanceItem>): Asset? {
        val qty = rows.sumOf { it.hldgQty.toBd() }
        if (qty <= BigDecimal.ZERO) return null

        val pchsAmt = rows.sumOf { it.pchsAmt.toBd() }
        val evluAmt = rows.sumOf { it.evluAmt.toBd() }
        val prpr    = rows.map { it.prpr.toBd() }.firstOrNull { it > BigDecimal.ZERO } ?: BigDecimal.ZERO

        val avgCost = if (pchsAmt > BigDecimal.ZERO)
            pchsAmt.divide(qty, 4, RoundingMode.HALF_UP)
        else rows.map { it.pchsAvgPric.toBd() }.firstOrNull { it > BigDecimal.ZERO } ?: BigDecimal.ZERO

        val hasMarketPrice = evluAmt > BigDecimal.ZERO || prpr > BigDecimal.ZERO
        val currentValue = when {
            evluAmt > BigDecimal.ZERO -> evluAmt
            prpr > BigDecimal.ZERO    -> prpr.multiply(qty)
            else                      -> avgCost.multiply(qty)
        }.setScale(2, RoundingMode.HALF_UP)

        return Asset.create(
            userId          = account.userId,
            accountId       = account.id,
            category        = AssetCategory.FINANCIAL,
            type            = AssetType.STOCK,
            sourceType      = AssetSourceType.STOCK_API,
            name            = rows.first().prdtName.ifBlank { pdno },
            symbol          = pdno,
            quantity        = qty,
            purchasePrice   = avgCost,
            currentValue    = currentValue,
            currency        = account.currency,
            valuationMethod = if (hasMarketPrice) ValuationMethod.MARKET_PRICE else ValuationMethod.USER_INPUT,
        )
    }

    /** "50123456_01" → ("50123456","01"). 방어적으로 숫자만 남긴 8-2 파싱도 지원. */
    private fun parseAccountNo(externalId: String): Pair<String, String> {
        val parts = externalId.split("_")
        if (parts.size >= 2 && parts[0].isNotBlank()) return parts[0] to parts[1]
        val digits = externalId.filter { it.isDigit() }
        return if (digits.length >= 10) digits.take(8) to digits.substring(8, 10)
        else digits to "01"
    }

    private fun String.toBd(): BigDecimal = trim().toBigDecimalOrNull() ?: BigDecimal.ZERO
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests "com.allfolio.unifiedasset.infrastructure.adapter.KisSyncAdapterTest"`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/adapter/KisSyncAdapter.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/infrastructure/adapter/KisSyncAdapterTest.kt
git commit -m "feat(kis): KisSyncAdapter — pdno 집계·현재가 폴백·잔고→자산 매핑 (TDD)"
```

---

### Task 4: 프론트 — KIS API 연동 폼

**Files:**
- Modify: `frontend/allfolio_app/app/unified/accounts/new/page.tsx`

> `types/unified.ts`의 `AccountProvider`에는 `'KIS'`가 이미 포함돼 있어 타입 변경은 불필요.

- [ ] **Step 1: 카테고리 타입/목록에 KIS 추가**

`new/page.tsx`의 `Category` 타입과 `CATEGORIES` 배열 수정:

```tsx
type Category = 'EXCHANGE' | 'STOCK' | 'WALLET' | 'MANUAL' | 'KIS_API'
const CATEGORIES: { key: Category; label: string; description: string }[] = [
  { key: 'EXCHANGE', label: '암호화폐 거래소', description: 'API로 잔고를 자동 조회합니다' },
  { key: 'KIS_API',  label: '증권사 API 연동', description: '한국투자증권 API로 잔고를 자동 조회합니다' },
  { key: 'STOCK',    label: '증권 계좌',       description: '거래내역을 로그로 관리합니다' },
  { key: 'WALLET',   label: '블록체인 지갑',   description: '지갑 주소로 잔고를 조회합니다' },
  { key: 'MANUAL',   label: '수동 입력',       description: '부동산, 금 등 비정형 자산' },
]
```

- [ ] **Step 2: form 상태에 계좌번호 필드 추가**

`useState` 초기값 객체에 `accountNo: ''` 추가:

```tsx
  const [form, setForm] = useState({
    accountName:   '',
    currency:      'USD',
    apiKey:        '',
    apiSecret:     '',
    passphrase:    '',
    walletAddress: '',
    chain:         'ETH',
    brokerage:     '',
    subtype:       '일반',
    accountNo:     '',   // KIS 계좌번호 (예: 50123456-01)
  })
```

- [ ] **Step 3: selectCategory에서 KIS_API → provider=KIS 매핑**

`selectCategory` 함수 수정:

```tsx
  const selectCategory = (cat: Category) => {
    setCategory(cat)
    if (cat === 'EXCHANGE') setProvider(null)
    else if (cat === 'KIS_API') setProvider('KIS')
    else setProvider(cat as unknown as AccountProvider)
    setTestStatus('idle')
    setTestResult(null)
  }
```

- [ ] **Step 4: handleSubmit에 KIS 분기 추가**

`handleSubmit` 내 `if (EXCHANGE_PROVIDERS.has(provider)) { ... }` 체인에 `provider === 'KIS'` 분기 추가 (STOCK 분기 앞에):

```tsx
    } else if (provider === 'KIS') {
      const digits = form.accountNo.replace(/[^0-9]/g, '')
      const cano = digits.slice(0, 8)
      const prdt = digits.slice(8, 10) || '01'
      accountName = form.accountName || `한국투자증권 ${cano}`
      payload.accountName = accountName
      payload.accountType = 'STOCK'
      payload.currency    = 'KRW'
      payload.apiKey      = form.apiKey
      payload.apiSecret   = form.apiSecret
      payload.externalId  = `${cano}_${prdt}`
    } else if (provider === 'STOCK') {
```

- [ ] **Step 5: KIS 입력 폼 블록 추가**

`{provider === 'STOCK' && ( ... )}` 블록 **앞에** KIS 폼 블록 삽입:

```tsx
            {/* ── 증권사 API 연동 (KIS) ── */}
            {provider === 'KIS' && (
              <>
                <Field label="별칭 (선택)">
                  <input type="text" placeholder="예: 한투 주식계좌"
                    value={form.accountName} onChange={e => set('accountName', e.target.value)}
                    className={inputCls}
                  />
                </Field>
                <Field label="앱키 (App Key) *" required>
                  <input required type="password" placeholder="한국투자증권 App Key"
                    value={form.apiKey} onChange={e => set('apiKey', e.target.value)}
                    className={inputCls}
                  />
                </Field>
                <Field label="앱시크릿 (App Secret) *" required>
                  <input required type="password" placeholder="한국투자증권 App Secret"
                    value={form.apiSecret} onChange={e => set('apiSecret', e.target.value)}
                    className={inputCls}
                  />
                </Field>
                <Field label="계좌번호 *" required>
                  <input required type="text" placeholder="예: 50123456-01"
                    value={form.accountNo} onChange={e => set('accountNo', e.target.value)}
                    className={inputCls}
                  />
                  <p className="mt-1 text-xs text-gray-500">계좌번호 체계 8-2 (앞 8자리 + 상품코드 2자리)</p>
                </Field>
                <div className="rounded-lg border border-gray-700 bg-gray-800/50 px-3 py-2 text-xs text-gray-400">
                  KIS Developers에서 발급한 App Key/Secret이 필요합니다. 조회 전용 권한을 권장합니다.
                </div>

                <div className="space-y-2">
                  <button
                    type="button"
                    onClick={handleTestConnection}
                    disabled={testStatus === 'loading' || !form.apiKey || !form.apiSecret}
                    className="w-full rounded-lg border border-blue-700 py-2 text-sm font-medium text-blue-400 hover:bg-blue-950/30 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                  >
                    {testStatus === 'loading' ? (
                      <span className="flex items-center justify-center gap-2">
                        <span className="animate-spin">⟳</span> 연결 테스트 중…
                      </span>
                    ) : '🔌 연결 테스트'}
                  </button>
                  {testResult && (
                    <div className={`rounded-lg border px-3 py-2.5 text-sm ${
                      testResult.success
                        ? 'border-emerald-800 bg-emerald-950/30 text-emerald-400'
                        : 'border-red-800 bg-red-950/30 text-red-400'
                    }`}>
                      {testResult.success ? '✓ ' : '✗ '}{testResult.message}
                    </div>
                  )}
                </div>
              </>
            )}

```

- [ ] **Step 6: 제출 버튼 게이팅에 KIS 반영**

`<button type="submit">`의 `disabled`와 라벨 조건에서 EXCHANGE에만 걸린 테스트 성공 게이팅을 KIS에도 적용:

```tsx
            <button type="submit"
              disabled={
                mutation.isPending || !api ||
                ((category === 'EXCHANGE' || provider === 'KIS') && testStatus !== 'success')
              }
              className="flex-1 rounded-lg bg-blue-600 py-2.5 text-sm font-medium hover:bg-blue-500 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              {mutation.isPending ? '생성 중…' :
               (category === 'EXCHANGE' || provider === 'KIS') && testStatus !== 'success'
                 ? '연결 테스트 후 추가 가능' : '계좌 추가'}
            </button>
```

- [ ] **Step 7: 프론트 빌드/린트 확인**

Run: `cd frontend/allfolio_app && npm run build`
Expected: 빌드 성공 (타입 에러 없음). `npm run lint` 있으면 함께 통과.

- [ ] **Step 8: Commit**

```bash
git add frontend/allfolio_app/app/unified/accounts/new/page.tsx
git commit -m "feat(kis): 계좌추가 폼에 KIS API 연동 경로 추가 (앱키/앱시크릿/계좌번호+연결테스트)"
```

---

### Task 5: 전체 빌드 + 실측 검증

**Files:** 없음 (검증 태스크)

- [ ] **Step 1: 백엔드 전체 테스트**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test`
Expected: BUILD SUCCESSFUL (KisSyncAdapterTest 포함 전 테스트 통과)

- [ ] **Step 2: 백엔드 JAR 빌드 (CI 체크 사전 확인)**

Run: `cd allfolio-backend && ./gradlew :backend-app:bootJar`
Expected: BUILD SUCCESSFUL (`KisSyncAdapter`/`KisSyncClientImpl`/`KisSyncProperties` 빈 등록 문제 없음)

- [ ] **Step 3: PR 생성 및 CI 확인**

`feat/kis-balance-sync` 브랜치 push → PR 생성 → `Build backend JAR` GitHub Actions 체크 pass 확인.

- [ ] **Step 4: 실측 (사용자 주도, 배포 후)**

> ⚠️ 앱키/앱시크릿/계좌번호 실제 값은 **사용자가 직접** UI에 입력한다. 대화/로그에 노출 금지.

1. main 머지 → Render 배포 완료 확인 (헬스 200)
2. `rkdghd123@naver.com` 로그인 → 계좌추가 → "증권사 API 연동" 선택
3. 앱키/앱시크릿/계좌번호 입력 → 연결 테스트 → "연결 성공" 확인
4. 계좌 추가 → 상세에서 **Sync** → 오늘 산 종목이 자산으로 표시되는지 확인
5. (장 시간외라 평가금액이 0 → 매입가 폴백으로 표시되면 정상)

---

## Self-Review

**1. Spec coverage:**
- KisSyncAdapter 신규(잔고조회·pdno 집계·prpr 폴백) → Task 3 ✅
- 토큰 캐시·실전/모의 base-url·tr_id → Task 1, 2 ✅
- 프론트 KIS 연동 폼(앱키/시크릿/계좌번호 단일칸/연결테스트/게이팅) → Task 4 ✅
- 실전 기본 설정(KIS_SYNC_MOCK) → Task 1 (`KisSyncProperties` 기본값) ✅
- 단위테스트(집계·폴백·에러) + 실측 → Task 3, 5 ✅
- 비목표(페이지네이션/rate-limit 재시도/per-account 모의/broker.KisAdapter) → 미포함 ✅
- testConnection이 계좌번호 없이 토큰만 검증하는 제약 → Task 3 구현/주석 + Task 4 안내 반영 ✅

**2. Placeholder scan:** TODO/TBD/"적절히 처리" 등 없음. 모든 코드 스텝에 완전한 코드 포함.

**3. Type consistency:**
- `KisSyncClient.fetchBalance(appKey, appSecret, cano, acntPrdtCd)` — Task 2 정의, Task 3 사용 일치 ✅
- `KisBalanceItem` 필드(pdno/prdtName/hldgQty/pchsAvgPric/pchsAmt/prpr/evluAmt) — Task 1 정의, Task 3 매핑/테스트 일치 ✅
- `Asset.create(...)` 시그니처 — 실제 도메인 시그니처와 일치(category/type/sourceType/valuationMethod 포함) ✅
- `ConnectionTestResult(success, message, assetCount=0)` — 실제 정의와 일치 ✅
- `AccountProvider.KIS`, `AssetSourceType.STOCK_API`, `AssetType.STOCK`, `AssetCategory.FINANCIAL`, `ValuationMethod.MARKET_PRICE/USER_INPUT` — 모두 실존 enum 값 ✅
- 프론트 `AccountProvider`에 `'KIS'` 존재 → 타입 변경 불필요 ✅
