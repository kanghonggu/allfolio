package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.domain.asset.*
import com.opencsv.CSVReaderBuilder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.StringReader
import java.math.BigDecimal
import java.util.UUID

data class CsvImportResult(
    val accountId: UUID,
    val imported: Int,
    val skipped: Int,
    val errors: List<String>,
)

data class CsvPreviewRow(
    val line: Int,
    val name: String,
    val symbol: String?,
    val type: String,
    val quantity: String,
    val purchasePrice: String,
    val currentValue: String,
    val error: String?,
)

@Service
class ImportCsvUseCase(private val assetRepository: AssetRepository) {
    /**
     * CSV format (header required):
     * name,symbol,type,quantity,purchasePrice,currentValue,currency,memo
     * Supported types: STOCK, CRYPTO, REAL_ESTATE, VEHICLE, GOLD, CASH, ETC
     */
    fun preview(csvContent: String): List<CsvPreviewRow> {
        val reader = CSVReaderBuilder(StringReader(csvContent)).withSkipLines(1).build()
        return reader.readAll().mapIndexed { idx, row ->
            val lineNo = idx + 2
            try {
                CsvPreviewRow(
                    line          = lineNo,
                    name          = row.getOrElse(0) { "" }.trim(),
                    symbol        = row.getOrNull(1)?.trim()?.ifBlank { null },
                    type          = row.getOrElse(2) { "" }.trim(),
                    quantity      = row.getOrElse(3) { "" }.trim(),
                    purchasePrice = row.getOrElse(4) { "" }.trim(),
                    currentValue  = row.getOrElse(5) { "" }.trim(),
                    error         = null,
                )
            } catch (e: Exception) {
                CsvPreviewRow(lineNo, "", null, "", "", "", "", e.message)
            }
        }
    }

    @Transactional
    fun execute(userId: UUID, accountId: UUID, csvContent: String): CsvImportResult {
        val reader = CSVReaderBuilder(StringReader(csvContent)).withSkipLines(1).build()
        val rows = reader.readAll()
        var skipped = 0
        val errors = mutableListOf<String>()
        val assets = mutableListOf<com.allfolio.unifiedasset.domain.asset.Asset>()

        rows.forEachIndexed { idx, row ->
            val lineNo = idx + 2
            try {
                if (row.size < 6) { errors += "Line $lineNo: 컬럼 수 부족"; skipped++; return@forEachIndexed }
                val name     = row[0].trim().ifBlank { throw IllegalArgumentException("이름 필수") }
                val symbol   = row.getOrNull(1)?.trim()?.ifBlank { null }
                val type     = AssetType.valueOf(row[2].trim().uppercase())
                val qty      = BigDecimal(row[3].trim())
                val purchase = BigDecimal(row[4].trim())
                val current  = BigDecimal(row[5].trim())
                val currency = row.getOrElse(6) { "KRW" }.trim().ifBlank { "KRW" }
                val memo     = row.getOrNull(7)?.trim()?.ifBlank { null }
                val category = if (type in listOf(AssetType.STOCK, AssetType.CRYPTO, AssetType.CASH))
                    AssetCategory.FINANCIAL else AssetCategory.MANUAL

                assets += com.allfolio.unifiedasset.domain.asset.Asset.create(
                    userId          = userId,
                    accountId       = accountId,
                    category        = category,
                    type            = type,
                    sourceType      = AssetSourceType.CSV,
                    name            = name,
                    symbol          = symbol,
                    quantity        = qty,
                    purchasePrice   = purchase,
                    currentValue    = current,
                    currency        = currency,
                    valuationMethod = ValuationMethod.USER_INPUT,
                    memo            = memo,
                )
            } catch (e: Exception) {
                errors += "Line $lineNo: ${e.message}"
                skipped++
            }
        }

        assetRepository.deleteByAccountId(accountId)
        assetRepository.saveAll(assets)
        return CsvImportResult(accountId, assets.size, skipped, errors)
    }
}
