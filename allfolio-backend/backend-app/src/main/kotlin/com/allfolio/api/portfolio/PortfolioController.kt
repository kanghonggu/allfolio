package com.allfolio.api.portfolio

import com.allfolio.portfolio.application.usecase.CreatePortfolioUseCase
import com.allfolio.portfolio.application.usecase.DeletePortfolioUseCase
import com.allfolio.portfolio.application.usecase.ListPortfoliosUseCase
import com.allfolio.portfolio.domain.Portfolio
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.LocalDateTime
import java.util.UUID

data class CreatePortfolioRequest(
    @field:NotBlank val name: String,
)

data class PortfolioResponse(
    val id: UUID,
    val userId: UUID,
    val name: String,
    val baseCurrency: String,
    val createdAt: LocalDateTime,
)

@RestController
@RequestMapping("/api/portfolios")
class PortfolioController(
    private val createPortfolioUseCase: CreatePortfolioUseCase,
    private val listPortfoliosUseCase: ListPortfoliosUseCase,
    private val deletePortfolioUseCase: DeletePortfolioUseCase,
) {
    @PostMapping
    fun create(
        @RequestHeader("X-User-Id") userId: UUID,
        @Valid @RequestBody request: CreatePortfolioRequest,
    ): ResponseEntity<PortfolioResponse> {
        val portfolio = createPortfolioUseCase.execute(userId, request.name)
        return ResponseEntity
            .created(URI.create("/api/portfolios/${portfolio.id.value}"))
            .body(portfolio.toResponse())
    }

    @GetMapping
    fun list(
        @RequestHeader("X-User-Id") userId: UUID,
    ): List<PortfolioResponse> =
        listPortfoliosUseCase.execute(userId).map { it.toResponse() }

    @DeleteMapping("/{id}")
    fun delete(
        @RequestHeader("X-User-Id") userId: UUID,
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        deletePortfolioUseCase.execute(userId, id)
        return ResponseEntity.ok().build()
    }

    private fun Portfolio.toResponse() = PortfolioResponse(
        id = id.value,
        userId = tenantId,
        name = name,
        baseCurrency = baseCurrency,
        createdAt = createdAt,
    )
}
