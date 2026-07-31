package com.allfolio.unifiedasset.api

import com.allfolio.common.crypto.SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE
import com.allfolio.common.crypto.requiresSensitiveDataReconnection
import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.FxConverter
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
    val currency: String = "USD",
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
    val lastSyncedAt: LocalDateTime?,
    val createdAt: LocalDateTime,
    val brokerage: String?,
)

data class CreateManualAssetRequest(
    @field:NotBlank val name: String,
    val symbol: String?,
    val type: com.allfolio.unifiedasset.domain.asset.AssetType,
    val subType: String? = null,          // OWN/JEONSE/MONTHLY/PRESALE/LEASE/RENTAL
    val quantity: java.math.BigDecimal = java.math.BigDecimal.ONE,
    val areaPyeong: java.math.BigDecimal? = null,
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
    private val syncAccountUseCase: SyncAccountUseCase,
    private val importCsvUseCase: ImportCsvUseCase,
    private val testConnectionUseCase: TestConnectionUseCase,
    private val accountRepository: AccountRepository,
    private val assetRepository: AssetRepository,
    private val stockTradeRepository: StockTradeRepository,
    private val syncLogRepository: SyncLogRepository,
    private val getSyncStatusUseCase: GetSyncStatusUseCase,
    private val snapshotService: PerformanceSnapshotService,
    private val authorizationService: AuthorizationService,
    private val fx: FxConverter,
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
    ): AccountResponse =
        createAccountUseCase.execute(
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
        ).toResponse()

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
        require(account.userId == userId) { "Forbidden" }
        assetRepository.deleteByAccountId(id)
        syncLogRepository.deleteByAccountId(id)
        accountRepository.delete(id)
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
        require(account.userId == userId) { "Forbidden" }
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
        require(account.userId == userId) { "Forbidden" }
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
        )
        val saved = assetRepository.save(asset)
        val nav = assetRepository.findByUserId(userId).navInKrw(fx)
        snapshotService.record(userId, nav)
        return saved.toResponse()
    }

    @PostMapping("/{id}/csv")
    fun importCsv(
        @RequestHeader("X-User-Id") userId: UUID,
        @PathVariable id: UUID,
        @RequestParam("file") file: MultipartFile,
    ): CsvImportResult {
        val account = accountRepository.findById(id)
            ?: throw NoSuchElementException("Account not found: $id")
        require(account.userId == userId) { "Forbidden" }
        val content = file.inputStream.bufferedReader().readText()
        val result = importCsvUseCase.execute(userId, id, content)
        val nav = assetRepository.findByUserId(userId).navInKrw(fx)
        snapshotService.record(userId, nav)
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
        require(account.userId == userId) { "Forbidden" }
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
        require(account.userId == userId) { "Forbidden" }
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
        require(account.userId == userId) { "Forbidden" }
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
        return stockTradeRepository.save(trade).toResponse()
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
        require(account.userId == userId) { "Forbidden" }
        val trade = stockTradeRepository.findById(tradeId)
            ?: throw NoSuchElementException("Trade not found: $tradeId")
        require(trade.accountId == id) { "Trade does not belong to this account" }
        stockTradeRepository.delete(tradeId)
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
        lastSyncedAt = lastSyncedAt,
        createdAt    = createdAt,
        brokerage    = externalId,
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
