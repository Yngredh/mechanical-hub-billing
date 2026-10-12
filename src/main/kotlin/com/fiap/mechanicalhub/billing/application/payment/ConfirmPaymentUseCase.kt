package com.fiap.mechanicalhub.billing.application.payment

import com.fiap.mechanicalhub.billing.domain.payment.OrderLookup
import com.fiap.mechanicalhub.billing.domain.payment.PaymentStatusEnum
import com.fiap.mechanicalhub.billing.domain.payment.PaymentTransition
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock

enum class WebhookResult {
    /** Pagamento aprovado nesta chamada. */
    CONFIRMED,

    /** Notificação sem efeito (repetida, não paga ou desconhecida). */
    IGNORED,

    /** Falha transitória: o Mercado Pago deve reenviar. */
    RETRY,
}

/** Confirma o pagamento a partir da notificação do Mercado Pago (TD_finishedOS 17). */
@Service
class ConfirmPaymentUseCase(
    private val gateway: PaymentGateway,
    private val store: PaymentStore,
    private val outbox: EventOutbox,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    /** A consulta ao Mercado Pago acontece fora da transação; só a escrita final trava a linha. */
    fun execute(mpOrderId: String): WebhookResult =
        when (val lookup = gateway.getOrder(mpOrderId)) {
            OrderLookup.Transient -> {
                WebhookResult.RETRY
            }

            OrderLookup.Permanent -> {
                log.error("Consulta da order {} falhou de forma permanente", mpOrderId)
                WebhookResult.IGNORED
            }

            is OrderLookup.Found -> {
                handle(mpOrderId, lookup)
            }
        }

    private fun handle(mpOrderId: String, order: OrderLookup.Found): WebhookResult =
        if (order.status == PROCESSED && order.statusDetail == ACCREDITED) {
            transactions.run { confirm(mpOrderId, order.status) }
        } else {
            WebhookResult.IGNORED
        }

    private fun confirm(mpOrderId: String, mpStatus: String): WebhookResult {
        val record = store.findByMpOrderIdForUpdate(mpOrderId)
        if (record == null) {
            log.warn("Notificacao para order {} sem pagamento correspondente", mpOrderId)
            return WebhookResult.IGNORED
        }
        return when (val transition = record.payment.approve(mpStatus, clock.instant())) {
            PaymentTransition.Skipped -> {
                if (record.payment.status == PaymentStatusEnum.CANCELED) {
                    log.error("ALERTA: order {} paga no Mercado Pago mas pagamento ja CANCELED", mpOrderId)
                }
                WebhookResult.IGNORED
            }

            is PaymentTransition.Applied -> {
                apply(record.copy(payment = transition.payment))
            }
        }
    }

    private fun apply(approved: PaymentRecord): WebhookResult {
        if (!store.updateIfPending(approved)) return WebhookResult.IGNORED
        outbox.publish(
            OutboxMessage(
                type = PAYMENT_CONFIRMED,
                orderId = approved.orderId,
                transactionId = approved.transactionId,
                payload =
                    mapOf(
                        "transactionId" to approved.transactionId.toString(),
                        "orderId" to approved.orderId.toString(),
                        "paymentId" to approved.paymentId.toString(),
                    ),
            ),
        )
        return WebhookResult.CONFIRMED
    }

    private companion object {
        const val PROCESSED = "processed"
        const val ACCREDITED = "accredited"
        const val PAYMENT_CONFIRMED = "paymentConfirmed"
        val log = LoggerFactory.getLogger(ConfirmPaymentUseCase::class.java)
    }
}
