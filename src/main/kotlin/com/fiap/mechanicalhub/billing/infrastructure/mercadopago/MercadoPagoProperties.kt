package com.fiap.mechanicalhub.billing.infrastructure.mercadopago

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@ConfigurationProperties("mercado-pago")
data class MercadoPagoProperties(
    val baseUrl: String,
    val accessToken: String = "",
    val webhookSecret: String = "",
)

@Configuration
@EnableConfigurationProperties(MercadoPagoProperties::class)
class MercadoPagoConfig
