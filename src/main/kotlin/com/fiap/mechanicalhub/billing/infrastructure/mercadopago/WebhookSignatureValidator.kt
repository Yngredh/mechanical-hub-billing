package com.fiap.mechanicalhub.billing.infrastructure.mercadopago

import org.springframework.stereotype.Component
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Valida o header `x-signature` (`ts=...,v1=...`) das notificações do Mercado Pago. */
@Component
class WebhookSignatureValidator(private val properties: MercadoPagoProperties) {
    fun isValid(signatureHeader: String?, requestId: String?, dataId: String?): Boolean {
        val secret = properties.webhookSecret
        val parts = signatureHeader?.let(::parse).orEmpty()
        val timestamp = parts["ts"]
        val received = parts["v1"]
        if (secret.isBlank() || timestamp == null || received == null) return false
        val expected = hmacHex(secret, manifest(dataId, requestId, timestamp))
        return MessageDigest.isEqual(expected.toByteArray(), received.lowercase().toByteArray())
    }

    private fun parse(header: String): Map<String, String> =
        header
            .split(',')
            .mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 } }
            .associate { (key, value) -> key.trim() to value.trim() }

    private fun manifest(dataId: String?, requestId: String?, timestamp: String): String =
        buildString {
            dataId?.let { append("id:${it.lowercase()};") }
            requestId?.let { append("request-id:$it;") }
            append("ts:$timestamp;")
        }

    private fun hmacHex(secret: String, message: String): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(), ALGORITHM))
        return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val ALGORITHM = "HmacSHA256"
    }
}
