package com.fiap.mechanicalhub.billing.infrastructure.messaging

import com.fiap.mechanicalhub.billing.application.payment.OutboxMessageSender
import com.fiap.mechanicalhub.billing.application.payment.TransactionRunner
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Entrega os eventos pendentes do outbox, em ordem de `sequence`. Só uma instância trabalha por vez:
 * o lock consultivo é de transação, então fica preso à conexão do lote e é liberado no commit.
 */
@Suppress("TooGenericExceptionCaught")
@Component
@EnableScheduling
@ConditionalOnProperty("billing.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
class OutboxRelay(
    private val jdbc: JdbcClient,
    private val transactions: TransactionRunner,
    private val sender: OutboxMessageSender,
) {
    private val log = LoggerFactory.getLogger(OutboxRelay::class.java)

    @Scheduled(fixedDelay = 1000)
    fun scheduled() {
        try {
            relay()
        } catch (e: Exception) {
            log.error("Falha no ciclo do relay do outbox", e)
        }
    }

    /** Roda um lote; devolve quantos eventos foram enviados. */
    fun relay(): Int =
        transactions.run {
            val leader =
                jdbc
                    .sql("SELECT pg_try_advisory_xact_lock(:key)")
                    .param("key", LOCK_KEY)
                    .query(Boolean::class.java)
                    .single()
            if (!leader) return@run 0
            val pending =
                jdbc
                    .sql(
                        """
                        SELECT sequence, event_id, aggregate_id, payload::text AS payload
                        FROM outbox_events
                        WHERE status = 'PENDING'
                        ORDER BY sequence
                        LIMIT :limit
                        """.trimIndent(),
                    ).param("limit", BATCH_SIZE)
                    .query { rs, _ ->
                        Pending(
                            rs.getLong("sequence"),
                            rs.getObject("event_id", UUID::class.java),
                            rs.getObject("aggregate_id", UUID::class.java),
                            rs.getString("payload"),
                        )
                    }.list()
            var sent = 0
            for (event in pending) {
                try {
                    sender.send(event.aggregateId, event.eventId, event.payload)
                } catch (e: Exception) {
                    log.error("Falha ao enviar outbox sequence={} eventId={}", event.sequence, event.eventId, e)
                    jdbc
                        .sql(MARK_FAILED_SQL)
                        .param("error", e.message ?: e.javaClass.name)
                        .param("seq", event.sequence)
                        .update()
                    break
                }
                jdbc
                    .sql("UPDATE outbox_events SET status = 'SENT' WHERE sequence = :sequence")
                    .param("sequence", event.sequence)
                    .update()
                sent++
            }
            sent
        }

    private data class Pending(val sequence: Long, val eventId: UUID, val aggregateId: UUID, val payload: String)

    private companion object {
        const val LOCK_KEY = 7_000_001L
        const val BATCH_SIZE = 100
        const val MARK_FAILED_SQL =
            "UPDATE outbox_events SET attempts = attempts + 1, last_error = :error WHERE sequence = :seq"
    }
}
