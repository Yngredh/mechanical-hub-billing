package com.fiap.mechanicalhub.billing.infrastructure.security

import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableWebSecurity
class SecurityConfig {
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        gatewayAuthenticationFilter: GatewayAuthenticationFilter,
    ): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
            .authorizeHttpRequests {
                it
                    .requestMatchers("/actuator/health/**", "/actuator/prometheus")
                    .permitAll()
                    // Público: autenticado pela assinatura HMAC dentro do controller.
                    .requestMatchers("/webhooks/payment")
                    .permitAll()
                    .anyRequest()
                    .authenticated()
            }.addFilterBefore(gatewayAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
            .build()

    /** O filtro entra só na cadeia do Spring Security, não como filtro de servlet solto. */
    @Bean
    fun gatewayFilterRegistration(
        filter: GatewayAuthenticationFilter,
    ): FilterRegistrationBean<GatewayAuthenticationFilter> =
        FilterRegistrationBean(filter).apply { isEnabled = false }

    /** Sem usuários locais: a identidade vem do API Gateway. */
    @Bean
    fun userDetailsService(): UserDetailsService = InMemoryUserDetailsManager()
}
