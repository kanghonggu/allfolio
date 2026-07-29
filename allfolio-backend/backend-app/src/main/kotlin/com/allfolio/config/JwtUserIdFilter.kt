package com.allfolio.config

import com.allfolio.auth.JwtTokenService
import io.jsonwebtoken.JwtException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.Collections

/**
 * Allfolio JWT의 sub 클레임(유저 UUID)을 X-User-Id 헤더로 주입한다.
 * 컨트롤러는 기존과 동일하게 @RequestHeader("X-User-Id")를 사용할 수 있다.
 */
@Component
class JwtUserIdFilter(
    private val jwtTokenService: JwtTokenService,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val token = request.getHeader("Authorization")
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(" ")

        if (token != null) {
            val principal = try {
                jwtTokenService.parsePrincipal(token)
            } catch (e: JwtException) {
                SecurityContextHolder.clearContext()
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid access token")
                return
            }
            val userId = principal.userId.toString()
            val authorities = listOf(SimpleGrantedAuthority("ROLE_${principal.role.name}"))
            SecurityContextHolder.getContext().authentication =
                PreAuthenticatedAuthenticationToken(userId, token, authorities)
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
