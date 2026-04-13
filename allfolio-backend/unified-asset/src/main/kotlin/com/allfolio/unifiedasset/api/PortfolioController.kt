package com.allfolio.unifiedasset.api

import com.allfolio.unifiedasset.application.usecase.GetPortfolioUseCase
import com.allfolio.unifiedasset.application.usecase.PortfolioResponse
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/unified/portfolio")
class PortfolioController(private val useCase: GetPortfolioUseCase) {
    @GetMapping
    fun get(@RequestHeader("X-User-Id") userId: UUID): PortfolioResponse =
        useCase.execute(userId)
}
