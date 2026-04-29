package com.allfolio.dashboard

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/unified/dashboard")
class DashboardController(private val useCase: GetDashboardUseCase) {

    @GetMapping
    fun get(@RequestHeader("X-User-Id") userId: UUID): DashboardResponse =
        useCase.execute(userId)
}
