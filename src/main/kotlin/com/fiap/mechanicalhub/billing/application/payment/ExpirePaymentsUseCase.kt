package com.fiap.mechanicalhub.billing.application.payment

import com.fiap.mechanicalhub.billing.domain.payment.CancelOutcome
import com.fiap.mechanicalhub.billing.domain.payment.ExpirationDecision
import com.fiap.mechanicalhub.billing.domain.payment.ExpirationRules
import com.fiap.mechanicalhub.billing.domain.payment.PaymentTransition
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock

/** Resumo de um ciclo do job de vencimento. */
data class ExpirationSummary(val confirmed: Int, val failed: Int, val waiting: Int, val alerts: Int)

/** Job de vencimento (TD_paymentFailed F2/F3): cancela a order vencida no MP e finaliza o pagamento. */
@Suppress("TooGenericExceptionCaught")
@Service
class ExpirePaymentsUseCase(
    private val gateway: PaymentGateway,
    private val store: PaymentStore,
    private val outbox: EventOutbox,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    fun execute(): ExpirationSummary {
        var confirmed = 0
        var failed = 0
        var waiting = 0
        var alerts = 0
        for (due in store.findDue(clock.instant(), BATCH_SIZE)) {
            val decision =
                try {
                    decide(due)
                } catch (e: Exception) {
                    log.error("Falha ao processar vencimento da order {}", due.mpOrderId, e)
                    ExpirationDecision.Wait
                }
            when (decision) {
                is ExpirationDecision.Fail -> {
                    if (finalize(due, decision)) failed++
                }

                is ExpirationDecision.Confirm -> {
                    if (finalize(due, decision)) confirmed++
                }

                ExpirationDecision.Wait -> {
                    waiting++
                }

                ExpirationDecision.Alert -> {
                    log.error("ALERTA: erro permanente ao cancelar/consultar a order {}", due.mpOrderId)
                    alerts++
                }
            }
        }
        return ExpirationSummary(confirmed, failed, waiting, alerts)
    }

    /** Chamadas ao Mercado Pago ficam fora da transação. */
    private fun decide(due: PaymentRecord): ExpirationDecision {
        val cancel = gateway.cancelOrder(due.mpOrderId, "${due.transactionId}:CANCEL_MP_ORDER")
        val lookup = if (cancel == CancelOutcome.Conflict) gateway.getOrder(due.mpOrderId) else null
        return ExpirationRules.decide(cancel, lookup)
    }

    private fun finalize(due: PaymentRecord, decision: ExpirationDecision): Boolean =
        transactions.run {
            val record = store.findByMpOrderIdForUpdate(due.mpOrderId) ?: return@run false
            val now = clock.instant()
            val transition =
                when (decision) {
                    is ExpirationDecision.Confirm -> record.payment.approve(decision.mpStatus, now)
                    is ExpirationDecision.Fail -> record.payment.cancel(decision.mpStatus, now)
                    else -> PaymentTransition.Skipped
                }
            if (transition !is PaymentTransition.Applied) return@run false
            val updated = record.copy(payment = transition.payment)
            if (!store.updateIfPending(updated)) return@run false
            outbox.publish(message(updated, decision))
            true
        }

    private fun message(record: PaymentRecord, decision: ExpirationDecision): OutboxMessage {
        val base =
            mapOf(
                "transactionId" to record.transactionId.toString(),
                "orderId" to record.orderId.toString(),
            )
        return if (decision is ExpirationDecision.Fail) {
            OutboxMessage(
                PAYMENT_FAILED,
                record.orderId,
                record.transactionId,
                base + ("reason" to decision.reason.name),
            )
        } else {
            OutboxMessage(
                PAYMENT_CONFIRMED,
                record.orderId,
                record.transactionId,
                base + ("paymentId" to record.paymentId.toString()),
            )
        }
    }

    private companion object {
        const val BATCH_SIZE = 100
        const val PAYMENT_CONFIRMED = "paymentConfirmed"
        const val PAYMENT_FAILED = "paymentFailed"
        val log = LoggerFactory.getLogger(ExpirePaymentsUseCase::class.java)
    }
}
