package com.allfolio.unifiedasset.api

import com.allfolio.common.crypto.SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE
import com.allfolio.common.crypto.requiresSensitiveDataReconnection
import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.StockTradeRepository
import com.allfolio.unifiedasset.application.port.SyncLogRepository
import com.allfolio.unifiedasset.application.usecase.*
import com.allfolio.unifiedasset.domain.account.*
import com.allfolio.unifiedasset.domain.sync.SyncTrigger
import com.allfolio.unifiedasset.domain.asset.Asset
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

// ── DTOs ─────────────────────────────────────────────────────────

data class TestConnectionRequest(
    val provider:   AccountProvider,
    val apiKey:     String,
    val apiSecret:  String,
    val passphrase: String? = null,
)

data class CreateAccountRequest(
    @field:NotBlank val accountName: String,
    val provider: AccountProvider,
    val accountType: AccountType,
    val currency: String = "KRW",   // QA P2: 기본 통화 KRW
    val apiKey: String? = null,
    val apiSecret: String? = null,
    val walletAddress: String? = null,
    val chain: String? = null,
    val externalId: String? = null,
)

data class AccountResponse(
    val id: UUID,
    val userId: UUID,
    val provider: String,
    val accountType: String,
    val accountName: String,
    val currency: String,
    val status: String,
    /**
     * **오프셋을 달고 나간다.** 존 없는 `LocalDateTime`으로 내보내면 Jackson이 오프셋 없이
     * 적고(`"2026-08-21T06:55:18"`) 브라우저의 `new Date(...)`가 읽는 쪽 로컬 시각으로
     * 해석한다 — 저장은 UTC라 한국 사용자에게 9시간 어긋난다(2026-08-21 실측: 15:55 동기화가
     * `오전 6:55`로 표시). `generatedAt`이 같은 결론이다(ReportGeneratedAtOffsetTest).
     */
    val lastSyncedAt: OffsetDateTime?,
    val createdAt: LocalDateTime,
    /** 기관명 (externalId가 계좌번호형이면 null — 계좌번호는 accountNumber로) */
    val brokerage: String?,
    /** 마스킹된 계좌번호 (예: 4485****_01). 원문은 응답에 싣지 않는다 (QA P2) */
    val accountNumber: String?,
)

data class CreateManualAssetRequest(
    @field:NotBlank val name: String,
    val symbol: String?,
    val type: com.allfolio.unifiedasset.domain.asset.AssetType,
    val subType: String? = null,          // OWN/JEONSE/MONTHLY/PRESALE/LEASE/RENTAL
    val quantity: java.math.BigDecimal = java.math.BigDecimal.ONE,
    val areaPyeong: java.math.BigDecimal? = null,
    /**
     * 전용면적(㎡). **[areaPyeong]과 역할이 다르다** — 그쪽은 사용자가 적은 값이라
     * 전용인지 공급인지 모르고 표시용이다. 이쪽은 실거래가 매칭 키라 소스가 확정한
     * 값만 들어온다(R2 단지·평형 선택). 자세한 이유는 `Asset.exclusiveAreaM2` 참고.
     */
    val exclusiveAreaM2: java.math.BigDecimal? = null,
    val purchasePrice: java.math.BigDecimal,
    val currentValue: java.math.BigDecimal,
    val loanAmount: java.math.BigDecimal? = null,
    val currency: String = "KRW",
    val memo: String?,
    val maturityDate: java.time.LocalDate? = null,
)

data class CreateStockTradeRequest(
    val tradeType: StockTradeType,
    @field:NotBlank val stockName: String,
    val symbol: String? = null,
    val quantity: BigDecimal,
    val price: BigDecimal,
    val totalAmount: BigDecimal,
    val fee: BigDecimal = BigDecimal.ZERO,
    val tax: BigDecimal = BigDecimal.ZERO,
    val tradedAt: LocalDate,
    val memo: String? = null,
)

