package com.fiap.mechanicalhub.billing.infrastructure.persistence

import com.fiap.mechanicalhub.billing.application.payment.EventOutbox
import com.fiap.mechanicalhub.billing.application.payment.OutboxMessage
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Metrics
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.util.UUID

/** Grava o envelope JSON em `outbox_events` na transação corrente. */
@Repository
class JdbcEventOutbox(
    private val jdbc: JdbcClient,
    private val clock: Clock,
    private val registry: MeterRegistry = Metrics.globalRegistry,
) : EventOutbox {
    private val mapper = JsonMapper.builder().build()

    override fun publish(message: OutboxMessage) {
        val eventId = UUID.randomUUID()
        val envelope =
            mapOf(
                "eventId" to eventId.toString(),
                "transactionId" to message.transactionId.toString(),
                "orderId" to message.orderId.toString(),
                "type" to message.type,
                "version" to ENVELOPE_VERSION,
                "timestamp" to clock.instant().toString(),
            ) + message.payload
        jdbc
            .sql(
                """
                INSERT INTO outbox_events (event_id, aggregate_id, event_type, payload)
                VALUES (:eventId, :aggregateId, :eventType, CAST(:payload AS jsonb))
                """.trimIndent(),
            ).param("eventId", eventId)
            .param("aggregateId", message.orderId)
            .param("eventType", message.type)
            .param("payload", mapper.writeValueAsString(envelope))
            .update()
        if (message.type == "paymentFailed") {
            registry
                .counter("billing_payment_failed_total", "reason", message.payload["reason"]?.toString() ?: "UNKNOWN")
                .increment()
        }
    }

    private companion object {
        const val ENVELOPE_VERSION = 1
    }
}
