package com.allfolio.unifiedasset.api

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.StockTradeRepository
import com.allfolio.unifiedasset.application.usecase.*
import com.allfolio.unifiedasset.domain.account.*
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
    val quantity: java.math.BigDecimal,
    val purchasePrice: java.math.BigDecimal,
    val currentValue: java.math.BigDecimal,
    val currency: String = "KRW",
    val memo: String?,
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
    private val accountRepository: AccountRepository,
    private val assetRepository: AssetRepository,
    private val stockTradeRepository: StockTradeRepository,
    private val snapshotService: PerformanceSnapshotService,
) {
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
        accountRepository.delete(id)
    }

    @PostMapping("/{id}/sync")
    fun sync(
        @RequestHeader("X-User-Id") userId: UUID,
        @PathVariable id: UUID,
    ): SyncResult {
        val account = accountRepository.findById(id)
            ?: throw NoSuchElementException("Account not found: $id")
        require(account.userId == userId) { "Forbidden" }
        return syncAccountUseCase.execute(id)
    }

    @GetMapping("/{id}/assets")
    fun getAssets(
        @RequestHeader("X-User-Id") userId: UUID,
        @PathVariable id: UUID,
    ): List<AssetResponse> =
        assetRepository.findByAccountId(id).map { it.toResponse() }

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

        val asset = com.allfolio.unifiedasset.domain.asset.Asset.create(
            userId          = userId,
            accountId       = id,
            category        = category,
            type            = req.type,
            sourceType      = com.allfolio.unifiedasset.domain.asset.AssetSourceType.MANUAL,
            name            = req.name,
            symbol          = req.symbol,
            quantity        = req.quantity,
            purchasePrice   = req.purchasePrice,
            currentValue    = req.currentValue,
            currency        = req.currency,
            valuationMethod = com.allfolio.unifiedasset.domain.asset.ValuationMethod.USER_INPUT,
            memo            = req.memo,
        )
        val saved = assetRepository.save(asset)
        val nav = assetRepository.findByUserId(userId).sumOf { it.currentValue }
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
        val nav = assetRepository.findByUserId(userId).sumOf { it.currentValue }
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
    val category: String,
    val sourceType: String,
    val quantity: java.math.BigDecimal,
    val purchasePrice: java.math.BigDecimal,
    val currentValue: java.math.BigDecimal,
    val currency: String,
    val valuationMethod: String,
    val confidenceLevel: String,
    val unrealizedPnl: java.math.BigDecimal,
    val returnRate: java.math.BigDecimal,
    val memo: String?,
    val lastUpdatedAt: LocalDateTime,
)

fun Asset.toResponse() = AssetResponse(
    id               = id,
    accountId        = accountId,
    name             = name,
    symbol           = symbol,
    type             = type.name,
    category         = category.name,
    sourceType       = sourceType.name,
    quantity         = quantity,
    purchasePrice    = purchasePrice,
    currentValue     = currentValue,
    currency         = currency,
    valuationMethod  = valuationMethod.name,
    confidenceLevel  = confidenceLevel.name,
    unrealizedPnl    = unrealizedPnl(),
    returnRate       = returnRate(),
    memo             = memo,
    lastUpdatedAt    = lastUpdatedAt,
)
