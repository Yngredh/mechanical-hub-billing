package com.fiap.mechanicalhub.billing.infrastructure.mercadopago

import com.fiap.mechanicalhub.billing.application.payment.CreateOrderCommand
import com.fiap.mechanicalhub.billing.application.payment.CreatedOrder
import com.fiap.mechanicalhub.billing.application.payment.PermanentGatewayException
import com.fiap.mechanicalhub.billing.domain.payment.OrderLookup
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** Serialização e leitura dos corpos JSON da Orders API. */
internal object MercadoPagoJson {
    private val mapper = JsonMapper.builder().build()

    fun createOrderBody(command: CreateOrderCommand): String {
        val amount = command.amount.setScale(2).toPlainString()
        val item =
            mapOf(
                "title" to "Ordem de serviço ${command.orderId} - Mechanical Hub",
                "unit_price" to amount,
                "quantity" to 1,
            )
        val body =
            mapOf(
                "type" to "online",
                "processing_mode" to "manual",
                "total_amount" to amount,
                "external_reference" to command.orderId,
                "expiration_time" to command.expiresIn.toString(),
                "items" to listOf(item),
                "config" to mapOf("payment_method" to mapOf("not_allowed_types" to listOf("ticket"))),
            )
        return mapper.writeValueAsString(body)
    }

    fun parseCreated(body: String): CreatedOrder {
        val json = parse(body)
        val id = json.path("id").asString("")
        val checkoutUrl = json.path("checkout_url").asString("")
        if (id.isBlank() || checkoutUrl.isBlank()) {
            throw PermanentGatewayException("Resposta de criacao sem id ou checkout_url")
        }
        return CreatedOrder(id, checkoutUrl)
    }

    fun parseLookup(body: String): OrderLookup {
        val json = runCatching { parse(body) }.getOrNull()
        val status = json?.path("status")?.asString("").orEmpty()
        return if (json == null || status.isBlank()) {
            OrderLookup.Permanent
        } else {
            OrderLookup.Found(status, json.path("status_detail").asString("").ifBlank { null })
        }
    }

    private fun parse(body: String): JsonNode =
        try {
            mapper.readTree(body)
        } catch (exception: JacksonException) {
            throw PermanentGatewayException("Resposta invalida do Mercado Pago", exception)
        }
}
