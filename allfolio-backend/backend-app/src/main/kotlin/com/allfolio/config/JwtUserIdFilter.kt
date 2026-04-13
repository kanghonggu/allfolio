package com.allfolio.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.Collections

/**
 * JWT의 sub 클레임(Keycloak 유저 UUID)을 X-User-Id 헤더로 주입한다.
 * 컨트롤러는 기존과 동일하게 @RequestHeader("X-User-Id")를 사용할 수 있다.
 */
@Component
class JwtUserIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val auth = SecurityContextHolder.getContext().authentication
        if (auth is JwtAuthenticationToken) {
            val userId = auth.token.subject
            val wrapped = UserIdInjectedRequest(request, userId)
            chain.doFilter(wrapped, response)
        } else {
            chain.doFilter(request, response)
        }
    }

    private class UserIdInjectedRequest(
        request: HttpServletRequest,
        private val userId: String,
    ) : HttpServletRequestWrapper(request) {

        override fun getHeader(name: String): String? =
            if (name.equals("X-User-Id", ignoreCase = true)) userId
            else super.getHeader(name)

        override fun getHeaders(name: String): java.util.Enumeration<String> =
            if (name.equals("X-User-Id", ignoreCase = true))
                Collections.enumeration(listOf(userId))
            else super.getHeaders(name)

        override fun getHeaderNames(): java.util.Enumeration<String> {
            val names = super.getHeaderNames().toList().toMutableList()
            if (!names.any { it.equals("X-User-Id", ignoreCase = true) }) {
                names.add("X-User-Id")
            }
            return Collections.enumeration(names)
        }
    }
}
