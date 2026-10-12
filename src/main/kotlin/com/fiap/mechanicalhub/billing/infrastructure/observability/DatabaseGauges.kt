package com.fiap.mechanicalhub.billing.infrastructure.observability

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/** Gauges lidos do banco a cada scrape: idade do evento mais antigo do outbox e pagamentos por status. */
@Component
class DatabaseGauges(
    private val jdbc: JdbcClient,
    registry: MeterRegistry,
) {
    init {
        Gauge
            .builder("outbox_oldest_pending_age_seconds") { oldestPendingAgeSeconds() }
            .description("Idade em segundos do evento PENDING mais antigo do outbox (0 se vazio)")
            .register(registry)
        STATUSES.forEach { status ->
            Gauge
                .builder("billing_payments") { countPayments(status) }
                .tag("status", status)
                .description("Pagamentos por status")
                .register(registry)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun oldestPendingAgeSeconds(): Double =
        try {
            jdbc
                .sql(
                    "SELECT COALESCE(EXTRACT(EPOCH FROM (now() - MIN(created_at))), 0) " +
                        "FROM outbox_events WHERE status = 'PENDING'",
                ).query(Double::class.java)
                .single()
        } catch (_: Exception) {
            Double.NaN
        }

    @Suppress("TooGenericExceptionCaught")
    private fun countPayments(status: String): Double =
        try {
            jdbc
                .sql("SELECT count(*) FROM payments WHERE status = :status")
                .param("status", status)
                .query(Long::class.java)
                .single()
                .toDouble()
        } catch (_: Exception) {
            Double.NaN
        }

    private companion object {
        val STATUSES = listOf("PENDING", "APPROVED", "CANCELED")
    }
}
