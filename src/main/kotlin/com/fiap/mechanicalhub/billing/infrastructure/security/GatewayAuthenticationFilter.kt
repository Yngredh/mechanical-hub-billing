package com.fiap.mechanicalhub.billing.infrastructure.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/** Funcionario autenticado pelo API Gateway (Lambda `authorize`). */
data class GatewayPrincipal(
    val id: UUID,
    val name: String?,
    val role: String,
) {
    override fun toString(): String = id.toString()
}

/**
 * Converte os headers repassados pelo API Gateway em autenticacao do Spring
 * Security (mesma regra do monolito). Sem headers validos a requisicao segue
 * anonima e as rotas protegidas respondem 401.
 */
@Component
class GatewayAuthenticationFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val userId = request.getHeader(USER_ID_HEADER)?.takeIf { it.isNotBlank() }?.let(::parseUserId)
        val role = request.getHeader(USER_ROLE_HEADER)?.takeIf { it.isNotBlank() }

        if (userId != null && role != null) {
            val principal = GatewayPrincipal(userId, request.getHeader(USER_NAME_HEADER), role)
            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    listOf(SimpleGrantedAuthority("ROLE_${role.uppercase()}")),
                )
        }
        filterChain.doFilter(request, response)
    }

    private fun parseUserId(rawId: String): UUID? =
        runCatching { UUID.fromString(rawId) }
            .onFailure { log.warn("Cabecalho {} com valor invalido; requisicao seguira anonima", USER_ID_HEADER) }
            .getOrNull()

    companion object {
        const val USER_ID_HEADER = "x-user-id"
        const val USER_ROLE_HEADER = "x-user-role"
        const val USER_NAME_HEADER = "x-user-name"
        private val log = LoggerFactory.getLogger(GatewayAuthenticationFilter::class.java)
    }
}
