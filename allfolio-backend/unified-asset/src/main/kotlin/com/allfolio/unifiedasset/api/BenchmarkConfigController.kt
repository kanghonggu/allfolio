package com.allfolio.unifiedasset.api

import com.allfolio.unifiedasset.application.usecase.UserBenchmarkService
import com.allfolio.unifiedasset.domain.benchmark.BenchmarkType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/benchmark-config")
class BenchmarkConfigController(
    private val userBenchmark: UserBenchmarkService,
) {

    data class BenchmarkOption(val type: BenchmarkType, val label: String)

    data class ConfigResponse(
        val indexType: BenchmarkType?,
        val available: List<BenchmarkOption>,
    )

    data class ConfigRequest(val indexType: BenchmarkType?)

    @GetMapping
    fun get(@RequestHeader("X-User-Id") userId: UUID): ConfigResponse =
        ConfigResponse(
            indexType = userBenchmark.get(userId),
            available = BenchmarkType.entries.map { BenchmarkOption(it, it.label) },
        )

    @PutMapping
    fun set(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestBody request: ConfigRequest,
    ): ConfigResponse {
        userBenchmark.set(userId, request.indexType)
        return get(userId)
    }
}
