package com.allfolio.asset.domain

enum class AssetType(val description: String) {
    CRYPTO("Cryptocurrency"),
    STOCK("Stock / Equity"),
    GOLD("Precious Metal"),
    LUXURY("Luxury Asset"),
    CASH("Cash / Cash Equivalent"),
    DEBT("Debt / Fixed Income"),
    ;

    fun isTradable(): Boolean = this != CASH

    fun requiresFxConversion(): Boolean = this != CASH
}
