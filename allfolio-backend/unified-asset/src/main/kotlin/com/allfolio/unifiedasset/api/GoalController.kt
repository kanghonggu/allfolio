package com.allfolio.unifiedasset.api

import com.allfolio.unifiedasset.application.usecase.GoalRequest
import com.allfolio.unifiedasset.application.usecase.GoalService
import com.allfolio.unifiedasset.application.usecase.GoalsResponse
import com.allfolio.unifiedasset.application.usecase.GoalResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/goals")
class GoalController(private val svc: GoalService) {

    @GetMapping
    fun list(@RequestHeader("X-User-Id") userId: UUID): GoalsResponse =
        svc.list(userId)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestBody req: GoalRequest,
    ): GoalResponse = svc.create(userId, req)

    @PutMapping("/{id}")
    fun update(
        @RequestHeader("X-User-Id") userId: UUID,
        @PathVariable id: UUID,
        @RequestBody req: GoalRequest,
    ): GoalResponse = svc.update(userId, id, req)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @RequestHeader("X-User-Id") userId: UUID,
        @PathVariable id: UUID,
    ) = svc.delete(userId, id)
}
