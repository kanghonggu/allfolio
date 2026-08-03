package com.allfolio.config

import jakarta.validation.constraints.Positive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.time.LocalDate

/**
 * QA P0 #3 — 예외 → HTTP 상태 분화 검증.
 * 라우팅 실패 404, 바인딩·파싱 실패 400, @Valid 검증 실패 422, 그 외만 500.
 */
class GlobalExceptionHandlerTest {

    data class ValidatedBody(@field:Positive val amount: Int)

    @Validated
    @RestController
    class ProbeController {
        @GetMapping("/probe/date")
        fun date(@RequestParam d: LocalDate): String = d.toString()

        @PostMapping("/probe/valid")
        fun valid(@org.springframework.web.bind.annotation.RequestHeader("X-User-Id") userId: java.util.UUID,
                  @jakarta.validation.Valid @RequestBody body: ValidatedBody): String = "ok"

        @GetMapping("/probe/boom")
        fun boom(): String = throw IllegalStateException("진짜 결함")
    }

    private val mvc = MockMvcBuilders
        .standaloneSetup(ProbeController())
        .setControllerAdvice(GlobalExceptionHandler())
        .build()

    @Test
    fun `잘못된 날짜 파라미터는 400`() {
        mvc.get("/probe/date") { param("d", "2026-13-45") }
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `깨진 JSON 본문은 400`() {
        mvc.post("/probe/valid") {
            header("X-User-Id", java.util.UUID.randomUUID().toString())
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = "{ not json"
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `필수 헤더 누락은 400`() {
        mvc.post("/probe/valid") {
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"amount": 1}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `@Valid 검증 실패는 422`() {
        mvc.post("/probe/valid") {
            header("X-User-Id", java.util.UUID.randomUUID().toString())
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"amount": -1}"""
        }.andExpect { status { isUnprocessableEntity() } }
    }

    @Test
    fun `존재하지 않는 경로는 404`() {
        // 운영에선 DispatcherServlet이 NoResourceFoundException을 던져 advice로 전달됨
        val response = GlobalExceptionHandler()
            .handleNoResource(NoResourceFoundException(HttpMethod.GET, "/api/none"))
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `그 외 예외만 500`() {
        mvc.get("/probe/boom")
            .andExpect { status { isInternalServerError() } }
    }
}
