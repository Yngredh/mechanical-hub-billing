package com.fiap.mechanicalhub.billing.application.payment

import com.fiap.mechanicalhub.billing.domain.payment.Payment
import java.util.UUID

/** Pagamento persistido: o estado do domínio mais os identificadores usados nas mensagens. */
data class PaymentRecord(
    val paymentId: UUID,
    val orderId: UUID,
    val transactionId: UUID,
    val mpOrderId: String,
    val payment: Payment,
)

/** Evento a publicar na saga; entregue ao orquestrador pelo relay do outbox. */
data class OutboxMessage(
    val type: String,
    val orderId: UUID,
    val transactionId: UUID,
    val payload: Map<String, Any>,
)

interface PaymentStore {
    /** Busca o pagamento travando a linha (`FOR UPDATE`); exige transação ativa. */
    fun findByMpOrderIdForUpdate(mpOrderId: String): PaymentRecord?

    /** Grava a transição apenas se a linha ainda estiver PENDING; devolve se alterou. */
    fun updateIfPending(record: PaymentRecord): Boolean
}

/** Grava o evento no outbox na transação corrente. */
interface EventOutbox {
    fun publish(message: OutboxMessage)
}

interface TransactionRunner {
    fun <T> run(block: () -> T): T
}
