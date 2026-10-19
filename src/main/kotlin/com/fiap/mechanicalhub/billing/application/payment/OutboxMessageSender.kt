package com.fiap.mechanicalhub.billing.application.payment

import java.util.UUID

/** Entrega um evento já serializado à fila de eventos do orquestrador (FIFO). */
interface OutboxMessageSender {
    fun send(groupId: UUID, deduplicationId: UUID, body: String)
}