data class StockTradeResponse(
    val id: UUID,
    val accountId: UUID,
    val tradeType: String,
    val stockName: String,
    val symbol: String?,
    val quantity: BigDecimal,
    val price: BigDecimal,
    val totalAmount: BigDecimal,
    val fee: BigDecimal,
    val tax: BigDecimal,
    val tradedAt: LocalDate,
    val memo: String?,
    val createdAt: LocalDateTime,
)

// ── Controller ───────────────────────────────────────────────────

@RestController
@RequestMapping("/api/unified/accounts")
class AccountController(
    private val createAccountUseCase: CreateAccountUseCase,
    private val deleteAccountUseCase: DeleteAccountUseCase,
    private val deleteAssetUseCase: DeleteAssetUseCase,
    private val syncAccountUseCase: SyncAccountUseCase,
    private val importCsvUseCase: ImportCsvUseCase,
    private val testConnectionUseCase: TestConnectionUseCase,
    private val accountRepository: AccountRepository,
    private val assetRepository: AssetRepository,
    private val stockTradeRepository: StockTradeRepository,
    private val syncLogRepository: SyncLogRepository,
    private val getSyncStatusUseCase: GetSyncStatusUseCase,
    private val autoSyncTrigger: AutoSyncTrigger,
    private val snapshotService: PerformanceSnapshotService,
    private val authorizationService: AuthorizationService,
) {
    @PostMapping("/test-connection")
    fun testConnection(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestBody req: TestConnectionRequest,
    ): ConnectionTestResult = testConnectionUseCase.execute(
        provider   = req.provider,
        apiKey     = req.apiKey,
        apiSecret  = req.apiSecret,
        passphrase = req.passphrase,
    )

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestHeader("X-User-Id") userId: UUID,
        @Valid @RequestBody req: CreateAccountRequest,
    ): AccountResponse {
        val account = createAccountUseCase.execute(
            CreateAccountCommand(
                userId        = userId,
                provider      = req.provider,
                accountType   = req.accountType,
                accountName   = req.accountName,
                externalId    = req.externalId,
                currency      = req.currency,
                apiKey        = req.apiKey,
                apiSecret     = req.apiSecret,
                walletAddress = req.walletAddress,
                chain         = req.chain,
            )
        )
        // AF-90: 외부 조회가 가능한 계좌는 등록 즉시 자동 동기화 — 사용자가 sync 화면을
        // 찾아 들어가지 않아도 자산이 대시보드에 잡히게 한다
        if (account.provider in DailyAccountSyncer.SYNC_ELIGIBLE_PROVIDERS) {
            autoSyncTrigger.requestSync(account.id)
        }
        return account.toResponse()
    }

    @GetMapping
    fun list(@RequestHeader("X-User-Id") userId: UUID): List<AccountResponse> =
        accountRepository.findByUserId(userId).map { it.toResponse() }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @RequestHeader("X-User-Id") userId: UUID,
        @PathVariable id: UUID,
    ) {
        val account = accountRepository.findById(id)
            ?: throw NoSuchElementException("Account not found: $id")
        if (account.userId != userId) throw NoSuchElementException("Account not found: $id")
        deleteAccountUseCase.execute(id)
    }

    /** 계좌별 최신 동기화 상태 요약 (AF-9). */
    @GetMapping("/sync-status")
    fun syncStatus(@RequestHeader("X-User-Id") userId: UUID): List<AccountSyncStatus> =
        getSyncStatusUseCase.execute(userId)

    /** 계좌 동기화 이력 (AF-9). */
    @GetMapping("/{id}/sync-logs")
    fun syncLogs(
        @RequestHeader("X-User-Id") userId: UUID,
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "20") limit: Int,
    ): List<SyncLogView> {
        authorizationService.requireOwnedAccount(userId, id)
        return syncLogRepository.findByAccountId(id, limit.coerceIn(1, 100)).map { it.toView() }
    }

    @PostMapping("/{id}/sync")
    fun sync(
        @RequestHeader("X-User-Id") userId: UUID,
        @PathVariable id: UUID,
    ): SyncResult {
        val account = try {
            accountRepository.findById(id)
        } catch (e: RuntimeException) {
            if (e.requiresSensitiveDataReconnection()) {
                return SyncResult(id, 0, AccountStatus.ERROR, SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE)
            }
            throw e
        } ?: throw NoSuchElementException("Account not found: $id")
        if (account.userId != userId) throw NoSuchElementException("Account not found: $id")
        return syncAccountUseCase.execute(id, SyncTrigger.MANUAL)
    }

    @GetMapping("/{id}/assets")
    fun getAssets(
        @RequestHeader("X-User-Id") userId: UUID,
        @PathVariable id: UUID,
    ): List<AssetResponse> {
        authorizationService.requireOwnedAccount(userId, id)
        return assetRepository.findByAccountId(id).map { it.toResponse() }
    }

    @PostMapping("/{id}/assets")
    @ResponseStatus(HttpStatus.CREATED)
    fun addManualAsset(
        @RequestHeader("X-User-Id") userId: UUID,
        @PathVariable id: UUID,
        @Valid @RequestBody req: CreateManualAssetRequest,
    ): AssetResponse {
        val account = accountRepository.findById(id)
            ?: throw NoSuchElementException("Account not found: $id")
        if (account.userId != userId) throw NoSuchElementException("Account not found: $id")
        require(account.provider == AccountProvider.MANUAL) { "수동 계좌에만 자산을 추가할 수 있습니다" }

        val category = if (req.type in listOf(
            com.allfolio.unifiedasset.domain.asset.AssetType.STOCK,
            com.allfolio.unifiedasset.domain.asset.AssetType.CRYPTO,
            com.allfolio.unifiedasset.domain.asset.AssetType.CASH
        )) com.allfolio.unifiedasset.domain.asset.AssetCategory.FINANCIAL
        else com.allfolio.unifiedasset.domain.asset.AssetCategory.MANUAL

        val isAreaType = req.type in setOf(
            com.allfolio.unifiedasset.domain.asset.AssetType.REAL_ESTATE,
            com.allfolio.unifiedasset.domain.asset.AssetType.JEONSE,
        )

        val asset = com.allfolio.unifiedasset.domain.asset.Asset.create(
            userId          = userId,
            accountId       = id,
            category        = category,
            type            = req.type,
            sourceType      = com.allfolio.unifiedasset.domain.asset.AssetSourceType.MANUAL,
            name            = req.name,
            symbol          = req.symbol,
            quantity        = if (isAreaType) java.math.BigDecimal.ONE else req.quantity,
            purchasePrice   = req.purchasePrice,
            currentValue    = req.currentValue,
            currency        = req.currency,
            valuationMethod = com.allfolio.unifiedasset.domain.asset.ValuationMethod.USER_INPUT,
            memo            = req.memo,
            subType         = req.subType,
            loanAmount      = req.loanAmount,
            maturityDate    = req.maturityDate,
            areaPyeong      = if (isAreaType) req.areaPyeong else null,
            exclusiveAreaM2 = if (isAreaType) req.exclusiveAreaM2 else null,
        )
        val saved = assetRepository.save(asset)
        snapshotService.record(
            userId,
            assetRepository.findByUserId(userId).navByCurrency(),
            LocalDate.now(java.time.ZoneId.of("Asia/Seoul")),
        )
        return saved.toResponse()
    }

    /**
     * 자산 1건 삭제 (AF-153).
     *
     * 계좌 삭제(`DELETE /accounts/{id}`)와 **다른 일이다.** 그쪽은 계좌의 자산·거래내역을
     * 통째로 지운다 — 오타 하나를 고치려고 그걸 쓰는 상황이 이 엔드포인트가 생긴 이유다.
     *
     * 소유권은 두 번 본다. 여기서 계좌를, use case에서 자산이 그 계좌·그 사용자의 것인지를
     * 본다. 계좌만 보면 남의 자산 id를 내 계좌 경로에 실을 수 있다.
     */
    @DeleteMapping("/{id}/assets/{assetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteAsset(
        @RequestHeader("X-User-Id") userId: UUID,
        @PathVariable id: UUID,
        @PathVariable assetId: UUID,
    ) {
        authorizationService.requireOwnedAccount(userId, id)
        deleteAssetUseCase.execute(userId, id, assetId)
    }

    @PostMapping("/{id}/csv")
    fun importCsv(
        @RequestHeader("X-User-Id") userId: UUID,
        @PathVariable id: UUID,
        @RequestParam("file") file: MultipartFile,
    ): CsvImportResult {
        val account = accountRepository.findById(id)
            ?: throw NoSuchElementException("Account not found: $id")
        if (account.userId != userId) throw NoSuchElementException("Account not found: $id")
        val content = file.inputStream.bufferedReader().readText()
        val result = importCsvUseCase.execute(userId, id, content)
        snapshotService.record(
            userId,
            assetRepository.findByUserId(userId).navByCurrency(),
            LocalDate.now(java.time.ZoneId.of("Asia/Seoul")),
        )
        return result
    }

    @PostMapping("/{id}/csv/preview")
    fun previewCsv(
        @RequestHeader("X-User-Id") userId: UUID,
        @PathVariable id: UUID,
        @RequestParam("file") file: MultipartFile,
    ): List<CsvPreviewRow> {
        val account = accountRepository.findById(id)
            ?: throw NoSuchElementException("Account not found: $id")
        if (account.userId != userId) throw NoSuchElementException("Account not found: $id")
        val content = file.inputStream.bufferedReader().readText()
        return importCsvUseCase.preview(content)
    }

    // ── 증권 거래내역 ──────────────────────────────────────────────

    @GetMapping("/{id}/stock-trades")
    fun getStockTrades(
        @RequestHeader("X-User-Id") userId: UUID,
        @PathVariable id: UUID,
    ): List<StockTradeResponse> {
        val account = accountRepository.findById(id)
            ?: throw NoSuchElementException("Account not found: $id")
        if (account.userId != userId) throw NoSuchElementException("Account not found: $id")
        return stockTradeRepository.findByAccountId(id).map { it.toResponse() }
    }

    @PostMapping("/{id}/stock-trades")
    @ResponseStatus(HttpStatus.CREATED)
    fun addStockTrade(
        @RequestHeader("X-User-Id") userId: UUID,
        @PathVariable id: UUID,
        @Valid @RequestBody req: CreateStockTradeRequest,
    ): StockTradeResponse {
        val account = accountRepository.findById(id)
            ?: throw NoSuchElementException("Account not found: $id")
        if (account.userId != userId) throw NoSuchElementException("Account not found: $id")
        require(account.provider == AccountProvider.STOCK) { "증권 계좌에만 거래내역을 추가할 수 있습니다" }

        val trade = StockTrade.create(
            accountId   = id,
            userId      = userId,
            tradeType   = req.tradeType,
            stockName   = req.stockName,
            symbol      = req.symbol,
            quantity    = req.quantity,
            price       = req.price,
            totalAmount = req.totalAmount,
            fee         = req.fee,
            tax         = req.tax,
            tradedAt    = req.tradedAt,
            memo        = req.memo,
        )
        val saved = stockTradeRepository.save(trade).toResponse()
        // AF-90: 거래 저장이 포지션에 반영되도록 자동 동기화 (응답은 기다리지 않는다)
        autoSyncTrigger.requestSync(id)
        return saved
    }

    @DeleteMapping("/{id}/stock-trades/{tradeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteStockTrade(
        @RequestHeader("X-User-Id") userId: UUID,
        @PathVariable id: UUID,
        @PathVariable tradeId: UUID,
    ) {
        val account = accountRepository.findById(id)
            ?: throw NoSuchElementException("Account not found: $id")
        if (account.userId != userId) throw NoSuchElementException("Account not found: $id")
        val trade = stockTradeRepository.findById(tradeId)
            ?: throw NoSuchElementException("Trade not found: $tradeId")
        require(trade.accountId == id) { "Trade does not belong to this account" }
        stockTradeRepository.delete(tradeId)
        // AF-90: 삭제도 포지션에 즉시 반영 — 예전엔 "삭제 → sync → 새로고침" 3단계였다
        autoSyncTrigger.requestSync(id)
    }

    // ── Helpers ──

    private fun Account.toResponse() = AccountResponse(
        id           = id,
        userId       = userId,
        provider     = provider.name,
        accountType  = accountType.name,
        accountName  = accountName,
        currency     = currency,
        status       = status.name,
        lastSyncedAt = lastSyncedAt?.atOffset(ZoneOffset.UTC),   // 저장 벽시계가 UTC다
        createdAt    = createdAt,
        brokerage    = externalId?.takeUnless { com.allfolio.unifiedasset.domain.common.isAccountNumberLike(it) },
        accountNumber = externalId
            ?.takeIf { com.allfolio.unifiedasset.domain.common.isAccountNumberLike(it) }
            ?.let { com.allfolio.unifiedasset.domain.common.maskAccountNumber(it) },
    )

    private fun StockTrade.toResponse() = StockTradeResponse(
        id          = id,
        accountId   = accountId,
        tradeType   = tradeType.name,
        stockName   = stockName,
        symbol      = symbol,
        quantity    = quantity,
        price       = price,
        totalAmount = totalAmount,
        fee         = fee,
        tax         = tax,
        tradedAt    = tradedAt,
        memo        = memo,
        createdAt   = createdAt,
    )
}

