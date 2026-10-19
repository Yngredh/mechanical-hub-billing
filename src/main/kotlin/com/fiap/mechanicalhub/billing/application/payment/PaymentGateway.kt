package com.fiap.mechanicalhub.billing.application.payment

import com.fiap.mechanicalhub.billing.domain.payment.CancelOutcome
import com.fiap.mechanicalhub.billing.domain.payment.OrderLookup
import java.math.BigDecimal
import java.time.Instant

data class CreateOrderCommand(
    val orderId: String,
    val amount: BigDecimal,
    val expiresAt: Instant,
    val idempotencyKey: String,
)

data class CreatedOrder(val mpOrderId: String, val checkoutUrl: String)

/** Falha transitória (timeout, 5xx, 429): a mensagem deve ser tentada de novo. */
class TransientGatewayException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Falha permanente (400, 401, 403, 404, resposta inválida): não adianta repetir. */
class PermanentGatewayException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Porta do provedor de pagamento; o domínio não conhece o Mercado Pago. */
interface PaymentGateway {
    /** Lança [TransientGatewayException] ou [PermanentGatewayException] em caso de falha. */
    fun createOrder(command: CreateOrderCommand): CreatedOrder

    fun getOrder(mpOrderId: String): OrderLookup

    fun cancelOrder(mpOrderId: String, idempotencyKey: String): CancelOutcome
}
