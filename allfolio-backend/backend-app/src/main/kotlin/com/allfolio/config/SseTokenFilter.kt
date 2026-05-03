package com.allfolio.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.Collections

// SSE 전용: EventSource는 커스텀 헤더 불가 → ?token= 파라미터를 Authorization 헤더로 변환
// /api/sse/** 경로에서만 동작. HTTPS 환경에서는 쿼리 파라미터도 암호화됨.
@Component
@Order(1)
class SseTokenFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val token = request.getParameter("token")
        if (token != null && request.requestURI.startsWith("/api/sse/")) {
            chain.doFilter(TokenInjectedRequest(request, token), response)
        } else {
            chain.doFilter(request, response)
        }
    }

    private class TokenInjectedRequest(
        request: HttpServletRequest,
        private val token: String,
    ) : HttpServletRequestWrapper(request) {

        override fun getHeader(name: String): String? =
            if (name.equals("Authorization", ignoreCase = true)) "Bearer $token"
            else super.getHeader(name)

        override fun getHeaders(name: String): java.util.Enumeration<String> =
            if (name.equals("Authorization", ignoreCase = true))
                Collections.enumeration(listOf("Bearer $token"))
            else super.getHeaders(name)
    }
}
