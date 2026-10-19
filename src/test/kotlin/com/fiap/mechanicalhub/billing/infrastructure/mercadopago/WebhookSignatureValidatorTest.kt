package com.fiap.mechanicalhub.billing.infrastructure.mercadopago

import org.junit.jupiter.api.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebhookSignatureValidatorTest {
    private val secret = "segredo-de-teste"
    private val validator = WebhookSignatureValidator(MercadoPagoProperties("http://mp", "token", secret))

    private fun sign(manifest: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(manifest.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `given assinatura correta when validar then aceita`() {
        val hash = sign("id:ord123;request-id:req-1;ts:1700000000;")

        val valid = validator.isValid("ts=1700000000,v1=$hash", "req-1", "ORD123")

        assertTrue(valid)
    }

    @Test
    fun `given hash adulterado when validar then rejeita`() {
        val valid = validator.isValid("ts=1700000000,v1=abc", "req-1", "ORD123")

        assertFalse(valid)
    }

    @Test
    fun `given request id diferente when validar then rejeita`() {
        val hash = sign("id:ord123;request-id:req-1;ts:1700000000;")

        val valid = validator.isValid("ts=1700000000,v1=$hash", "req-2", "ORD123")

        assertFalse(valid)
    }

    @Test
    fun `given header ausente when validar then rejeita`() {
        assertFalse(validator.isValid(null, "req-1", "ORD123"))
    }

    @Test
    fun `given header malformado when validar then rejeita`() {
        assertFalse(validator.isValid("lixo", "req-1", "ORD123"))
    }

    @Test
    fun `given segredo nao configurado when validar then rejeita`() {
        val semSegredo = WebhookSignatureValidator(MercadoPagoProperties("http://mp"))

        val valid = semSegredo.isValid("ts=1,v1=abc", "req-1", "ORD123")

        assertFalse(valid)
    }
}