data class AssetResponse(
    val id: UUID,
    val accountId: UUID,
    val name: String,
    val symbol: String?,
    val type: String,
    val subType: String?,
    val category: String,
    val sourceType: String,
    val quantity: java.math.BigDecimal,
    val areaPyeong: java.math.BigDecimal?,
    /** 전용면적(㎡). 실거래가 매칭 키 — 소스가 확정한 값만 들어 있다 */
    val exclusiveAreaM2: java.math.BigDecimal?,
    val purchasePrice: java.math.BigDecimal,
    val currentValue: java.math.BigDecimal,
    val loanAmount: java.math.BigDecimal?,
    val netEquity: java.math.BigDecimal,
    val currency: String,
    val valuationMethod: String,
    val confidenceLevel: String,
    val unrealizedPnl: java.math.BigDecimal,
    val returnRate: java.math.BigDecimal,
    val memo: String?,
    val lastUpdatedAt: LocalDateTime,
    val maturityDate: java.time.LocalDate?,
    val liquidityType: String,
)

fun Asset.toResponse() = AssetResponse(
    id               = id,
    accountId        = accountId,
    name             = name,
    symbol           = symbol,
    type             = type.name,
    subType          = subType,
    category         = category.name,
    sourceType       = sourceType.name,
    quantity         = quantity,
    areaPyeong       = areaPyeong,
    exclusiveAreaM2  = exclusiveAreaM2,
    purchasePrice    = purchasePrice,
    currentValue     = currentValue,
    loanAmount       = loanAmount,
    netEquity        = netEquity(),
    currency         = currency,
    valuationMethod  = valuationMethod.name,
    confidenceLevel  = confidenceLevel.name,
    unrealizedPnl    = unrealizedPnl(),
    returnRate       = returnRate(),
    memo             = memo,
    lastUpdatedAt    = lastUpdatedAt,
    maturityDate     = maturityDate,
    liquidityType    = liquidityType.name,
)
