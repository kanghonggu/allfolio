package com.allfolio.asset.domain

import java.util.UUID

data class AssetId(val value: UUID) {

    override fun toString(): String = value.toString()

    companion object {
        fun newId(): AssetId = AssetId(UUID.randomUUID())

        fun of(value: String): AssetId = AssetId(UUID.fromString(value))

        fun of(value: UUID): AssetId = AssetId(value)
    }
}
