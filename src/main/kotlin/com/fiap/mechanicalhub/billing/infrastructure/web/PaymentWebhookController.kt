package com.fiap.mechanicalhub.billing.infrastructure.web

import com.fiap.mechanicalhub.billing.application.payment.ConfirmPaymentUseCase
import com.fiap.mechanicalhub.billing.application.payment.WebhookResult
import com.fiap.mechanicalhub.billing.infrastructure.mercadopago.WebhookSignatureValidator
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper

@RestController
@RequestMapping("/webhooks/payment")
class PaymentWebhookController(
    private val signatureValidator: WebhookSignatureValidator,
    private val confirmPayment: ConfirmPaymentUseCase,
) {
    private val mapper = JsonMapper.builder().build()

    @PostMapping
    fun receive(
        @RequestBody(required = false) body: String?,
        @RequestHeader("x-signature", required = false) signature: String?,
        @RequestHeader("x-request-id", required = false) requestId: String?,
        @RequestParam("data.id", required = false) queryDataId: String?,
    ): ResponseEntity<Void> {
        val dataId = extractDataId(body) ?: queryDataId?.takeIf { it.isNotBlank() }
        if (!signatureValidator.isValid(signature, requestId, dataId)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        if (dataId == null) return ResponseEntity.ok().build()
        return try {
            when (confirmPayment.execute(dataId)) {
                WebhookResult.CONFIRMED, WebhookResult.IGNORED -> ResponseEntity.ok().build()
                WebhookResult.RETRY -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()
            }
        } catch (exception: DataAccessException) {
            log.error("Falha ao processar webhook da order {}", dataId, exception)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()
        }
    }

    private fun extractDataId(body: String?): String? =
        body
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { mapper.readTree(it) }.getOrNull() }
            ?.path("data")
            ?.path("id")
            ?.asString("")
            ?.takeIf { it.isNotBlank() }

    private companion object {
        val log = LoggerFactory.getLogger(PaymentWebhookController::class.java)
    }
}
