package com.allfolio.asset.domain

import java.time.LocalDateTime

class Asset private constructor(
    val id: AssetId,
    val symbol: String,
    val assetType: AssetType,
    val nativeCurrency: String,
    val createdAt: LocalDateTime,
    name: String,
    active: Boolean,
) {
    var name: String = name
        private set

    var active: Boolean = active
        private set

    fun rename(newName: String) {
        if (newName.isBlank()) throw AssetException.blankName()
        name = newName
    }

    fun deactivate() {
        if (!active) throw AssetException.alreadyInactive(id)
        active = false
    }

    fun activate() {
        if (active) throw AssetException.alreadyActive(id)
        active = true
    }

    fun isActive(): Boolean = active

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Asset) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "Asset(id=$id, symbol=$symbol, type=$assetType, currency=$nativeCurrency, active=$active)"

    companion object {
        fun create(
            symbol: String,
            name: String,
            assetType: AssetType,
            nativeCurrency: String,
        ): Asset {
            if (symbol.isBlank()) throw AssetException.blankSymbol()
            if (name.isBlank()) throw AssetException.blankName()
            if (nativeCurrency.isBlank()) throw AssetException.blankNativeCurrency()

            return Asset(
                id = AssetId.newId(),
                symbol = symbol.uppercase(),
                name = name,
                assetType = assetType,
                nativeCurrency = nativeCurrency.uppercase(),
                createdAt = LocalDateTime.now(),
                active = true,
            )
        }

        fun reconstruct(
            id: AssetId,
            symbol: String,
            name: String,
            assetType: AssetType,
            nativeCurrency: String,
            createdAt: LocalDateTime,
            active: Boolean,
        ): Asset = Asset(
            id = id,
            symbol = symbol,
            name = name,
            assetType = assetType,
            nativeCurrency = nativeCurrency,
            createdAt = createdAt,
            active = active,
        )
    }
}
